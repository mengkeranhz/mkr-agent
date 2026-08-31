package com.mkr.verify;

import com.mkr.core.RunContext;
import com.mkr.guard.Sandbox;
import com.mkr.tools.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编译验证器：write/edit_file 生成的 Java 源码跑 javac，失败返回（文件+行+原因）回灌。
 * 只验证本轮新写入/修改的 .java（RunContext 记录，验证后消费）。
 */
public final class CompileVerifier implements Verifier {

    private static final Pattern JAVAC_ERROR =
            Pattern.compile("^\\s*(\\S+\\.java):\\[(\\d+),(\\d+)\\]\\s+(error|错误)\\s*:\\s*(.+)$");

    private final Sandbox sandbox;
    private final Path projectRoot;

    public CompileVerifier(Sandbox sandbox, Path projectRoot) {
        this.sandbox = sandbox;
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "compile";
    }

    @Override
    public VerifyResult verify(ToolResult result, RunContext ctx) {
        List<Path> javaFiles = ctx.drainWrittenFiles(".java");
        if (javaFiles.isEmpty()) {
            return VerifyResult.pass();
        }
        StringBuilder filesArg = new StringBuilder();
        for (Path p : javaFiles) {
            filesArg.append('\'').append(p).append("' ");
        }
        Path outDir = ctx.workspaceRoot().resolve(".artifacts").resolve("classes-" + System.currentTimeMillis());
        String cmd = "mkdir -p '" + outDir + "' && javac -encoding UTF-8 -nowarn -d '" + outDir + "' " + filesArg;
        Sandbox.ExecResult exec = sandbox.exec(cmd, projectRoot, 120_000);
        if (exec.ok()) {
            return VerifyResult.pass();
        }
        List<String> errors = new ArrayList<>();
        for (String line : (exec.stderr() + "\n" + exec.stdout()).split("\n")) {
            // javac 输出两种风格：file:line: msg（GNU）或 file:[line,col] 错误: msg（本地化）
            Matcher m = JAVAC_ERROR.matcher(line);
            if (m.find()) {
                String rel = relativize(m.group(1));
                errors.add(rel + ":" + m.group(2) + ": " + m.group(5));
                continue;
            }
            Matcher alt = Pattern.compile("^\\s*(\\S+\\.java):(\\d+):\\s*(.+)$").matcher(line);
            if (alt.find() && !line.contains("warning")) {
                errors.add(relativize(alt.group(1)) + ":" + alt.group(2) + ": " + alt.group(3));
            }
            if (errors.size() >= 10) {
                break;
            }
        }
        if (errors.isEmpty()) {
            errors.add("javac 退出码 " + exec.exitCode() + ": "
                    + firstLines(exec.stderr().isBlank() ? exec.stdout() : exec.stderr(), 6));
        }
        return VerifyResult.fail("编译失败（已写文件需修复）:\n" + String.join("\n", errors));
    }

    private String relativize(String file) {
        try {
            Path p = Path.of(file);
            return Files.exists(p) || p.isAbsolute() ? projectRoot.relativize(p.toAbsolutePath().normalize()).toString() : file;
        } catch (Exception e) {
            return file;
        }
    }

    private static String firstLines(String s, int n) {
        String[] lines = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, lines.length); i++) {
            sb.append(lines[i].trim()).append(" | ");
        }
        return sb.toString();
    }
}
