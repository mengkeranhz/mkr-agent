package com.mkr.verify;

import com.mkr.api.Message;
import com.mkr.guard.InjectionSanitizer;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 引用合规校验（纯代码，无 LLM）——与 CompletionJudge 同一哲学：模型不能自我证明可溯源。
 *
 * <p>规则（轨迹中无 &lt;external source=…&gt; 外部内容时整体豁免，纯本地/闲聊任务不受影响）：
 * ① 防编造：最终答复中出现的 URL 必须落在轨迹来源白名单内；
 * ② 防漏标：任务用过外部检索时，答复须含至少一个来源 URL，或明确标注「未溯源」。</p>
 */
public final class CitationVerifier {

    /** 回答中 URL 提取（排除空白与 markdown/中文括号尾缀）。 */
    private static final Pattern URL_IN_ANSWER = Pattern.compile("https?://[^\\s<>\"'）)\\]]+");

    private static final String UNCITED = "未溯源";

    private CitationVerifier() {
    }

    public record Check(boolean ok, String reason) {
    }

    public static Check check(List<Message> messages, String answer) {
        StringBuilder toolContent = new StringBuilder();
        for (Message m : messages) {
            if (m.role() == Message.Role.TOOL) {
                toolContent.append(m.content()).append('\n');
            }
        }
        List<String> whitelist = InjectionSanitizer.extractSources(toolContent.toString());
        if (whitelist.isEmpty()) {
            return new Check(true, "无外部内容，豁免引用校验");
        }
        String ans = answer == null ? "" : answer;

        // ① 防编造：答复 URL 必须可溯源
        Matcher m = URL_IN_ANSWER.matcher(ans);
        while (m.find()) {
            String u = m.group().replaceAll("[.,;，。；：]+$", "");
            if (!matchesWhitelist(u, whitelist)) {
                return new Check(false, "答复中的 URL 不在轨迹来源（<external source=…>）内，疑似编造: " + u
                        + "。只能引用工具结果中的来源 URL，无法溯源的表述改标「未溯源」。");
            }
        }

        // ② 防漏标：用过外部检索，答复须有来源标注
        boolean cited = whitelist.stream().anyMatch(ans::contains);
        if (!cited && !ans.contains(UNCITED)) {
            return new Check(false, "任务使用了外部检索，但答复未标注任何来源 URL，也未标注「未溯源」。"
                    + "请为事实性论断逐条补充来源 URL（轨迹来源: " + firstFew(whitelist) + "），或标注「未溯源」。");
        }
        return new Check(true, "引用合规");
    }

    /** 精确匹配或前缀扩展（锚点/尾斜杠差异容忍）。 */
    private static boolean matchesWhitelist(String url, List<String> whitelist) {
        for (String w : whitelist) {
            if (w.equals(url) || w.startsWith(url) || url.startsWith(w)) {
                return true;
            }
        }
        return false;
    }

    private static String firstFew(List<String> urls) {
        List<String> few = urls.stream().limit(3).toList();
        return String.join("; ", few) + (urls.size() > 3 ? " 等 " + urls.size() + " 个" : "");
    }
}
