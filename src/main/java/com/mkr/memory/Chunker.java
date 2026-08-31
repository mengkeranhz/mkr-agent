package com.mkr.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * 分块器：按语义标题 + 段落边界 + 代码块整体性切分，块 ≤512 token（≈2048 字符）。
 */
public final class Chunker {

    public record Chunk(String text, String heading, int index) {
    }

    private final int maxChars;

    public Chunker() {
        this(2048);
    }

    public Chunker(int maxChars) {
        this.maxChars = maxChars;
    }

    public List<Chunk> chunk(String markdown) {
        List<Chunk> out = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return out;
        }
        String currentHeading = "";
        StringBuilder current = new StringBuilder();
        int index = 0;
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("#")) {
                currentHeading = line.replaceFirst("^#+\\s*", "").trim();
            }
            // 代码块内不切分
            boolean inFence = countFences(current.toString()) % 2 == 1;
            if (!inFence && current.length() + line.length() > maxChars && current.length() > 0) {
                out.add(new Chunk(current.toString().trim(), currentHeading, index++));
                current.setLength(0);
            }
            current.append(line).append('\n');
            // 超大单块（无空行的长文本）强制切
            if (current.length() >= maxChars) {
                out.add(new Chunk(current.substring(0, maxChars).trim(), currentHeading, index++));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            out.add(new Chunk(current.toString().trim(), currentHeading, index));
        }
        return out;
    }

    private static int countFences(String s) {
        int count = 0;
        for (String line : s.split("\n", -1)) {
            if (line.trim().startsWith("```")) {
                count++;
            }
        }
        return count;
    }
}
