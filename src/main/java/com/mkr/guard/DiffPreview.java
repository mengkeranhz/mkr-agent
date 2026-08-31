package com.mkr.guard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 审批 diff 预览：公共前后缀 + 中段增删行（有界），供 ApprovalPrompter 展示。
 */
public final class DiffPreview {

    private DiffPreview() {
    }

    public static String of(String oldText, String newText, int maxLines) {
        List<String> oldLines = lines(oldText);
        List<String> newLines = lines(newText);
        int prefix = 0;
        while (prefix < oldLines.size() && prefix < newLines.size()
                && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < oldLines.size() - prefix && suffix < newLines.size() - prefix
                && oldLines.get(oldLines.size() - 1 - suffix).equals(newLines.get(newLines.size() - 1 - suffix))) {
            suffix++;
        }
        List<String> out = new ArrayList<>();
        if (prefix == oldLines.size() && prefix == newLines.size()) {
            return "(无变化)";
        }
        if (prefix > 0) {
            out.add("  …（前 " + prefix + " 行相同）");
        }
        int shown = 0;
        for (int i = prefix; i < oldLines.size() - suffix && shown < maxLines; i++, shown++) {
            out.add("- " + oldLines.get(i));
        }
        shown = 0;
        for (int i = prefix; i < newLines.size() - suffix && shown < maxLines; i++, shown++) {
            out.add("+ " + newLines.get(i));
        }
        int oldRemain = Math.max(0, oldLines.size() - suffix - prefix - maxLines);
        int newRemain = Math.max(0, newLines.size() - suffix - prefix - maxLines);
        if (oldRemain > 0) {
            out.add("  …（另有 " + oldRemain + " 行删除未展示）");
        }
        if (newRemain > 0) {
            out.add("  …（另有 " + newRemain + " 行新增未展示）");
        }
        if (suffix > 0) {
            out.add("  …（后 " + suffix + " 行相同）");
        }
        return String.join("\n", out);
    }

    public static String deletion(Path file, int maxLines) {
        try {
            if (!Files.isRegularFile(file)) {
                return "(目标不存在)";
            }
            List<String> all = lines(Files.readString(file));
            List<String> shown = all.subList(0, Math.min(all.size(), maxLines));
            StringBuilder sb = new StringBuilder("将删除 " + all.size() + " 行:\n");
            shown.forEach(l -> sb.append("- ").append(l).append("\n"));
            if (all.size() > maxLines) {
                sb.append("…（另有 ").append(all.size() - maxLines).append(" 行）\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "(无法读取原文件: " + e.getMessage() + ")";
        }
    }

    public static String content(String text, int maxLines) {
        List<String> all = lines(text);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(all.size(), maxLines); i++) {
            sb.append("+ ").append(all.get(i)).append("\n");
        }
        if (all.size() > maxLines) {
            sb.append("…（共 ").append(all.size()).append(" 行）\n");
        }
        return sb.toString();
    }

    private static List<String> lines(String s) {
        if (s == null || s.isEmpty()) {
            return List.of();
        }
        return List.of(s.split("\n", -1));
    }
}
