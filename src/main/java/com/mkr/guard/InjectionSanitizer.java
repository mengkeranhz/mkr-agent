package com.mkr.guard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 注入清洗：web/文件等外部内容统一包装来源标签、截断（≤50K token ≈ 200K chars）、
 * 去控制字符；记忆/skill 写入前做提示注入信任审查。
 */
public final class InjectionSanitizer {

    /** ≈50K token（chars/4 启发式）。 */
    public static final int MAX_EXTERNAL_CHARS = 200_000;

    /** source="…" 属性（&lt;external source="URL"&gt; 原始形式与折叠保留形式均匹配）。 */
    private static final Pattern SOURCE_ATTR = Pattern.compile("source=\"([^\"]+)\"");

    private static final String[] SUSPICIOUS = {
            "ignore previous instructions", "ignore all previous", "disregard the above",
            "disregard previous", "forget your instructions", "override your instructions",
            "忽略之前的指令", "忽略以上指令", "无视之前的", "覆盖你的指令", "你现在是一个新的",
            "system prompt:", "reveal your system prompt", "打印你的系统提示"
    };

    /** 包装外部内容并标注来源。 */
    public String wrap(String content, String source) {
        String body = truncate(content == null ? "" : content, MAX_EXTERNAL_CHARS);
        return "<external source=\"" + (source == null ? "unknown" : source) + "\">\n"
                + body + "\n</external>";
    }

    /** 提取内容中全部 source="…" 来源 URL（去重保序）；折叠/预览保留的形式同样可提取。 */
    public static List<String> extractSources(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = SOURCE_ATTR.matcher(content);
        while (m.find()) {
            String u = m.group(1).strip();
            if (!u.isEmpty() && !out.contains(u)) {
                out.add(u);
            }
        }
        return out;
    }

    /** 压缩折叠/截断时保留来源：生成 {@code [folded-sources source="u1" source="u2"]}（空来源返回 ""）。 */
    public static String retainedSources(String content) {
        List<String> urls = extractSources(content);
        if (urls.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[folded-sources");
        for (String u : urls) {
            sb.append(" source=\"").append(u).append("\"");
        }
        return sb.append(']').toString();
    }

    public String truncate(String s, int maxChars) {
        if (s == null || s.length() <= maxChars) {
            return s == null ? "" : s;
        }
        int head = (int) (maxChars * 0.8);
        int tail = maxChars - head;
        return s.substring(0, head) + "\n…[已截断，原始长度 " + s.length() + " 字符，尾部 " + tail + " 字符省略]…\n"
                + s.substring(s.length() - tail);
    }

    /** 疑似提示注入（外部内容中的指令劫持话术）。 */
    public boolean suspicious(String s) {
        if (s == null) {
            return false;
        }
        String lower = s.toLowerCase();
        for (String marker : SUSPICIOUS) {
            if (lower.contains(marker.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 记忆/skill 写入信任审查：截断 + 注入话术拒绝。 */
    public String sanitizeMemory(String text) {
        String cleaned = stripControl(truncate(text, MAX_EXTERNAL_CHARS));
        if (suspicious(cleaned)) {
            throw new IllegalArgumentException("内容疑似提示注入（包含指令劫持话术），已拒绝写入。可改写为中性陈述后重试。");
        }
        return cleaned;
    }

    public static String stripControl(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
    }
}
