package com.mkr.guard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本机沙箱（默认）：ProcessBuilder + 超时杀进程 + 输出上限。
 * 危险命令检测与审批门控在 Guardrail 层完成，这里是执行面。
 */
public final class LocalSandbox implements Sandbox {

    private final int maxOutputChars;

    public LocalSandbox(int maxOutputChars) {
        this.maxOutputChars = maxOutputChars;
    }

    @Override
    public String type() {
        return "local";
    }

    @Override
    public ExecResult exec(String command, Path workdir, long timeoutMs) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> cmd = windows
                ? List.of("cmd", "/c", command)
                : List.of("sh", "-c", command);
        return capture(cmd, workdir, timeoutMs, maxOutputChars);
    }

    /** 进程捕获公共设施：双流并行读取（防管道塞死）+ 超时强杀 + 输出截断。 */
    public static ExecResult capture(List<String> cmd, Path workdir, long timeoutMs, int maxChars) {
        long start = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workdir != null) {
            pb.directory(workdir.toFile());
        }
        try {
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread tOut = Thread.ofVirtual().start(() -> readCap(proc.getInputStream(), out, maxChars));
            Thread tErr = Thread.ofVirtual().start(() -> readCap(proc.getErrorStream(), err, maxChars));
            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            boolean timedOut = false;
            if (!finished) {
                proc.destroyForcibly();
                timedOut = true;
                proc.waitFor(5, TimeUnit.SECONDS);
            }
            tOut.join(2000);
            tErr.join(2000);
            return new ExecResult(timedOut ? 137 : proc.exitValue(), out.toString(), err.toString(),
                    timedOut, System.currentTimeMillis() - start);
        } catch (IOException e) {
            return new ExecResult(127, "", "启动失败: " + e.getMessage(), false,
                    System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult(130, "", "执行被中断", false, System.currentTimeMillis() - start);
        }
    }

    private static void readCap(InputStream in, StringBuilder sb, int maxChars) {
        try (InputStream is = in) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                if (sb.length() < maxChars) {
                    sb.append(new String(buf, 0, Math.min(n, maxChars - sb.length()), StandardCharsets.UTF_8));
                }
                // 超限后继续读并丢弃，防止子进程写管道阻塞
            }
            if (sb.length() >= maxChars) {
                sb.append("\n…[输出超过 ").append(maxChars).append(" 字符已截断]…");
            }
        } catch (IOException ignored) {
            // 进程被杀时流关闭
        }
    }
}
