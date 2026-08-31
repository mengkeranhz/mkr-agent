package com.mkr.guard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Docker 沙箱（可选）：一次性容器，默认 --network=none（allowlist 外断外网）、
 * --cpus/--memory/--pids-limit 限额、超时杀容器，workspace 挂载为 /workspace。
 * 通过 docker CLI 实现，无需 docker-java 依赖。
 */
public final class DockerSandbox implements Sandbox {

    private final String image;
    private final String memory;
    private final double cpus;
    private final int pidsLimit;
    private final List<String> networkAllowlist;
    private final Path workspaceRoot;
    private final int maxOutputChars;

    public DockerSandbox(String image, String memory, double cpus, int pidsLimit,
                         List<String> networkAllowlist, Path workspaceRoot, int maxOutputChars) {
        this.image = image == null || image.isBlank() ? "alpine:3.20" : image;
        this.memory = memory == null || memory.isBlank() ? "2g" : memory;
        this.cpus = cpus > 0 ? cpus : 1.0;
        this.pidsLimit = pidsLimit > 0 ? pidsLimit : 256;
        this.networkAllowlist = networkAllowlist == null ? List.of() : networkAllowlist;
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public String type() {
        return "docker";
    }

    @Override
    public ExecResult exec(String command, Path workdir, long timeoutMs) {
        if (!available()) {
            return ExecResult.fail("docker 命令不可用，无法使用 docker 沙箱（可设 tools.sandbox.type=local）");
        }
        String containerDir = mapWorkdir(workdir);
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "--rm"));
        if (networkAllowlist.isEmpty()) {
            cmd.add("--network=none"); // 默认断外网
        } else {
            cmd.add("--network=" + String.join(",", networkAllowlist));
        }
        cmd.add("--memory=" + memory);
        cmd.add("--cpus=" + cpus);
        cmd.add("--pids-limit=" + pidsLimit);
        cmd.add("-v");
        cmd.add(workspaceRoot + ":/workspace");
        cmd.add("-w");
        cmd.add(containerDir);
        cmd.add(image);
        cmd.add("sh");
        cmd.add("-c");
        cmd.add(command);
        return LocalSandbox.capture(cmd, null, timeoutMs, maxOutputChars);
    }

    private String mapWorkdir(Path workdir) {
        if (workdir == null) {
            return "/workspace";
        }
        Path abs = workdir.toAbsolutePath().normalize();
        if (abs.startsWith(workspaceRoot)) {
            return "/workspace/" + workspaceRoot.relativize(abs);
        }
        return "/workspace";
    }

    private boolean available() {
        ExecResult probe = LocalSandbox.capture(List.of("docker", "version", "--format", "{{.Server.Version}}"),
                null, 5_000, 1000);
        return probe.ok();
    }
}
