package com.mkr.guard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 文件访问控制：deny 拒绝、受保护 ASK、覆盖/删除 ASK、symlink/.. 越权拦截。 */
class FileAccessGuardTest {

    @TempDir
    Path tmp;

    private FileAccessGuard guard() {
        PathPolicy policy = PathPolicy.of(
                List.of(tmp.resolve("workspace").toString() + "/**"),
                List.of(),
                List.of("~/.ssh/**", ".env", "**/*.pem"));
        ProtectedDirs protectedDirs = new ProtectedDirs(List.of("~/.ssh", "~/.gnupg", ".env", "**/*.pem"));
        return new FileAccessGuard(tmp, policy, protectedDirs, true, true);
    }

    @Test
    void denyRulesRejectEvenWithApprovalMemo() {
        FileAccessGuard g = guard();
        var decision = g.check("write", "~/.ssh/authorized_keys", key -> true);
        assertTrue(decision.denied());
        assertEquals(FileAccessGuard.Outcome.DENY, g.check("read", ".env", null).outcome());
        assertEquals(FileAccessGuard.Outcome.DENY, g.check("write", "/x/y/key.pem", null).outcome());
    }

    @Test
    void protectedDirsEscalateToAskUnlessApproved() {
        FileAccessGuard g = guard();
        // ~/.gnupg 受保护但不在 deny 列表 → ASK（~/.ssh/** 命中 deny 是 DENY，见上）
        assertEquals(FileAccessGuard.Outcome.ASK, g.check("write", "~/.gnupg/pubring.kbx", null).outcome());
        // 审批记忆放行
        Path norm = g.normalize("~/.gnupg/pubring.kbx");
        assertEquals(FileAccessGuard.Outcome.ALLOW,
                g.check("write", "~/.gnupg/pubring.kbx",
                        key -> key.equals(FileAccessGuard.approvalKey("write", norm))).outcome());
    }

    @Test
    void overwriteAndDeleteNeedApproval() throws Exception {
        FileAccessGuard g = guard();
        Path ws = tmp.resolve("workspace");
        Files.createDirectories(ws);
        Path existing = Files.writeString(ws.resolve("a.txt"), "old");

        assertEquals(FileAccessGuard.Outcome.ALLOW, g.check("write", ws.resolve("new.txt").toString(), null).outcome());
        assertEquals(FileAccessGuard.Outcome.ASK, g.check("write", existing.toString(), null).outcome());
        assertEquals(FileAccessGuard.Outcome.ASK, g.check("delete", existing.toString(), null).outcome());

        // auto-approve 模式放行覆盖与删除（受保护除外）
        assertEquals(FileAccessGuard.Outcome.ALLOW,
                g.check("write", existing.toString(), null, PermissionMode.AUTO_APPROVE).outcome());
        assertEquals(FileAccessGuard.Outcome.ALLOW,
                g.check("delete", existing.toString(), null, PermissionMode.AUTO_APPROVE).outcome());
        // accept-edits 放行覆盖、不放行删除
        assertEquals(FileAccessGuard.Outcome.ALLOW,
                g.check("write", existing.toString(), null, PermissionMode.ACCEPT_EDITS).outcome());
        assertEquals(FileAccessGuard.Outcome.ASK,
                g.check("delete", existing.toString(), null, PermissionMode.ACCEPT_EDITS).outcome());
    }

    @Test
    void symlinkEscapeIsResolvedAndChecked() throws Exception {
        FileAccessGuard g = guard();
        Path ssh = Path.of(System.getProperty("user.home"), ".ssh");
        Files.createDirectories(ssh);
        Path link = tmp.resolve("innocent.txt");
        Files.deleteIfExists(link);
        Files.createSymbolicLink(link, ssh.resolve("id_rsa"));
        var decision = g.check("read", link.toString(), null);
        assertTrue(decision.denied() || decision.outcome() == FileAccessGuard.Outcome.ASK,
                "symlink 指向受保护目标必须被拦截: " + decision);
    }

    @Test
    void dotDotTraversalNormalized() {
        FileAccessGuard g = guard();
        Path normalized = g.normalize("workspace/../../etc/passwd");
        assertTrue(normalized.endsWith("etc/passwd"), normalized.toString());
        assertThrows(FileAccessGuard.AccessDeniedException.class,
                () -> g.normalize("~other/secret"));
    }
}
