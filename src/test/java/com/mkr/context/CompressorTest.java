package com.mkr.context;

import com.mkr.api.Message;
import com.mkr.api.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 压缩器：① 大输出写盘归档；② 噪声折叠。 */
class CompressorTest {

    @TempDir
    Path tmp;

    private ContextConfig cfg(int contextMax, int threshold) {
        return new ContextConfig(contextMax, true, true, threshold, 3, 30, 5, 60_000);
    }

    @Test
    void tier1CapsToolOutputToArtifact() throws Exception {
        Compressor c = new Compressor(cfg(64_000, 1_000), tmp, null, "test");
        String big = "x".repeat(5_000);
        String capped = c.capToolOutput("bash", big);
        assertTrue(capped.contains(".artifacts"), capped.substring(0, 80));
        assertTrue(capped.length() < big.length());
        assertTrue(Files.list(tmp.resolve(".artifacts")).findAny().isPresent(), "artifact 文件应已写盘");
    }

    @Test
    void tier2FoldsOldToolOutputsOnBudget() {
        Compressor c = new Compressor(cfg(3_000, 8_000), tmp, null, "test");
        MessageList ml = new MessageList();
        ml.append(Message.system("S"));
        ml.append(Message.user("T"));
        ml.markPrefix();
        for (int i = 0; i < 12; i++) {
            ToolCall call = new ToolCall("id-" + i, "bash", "{\"command\":\"echo " + i + "\"}");
            ml.append(Message.assistant("", null, List.of(call)));
            ml.append(Message.toolResult("id-" + i, "bash",
                    "line " + i + "\n" + "detail ".repeat(200))); // 每条 ≈350 token
        }
        int before = ml.estimateTokens();
        boolean compressed = c.compressIfNeeded(ml);
        assertTrue(compressed);
        assertTrue(ml.estimateTokens() < before, "压缩后 token 应下降: " + ml.estimateTokens() + " < " + before);
        // 旧工具结果被折叠为一行（保留最近 4 条完整）
        long folded = ml.suffix().stream()
                .filter(m -> m.role() == Message.Role.TOOL)
                .filter(m -> m.content().startsWith("[tool bash "))
                .count();
        assertTrue(folded > 0, "应有折叠的工具输出");
    }

    @Test
    void noCompressionUnderBudget() {
        Compressor c = new Compressor(cfg(1_000_000, 8_000), tmp, null, "test");
        MessageList ml = new MessageList();
        ml.append(Message.system("S"));
        ml.append(Message.user("T"));
        ml.markPrefix();
        assertTrue(!c.compressIfNeeded(ml));
    }

    @Test
    void externalSourcesSurviveCapAndFold() {
        Compressor c = new Compressor(cfg(3_000, 1_000), tmp, null, "test");
        // ① capToolOutput：预览（600 字符）之外的来源 URL 也必须保留
        String big = "<external source=\"https://a.example.com/one\">\n" + "x".repeat(2_000)
                + "\n</external>\n<external source=\"https://a.example.com/two\">\n" + "y".repeat(2_000) + "\n</external>";
        String capped = c.capToolOutput("web_fetch", big);
        assertTrue(capped.contains("source=\"https://a.example.com/two\""),
                "预览外的来源必须以 [folded-sources] 保底: " + capped.substring(0, 120));

        // ② tier2 fold：折叠行保留来源
        MessageList ml = new MessageList();
        ml.append(Message.system("S"));
        ml.append(Message.user("T"));
        ml.markPrefix();
        for (int i = 0; i < 12; i++) {
            ToolCall call = new ToolCall("id-" + i, "web_search", "{}");
            ml.append(Message.assistant("", null, List.of(call)));
            ml.append(Message.toolResult("id-" + i, "web_search",
                    "搜索: q" + i + "\n<external source=\"https://s.example.com/" + i + "\">\n"
                            + "detail ".repeat(200) + "\n</external>"));
        }
        c.compressIfNeeded(ml);
        long kept = ml.suffix().stream()
                .filter(m -> m.role() == Message.Role.TOOL)
                .filter(m -> m.content().contains("[folded-sources"))
                .count();
        assertTrue(kept > 0, "折叠行应保留 source URL（引用链不断）");
    }
}
