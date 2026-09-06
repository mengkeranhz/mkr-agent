package com.mkr.tools.route;

import com.mkr.tools.ToolResult;
import com.mkr.tools.builtin.BrowserTool;
import org.jsoup.Jsoup;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 路线抓取（设计方案 §二.2）：高德官方 API 优先（有 Key 且坐标已解析），
 * 失败或无 Key 降级到浏览器渲染页（复用 BrowserTool 同一会话，Anti-bot 友好）。
 *
 * <p>浏览器路径解析已验证的 ditu.amap.com/dir 路线页：{@code from[name]/to[name]}
 * 由高德站内自行解析地名；主方案与「备选方案」卡片均含「xx.x公里 / xx分钟」文本，
 * 按邻近配对逐卡提取全部线路（见 {@link #parsePlans}）。名称无法自动消歧时页面进入
 * 「请选择正确的起点」待选择态并列出候选（常见于地铁站等多同名 POI），此时自动隐藏
 * 遮挡浮层并依次点选起点/终点首候选（见 {@link #pickUnresolvedPois}）。</p>
 */
public final class RouteFetcher {

    private static final Pattern KILOMETERS = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*公里");
    private static final Pattern METERS = Pattern.compile("(\\d+)\\s*米");
    private static final Pattern HOUR_MIN = Pattern.compile("(\\d+)\\s*小时(?:\\s*(\\d+)\\s*分(?:钟)?)?");
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*分(?!积)(?:钟)?");
    private static final Pattern YUAN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*元");
    private static final Pattern TRANSFERS = Pattern.compile("换乘\\s*(\\d+)\\s*次");
    /** 卡片策略标签（备选卡区分维度，展示用）。 */
    private static final Pattern STRATEGY = Pattern.compile(
            "推荐方案|红绿灯少|避开拥堵|拥堵少|时间短|距离短|少花钱|步行少|换乘少|少换乘");
    /** 导航分段语句结尾动词（「沿xx路行驶1.2公里」「步行450米」）：是步骤不是卡片。 */
    private static final Pattern STEP_HINT =
            Pattern.compile("(行驶|步行|骑行|驾车|直行|左转|右转|掉头|进入|靠左|靠右)$");

    /** 距离↔耗时 邻近配对窗口（字符）：同一卡片内两者紧邻，跨卡片必超窗。 */
    private static final int PAIR_WINDOW = 16;
    /** 卡片尾部（票价/换乘/策略标签）扫描窗口（字符）。 */
    private static final int CARD_TAIL_WINDOW = 24;
    /** 网页解析方案数上限（防御结构异常的页面）。 */
    private static final int MAX_WEB_PLANS = 5;

    /** 文本数值匹配（位置用于卡片邻近配对）。 */
    private record Num(long value, int start, int end) {}

    private final AmapRouteClient api;
    private final BrowserTool browser = new BrowserTool();

    public RouteFetcher(java.net.http.HttpClient http, String amapKey) {
        this.api = new AmapRouteClient(http, amapKey);
    }

    /** 抓取指定方式（all = car+bus+walk 依次）的全部方案；全部失败抛异常并附页面证据。 */
    public List<RoutePlan> fetch(GeoLocation from, GeoLocation to, String mode,
                                 boolean headless, int waitMs) throws Exception {
        List<String> modes = "all".equals(mode) ? List.of("car", "bus", "walk") : List.of(mode);
        List<RoutePlan> plans = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String m : modes) {
            try {
                plans.addAll(fetchOne(from, to, m, headless, waitMs));
            } catch (Exception e) {
                failures.add(RoutePlan.modeLabel(m) + ": " + e.getMessage());
            }
        }
        if (plans.isEmpty()) {
            throw new IllegalStateException("路线抓取失败（" + String.join("；", failures) + "）");
        }
        return plans;
    }

    private List<RoutePlan> fetchOne(GeoLocation from, GeoLocation to, String mode,
                                     boolean headless, int waitMs) throws Exception {
        if (api.hasKey() && from.lngLat() != null && to.lngLat() != null) {
            try {
                List<RoutePlan> plans = switch (mode) {
                    case "car" -> api.driving(from.lngLat(), to.lngLat());
                    case "walk" -> api.walking(from.lngLat(), to.lngLat());
                    default -> api.transit(from.lngLat(), to.lngLat(), from.city(), to.city());
                };
                if (!plans.isEmpty()) {
                    return plans;
                }
            } catch (Exception ignored) {
                // API 失败（配额/参数/网络）→ 浏览器降级
            }
        }
        return fetchViaBrowser(from, to, mode, headless, waitMs);
    }

    /**
     * 浏览器降级：ditu.amap.com/dir 路线页 → 渲染文本逐卡解析全部线路（主方案在前、备选方案在后）。
     *
     * <p>两个已实测的失败模式各有对策：① 路线规划是页面加载后的异步请求，固定等待常赶不上
     * 渲染完成 → 轮询直至出现路线卡（总预算 {@code max(wait_ms*3, 6s)}）；② 高德对同一
     * 浏览器会话的连续 /dir 导航限流（页面加载但忽略名称参数，呈空表单态）→ 失败时
     * 关闭会话换新指纹 + 退避后重试一次。</p>
     */
    private List<RoutePlan> fetchViaBrowser(GeoLocation from, GeoLocation to, String mode,
                                            boolean headless, int waitMs) throws Exception {
        String firstError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (attempt > 0) {
                browser.run(Map.of("action", "close"), null); // 丢弃被限流会话（新 cookie/指纹）
                Thread.sleep(1500);                            // 退避，避开短时限流窗口
            }
            try {
                return fetchViaBrowserOnce(from, to, mode, headless, waitMs);
            } catch (Exception e) {
                if (firstError == null) {
                    firstError = e.getMessage();
                }
            }
        }
        throw new IllegalStateException(firstError
                + "。反复失败可配置 AMAP_KEY 走官方 API（place/text 多候选消歧 + 免浏览器反爬）");
    }

    /** 单次浏览器尝试：goto → 轮询路线卡 → 待选择态自动点选 → 单值兜底。 */
    private List<RoutePlan> fetchViaBrowserOnce(GeoLocation from, GeoLocation to, String mode,
                                                boolean headless, int waitMs) throws Exception {
        String url = "https://ditu.amap.com/dir?type=" + amapType(mode) + "&policy=2"
                + "&from%5Bname%5D=" + URLEncoder.encode(from.formatted(), StandardCharsets.UTF_8)
                + "&to%5Bname%5D=" + URLEncoder.encode(to.formatted(), StandardCharsets.UTF_8)
                + "&src=mkr-agent&callnative=0&innersrc=uriapi";

        ToolResult open = browser.run(Map.of(
                "action", "goto", "url", url, "headless", headless, "wait_ms", waitMs), null);
        if (!open.success()) {
            throw new IllegalStateException("路线页打开失败: " + open.render());
        }
        long deadline = System.currentTimeMillis() + Math.max(waitMs * 3L, 6000L);
        StringBuilder text = new StringBuilder();
        List<RoutePlan> plans = pollForCards(mode, deadline, text);
        // 待选择态（URI 名称未能自动消歧，页面已列出候选）：自动点选后再等路线卡
        if (plans.isEmpty() && text.toString().contains("请选择正确的起点")) {
            pickUnresolvedPois();
            plans = pollForCards(mode, System.currentTimeMillis() + 8000L, text);
        }
        if (!plans.isEmpty()) {
            return plans;
        }
        // 逐卡配对失败（页面残缺/结构改版/名称未解析）→ 单值兜底：仅当存在可信耗时
        // 才构造（距离可缺省）；无耗时不编造数据，如实报「未渲染出路线」
        RoutePlan fallback = fallbackPlan(text.toString(), mode);
        if (fallback == null) {
            throw new IllegalStateException(failReason(text.toString()));
        }
        return List.of(fallback);
    }

    /** 轮询页面直至出现路线卡或超时；最后读到的正文文本写入 out（供失败归因）。 */
    private List<RoutePlan> pollForCards(String mode, long deadlineMs, StringBuilder out) throws Exception {
        while (true) {
            ToolResult content = browser.run(Map.of("action", "content"), null);
            if (!content.success()) {
                throw new IllegalStateException("页面内容获取失败: " + content.render());
            }
            out.setLength(0);
            out.append(pageText(content.output()));
            List<RoutePlan> plans = parsePlans(out.toString(), mode);
            if (!plans.isEmpty()) {
                return plans;
            }
            if (System.currentTimeMillis() >= deadlineMs) {
                return List.of();
            }
            Thread.sleep(800);
        }
    }

    /**
     * 待选择态自动点选：侧栏已按未消歧名称列出候选（li.dir_choose_item，起点在前、
     * 点选后刷新出终点候选）。遮挡侧栏的登录/推广浮层（mask--*，z-index 10000）的
     * 关闭按钮都在其下点不动，实测仅 JS 隐藏有效。最多点两轮（起点+终点）。
     */
    private void pickUnresolvedPois() throws Exception {
        String hideMask = "document.querySelectorAll('[class*=\"mask--\"]')"
                + ".forEach(e => e.style.display = 'none')";
        browser.run(Map.of("action", "eval", "script", hideMask), null);
        Thread.sleep(500);
        for (int round = 0; round < 2; round++) {
            ToolResult click = browser.run(Map.of("action", "click",
                    "selector", "li.dir_choose_item", "timeout_ms", 4000, "wait_ms", 2000), null);
            if (!click.success()) {
                break; // 无候选可点（名称完全无匹配）
            }
            browser.run(Map.of("action", "eval", "script", hideMask), null);
            Thread.sleep(800);
        }
    }

    /**
     * 最终失败原因分类：空表单态（「请选择正确的起点」提示仍在且无路线卡）多为
     * 歧义名称（地铁站/火车站等多同名 POI，URI 参数无法自动消歧，实测换名变体亦无效）；
     * 其余按未渲染处理。包级可见供离线单测。
     */
    static String failReason(String text) {
        return text.contains("请选择正确的起点")
                ? "高德路线页未能自动解析起/终点名称（歧义名常见于地铁站、火车站等多同名 POI），"
                        + "请改用唯一性地标名（如「天安门」）或更具体名称"
                : "页面未渲染出路线，正文片段: " + snippet(text);
    }

    /** BrowserTool 输出 → 渲染正文纯文本（兼容新旧输出标记）。 */
    private static String pageText(String toolOutput) {
        int marker = toolOutput.indexOf("--- 正文（markdown） ---");
        if (marker < 0) {
            marker = toolOutput.indexOf("--- HTML 内容 ---"); // 旧版 BrowserTool 输出标记
        }
        return Jsoup.parse(marker >= 0 ? toolOutput.substring(marker) : toolOutput).text();
    }

    /**
     * 单值提取兜底：只渲染出耗时时仍返回一条（距离未知诚实标注）。
     * <b>必须存在可信耗时</b>——只有孤立的距离值（如页面噪声「30.0公里」）时返回 null，
     * 由调用方报错，杜绝「30.0公里/1分钟」式编造。包级可见供离线单测。
     */
    static RoutePlan fallbackPlan(String text, String mode) {
        long durSec = firstDurationSec(text);
        if (durSec <= 0) {
            return null;
        }
        long distM = firstDistanceMeters(text);
        Double cost = "bus".equals(mode) ? firstCost(text) : null;
        return new RoutePlan(mode, Math.max(durSec, 60), distM, cost, 0, "", "amap-web");
    }

    private static String amapType(String mode) {
        return switch (mode) {
            case "bus" -> "bus";
            case "walk" -> "walk";
            default -> "car";
        };
    }

    /** 首个可信距离：公里优先（取首个合法公里值），无公里再取米（≥100，过滤比例尺图例）。 */
    static long firstDistanceMeters(String text) {
        Matcher km = KILOMETERS.matcher(text);
        while (km.find()) {
            double v = Double.parseDouble(km.group(1));
            if (v >= 0.1 && v <= 5000) {
                return Math.round(v * 1000);
            }
        }
        Matcher m = METERS.matcher(text);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            if (v >= 100 && v <= 100_000) {
                return v;
            }
        }
        return -1;
    }

    /** 首个可信耗时：两模式按位置取最早（主方案卡片在前，备选方案在后）。 */
    static long firstDurationSec(String text) {
        long best = -1;
        int bestPos = Integer.MAX_VALUE;
        Matcher hm = HOUR_MIN.matcher(text);
        while (hm.find()) {
            long h = Long.parseLong(hm.group(1));
            long min = hm.group(2) == null ? 0 : Long.parseLong(hm.group(2));
            long total = h * 60 + min;
            if (total >= 1 && total <= 3000 && hm.start() < bestPos) {
                best = total * 60;
                bestPos = hm.start();
            }
        }
        Matcher m = MINUTES.matcher(text);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            if (v >= 1 && v <= 3000 && m.start() < bestPos) {
                best = v * 60;
                bestPos = m.start();
            }
        }
        return best;
    }

    static Double firstCost(String text) {
        Matcher y = YUAN.matcher(text);
        return y.find() ? Double.parseDouble(y.group(1)) : null;
    }

    // ---------------- 渲染页多卡片解析 ----------------

    /**
     * 渲染文本 → 全部路线卡（主方案在前、备选方案在后）：距离与耗时成对且邻近才算一张卡，
     * 比例尺/导航分段距离等孤立数值不会误入；同一卡片重复渲染（侧栏+浮层）按（耗时, 距离）去重。
     * bus 备选卡常不显示距离（仅 耗时+换乘/票价），允许距离未知。
     */
    static List<RoutePlan> parsePlans(String text, String mode) {
        List<Num> durs = durationNums(text);
        List<Num> dists = distanceNums(text);
        Set<Num> paired = new HashSet<>();
        List<RoutePlan> plans = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < durs.size() && plans.size() < MAX_WEB_PLANS; i++) {
            Num dur = durs.get(i);
            Num dist = nearestUnpaired(dists, paired, dur.start());
            if (dist == null && !busCardHint(text, dur)) {
                continue;
            }
            if (dist != null) {
                paired.add(dist);
            }
            if (!seen.add(dur.value() + "/" + (dist == null ? -1 : dist.value()))) {
                continue;
            }
            int limit = i + 1 < durs.size() ? durs.get(i + 1).start() : text.length();
            plans.add(cardPlan(mode, dur, dist, text, limit));
        }
        // 离群距离过滤：同一起终点的备选方案距离不会相差数倍，显著偏小的「距离」
        // 是页面噪声与重复渲染耗时的误配（如真实 200 公里级路线混入 30.0公里）
        final long maxDist = plans.stream().filter(p -> p.distanceMeters() > 0)
                .mapToLong(RoutePlan::distanceMeters).max().orElse(0L);
        if (maxDist > 0) {
            plans.removeIf(p -> p.distanceMeters() > 0 && p.distanceMeters() * 4 < maxDist);
        }
        return plans;
    }

    /** 距目标位置最近且未配对的距离（超窗口视为他卡数值，返回 null）。 */
    private static Num nearestUnpaired(List<Num> dists, Set<Num> paired, int pos) {
        Num best = null;
        int bestGap = PAIR_WINDOW + 1;
        for (Num d : dists) {
            if (paired.contains(d)) {
                continue;
            }
            int gap = Math.abs(d.start() - pos);
            if (gap <= PAIR_WINDOW && gap < bestGap) {
                best = d;
                bestGap = gap;
            }
        }
        return best;
    }

    /** bus 卡可能只显示 耗时+换乘/票价（无距离）：有换乘/票价佐证才算卡，防孤立耗时噪声。 */
    private static boolean busCardHint(String text, Num dur) {
        String seg = text.substring(dur.start(), Math.min(text.length(), dur.end() + CARD_TAIL_WINDOW));
        return TRANSFERS.matcher(seg).find() || seg.contains("票价");
    }

    /** 由配对数值构造卡片方案：bus 取票价/换乘；驾车网页打车价≠过路费，不取费用。 */
    private static RoutePlan cardPlan(String mode, Num dur, Num dist, String text, int limit) {
        long distM = dist == null ? -1 : dist.value();
        int lastEnd = dist == null ? dur.end() : Math.max(dur.end(), dist.end());
        String seg = text.substring(dur.start(),
                Math.min(text.length(), Math.min(lastEnd + CARD_TAIL_WINDOW, limit)));
        Double cost = null;
        int transfers = 0;
        if ("bus".equals(mode)) {
            Matcher y = YUAN.matcher(seg);
            cost = y.find() ? Double.parseDouble(y.group(1)) : null;
            Matcher t = TRANSFERS.matcher(seg);
            transfers = t.find() ? Integer.parseInt(t.group(1)) : 0;
        }
        Matcher s = STRATEGY.matcher(seg);
        String strategy = s.find() ? s.group() : "";
        return new RoutePlan(mode, Math.max(dur.value(), 60), distM, cost, transfers, strategy, "amap-web");
    }

    /** 全部可信耗时（按位置排序；「1小时25分钟」中的「25分钟」不重复计数）。 */
    static List<Num> durationNums(String text) {
        List<Num> all = new ArrayList<>();
        Matcher hm = HOUR_MIN.matcher(text);
        while (hm.find()) {
            long h = Long.parseLong(hm.group(1));
            long min = hm.group(2) == null ? 0 : Long.parseLong(hm.group(2));
            long total = h * 60 + min;
            if (total >= 1 && total <= 3000) {
                all.add(new Num(total * 60, hm.start(), hm.end()));
            }
        }
        Matcher m = MINUTES.matcher(text);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            if (v >= 1 && v <= 3000) {
                all.add(new Num(v * 60, m.start(), m.end()));
            }
        }
        all.sort(Comparator.comparingInt(Num::start));
        List<Num> out = new ArrayList<>();
        int lastEnd = -1;
        for (Num n : all) {
            if (n.start() >= lastEnd) {
                out.add(n);
                lastEnd = n.end();
            }
        }
        return out;
    }

    /** 全部可信距离（按位置排序；导航分段距离「沿xx行驶1.2公里」「步行450米」不算卡片）。 */
    static List<Num> distanceNums(String text) {
        List<Num> out = new ArrayList<>();
        Matcher km = KILOMETERS.matcher(text);
        while (km.find()) {
            double v = Double.parseDouble(km.group(1));
            if (v >= 0.1 && v <= 5000 && !stepPrefix(text, km.start())) {
                out.add(new Num(Math.round(v * 1000), km.start(), km.end()));
            }
        }
        Matcher m = METERS.matcher(text);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            if (v >= 100 && v <= 100_000 && !stepPrefix(text, m.start())) {
                out.add(new Num(v, m.start(), m.end()));
            }
        }
        out.sort(Comparator.comparingInt(Num::start));
        return out;
    }

    /** 距离数值紧贴导航动词且无空白分隔 → 转向步骤的分段距离，不是路线卡。 */
    private static boolean stepPrefix(String text, int start) {
        if (start == 0) {
            return false;
        }
        String before = text.substring(Math.max(0, start - 9), start);
        return !Character.isWhitespace(before.charAt(before.length() - 1))
                && STEP_HINT.matcher(before).find();
    }

    static String snippet(String s) {
        String t = s.replaceAll("\\s+", " ").strip();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }
}
