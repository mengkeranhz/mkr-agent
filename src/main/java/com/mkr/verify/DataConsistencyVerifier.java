package com.mkr.verify;

import com.mkr.api.Message;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据一致性验证器（纯代码，无 LLM）——跨领域通用版。
 *
 * <p>在 CompletionJudge 的引用合规检查之后、策略判定之前执行。解决四类数据质量问题：
 * <ol>
 *   <li>口径不匹配：答复混用不同统计口径的数据（挂牌价 vs 成交价、零售价 vs 批发价、均价 vs 中位数等）</li>
 *   <li>未实际计算且未声明引用：任务要求"计算"但直接引用现成数据，无计算过程也无引用声明
 *       （反推形 X/(1±p) 不算正向计算）</li>
 *   <li>幽灵数值：答复中的显著数值既无工具结果支撑，也无推导标注或计算式</li>
 *   <li>数据矛盾未处理：同指标、同口径、同期多来源差异大但未做说明（仅警告，不阻断）</li>
 * </ol>
 *
 * <p>通用性设计：
 * <ul>
 *   <li>豁免门控：答复为空、或工具轨迹中无可识别数值时整体豁免——其他领域/语言的任务不受影响；</li>
 *   <li>数值识别跨领域：「数字 + 可选万/亿倍率 + 单位词」，单位词表覆盖货币/计数/面积/度量/百分比；
 *       显著性按量级判定（基础单位 ≥1000、带万/亿倍率任意值、五位以上裸数字），与具体领域无关；</li>
 *   <li>口径词表跨领域（房价/物价/统计口径）；同比/环比视为指标修饰语而非口径，不再造成虚假"多口径"；</li>
 *   <li>推导豁免分两级：低歧义显式词（反推/推算/估算/推导）整行豁免；约/左右/≈ 等近似词仅紧邻数值时豁免，
 *       避免「预约/约束/参考文献」等常用词误放行；</li>
 *   <li>工具支撑匹配：精确串匹配 + 万/亿双向换算 + 相对容差（「5.5万」可匹配「54,631」）；</li>
 *   <li>矛盾检查按 单位+口径+时间 分组，跨期数据不再误报；仅产生警告。</li>
 * </ul>
 *
 * <p>已知局限：矛盾分组未含实体维度（不同城市/商品的同期同口径对比可能误报警告，因警告不阻断影响有限）；
 * 口径词表之外的领域概念（如 GAAP/非GAAP）不会被识别；英文仅覆盖少量时间/近似/计算词形。
 */
public final class DataConsistencyVerifier {

    /** 数据点：从工具结果中提取的数值数据 */
    public record DataPoint(
            String value,    // 数值（纯数字，已去逗号）
            String unit,     // 单位（含万/亿倍率前缀，如「万元」「万人」）
            String metric,   // 指标名称（从上下文推断）
            String time,     // 时间
            String caliber,  // 口径（挂牌价/成交价/零售价/均价/指数等）
            String source,   // 来源 URL（如有）
            String rawText   // 原始上下文片段（供反验）
    ) {}

    /** 验证结果 */
    public record CheckResult(
            boolean passed,
            String reason,
            List<String> warnings,
            List<DataPoint> inconsistencies
    ) {
        public static CheckResult ok() {
            return new CheckResult(true, "数据一致性检查通过", List.of(), List.of());
        }

        public static CheckResult fail(String reason) {
            return new CheckResult(false, reason, List.of(), List.of());
        }

        public static CheckResult warn(String reason, List<String> warnings) {
            return new CheckResult(true, reason, warnings, List.of());
        }
    }

    // ---- 阈值常量（量级判断，与具体领域无关） ----

    /** 带单位数值的显著性下限（基础单位；「3人」「500元」等小值不查，避免修辞误伤） */
    private static final double NOTABLE_BASE = 1000;

    /** 裸数字的显著性下限（年份、序号等 4 位数以下豁免） */
    private static final double BARE_NOTABLE = 10000;

    /** 工具支撑匹配的相对容差（吸收四舍五入表述，如 5.5万 vs 54,631） */
    private static final double VALUE_TOLERANCE = 0.01;

    /** 同组数据差异告警阈值 */
    private static final double CONFLICT_RATIO = 0.2;

    /** 近似词豁免的紧邻窗口（字符） */
    private static final int APPROX_WINDOW = 10;

    // ---- 正则 ----

    /** 数值 + 可选万/亿倍率 + 单位词（货币/面积/计数/度量/百分比，长词在前；元后接「月」（元月）不算单位） */
    private static final Pattern NUMBER_UNIT = Pattern.compile(
            "([\\d,]+\\.?\\d*)\\s*([万亿])?\\s*("
                    + "元/平方米|元/平米|元/㎡"                       // 单价
                    + "|个百分点|%|％"                                 // 百分比
                    + "|美元|欧元|日元|英镑|港币|人民币|元(?!月)"        // 货币
                    + "|平方米|平米|㎡|公顷|亩"                        // 面积
                    + "|人|名|位|台|辆|件|家|个|套|篇|字|张|户|头|次|单" // 计数
                    + "|吨|公斤|克|千米|公里|米"                        // 度量
                    + "|GB|MB|TB|kWh|kW|℃|°C"                        // 存储/功率/温度
                    + ")",
            Pattern.CASE_INSENSITIVE
    );

    /** 时间（中英常见格式） */
    private static final Pattern TIME = Pattern.compile(
            "\\d{4}年\\d{1,2}月|\\d{4}年?[一二三四1-4]季度|\\d{4}Q[1-4]|Q[1-4]\\s?\\d{4}"
                    + "|\\d{4}[-/.]\\d{1,2}|\\d{4}年"
    );

    /** 口径关键词（跨领域：房价/物价/统计口径；同比/环比是修饰语，不算口径） */
    private static final Pattern CALIBER = Pattern.compile(
            "参考价|中位数|挂牌价|成交价|网签价|备案价|零售价|批发价|出厂价|均价|平均|中位"
                    + "|参考|挂牌|成交|网签|备案|零售|批发|出厂|指数"
    );

    /** 任务中的计算关键词（中英） */
    private static final Pattern CALC_TASK = Pattern.compile(
            "计算|算出|求出|推算出|求.*增[减幅跌涨]|算.*同比|算.*环比|同比.*增跌|增跌幅|calculat|compute",
            Pattern.CASE_INSENSITIVE
    );

    /** 正向计算表达式：(A−B)/B、A/B−1、两大数相除（分母为 "(1±p)" 的反推形因含括号不会命中） */
    private static final Pattern FORWARD_CALC = Pattern.compile(
            "\\([\\d,.]+\\s*[-－−]\\s*[\\d,.]+\\)\\s*[/÷]\\s*[\\d,.]+"   // (A−B)/B
                    + "|[\\d,.]+\\s*[/÷]\\s*[\\d,.]+\\s*[-－−]\\s*1"    // A/B−1
                    + "|[\\d,.]{4,}\\s*[/÷]\\s*[\\d,.]{4,}"            // 两大数相除
    );

    /** 引用披露标记：声明数值来自来源已公布结果而非自行计算 */
    private static final Pattern CITE_DISCLOSURE = Pattern.compile(
            "已公布|发布值|公布的|官方发布|官方数据|官方统计|引用|非自行计算|替代"
    );

    /** 显式推导声明：低歧义词，所在行任意位置即可豁免 */
    private static final Pattern DERIVED_EXPLICIT = Pattern.compile("反推|推算|估算|推导|推得");

    /**
     * 近似表述：仅当紧邻数值（±APPROX_WINDOW 字符）才豁免。
     * 后瞻限定后面必须紧跟数字/空白/停顿符，排除「预约人数」「约束条件」类复合词中的「约」。
     */
    private static final Pattern APPROX_NEAR = Pattern.compile(
            "(?:大约|约|左右|上下|接近|近|≈|～|~|approx\\.?|approximately|estimated|roughly)"
                    + "(?=[\\s\\d.,，。；、）)．%％]|$)",
            Pattern.CASE_INSENSITIVE
    );

    /** 计算式上下文：等号或两数相除 */
    private static final Pattern CALC_CONTEXT = Pattern.compile(
            "[=＝]|[\\d,]\\s*[/÷]\\s*[\\d(]"
    );

    /** 五位以上裸数字（表内数值常不带单位，单位只在表头） */
    private static final Pattern BIG_NUMBER = Pattern.compile("[\\d,]{5,}");

    private DataConsistencyVerifier() {
    }

    // ======================== 公共入口 ========================

    /**
     * 验证数据一致性。
     *
     * @param messages 消息轨迹
     * @param answer   最终答复
     * @param task     任务描述
     * @return 验证结果
     */
    public static CheckResult verify(List<Message> messages, String answer, String task) {
        if (answer == null || answer.isBlank()) {
            return CheckResult.ok();
        }

        // 只在有 TOOL 消息且含可识别数值时启用（无外部数据则豁免）
        List<DataPoint> dataPoints = extractDataPoints(messages);
        if (dataPoints.isEmpty()) {
            return CheckResult.ok();
        }

        List<String> allWarnings = new ArrayList<>();

        // 1. 计算验证（计算与引用区分）
        if (needsCalculation(task)) {
            CheckResult cr = verifyCalculation(answer);
            if (!cr.passed()) {
                return cr; // fail 直返
            }
            if (cr.warnings != null) {
                allWarnings.addAll(cr.warnings);
            }
        }

        // 2. 答复数值溯源（幽灵数值）
        CheckResult provenanceCr = verifyAnswerValueProvenance(messages, answer, dataPoints);
        if (!provenanceCr.passed()) {
            return provenanceCr; // fail 直返
        }
        if (provenanceCr.warnings != null) {
            allWarnings.addAll(provenanceCr.warnings);
        }

        // 3. 口径一致性
        CheckResult caliberCr = verifyCaliberConsistency(answer, dataPoints);
        if (!caliberCr.passed()) {
            return caliberCr; // fail 直返
        }
        if (caliberCr.warnings != null) {
            allWarnings.addAll(caliberCr.warnings);
        }

        // 4. 数据矛盾
        CheckResult conflictCr = verifyDataConflicts(answer, dataPoints);
        if (!conflictCr.passed()) {
            return conflictCr;
        }
        if (conflictCr.warnings != null) {
            allWarnings.addAll(conflictCr.warnings);
        }

        if (!allWarnings.isEmpty()) {
            return CheckResult.warn("存在数据一致性警告", allWarnings);
        }
        return CheckResult.ok();
    }

    /**
     * 从文本中提取数据点（package-private，供测试调用）。
     */
    static void extractDataPointsFromText(String text, List<DataPoint> points) {
        if (text == null || text.isBlank()) {
            return;
        }
        // 先从全文提取时间和口径列表，避免 find() 消耗游标
        List<String> times = extractAll(TIME, text);
        List<String> calibers = extractAll(CALIBER, text);

        String firstTime = times.isEmpty() ? "" : times.get(0);
        String firstCaliber = calibers.isEmpty() ? "" : calibers.get(0);

        Matcher m = NUMBER_UNIT.matcher(text);
        while (m.find()) {
            String value = m.group(1).replace(",", "");
            String unit = m.group(2) == null ? m.group(3) : m.group(2) + m.group(3);

            // 取数值附近 80 字符上下文来推断时间和口径
            int ctxStart = Math.max(0, m.start() - 40);
            int ctxEnd = Math.min(text.length(), m.end() + 40);
            String window = text.substring(ctxStart, ctxEnd);

            List<String> localTimes = extractAll(TIME, window);
            List<String> localCalibers = extractAll(CALIBER, window);

            String dpTime = localTimes.isEmpty() ? firstTime : localTimes.get(0);
            String dpCaliber = localCalibers.isEmpty() ? firstCaliber : localCalibers.get(0);

            points.add(new DataPoint(
                    value, unit, "", dpTime, dpCaliber, "",
                    window.trim()
            ));
        }
    }

    // ======================== 内部实现 ========================

    private static List<DataPoint> extractDataPoints(List<Message> messages) {
        List<DataPoint> points = new ArrayList<>();
        for (Message msg : messages) {
            if (msg.role() == Message.Role.TOOL) {
                extractDataPointsFromText(msg.content(), points);
            }
        }
        return points;
    }

    private static List<String> extractAll(Pattern p, String text) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static boolean needsCalculation(String task) {
        return task != null && CALC_TASK.matcher(task).find();
    }

    // ---- 1. 计算验证（计算与引用区分） ----

    private static CheckResult verifyCalculation(String answer) {
        boolean hasForwardCalc = FORWARD_CALC.matcher(answer).find();
        boolean hasCiteDisclosure = CITE_DISCLOSURE.matcher(answer).find();

        if (!hasForwardCalc && !hasCiteDisclosure) {
            return CheckResult.fail(
                    "任务要求计算，但答复中既无正向计算过程，也无引用声明。请二选一：" +
                            "① 展示由两期原始数据出发的计算式（如 (本期-去年同期)/去年同期）；" +
                            "② 写明「该指标采用××已公布值（引用，非自行计算），因缺少同期同口径原始数据」，" +
                            "并可用 本期/(1±同比) 反推验算其算术自洽。"
            );
        }
        return CheckResult.ok();
    }

    // ---- 2. 答复数值溯源（幽灵数值） ----

    /**
     * 答复中的显著数值必须满足其一：工具结果支撑 / 出现在计算式行 / 带推导或紧邻近似标注。
     * 百分比不硬校验（修辞性阈值如"差异超 20%"易误伤，由 CompletionJudge LLM 评审承担）。
     */
    private static CheckResult verifyAnswerValueProvenance(List<Message> messages, String answer,
                                                           List<DataPoint> dataPoints) {
        ToolValueSet toolValues = ToolValueSet.of(dataPoints, messages);

        List<String> ghosts = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 候选一：带单位的显著数值（基础单位 ≥1000；带万/亿倍率任意；百分比跳过）
        Matcher m = NUMBER_UNIT.matcher(answer);
        while (m.find()) {
            String unit = m.group(2) == null ? m.group(3) : m.group(2) + m.group(3);
            if (isPercent(unit)) {
                continue;
            }
            String raw = m.group(1).replace(",", "");
            double v = parseOr(raw, -1);
            if (v < 0) {
                continue;
            }
            boolean bigUnit = unit.startsWith("万") || unit.startsWith("亿");
            if (!bigUnit && v < NOTABLE_BASE) {
                continue;
            }
            if (seen.add(raw) && !toolValues.backs(raw, effective(raw, unit))
                    && !exemptByContext(answer, m.start(), m.end())) {
                ghosts.add(m.group(1) + unit);
            }
        }

        // 候选二：五位以上裸数字（表格数值常不带单位，单位只在表头）
        Matcher b = BIG_NUMBER.matcher(answer);
        while (b.find()) {
            String raw = b.group().replace(",", "");
            double v = parseOr(raw, -1);
            if (v < BARE_NOTABLE) {
                continue; // 年份、序号等豁免
            }
            if (seen.add(raw) && !toolValues.backs(raw, v)
                    && !exemptByContext(answer, b.start(), b.end())) {
                ghosts.add(b.group());
            }
        }

        if (!ghosts.isEmpty()) {
            return CheckResult.fail(
                    "答复中存在无法溯源且未标注推导性质的数值: " +
                            String.join("、", ghosts.stream().limit(5).toList()) +
                            "。每个数值须满足其一：① 直接来自工具结果（支持万/亿倍率换算与四舍五入容差）；" +
                            "② 出现在计算式（含 = 或两数相除）中；" +
                            "③ 所在行标注反推/推算/估算/推导，或紧邻数值使用 约/≈/左右 等近似表述" +
                            "（如「约5.4万，为推导近似值，仅供参考」）。"
            );
        }
        return CheckResult.ok();
    }

    /** 工具侧数值库：原文串 + 规范化数值（万/亿换算到基准单位），支持双向匹配 */
    private record ToolValueSet(Set<String> raws, List<Double> effs) {

        static ToolValueSet of(List<DataPoint> dataPoints, List<Message> messages) {
            Set<String> raws = new HashSet<>();
            List<Double> effs = new ArrayList<>();
            for (DataPoint dp : dataPoints) {
                if (isPercent(dp.unit())) {
                    continue;
                }
                raws.add(dp.value());
                double eff = effective(dp.value(), dp.unit());
                if (eff >= 0) {
                    effs.add(eff);
                }
            }
            for (Message msg : messages) {
                if (msg.role() != Message.Role.TOOL) {
                    continue;
                }
                Matcher b = BIG_NUMBER.matcher(msg.content());
                while (b.find()) {
                    String raw = b.group().replace(",", "");
                    raws.add(raw);
                    double eff = parseOr(raw, -1);
                    if (eff >= 0) {
                        effs.add(eff);
                    }
                }
            }
            return new ToolValueSet(raws, effs);
        }

        boolean backs(String raw, double eff) {
            if (raws.contains(raw)) {
                return true;
            }
            for (double t : effs) {
                if (t > 0 && Math.abs(eff - t) <= Math.max(1, Math.abs(t) * VALUE_TOLERANCE)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 把「数值+单位」规范化为基准单位数值（万/亿倍率换算，如 5.5万元 → 55000） */
    private static double effective(String rawNumber, String unit) {
        double v = parseOr(rawNumber, -1);
        if (v < 0) {
            return -1;
        }
        if (unit != null && unit.startsWith("亿")) {
            return v * 1e8;
        }
        if (unit != null && unit.startsWith("万")) {
            return v * 1e4;
        }
        return v;
    }

    private static boolean isPercent(String unit) {
        return unit != null && (unit.contains("%") || unit.contains("％") || unit.contains("个百分点"));
    }

    /**
     * 豁免判定：所在行含显式推导声明或计算式（= / 两数相除）→ 整行豁免；
     * 否则近似词须紧邻数值（±APPROX_WINDOW 字符）才豁免。
     */
    private static boolean exemptByContext(String answer, int start, int end) {
        int lineStart = answer.lastIndexOf('\n', start) + 1;
        int lineEnd = answer.indexOf('\n', end);
        if (lineEnd < 0) {
            lineEnd = answer.length();
        }
        String line = answer.substring(lineStart, lineEnd);
        if (DERIVED_EXPLICIT.matcher(line).find() || CALC_CONTEXT.matcher(line).find()) {
            return true;
        }
        int nearStart = Math.max(lineStart, start - APPROX_WINDOW);
        int nearEnd = Math.min(lineEnd, end + APPROX_WINDOW);
        return APPROX_NEAR.matcher(answer.substring(nearStart, nearEnd)).find();
    }

    private static double parseOr(String s, double dft) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    // ---- 3. 口径一致性 ----

    private static CheckResult verifyCaliberConsistency(String answer, List<DataPoint> dataPoints) {
        // 归一口径标签
        Map<String, List<DataPoint>> byNormCaliber = new HashMap<>();
        for (DataPoint dp : dataPoints) {
            String norm = normalizeCaliber(dp.caliber());
            byNormCaliber.computeIfAbsent(norm, k -> new ArrayList<>()).add(dp);
        }

        // 过滤掉"未知"口径组（无法判断）
        List<String> knownCalibers = byNormCaliber.keySet().stream()
                .filter(k -> !"未知".equals(k))
                .toList();

        if (knownCalibers.size() <= 1) {
            return CheckResult.ok(); // 最多一种已知口径，无需检查
        }

        // 多种口径 → 检查答复是否说明了差异
        boolean hasExplanation = answer.contains("口径") ||
                answer.contains("不同平台") ||
                answer.contains("统计方式") ||
                answer.contains("统计范围") ||
                answer.contains("统计标准");

        if (!hasExplanation) {
            return CheckResult.fail(
                    "发现多种数据口径（" + String.join("、", knownCalibers) +
                            "），但答复中未说明口径差异。" +
                            "请在答复中明确标注每个数值的口径，避免将不同口径的数据混为一表。" +
                            "若某口径数据暂缺，须明确写出「××口径暂缺，采用××口径替代」。"
            );
        }

        return CheckResult.ok();
    }

    /** 口径归一化（跨领域：价格/统计口径） */
    private static String normalizeCaliber(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未知";
        }
        String s = raw.trim();
        if (s.contains("挂牌")) return "挂牌价";
        if (s.contains("成交")) return "成交价";
        if (s.contains("网签")) return "网签价";
        if (s.contains("备案")) return "备案价";
        if (s.contains("零售")) return "零售价";
        if (s.contains("批发")) return "批发价";
        if (s.contains("出厂")) return "出厂价";
        if (s.contains("指数")) return "指数";
        if (s.contains("参考")) return "参考价";
        if (s.contains("中位")) return "中位数";
        if (s.contains("均价") || s.contains("平均")) return "均价";
        return s;
    }

    // ---- 4. 数据矛盾 ----

    private static CheckResult verifyDataConflicts(String answer, List<DataPoint> dataPoints) {
        // 按 单位+口径+时间 归组（同单位同口径同期才可比；跨期涨跌不是矛盾）
        Map<String, List<DataPoint>> groups = new HashMap<>();
        for (DataPoint dp : dataPoints) {
            String key = dp.unit() + "|" + normalizeCaliber(dp.caliber()) + "|" + dp.time();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(dp);
        }

        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, List<DataPoint>> entry : groups.entrySet()) {
            List<DataPoint> pts = entry.getValue();
            if (pts.size() < 2) {
                continue;
            }
            try {
                double min = pts.stream()
                        .mapToDouble(dp -> Double.parseDouble(dp.value()))
                        .min().orElse(0);
                double max = pts.stream()
                        .mapToDouble(dp -> Double.parseDouble(dp.value()))
                        .max().orElse(0);

                if (min > 0 && (max - min) / min > CONFLICT_RATIO) {
                    // 差异显著 → 检查答复是否处理
                    boolean explained = answer.contains("差异") ||
                            answer.contains("矛盾") ||
                            answer.contains("不一致") ||
                            answer.contains("分歧") ||
                            answer.contains("相差") ||
                            answer.contains("出入") ||
                            answer.contains("来源不同") ||
                            answer.contains("口径") ||
                            answer.contains("采信") ||
                            answer.contains("主口径");

                    if (!explained) {
                        warnings.add(String.format(
                                "同期同口径数据存在显著差异（%.0f vs %.0f，偏差 %.0f%%），" +
                                        "建议在答复中说明原因或选择主口径",
                                min, max, (max - min) / min * 100
                        ));
                    }
                }
            } catch (NumberFormatException ignored) {
            }
        }

        if (!warnings.isEmpty()) {
            return CheckResult.warn("发现数据矛盾", warnings);
        }
        return CheckResult.ok();
    }
}
