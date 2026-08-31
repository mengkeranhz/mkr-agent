package com.mkr.context;

import com.mkr.api.Message;
import com.mkr.api.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 消息列表：append-only、前缀锁定、恢复重算、token 估算。 */
class MessageListTest {

    @Test
    void prefixLockAndSuffix() {
        MessageList ml = new MessageList();
        ml.append(Message.system("SYSTEM"));
        ml.append(Message.user("task"));
        ml.markPrefix();
        assertEquals(2, ml.prefixEnd());

        ml.append(Message.assistant("thinking...", null, List.of(ToolCall.of("bash", java.util.Map.of("command", "ls")))));
        ml.append(Message.toolResult("id", "bash", "[ERROR EXIT_1] x"));

        assertEquals(4, ml.size());
        assertEquals(2, ml.suffix().size()); // 前缀不动
        assertTrue(ml.estimateTokens() > 0);
    }

    @Test
    void rewriteSuffixKeepsPrefix() {
        MessageList ml = new MessageList();
        ml.append(Message.system("S"));
        ml.append(Message.user("T"));
        ml.markPrefix();
        ml.append(Message.assistant("a1"));
        ml.rewriteSuffix(List.of(Message.assistant("compressed")));
        assertEquals(3, ml.size());
        assertEquals(Message.Role.SYSTEM, ml.get(0).role());
        assertEquals("compressed", ml.get(2).content());
    }

    @Test
    void loadHistoryComputesPrefix() {
        List<Message> history = List.of(
                Message.system("S"),
                Message.user("task1"),
                Message.assistant("a"),
                Message.user("task2"));
        MessageList ml = MessageList.loadHistory(history);
        assertEquals(2, ml.prefixEnd()); // system + 首条任务
        assertEquals(4, ml.size());
    }

    @Test
    void resetReplacesContent() {
        MessageList ml = new MessageList();
        ml.append(Message.user("x"));
        ml.reset(List.of(Message.system("S"), Message.user("T")));
        assertEquals(2, ml.size());
        assertEquals(2, ml.prefixEnd());
    }
}
