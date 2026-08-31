package com.mkr.verify;

import com.mkr.core.RunContext;
import com.mkr.guard.Sandbox;
import com.mkr.tools.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 测试验证器：自动探测构建系统（mvn / pytest / go test / npm test），
 * 汇总 pass/fail；不自动挂在主循环（显式调用 / eval 用）。
 */
public final class TestVerifier implements Verifier {

    private static final Pattern SUREFIRE = Pattern.compile("Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+)");
    private static final Pattern PYTEST = Pattern.compile("^(\\d+) passed.*?(?:(\\d+) failed)?", Pattern.MULTILINE);

    private final Sandbox sandbox;
    private final Path projectRoot;

    public TestVerifier(Sandbox sandbox, Path projectRoot) {
        this.sandbox = sandbox;
        this.projectRoot = projectRoot;
    }

    @Override
    public String name() {
        return "test";
    }

    @Override
    public VerifyResult verify(ToolResult result, RunContext ctx) {
        String cmd = detectCommand(projectRoot);
        if (cmd == null) {
            return VerifyResult.fail("未识别测试命令（无 pom.xml / pytest / go.mod / package.json）");
        }
        Sandbox.ExecResult exec = sandbox.exec(cmd, projectRoot, 300_000);
        String output = exec.stdout() + "\n" + exec.stderr();
        Matcher m = SUREFIRE.matcher(output);
        if (m.find()) {
            int total = Integer.parseInt(m.group(1));
            int failures = Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(3));
            return failures == 0 && total > 0
                    ? VerifyResult.pass()
                    : VerifyResult.fail("mvn test: " + total + " 个测试，" + failures + " 个失败");
        }
        Matcher py = PYTEST.matcher(output);
        if (py.find()) {
            int failed = py.group(2) == null ? 0 : Integer.parseInt(py.group(2));
            return failed == 0 ? VerifyResult.pass() : VerifyResult.fail("pytest: " + failed + " 个失败");
        }
        return exec.ok()
                ? VerifyResult.pass()
                : VerifyResult.fail("测试命令失败(" + cmd + "): " + output.strip().substring(0, Math.min(300, output.strip().length())));
    }

    static String detectCommand(Path root) {
        if (Files.isRegularFile(root.resolve("pom.xml"))) {
            return "mvn -q test";
        }
        if (Files.isRegularFile(root.resolve("pytest.ini"))
                || Files.isRegularFile(root.resolve("setup.py"))
                || Files.isRegularFile(root.resolve("pyproject.toml"))) {
            return "python3 -m pytest -q";
        }
        if (Files.isRegularFile(root.resolve("go.mod"))) {
            return "go test ./...";
        }
        if (Files.isRegularFile(root.resolve("package.json"))) {
            return "npm test --silent";
        }
        return null;
    }
}
