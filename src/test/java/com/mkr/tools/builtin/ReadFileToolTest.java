package com.mkr.tools.builtin;

import com.mkr.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** read_file keywords：段落切分、关键字匹配、结果数上限、边界与截断。 */
class ReadFileToolTest {

    @Test
    void parseKeywordsVariants() {
        assertEquals(List.of("甜品", "巧克力", "饼干"), ReadFileTool.parseKeywords("甜品,巧克力,饼干"));
        assertEquals(List.of("甜品", "巧克力", "饼干"), ReadFileTool.parseKeywords("甜品，巧克力,饼干"));
        assertEquals(List.of("甜品", "巧克力"), ReadFileTool.parseKeywords(" 甜品 ,, 巧克力 , 甜品 "));
        assertEquals(List.of("甜品", "巧克力"), ReadFileTool.parseKeywords("甜品;巧克力；甜品"));
        assertEquals(List.of(), ReadFileTool.parseKeywords(null));
        assertEquals(List.of(), ReadFileTool.parseKeywords("  ,，;  "));
    }

    @Test
    void returnsCompleteParagraphs() {
        List<String> lines = List.of(
                "引言段落第一行",
                "引言段落第二行",
                "",
                "这里提到甜品相关的内容。",
                "同一段的后续文字，应该一起返回。",
                "",
                "完全无关的一段。",
                "",
                "巧克力在结尾出现。");
        ToolResult r = ReadFileTool.searchByKeywords(List.of("甜品"), lines, 5);
        assertTrue(r.success());
        String out = r.output();
        assertTrue(out.contains("甜品相关的内容"), out);
        assertTrue(out.contains("同一段的后续文字"), out); // 完整段落
        assertFalse(out.contains("完全无关的一段"), out);
        assertFalse(out.contains("巧克力在结尾出现"), out);
        assertFalse(out.contains("引言段落第一行"), out);
    }

    @Test
    void multipleParagraphsAndIgnoreCase() {
        List<String> lines = List.of(
                "First paragraph about chocolate.",
                "",
                "Second paragraph with CAKE.",
                "",
                "Third without.");
        ToolResult r = ReadFileTool.searchByKeywords(List.of("chocolate", "cake"), lines, 5);
        assertTrue(r.success());
        String out = r.output();
        assertTrue(out.contains("命中 2 段"), out);
        assertTrue(out.contains("First paragraph about chocolate."), out);
        assertTrue(out.contains("Second paragraph with CAKE."), out); // 忽略大小写
        assertFalse(out.contains("Third without."), out);
    }

    @Test
    void noMatch() {
        ToolResult r = ReadFileTool.searchByKeywords(List.of("不存在"), List.of("abc", "", "def"), 5);
        assertTrue(r.success());
        assertTrue(r.output().contains("无命中"), r.output());
    }

    @Test
    void blankLineOnlyContent() {
        // 单段且命中首行：整段（含空行外所有行）返回
        ToolResult r = ReadFileTool.searchByKeywords(List.of("a"), List.of("a-line", "b-line", "c-line"), 5);
        assertTrue(r.success());
        assertTrue(r.output().contains("a-line"));
        assertTrue(r.output().contains("c-line"));
    }

    @Test
    void limitsResultsToMax() {
        List<String> lines = new ArrayList<>();
        for (int p = 0; p < 8; p++) {
            lines.add("段落" + p + " 包含关键字");
            lines.add("");
        }
        ToolResult r = ReadFileTool.searchByKeywords(List.of("关键字"), lines, 3);
        assertTrue(r.success());
        String out = r.output();
        assertTrue(out.contains("命中 8 段"), out);
        assertTrue(out.contains("仅展示前 3 段"), out);
        assertTrue(out.contains("段落0 包含关键字"), out);
        assertTrue(out.contains("段落2 包含关键字"), out);
        assertFalse(out.contains("段落3 包含关键字"), out);
        assertTrue(out.contains("另有 5 段未展示"), out);
    }

    @Test
    void zeroMeansUnlimited() {
        List<String> lines = new ArrayList<>();
        for (int p = 0; p < 8; p++) {
            lines.add("段落" + p + " 包含关键字");
            lines.add("");
        }
        ToolResult r = ReadFileTool.searchByKeywords(List.of("关键字"), lines, 0);
        assertTrue(r.success());
        String out = r.output();
        assertTrue(out.contains("命中 8 段"), out);
        assertTrue(out.contains("段落7 包含关键字"), out);
        assertFalse(out.contains("仅展示"), out);
        assertFalse(out.contains("未展示"), out);
    }

    @Test
    void truncatesOnBudget() {
        // 单段超长（远超 MAX_KEYWORD_CHARS），应截断并给出提示
        List<String> lines = new ArrayList<>();
        lines.add("命中");
        for (int i = 0; i < 100_000; i++) {
            lines.add("x".repeat(100));
        }
        ToolResult r = ReadFileTool.searchByKeywords(List.of("命中"), lines, 5);
        assertTrue(r.success());
        assertTrue(r.output().contains("已截断"), "应包含截断提示");
        assertTrue(r.output().length() < 80_000, "输出应被约束，实际=" + r.output().length());
    }
}
