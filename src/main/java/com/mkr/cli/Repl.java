package com.mkr.cli;

import com.mkr.config.AppAssembler;
import com.mkr.core.AgentLoop;
import com.mkr.core.AgentResult;
import com.mkr.core.RunContext;
import com.mkr.guard.PermissionMode;
import com.mkr.tools.Tool;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 交互式 REPL（类 Claude Code 体验）：JLine 行编辑 + 历史（~/.mkr/history），
 * 普通输入→任务（同一 RunContext 多轮续跑），/ 开头→命令分派。
 * Agent 在主线程执行，审批直接经共享 reader 读行。
 */
public final class Repl {

    private final AppAssembler.App app;
    private final RichConsole console = new RichConsole();
    private final SessionStore sessions = new SessionStore();
    private final AtomicReference<RunContext> current = new AtomicReference<>();
    private final LineReader reader;

    public Repl(Path cwd, com.mkr.config.AppConfig cfg) {
        LineReader r;
        try {
            Terminal terminal = TerminalBuilder.builder().dumb(true).build();
            r = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .variable(LineReader.HISTORY_FILE, Path.of(System.getProperty("user.home"), ".mkr", "history"))
                    .build();
        } catch (IOException e) {
            console.error("终端初始化失败: " + e.getMessage());
            r = null;
        }
        this.reader = r;
        // 审批共享同一 reader（agent 在主线程执行，直接阻塞读行）
        this.app = AppAssembler.assemble(cwd, cfg, new ApprovalPrompter(() -> {
            try {
                return reader == null ? null : reader.readLine("允许执行? [y/n]: ");
            } catch (Exception e) {
                return null;
            }
        }, console), true);
    }

    public void run() {
        console.printBanner();
        if (app.config.llm.apiKey == null || app.config.llm.apiKey.isBlank()) {
            console.warn("LLM_API_KEY 未配置：请在 .env 或环境变量设置后再执行任务（/tools 等本地命令可用）");
        }
        if (reader == null) {
            return;
        }
        while (true) {
            String line;
            try {
                RunContext ctx = current.get();
                String prompt = ctx == null ? "mkr> " : "mkr(" + ctx.mode() + "/" + ctx.model() + ")> ";
                line = reader.readLine(prompt);
            } catch (UserInterruptException e) {
                RunContext ctx = current.get();
                if (ctx != null) {
                    ctx.cancel();
                }
                continue;
            } catch (EndOfFileException e) {
                break;
            }
            if (line == null) {
                break;
            }
            String input = line.strip();
            if (input.isEmpty()) {
                continue;
            }
            if (input.startsWith("/")) {
                if (command(input)) {
                    break;
                }
            } else {
                submit(input);
            }
        }
        console.info("再见。");
    }

    // ---------------- 任务执行 ----------------

    private void submit(String task) {
        RunContext ctx = ensureSession(task);
        AgentLoop loop = AgentLoop.create(ctx.mode(), ctx);
        try {
            AgentResult result = loop.run(task);
            if (!result.success()) {
                console.warn("任务未完成 [" + result.status() + "]: " + result.reason());
            }
        } catch (Exception e) {
            console.error("执行异常: " + e.getMessage());
        }
    }

    private RunContext ensureSession(String task) {
        RunContext ctx = current.get();
        if (ctx != null) {
            return ctx;
        }
        Path sessionDir = null;
        try {
            sessionDir = sessions.create(task, app.config.llm.model, app.config.agent.defaultMode);
        } catch (IOException ignored) {
        }
        ctx = app.newSession(task, "mkr", app.config.agent.defaultMode, null, sessionDir, null);
        ctx.addListener(console);
        if (sessionDir != null) {
            ctx.addListener(sessions.persistence(sessionDir));
        }
        current.set(ctx);
        return ctx;
    }

    // ---------------- 命令 ----------------

    /** 返回 true = 退出。 */
    private boolean command(String input) {
        String[] parts = input.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].strip() : "";
        switch (cmd) {
            case "/help", "/?" -> help();
            case "/exit", "/quit", "/q" -> {
                return true;
            }
            case "/model" -> model(arg);
            case "/mode" -> mode(arg);
            case "/permissions" -> permissions(arg);
            case "/tools" -> tools();
            case "/skills" -> skills();
            case "/status" -> status();
            case "/plan" -> plan();
            case "/approve" -> console.info("无待审批项（审批提示出现时直接输入 y / n，或 /approve、/deny）");
            case "/deny" -> console.info("无待审批项");
            case "/cost" -> console.info(app.cost.report());
            case "/reset" -> {
                current.set(null);
                console.success("会话已清空（下次输入开启新会话）");
            }
            default -> console.warn("未知命令 " + cmd + "（/help 查看全部）");
        }
        return false;
    }

    private void help() {
        console.info("""
                命令列表:
                  /model <name>     切换模型（glm-4-plus / gpt-4o / claude-sonnet-4-5 ...）
                  /mode <mode>      切换思考模式: react | plan | reflect | func
                  /permissions [m]  查看/切换权限模式: default | accept-edits | plan | auto-approve
                  /tools            查看已注册工具
                  /skills           查看已安装 skills
                  /status           状态栏（TODO/工具计数/错误）
                  /plan             当前 TODO 计划
                  /cost             本会话费用与 token
                  /reset            清空会话
                  /exit             退出""");
    }

    private void model(String name) {
        RunContext ctx = current.get();
        if (name == null || name.isBlank()) {
            console.info("当前模型: " + (ctx == null ? app.config.llm.model : ctx.model())
                    + "（provider=" + app.config.llm.provider + "）");
            return;
        }
        if (ctx != null) {
            ctx.setModel(name);
        }
        console.success("模型已切换为 " + name + "（新会话生效于下一次请求）");
    }

    private void mode(String m) {
        RunContext ctx = current.get();
        if (m == null || m.isBlank()) {
            console.info("当前模式: " + (ctx == null ? app.config.agent.defaultMode : ctx.mode()));
            return;
        }
        if (!List.of("react", "plan", "reflect", "func", "manager").contains(m)) {
            console.warn("未知模式 " + m + "（可选 react | plan | reflect | func）");
            return;
        }
        if (ctx != null) {
            ctx.setMode(m);
            console.success("模式已切换为 " + m + "（下一条输入起生效；模式指令属 system 前缀，建议 /reset 后切换）");
        } else {
            console.success("模式已预设为 " + m);
        }
    }

    private void permissions(String arg) {
        RunContext ctx = current.get();
        if (arg == null || arg.isBlank()) {
            PermissionMode pm = ctx == null ? PermissionMode.parse(app.config.agent.permissionMode) : ctx.permissionMode();
            console.info("当前权限模式: " + pm.key() + " — " + pm.description());
            console.info("路径规则:\n  " + String.join("\n  ", app.guard.files().policy().describe()));
            console.info("受保护: " + String.join(", ", app.guard.files().protectedDirs().rules()));
            return;
        }
        PermissionMode pm = PermissionMode.parse(arg);
        if (ctx != null) {
            ctx.setPermissionMode(pm);
        }
        console.success("权限模式 → " + pm.key() + "（" + pm.description() + "）");
    }

    private void tools() {
        List<Tool> all = app.tools.all();
        console.info("已注册 " + all.size() + " 个工具（渐进发现=" + (app.tools.progressive() ? "on" : "off") + "）:");
        for (Tool t : all) {
            console.info("  [" + t.risk() + "] " + t.name() + " — " + firstLine(t.description()));
        }
    }

    private void skills() {
        try {
            String idx = app.skills.indexMarkdown();
            console.info(idx.isBlank() ? "（暂无 skill，可 mkr skills install 或让 Agent 用 create_skill 自进化创建）" : idx);
        } catch (Exception e) {
            console.error(e.getMessage());
        }
    }

    private void status() {
        RunContext ctx = current.get();
        if (ctx == null) {
            console.info("（无活动会话）");
            return;
        }
        console.info(ctx.status().render());
        console.info("会话: " + ctx.sessionId() + "  消息: " + ctx.messages().size()
                + "  ~" + ctx.messages().estimateTokens() + " tokens");
    }

    private void plan() {
        RunContext ctx = current.get();
        if (ctx == null || ctx.status().todos().isEmpty()) {
            console.info("（无计划；plan 模式或 update_plan 工具会生成 TODO）");
            return;
        }
        ctx.status().todos().forEach(t -> console.info(
                (t.done() ? "  [x] " : "  [ ] ") + t.text()));
    }

    private static String firstLine(String s) {
        String l = s == null ? "" : s.split("\n")[0];
        return l.length() > 100 ? l.substring(0, 100) + "…" : l;
    }
}
