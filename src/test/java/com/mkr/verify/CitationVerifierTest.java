package com.mkr.verify;

import com.mkr.api.Message;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 引用合规校验：豁免 / 防编造 / 防漏标 / 前缀容忍 / 折叠来源仍可提取。 */
class CitationVerifierTest {

    private static final String FETCHED =
            "<external source=\"https://a.example.com/stats\">\n2026 年 8 月均价 34000 元/平米\n</external>";

    private static List<Message> traj(String... toolContents) {
        return Arrays.stream(toolContents)
                .map(c -> Message.toolResult("id", "web_fetch", c))
                .toList();
    }

    @Test
    void exemptWhenNoExternalContent() {
        var c = CitationVerifier.check(traj("line1\nline2"), "没有任何 URL 的普通回答");
        assertTrue(c.ok(), c.reason());
    }

    @Test
    void okWhenCitingWhitelistedUrl() {
        var c = CitationVerifier.check(traj(FETCHED),
                "2026 年 8 月均价 34000 元/平米（来源: https://a.example.com/stats）");
        assertTrue(c.ok(), c.reason());
    }

    @Test
    void rejectFabricatedUrl() {
        var c = CitationVerifier.check(traj(FETCHED), "见 https://made-up.example.net/report");
        assertFalse(c.ok(), "编造 URL 应被拒绝");
        assertTrue(c.reason().contains("编造"), c.reason());
    }

    @Test
    void rejectMissingCitationWhenExternalUsed() {
        var c = CitationVerifier.check(traj(FETCHED), "均价 34000 元/平米。");
        assertFalse(c.ok(), "用了外部检索但无来源标注应被拒绝");
        assertTrue(c.reason().contains("来源"), c.reason());
    }

    @Test
    void uncitedMarkerPasses() {
        var c = CitationVerifier.check(traj(FETCHED), "均价约 3.4 万/平米（未溯源）");
        assertTrue(c.ok(), c.reason());
    }

    @Test
    void prefixToleranceForAnchorAndTrailingSlash() {
        var c = CitationVerifier.check(traj(FETCHED),
                "来源: https://a.example.com/stats/#section1 与 https://a.example.com/stats/");
        assertTrue(c.ok(), c.reason());
    }

    @Test
    void foldedSourcesStillCountAsWhitelist() {
        // 压缩折叠后保留的 [folded-sources source=…] 形式同样可提取为白名单
        var c = CitationVerifier.check(
                traj("[tool web_search ok] 搜索: 均价 [folded-sources source=\"https://b.example.com/x\"]"),
                "数据来源: https://b.example.com/x");
        assertTrue(c.ok(), c.reason());
    }
}
