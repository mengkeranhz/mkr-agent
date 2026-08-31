package com.mkr.guard;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 路径规则：deny ＞ ask ＞ allow ＞ 默认；~ 展开；任意深度文件名匹配。 */
class PathPolicyTest {

    private final PathPolicy policy = PathPolicy.of(
            List.of("./workspace/**"),
            List.of("./pom.xml"),
            List.of("~/.ssh/**", ".env", "**/*.pem", "/etc/**", "**/target/**"));

    private Path home(String rel) {
        return Path.of(System.getProperty("user.home")).resolve(rel);
    }

    @Test
    void denyBeatsAskAndAllow() {
        // ~/.ssh 命中 deny（即使也在 allow 列表）
        assertEquals(PathPolicy.Decision.DENY, policy.decide(home(".ssh/id_rsa")));
        // .env 任意深度 deny
        assertEquals(PathPolicy.Decision.DENY, policy.decide(Path.of("/Users/x/project/.env")));
        assertEquals(PathPolicy.Decision.DENY, policy.decide(Path.of("/Users/x/project/deep/nested/.env")));
        // **/*.pem 任意深度
        assertEquals(PathPolicy.Decision.DENY, policy.decide(Path.of("/a/b/c/server.pem")));
        // /etc/** 与 /etc 本体
        assertEquals(PathPolicy.Decision.DENY, policy.decide(Path.of("/etc/passwd")));
        assertEquals(PathPolicy.Decision.DENY, policy.decide(Path.of("/etc")));
        // **/target/** 跨目录
        assertEquals(PathPolicy.Decision.DENY, policy.decide(Path.of("/w/proj/target/classes/A.class")));
    }

    @Test
    void askBeatsAllow() {
        PathPolicy p = PathPolicy.of(
                List.of("./**"), List.of("./pom.xml"), List.of());
        assertEquals(PathPolicy.Decision.ASK, p.decide(Path.of(System.getProperty("user.dir")).resolve("pom.xml")));
    }

    @Test
    void allowAndDefault() {
        assertEquals(PathPolicy.Decision.ALLOW,
                policy.decide(Path.of(System.getProperty("user.dir")).resolve("workspace/memories/a.md")));
        assertEquals(PathPolicy.Decision.DEFAULT, policy.decide(Path.of("/tmp/anything.txt")));
    }

    @Test
    void plainFileNameDoesNotMatchSuffixOfOthers() {
        // ".env" 不应命中 "my.env"（前缀边界）
        assertEquals(PathPolicy.Decision.DEFAULT, policy.decide(Path.of("/w/my.env")));
    }

    @Test
    void nonWildcardDirectoryCoversChildren() {
        PathPolicy p = PathPolicy.of(List.of(), List.of(), List.of("/etc"));
        assertEquals(PathPolicy.Decision.DENY, p.decide(Path.of("/etc/ssh/ssh_config")));
    }
}
