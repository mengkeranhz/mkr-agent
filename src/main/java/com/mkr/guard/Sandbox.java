package com.mkr.guard;

import java.nio.file.Path;

/** 沙箱抽象：本机 / Docker / bwrap / 云，限网 + 限额 + 超时。 */
public interface Sandbox {

    ExecResult exec(String command, Path workdir, long timeoutMs);

    String type();

    record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut, long durationMs) {
        public boolean ok() {
            return exitCode == 0 && !timedOut;
        }

        public String combined() {
            StringBuilder sb = new StringBuilder();
            if (stdout != null && !stdout.isBlank()) {
                sb.append(stdout.strip());
            }
            if (stderr != null && !stderr.isBlank()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("[stderr] ").append(stderr.strip());
            }
            if (timedOut) {
                sb.append("\n[TIMEOUT] 执行超时被终止");
            }
            return sb.toString();
        }

        public static ExecResult fail(String message) {
            return new ExecResult(125, "", message, false, 0);
        }
    }
}
