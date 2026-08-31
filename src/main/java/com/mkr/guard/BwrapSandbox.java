package com.mkr.guard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * bwrap 沙箱（可选，Linux）：bubblewrap namespaces + seccomp，无 daemon。
 * 只读绑定系统目录、workspace 可写、--unshare-net 断网。
 */
public final class BwrapSandbox implements Sandbox {

    private final Path workspaceRoot;
    private final int maxOutputChars;

    public BwrapSandbox(Path workspaceRoot, int maxOutputChars) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public String type() {
        return "bwrap";
    }

    @Override
    public ExecResult exec(String command, Path workdir, long timeoutMs) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            return ExecResult.fail("bwrap 沙箱仅支持 Linux（当前非 Linux，请用 local/docker）");
        }
        String dir = workdir == null ? workspaceRoot.toString()
                : workdir.toAbsolutePath().normalize().toString();
        if (!dir.startsWith(workspaceRoot.toString())) {
            dir = workspaceRoot.toString();
        }
        List<String> cmd = new ArrayList<>(List.of(
                "bwrap",
                "--ro-bind", "/usr", "/usr",
                "--symlink", "usr/lib", "/lib",
                "--symlink", "usr/lib64", "/lib64",
                "--symlink", "usr/bin", "/bin",
                "--symlink", "usr/sbin", "/sbin",
                "--dev", "/dev",
                "--proc", "/proc",
                "--tmpfs", "/tmp",
                "--ro-bind-try", "/etc/ssl", "/etc/ssl",
                "--bind", workspaceRoot.toString(), workspaceRoot.toString(),
                "--chdir", dir,
                "--unshare-net",
                "--die-with-parent",
                "--new-session",
                "sh", "-c", command));
        return LocalSandbox.capture(cmd, null, timeoutMs, maxOutputChars);
    }
}
