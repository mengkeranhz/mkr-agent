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
 * 数据一致性验证器（纯代码，无 LLM）。
 *
 * <p>在 CompletionJudge 的引用合规检查之后、策略判定之前执行。
 * 解决四类数据质量问题：
 * <ol>
 *   <li>口径不匹配：最新值和同比数据来自不同口径（挂牌价 vs 网签成交价）</li>
 *   <li>未实际计算且未声明引用：任务要求"计算"但直接引用现成数据，无计算过程也无引用声明
 *       （反推形 X/(1±p) 不算正向计算）</li>
 *   <li>幽灵数值：答复中的货币类数值既无工具结果支撑，也无推导标注（反推/估算/约）或计算式</li>
 *   <li>数据矛盾未处理：同一指标多来源差异大但未做出判断</li>
 * </ol>
 * 当轨迹中无 TOOL 消息时整体豁免（纯本地/闲聊任务不受影响）。</p>
 */
public final class DataConsistencyVerifier {

    /** 数据点：从工具结果中提取的数值数据 */
    public record DataPoint(
            String value,    // 数值（纯数字，已去逗号）
            String unit,     // 单位
            String metric,   // 指标名称（从上下文推断）
            String time,     // 时间
            String caliber,  // 口径（挂牌价/成交价/网签价/指数等）
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

    // ---- 正则 ----

    /** 数值+单位 */
    private static final Pattern NUMBER_UNIT = Pattern.compile(
            "([\\d,]+\\.?\\d*)\\s*(元/㎡|元/平米|元/平方米|万元|亿元|%)"
    );

    /** 时间 */
    private static final Pattern TIME = Pattern.compile(
            "\\d{4}年\\d{1,2}月|\\d{4}-\\d{2}|\\d{4}年"
    );

    /** 口径关键词 */
    private static final Pattern CALIBER = Pattern.compile(
            "挂牌[价均价]?|成交[价均价]?|网签[价均价]?|备案价|参考价|均价|指数|同比|环比"
    );

    /** 任务中的计算关键词 */
    private static final Pattern CALC_TASK = Pattern.compile(
            "计算|算出|求.*增[减幅跌涨]|算.*同比|算.*环比|同比.*增跌|增跌幅"
    );

    /** 正向计算表达式：(A-B)/B、A/B-1、两大数相除（分母为 "(1±p)" 的反推形因含括号不会命中） */
    private static final Pattern FORWARD_CALC = Pattern.compile(
            "\\([\\d,.]+\\s*[-－]\\s*[\\d,.]+\\)\\s*/\\s*[\\d,.]+"   // (A-B)/B
                    + "|[\\d,.]+\\s*/\\s*[\\d,.]+\\s*-\\s*1"        // A/B-1
                    + "|[\\d,.]{4,}\\s*/\\s*[\\d,.]{4,}"            // 两大数相除
    );

    /** 引用披露标记：声明数值来自来源已公布结果而非自行计算 */
    private static final Pattern CITE_DISCLOSURE = Pattern.compile(
            "已公布|发布值|公布的|官方发布|引用|非自行计算|替代"
    );

    /** 推导标记：反推/估算出的近似值 */
    private static final Pattern DERIVED_MARK = Pattern.compile(
            "反推|推算|估算|约|≈|左右|参考"
    );

    /** 计算式上下文：等号或两数相除 */
    private static final Pattern CALC_CONTEXT = Pattern.compile(
            "[=＝]|[\\d,]\\s*/\\s*[\\d(]"
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

        // 只在有 TOOL 消息时启用（无外部数据则豁免）
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
            String unit = m.group(2);

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
                            "② 写明「同比采用××已公布值（引用，非自行计算），因缺少同期同口径原始数据」，" +
                            "并可用 本期/(1+同比) 反推验算其算术自洽。"
            );
        }
        return CheckResult.ok();
    }

    // ---- 2. 答复数值溯源（幽灵数值） ----

    /**
     * 答复中的货币类数值必须满足其一：工具结果支撑 / 出现在计算式行 / 带推导标记。
     * 百分比不硬校验（修辞性阈值如"差异超 20%"易误伤，由 CompletionJudge LLM 评审承担）。
     */
    private static CheckResult verifyAnswerValueProvenance(List<Message> messages, String answer,
                                                           List<DataPoint> dataPoints) {
        Set<String> toolValues = toolBackedValues(dataPoints, messages);

        List<String> ghosts = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 候选一：带货币单位的数值（元类 ≥1000；万元/亿元任意）
        Matcher m = NUMBER_UNIT.matcher(answer);
        while (m.find()) {
            String unit = m.group(2);
            if (unit.contains("%")) {
                continue;
            }
            String raw = m.group(1).replace(",", "");
            double v = parseOr(raw, -1);
            if (v < 0) {
                continue;
            }
            boolean bigUnit = unit.startsWith("万") || unit.startsWith("亿");
            if (!bigUnit && v < 1000) {
                continue;
            }
            if (seen.add(raw) && !backed(raw, v, unit, toolValues) && !exemptByContext(answer, m.start(), m.end())) {
                ghosts.add(m.group(1) + unit);
            }
        }

        // 候选二：五位以上裸数字（表格数值常不带单位，单位只在表头）
        Matcher b = BIG_NUMBER.matcher(answer);
        while (b.find()) {
            String raw = b.group().replace(",", "");
            double v = parseOr(raw, -1);
            if (v < 10000) {
                continue; // 年份、序号等豁免
            }
            if (seen.add(raw) && !toolValues.contains(raw) && !exemptByContext(answer, b.start(), b.end())) {
                ghosts.add(b.group());
            }
        }

        if (!ghosts.isEmpty()) {
            return CheckResult.fail(
                    "答复中存在无法溯源且未标注推导性质的数值: " +
                            String.join("、", ghosts.stream().limit(5).toList()) +
                            "。每个数值须满足其一：① 直接来自工具结果；② 出现在计算式（含 = ）中；" +
                            "③ 标注反推/估算/约/≈ 并声明「该值为根据××反推的近似值，仅供参考，非来源直接发布」。"
            );
        }
        return CheckResult.ok();
    }

    /** 工具轨迹中的全部货币数值（DataPoint + TOOL 消息中的大数） */
    private static Set<String> toolBackedValues(List<DataPoint> dataPoints, List<Message> messages) {
        Set<String> vals = new HashSet<>();
        for (DataPoint dp : dataPoints) {
            if (!dp.unit().contains("%")) {
                vals.add(dp.value());
            }
        }
        for (Message msg : messages) {
            if (msg.role() != Message.Role.TOOL) {
                continue;
            }
            Matcher b = BIG_NUMBER.matcher(msg.content());
            while (b.find()) {
                vals.add(b.group().replace(",", ""));
            }
        }
        return vals;
    }

    /** 工具支撑判定：精确匹配；万元/亿元换算为元后匹配 */
    private static boolean backed(String raw, double v, String unit, Set<String> toolValues) {
        if (toolValues.contains(raw)) {
            return true;
        }
        if (unit.startsWith("万") || unit.startsWith("亿")) {
            double factor = unit.startsWith("亿") ? 1e8 : 1e4;
            return toolValues.contains(String.valueOf(Math.round(v * factor)));
        }
        return false;
    }

    /** 所在行含推导标记（反推/估算/约/≈）或计算式（= 或两数相除）则豁免 */
    private static boolean exemptByContext(String answer, int start, int end) {
        int lineStart = answer.lastIndexOf('\n', start) + 1;
        int lineEnd = answer.indexOf('\n', end);
        if (lineEnd < 0) {
            lineEnd = answer.length();
        }
        String line = answer.substring(lineStart, lineEnd);
        return DERIVED_MARK.matcher(line).find() || CALC_CONTEXT.matcher(line).find();
    }

    private static double parseOr(String s, double dft) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return dft;
        }
    }

    // ---- 2. 口径一致性 ----

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
                answer.contains("口径不同") ||
                answer.contains("口径差异") ||
                answer.contains("统计口径") ||
                answer.contains("口径不一致") ||
                answer.contains("不同平台") ||
                answer.contains("口径说明") ||
                answer.contains("非同口径");

        if (!hasExplanation) {
            return CheckResult.fail(
                    "发现多种数据口径（" + String.join("、", knownCalibers) +
                            "），但答复中未说明口径差异。" +
                            "请在答复中明确标注每个数值的口径，避免将不同口径的数据混为一表。" +
                            "若无法获取同口径的同比数据，须明确写出「挂牌均价同比暂缺，采用××口径替代」。"
            );
        }

        return CheckResult.ok();
    }

    /** 口径归一化 */
    private static String normalizeCaliber(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未知";
        }
        String s = raw.trim();
        if (s.contains("挂牌")) return "挂牌价";
        if (s.contains("成交")) return "成交价";
        if (s.contains("网签")) return "网签价";
        if (s.contains("备案")) return "备案价";
        if (s.contains("指数")) return "价格指数";
        if (s.contains("参考")) return "参考价";
        if (s.contains("均价")) return "均价";
        return s;
    }

    // ---- 3. 数据矛盾 ----

    private static CheckResult verifyDataConflicts(String answer, List<DataPoint> dataPoints) {
        // 按 unit+caliber 归组（同单位同口径的才可比）
        Map<String, List<DataPoint>> groups = new HashMap<>();
        for (DataPoint dp : dataPoints) {
            String key = dp.unit() + "|" + normalizeCaliber(dp.caliber());
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

                if (min > 0 && (max - min) / min > 0.2) {
                    // 差异超 20%，检查答复是否处理
                    boolean explained = answer.contains("差异") ||
                            answer.contains("矛盾") ||
                            answer.contains("不一致") ||
                            answer.contains("数据来源不同") ||
                            answer.contains("口径差异") ||
                            answer.contains("口径不同") ||
                            answer.contains("采信") ||
                            answer.contains("主口径");

                    if (!explained) {
                        warnings.add(String.format(
                                "同组数据存在显著差异（%.0f vs %.0f，偏差 %.0f%%），" +
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
