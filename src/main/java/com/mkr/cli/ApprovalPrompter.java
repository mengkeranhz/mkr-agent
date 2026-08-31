package com.mkr.cli;

import com.mkr.guard.ApprovalGate;

import java.util.Scanner;
import java.util.function.Supplier;

/**
 * 审批交互：显示路径 + diff 预览/删除清单，等待 y/n（或 /approve、/deny）。
 * REPL 模式共享 JLine reader（agent 在主线程执行，可直接阻塞读行）。
 */
public final class ApprovalPrompter implements ApprovalGate.Prompter {

    private static final Scanner STDIN = new Scanner(System.in);

    private final Supplier<String> lineReader;
    private final RichConsole console;

    public ApprovalPrompter(Supplier<String> lineReader, RichConsole console) {
        this.lineReader = lineReader;
        this.console = console;
    }

    @Override
    public boolean request(ApprovalGate.ApprovalRequest req) {
        console.newlineIfNeeded();
        System.out.println();
        System.out.println("┌─ 审批请求 " + "─".repeat(Math.max(3, 40 - req.title().length())));
        System.out.println("│ " + req.title());
        System.out.println("│ 原因: " + req.reason());
        System.out.println("│ 风险: " + req.risk());
        if (req.preview() != null && !req.preview().isBlank()) {
            System.out.println("│ 预览:");
            String[] lines = req.preview().split("\n");
            for (int i = 0; i < Math.min(30, lines.length); i++) {
                System.out.println("│   " + lines[i]);
            }
            if (lines.length > 30) {
                System.out.println("│   …（其余 " + (lines.length - 30) + " 行省略）");
            }
        }
        System.out.println("└" + "─".repeat(48));
        System.out.print("允许执行? [y=允许 / n=拒绝 / a=本次会话全部允许]: ");
        System.out.flush();
        String line = readLine();
        if (line == null) {
            return false;
        }
        String answer = line.trim().toLowerCase();
        return switch (answer) {
            case "y", "yes", "是", "/approve", "/approve " -> true;
            case "a", "all" -> true; // 会话内全部允许由 REPL /permissions 处理，此处等价 y
            default -> false;
        };
    }

    private String readLine() {
        if (lineReader != null) {
            return lineReader.get();
        }
        return STDIN.hasNextLine() ? STDIN.nextLine() : null;
    }
}
