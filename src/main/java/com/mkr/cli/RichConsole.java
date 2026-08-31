package com.mkr.cli;

import com.mkr.api.Message;
import com.mkr.api.ToolCall;
import com.mkr.core.AgentState;
import com.mkr.core.LoopListener;
import com.mkr.tools.ToolResult;

/**
 * 富控制台：流式 token 渲染、工具调用实时显示 [tool:name args]、输出折叠。
 * 非 TTY / NO_COLOR / TERM=dumb 自动降级为纯文本。
 */
public final class RichConsole implements LoopListener {

    private final boolean ansi;
    private final int foldLines;
    private int currentLineLen = 0;
    private boolean inAnswer = false;

    public RichConsole() {
        String term = System.getenv("TERM");
        this.ansi = System.getenv("NO_COLOR") == null
                && term != null
                && !"dumb".equals(term)
                && System.console() != null;
        this.foldLines = 12;
    }

    // ---------------- 颜色 ----------------

    private String dim(String s) {
        return ansi ? "\u001b[2m" + s + "\u001b[0m" : s;
    }

    private String bold(String s) {
        return ansi ? "\u001b[1m" + s + "\u001b[0m" : s;
    }

    private String cyan(String s) {
        return ansi ? "\u001b[36m" + s + "\u001b[0m" : s;
    }

    private String green(String s) {
        return ansi ? "\u001b[32m" + s + "\u001b[0m" : s;
    }

    private String red(String s) {
        return ansi ? "\u001b[31m" + s + "\u001b[0m" : s;
    }

    private String yellow(String s) {
        return ansi ? "\u001b[33m" + s + "\u001b[0m" : s;
    }

    // ---------------- 通用输出 ----------------

    public void info(String s) {
        System.out.println(dim(s));
    }

    public void warn(String s) {
        System.out.println(yellow("⚠ " + s));
    }

    public void error(String s) {
        System.out.println(red("✗ " + s));
    }

    public void success(String s) {
        System.out.println(green("✓ " + s));
    }

    public void line() {
        System.out.println(dim("─".repeat(60)));
    }

    // ---------------- LoopListener ----------------

    @Override
    public void onToken(String text) {
        System.out.print(text);
        System.out.flush();
        inAnswer = true;
    }

    @Override
    public void onThinking(String thinking) {
        System.out.print(dim(thinking));
        System.out.flush();
    }

    @Override
    public void onRound(int round) {
        newlineIfNeeded();
        System.out.println(dim("── 第 " + round + " 轮 ──"));
    }

    @Override
    public void onToolStart(ToolCall call) {
        newlineIfNeeded();
        String args = call.argumentsJson() == null ? "{}" : call.argumentsJson();
        if (args.length() > 120) {
            args = args.substring(0, 120) + "…";
        }
        System.out.println(cyan(bold("[tool] ")) + call.name() + dim(" " + args));
    }

    @Override
    public void onToolEnd(ToolCall call, ToolResult result) {
        String head = result.success() ? green("  ↳ ok") : red("  ↳ " + (result.errorCode() == null ? "FAIL" : result.errorCode()));
        String body = result.render().strip();
        String[] lines = body.split("\n");
        StringBuilder sb = new StringBuilder(head).append(dim(" (" + lines.length + " 行)"));
        for (int i = 0; i < Math.min(foldLines, lines.length); i++) {
            String l = lines[i].length() > 200 ? lines[i].substring(0, 200) + "…" : lines[i];
            sb.append("\n").append(dim("  │ ")).append(l);
        }
        if (lines.length > foldLines) {
            sb.append("\n").append(dim("  │ …（其余 " + (lines.length - foldLines) + " 行折叠）"));
        }
        System.out.println(sb);
    }

    @Override
    public void onApprovalPending(String reason) {
        newlineIfNeeded();
        System.out.println(yellow("⏸ 等待审批: " + reason));
    }

    @Override
    public void onState(AgentState state) {
        // 状态变化仅在调试时展示（避免噪声）
    }

    @Override
    public void onAnswer(String answer) {
        newlineIfNeeded();
        System.out.println(bold(green("◆ 最终答复")));
        System.out.println(answer);
        System.out.println(dim("──".repeat(15)));
    }

    @Override
    public void onMessage(Message message) {
        // 控制台不重复渲染轨迹消息（token 流已实时展示）
    }

    public void newlineIfNeeded() {
        if (inAnswer || currentLineLen > 0) {
            System.out.println();
            inAnswer = false;
            currentLineLen = 0;
        }
    }

    public void printBanner() {
        System.out.println(cyan(bold("  mkr")) + dim(" · Agent Harness  v0.1.0"));
        System.out.println(dim("  四思考模式 · 多模型 · 护栏 · 验证 · 恢复   输入 /help 查看命令"));
        System.out.println();
    }
}
