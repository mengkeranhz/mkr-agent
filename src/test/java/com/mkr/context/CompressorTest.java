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
}
