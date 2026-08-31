package com.mkr.tools;

import java.util.List;
import java.util.Map;

/** 工具参数取值辅助（LLM 参数容错：数字可能是字符串、null 缺省等）。 */
public final class ToolArgs {

    private ToolArgs() {
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static String str(Map<String, Object> m, String key, String dft) {
        String v = str(m, key);
        return v == null || v.isBlank() ? dft : v;
    }

    public static int Int(Map<String, Object> m, String key, int dft) {
        Object v = m.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return (int) Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return dft;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean dft) {
        Object v = m.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            return s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes");
        }
        return dft;
    }

    public static List<String> strList(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }
}
