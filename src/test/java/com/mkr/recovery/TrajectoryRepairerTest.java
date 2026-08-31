package com.mkr.recovery;

import com.mkr.api.Message;
import com.mkr.api.ToolCall;
import com.mkr.context.MessageList;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 轨迹修复：缺失 tool_result 的调用补占位，保证成对。 */
class TrajectoryRepairerTest {

    @Test
    void insertsPlaceholderForMissingResults() {
        MessageList ml = new MessageList();
        ml.append(Message.system("S"));
        ml.append(Message.user("T"));
        ml.markPrefix();
        ToolCall ok = new ToolCall("id-1", "bash", "{\"command\":\"ls\"}");
        ToolCall missing = new ToolCall("id-2", "read_file", "{\"path\":\"a\"}");
        ml.append(Message.assistant("a", null, List.of(ok, missing)));
        ml.append(Message.toolResult("id-1", "bash", "out"));

        int repaired = new TrajectoryRepairer().repair(ml);
        assertEquals(1, repaired);
        assertEquals(5, ml.size());
        // 占位紧跟 assistant 插入，与两个 tool_call 均成对
        long id2 = ml.snapshot().stream()
                .filter(m -> m.role() == Message.Role.TOOL && "id-2".equals(m.toolCallId())).count();
        long id1 = ml.snapshot().stream()
                .filter(m -> m.role() == Message.Role.TOOL && "id-1".equals(m.toolCallId())).count();
        assertEquals(1, id1);
        assertEquals(1, id2);
    }

    @Test
    void noRepairWhenPaired() {
        MessageList ml = new MessageList();
        ToolCall c = new ToolCall("id-1", "bash", "{}");
        ml.append(Message.assistant("a", null, List.of(c)));
        ml.append(Message.toolResult("id-1", "bash", "ok"));
        assertEquals(0, new TrajectoryRepairer().repair(ml));
        assertEquals(2, ml.size());
    }
}
