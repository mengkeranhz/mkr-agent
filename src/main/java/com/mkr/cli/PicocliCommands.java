package com.mkr.cli;

import com.mkr.config.AppAssembler;
import com.mkr.config.AppConfig;
import com.mkr.core.AgentLoop;
import com.mkr.core.AgentResult;
import com.mkr.core.RunContext;
import com.mkr.guard.PermissionMode;
import com.mkr.mcp.McpManager;
import com.mkr.obs.EvalRunner;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolSchemaExporter;
import com.mkr.util.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * picocli 命令集：init / run / eval / tools / model / cost / reset / skills / mcp / permissions / repl。
 */
@Command(name = "mkr", mixinStandardHelpOptions = true, version = "mkr 0.1.0",
        description = "生产级通用 Agent Harness（上下文 + 工具 + 护栏 + 验证 + 恢复）",
        subcommands = {
                PicocliCommands.InitCmd.class,
                PicocliCommands.RunCmd.class,
                PicocliCommands.EvalCmd.class,
                PicocliCommands.ToolsCmd.class,
                PicocliCommands.ModelCmd.class,
                PicocliCommands.CostCmd.class,
                PicocliCommands.ResetCmd.class,
                PicocliCommands.SkillsCmd.class,
                PicocliCommands.McpCmd.class,
                PicocliCommands.PermissionsCmd.class,
                PicocliCommands.ReplCmd.class})
public final class PicocliCommands {

    static Path cwd() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    static AppConfig loadConfig(Path configOverride) {
        return AppConfig.load(cwd(), configOverride);
    }

    // ---------------- init ----------------

    @Command(name = "init", description = "初始化配置（config/config.yaml + .env + permissions 模板 + SOUL.md）")
    public static final class InitCmd implements Callable<Integer> {

        @Override
        public Integer call() {
            try {
                Path cwd = cwd();
                copyTemplate("/templates/config.yaml", cwd.resolve("config/config.yaml"), false);
                copyTemplate("/templates/permissions.yml", cwd.resolve("config/permissions.yml"), false);
                copyTemplate("/templates/env.txt", cwd.resolve(".env"), false);
                copyTemplate("/templates/mcp.yml", cwd.resolve("config/mcp.yml"), false);
                copyTemplate("/templates/tasks.json", cwd.resolve("eval/tasks.json"), false);
                Path soul = Path.of(System.getProperty("user.home"), ".mkr", "SOUL.md");
                copyTemplate("/templates/SOUL.md", soul, false);
                Files.createDirectories(cwd.resolve("workspace/memories"));
                Files.createDirectories(cwd.resolve("skills"));
                if (!Files.isRegularFile(cwd.resolve("SOUL.md"))) {
                    copyTemplate("/templates/SOUL.md", cwd.resolve("SOUL.md"), false);
                }
                System.out.println("初始化完成:");
                System.out.println("  config/config.yaml      主配置（模型/沙箱/权限/记忆）");
                System.out.println("  config/permissions.yml  路径规则（allow/ask/deny）");
                System.out.println("  config/mcp.yml          MCP 服务器");
                System.out.println("  .env                    密钥（LLM_API_KEY / TAVILY_API_KEY）");
                System.out.println("  " + soul + "  人格文件（5 段模板，填写后生效）");
                System.out.println("  eval/tasks.json         评估集样例");
                System.out.println("下一步: 在 .env 填入 LLM_API_KEY，然后 mkr 或 mkr run \"<任务>\"");
                return 0;
            } catch (Exception e) {
                System.err.println("init 失败: " + e.getMessage());
                return 1;
            }
        }
    }

    private static void copyTemplate(String resource, Path target, boolean overwrite) throws IOException {
        if (Files.isRegularFile(target) && !overwrite) {
            System.out.println("跳过（已存在）: " + target);
            return;
        }
        try (InputStream in = PicocliCommands.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("模板缺失: " + resource);
            }
            Files.createDirectories(target.getParent() == null ? target : target.getParent());
            Files.writeString(target, new String(in.readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            System.out.println("生成: " + target);
        }
    }

    // ---------------- run ----------------

    @Command(name = "run", description = "单次执行任务（流式输出）")
    public static final class RunCmd implements Callable<Integer> {

        @Parameters(index = "0", description = "任务描述", arity = "1")
        String task;

        @Option(names = "--mode", description = "思考模式: react | plan | reflect | func | manager")
        String mode;

        @Option(names = "--model", description = "模型覆盖（如 glm-4-plus）")
        String model;

        @Option(names = "--permission-mode", description = "权限模式: default | accept-edits | plan | auto-approve")
        String permissionMode;

        @Option(names = "--profile", description = "人格 profile（~/.mkr/profiles/<name>/SOUL.md）")
        String profile;

        @Option(names = {"--resume", "-r"}, description = "恢复会话 ID")
        String resume;

        @Option(names = "--config", description = "指定 config.yaml 路径")
        Path config;

        @Option(names = {"--json"}, description = "以 JSON 输出结果（供 CI/eval 调用）")
        boolean json;

        @Override
        public Integer call() {
            AppConfig cfg = loadConfig(config);
            if (profile != null && !profile.isBlank()) {
                cfg.agent.profile = profile;
            }
            RichConsole console = new RichConsole();
            // 非交互：审批按模式策略（default 拒绝 HIGH；auto-approve 放行但受保护仍强制人工→拒绝）
            AppAssembler.App app = AppAssembler.assemble(cwd(), cfg, null, false);
            SessionStore sessions = new SessionStore();
            Path sessionDir = null;
            try {
                sessionDir = resume == null
                        ? sessions.create(task, cfg.llm.model, mode == null ? cfg.agent.defaultMode : mode)
                        : sessions.find(resume);
                if (sessionDir == null && resume != null) {
                    console.error("会话不存在: " + resume);
                    return 2;
                }
            } catch (IOException e) {
                console.warn("会话目录创建失败: " + e.getMessage());
            }
            RunContext ctx = app.newSession(task, "mkr", mode, PermissionMode.parse(permissionMode), sessionDir, model);
            if (resume != null) {
                try {
                    ctx.adoptHistory(sessions.load(sessionDir));
                    console.info("已恢复会话 " + sessionDir.getFileName());
                } catch (IOException e) {
                    console.warn("轨迹读取失败，从零开始: " + e.getMessage());
                }
            }
            ctx.addListener(console);
            if (sessionDir != null) {
                ctx.addListener(sessions.persistence(sessionDir));
            }
            AgentResult result = AgentLoop.create(ctx.mode(), ctx).run(task);
            if (sessionDir != null) {
                try {
                    Files.writeString(sessionDir.resolve("cost.json"), Json.write(Map.of(
                            "usd", ctx.cost().totalUsd(), "tokens", ctx.cost().totalTokens(),
                            "status", result.status().name())), StandardCharsets.UTF_8);
                } catch (IOException ignored) {
                }
            }
            if (json) {
                System.out.println(Json.write(Map.of(
                        "status", result.status().name(),
                        "answer", result.answer() == null ? "" : result.answer(),
                        "reason", result.reason() == null ? "" : result.reason(),
                        "rounds", result.rounds(),
                        "costUsd", result.costUsd(),
                        "sessionId", ctx.sessionId())));
            } else if (!result.success()) {
                console.error("[" + result.status() + "] " + result.reason());
            }
            try {
                app.close();
            } catch (Exception ignored) {
            }
            return result.success() ? 0 : 1;
        }
    }

    // ---------------- eval ----------------

    @Command(name = "eval", description = "运行评估集（eval/tasks.json → eval/report.json）")
    public static final class EvalCmd implements Callable<Integer> {

        @Option(names = "--set", description = "评估集路径（默认 eval/tasks.json）")
        Path set;

        @Option(names = "--config", description = "指定 config.yaml 路径")
        Path config;

        @Override
        public Integer call() throws Exception {
            AppConfig cfg = loadConfig(config);
            Path dataset = set == null ? cwd().resolve(cfg.eval.dataset) : set;
            if (!Files.isRegularFile(dataset)) {
                System.err.println("评估集不存在: " + dataset + "（mkr init 可生成样例）");
                return 2;
            }
            RichConsole console = new RichConsole();
            AppAssembler.App app = AppAssembler.assemble(cwd(), cfg, null, false);
            EvalRunner runner = new EvalRunner(task -> {
                RunContext ctx = app.newSession(task.task(), "eval", task.mode(),
                        PermissionMode.parse(task.permissionMode()), null, null);
                ctx.addListener(console);
                long tokensBefore = app.cost.totalTokens();
                AgentResult r = AgentLoop.create(ctx.mode(), ctx).run(task.task());
                double cost = app.cost.totalUsd(); // 会话级成本由全局追踪累计
                return new EvalRunner.Outcome(r.answer(), r.rounds(), cost, r.durationMs(),
                        r.success() ? null : r.reason());
            }, (task, answer) -> {
                RunContext judgeCtx = app.newSession(task.task(), "judge", "func", null, null, null);
                judgeCtx.setExpectedAnswer(task.expected());
                judgeCtx.setRubric(task.rubric().toArray(new String[0]));
                var j = app.judge.judge(judgeCtx, answer == null ? "" : answer);
                return new EvalRunner.Result(task.id(), j.complete(), j.reason(), answer, 0, 0, 0, null);
            });
            Path report = cwd().resolve("eval/report.json");
            EvalRunner.Report r = runner.run(dataset, report);
            System.out.printf("评估完成: %d/%d 通过（%.1f%%）→ %s%n",
                    r.passed(), r.total(), r.passRate(), report);
            try {
                app.close();
            } catch (Exception ignored) {
            }
            return r.total() > 0 && r.passed() == r.total() ? 0 : 1;
        }
    }

    // ---------------- tools ----------------

    @Command(name = "tools", description = "查看/导出工具")
    public static final class ToolsCmd implements Callable<Integer> {

        @Option(names = "--config", description = "指定 config.yaml 路径")
        Path config;

        @Override
        public Integer call() {
            AppConfig cfg = loadConfig(config);
            AppAssembler.App app = AppAssembler.assemble(cwd(), cfg, null, false);
            List<Tool> all = app.tools.all();
            System.out.println("已注册 " + all.size() + " 个工具（渐进发现=" + (app.tools.progressive() ? "on" : "off") + "）:");
            for (Tool t : all) {
                System.out.printf("  %-18s [%s] %s%n", t.name(), t.risk(),
                        t.description().split("\n")[0].replaceAll("^(。|\\s)+", ""));
            }
            try {
                app.close();
            } catch (Exception ignored) {
            }
            return 0;
        }
    }

    // ---------------- model ----------------

    @Command(name = "model", description = "查看模型目录与当前配置")
    public static final class ModelCmd implements Callable<Integer> {

        @Option(names = "--config", description = "指定 config.yaml 路径")
        Path config;

        @Override
        public Integer call() {
            AppConfig cfg = loadConfig(config);
            System.out.println("当前: provider=" + cfg.llm.provider + " model=" + cfg.llm.model
                    + " base-url=" + cfg.llm.baseUrl);
            System.out.println("\n可用 provider / 示例模型（OpenAI 兼容协议为主，改 config.yaml 即可接入）:");
            System.out.println("  openai      gpt-4o / gpt-4o-mini / gpt-4.1 / o3");
            System.out.println("  anthropic   claude-sonnet-4-5 / claude-haiku-4-5（原生协议 + thinking）");
            System.out.println("  zhipu       glm-4-plus / glm-4-flash（免费）  base-url=https://open.bigmodel.cn/api/paas/v4");
            System.out.println("  doubao      doubao-seed-1-6（火山方舟）");
            System.out.println("  deepseek    deepseek-chat / deepseek-reasoner");
            System.out.println("  ollama      qwen2.5 / llama3.1（本地）      base-url=http://localhost:11434/v1");
            System.out.println("  vllm        任意自托管                      base-url=http://localhost:8000/v1");
            System.out.println("  openrouter  任意（含 Hermes/Nous 系）");
            return 0;
        }
    }

    // ---------------- cost ----------------

    @Command(name = "cost", description = "查看历史会话成本")
    public static final class CostCmd implements Callable<Integer> {

        @Override
        public Integer call() {
            SessionStore sessions = new SessionStore();
            List<SessionStore.SessionInfo> all = sessions.list();
            if (all.isEmpty()) {
                System.out.println("暂无会话记录（~/.mkr/sessions/）");
                return 0;
            }
            double totalUsd = 0;
            long totalTokens = 0;
            System.out.printf("%-28s %-10s %10s %12s  %s%n", "会话", "模型", "tokens", "成本$", "任务");
            for (SessionStore.SessionInfo s : all) {
                double usd = 0;
                long tokens = 0;
                try {
                    Map<String, Object> cost = Json.readMap(Files.readString(
                            sessions.find(s.id()).resolve("cost.json"), StandardCharsets.UTF_8));
                    usd = cost.get("usd") instanceof Number n ? n.doubleValue() : 0;
                    tokens = cost.get("tokens") instanceof Number n ? n.longValue() : 0;
                } catch (Exception ignored) {
                }
                totalUsd += usd;
                totalTokens += tokens;
                String taskLine = s.task() == null ? "" : s.task().replace("\n", " ");
                System.out.printf("%-28s %-10s %10d %12.4f  %s%n", s.id(), s.model(), tokens, usd,
                        taskLine.length() > 40 ? taskLine.substring(0, 40) + "…" : taskLine);
            }
            System.out.printf("合计: %d 会话, %d tokens, $%.4f%n", all.size(), totalTokens, totalUsd);
            return 0;
        }
    }

    // ---------------- reset ----------------

    @Command(name = "reset", description = "删除会话")
    public static final class ResetCmd implements Callable<Integer> {

        @Option(names = "--session", required = true, description = "会话 ID（或前缀）")
        String session;

        @Override
        public Integer call() {
            SessionStore sessions = new SessionStore();
            if (sessions.delete(session)) {
                System.out.println("已删除会话 " + session);
                return 0;
            }
            System.err.println("会话不存在: " + session);
            return 2;
        }
    }

    // ---------------- skills ----------------

    @Command(name = "skills", description = "skill 管理: list | install <路径|git>")
    public static final class SkillsCmd implements Callable<Integer> {

        @Parameters(arity = "0..2", description = "list | install <path-or-git-url>")
        List<String> args;

        @Override
        public Integer call() throws Exception {
            String action = args == null || args.isEmpty() ? "list" : args.get(0);
            com.mkr.context.SkillsLoader loader = new com.mkr.context.SkillsLoader(cwd().resolve("skills"));
            switch (action) {
                case "list" -> {
                    String idx = loader.indexMarkdown();
                    System.out.println(idx.isBlank() ? "（暂无 skill）" : idx);
                    return 0;
                }
                case "install" -> {
                    if (args.size() < 2) {
                        System.err.println("用法: mkr skills install <本地目录|git-url>");
                        return 2;
                    }
                    String source = args.get(1);
                    return install(loader, source);
                }
                default -> {
                    System.err.println("未知子命令: " + action + "（list | install）");
                    return 2;
                }
            }
        }

        private int install(com.mkr.context.SkillsLoader loader, String source) throws Exception {
            Path skillsDir = cwd().resolve("skills");
            Files.createDirectories(skillsDir);
            Path src;
            if (source.startsWith("git@") || source.startsWith("https://") && source.endsWith(".git")) {
                String name = source.substring(source.lastIndexOf('/') + 1).replaceAll("\\.git$", "");
                if (name.isBlank()) {
                    name = "skill-" + System.currentTimeMillis();
                }
                Path target = skillsDir.resolve(name);
                if (Files.exists(target)) {
                    System.err.println("已存在同名 skill: " + name);
                    return 2;
                }
                Sandbox_lite("git clone --depth 1 " + source + " '" + target + "'");
                src = target;
            } else {
                src = Path.of(source).toAbsolutePath().normalize();
                if (!Files.isRegularFile(src.resolve("SKILL.md"))) {
                    System.err.println("无效 skill 目录（缺少 SKILL.md）: " + src);
                    return 2;
                }
            }
            if (!Files.isRegularFile(src.resolve("SKILL.md"))) {
                System.err.println("安装源缺少 SKILL.md: " + src);
                return 2;
            }
            Path dest = skillsDir.resolve(src.getFileName().toString());
            if (!Files.exists(dest)) {
                Files.createDirectories(dest);
                try (var walk = Files.walk(src)) {
                    walk.forEach(f -> {
                        try {
                            Path to = dest.resolve(src.relativize(f).toString());
                            if (Files.isDirectory(f)) {
                                Files.createDirectories(to);
                            } else {
                                Files.createDirectories(to.getParent());
                                Files.copy(f, to);
                            }
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
            System.out.println("已安装 skill: " + dest.getFileName());
            return 0;
        }

        private void Sandbox_lite(String cmd) throws IOException, InterruptedException {
            Process p = new ProcessBuilder("sh", "-c", cmd).inheritIO().start();
            if (p.waitFor() != 0) {
                throw new IOException("git clone 失败: " + cmd);
            }
        }
    }

    // ---------------- mcp ----------------

    @Command(name = "mcp", description = "MCP 服务器管理: add <name> -- <cmd...> | list | remove <name>")
    public static final class McpCmd implements Callable<Integer> {

        @Parameters(arity = "0..10", description = "子命令与参数（-- 后为启动命令）")
        List<String> args;

        @Override
        public Integer call() throws Exception {
            Path configFile = cwd().resolve("config/mcp.yml");
            McpManager manager = new McpManager(configFile);
            String action = args == null || args.isEmpty() ? "list" : args.get(0);
            switch (action) {
                case "list" -> {
                    Map<String, McpManager.ServerConfig> configs = manager.loadConfigs();
                    if (configs.isEmpty()) {
                        System.out.println("（无 MCP 服务器，添加: mkr mcp add <name> -- <command>）");
                        return 0;
                    }
                    for (McpManager.ServerConfig c : configs.values()) {
                        System.out.println("- " + c.name() + ": " + String.join(" ", c.command()));
                    }
                    return 0;
                }
                case "add" -> {
                    if (args == null || args.size() < 2) {
                        System.err.println("用法: mkr mcp add <name> -- <command args...>");
                        return 2;
                    }
                    int sep = args.indexOf("--");
                    if (sep < 2 || args.size() <= sep + 1) {
                        System.err.println("缺少 -- 后的启动命令");
                        return 2;
                    }
                    String name = args.get(1);
                    List<String> command = args.subList(sep + 1, args.size());
                    Map<String, McpManager.ServerConfig> configs = manager.loadConfigs();
                    configs.put(name, new McpManager.ServerConfig(name, command, Map.of()));
                    manager.saveConfigs(configs);
                    System.out.println("已添加 MCP 服务器 " + name + "（" + String.join(" ", command) + "）");
                    System.out.println("提示: config.yaml 设 mcp.auto-start: true 或重启会话后生效；凭证用环境变量，勿写入命令");
                    return 0;
                }
                case "remove" -> {
                    if (args == null || args.size() < 2) {
                        System.err.println("用法: mkr mcp remove <name>");
                        return 2;
                    }
                    Map<String, McpManager.ServerConfig> configs = manager.loadConfigs();
                    if (configs.remove(args.get(1)) == null) {
                        System.err.println("不存在: " + args.get(1));
                        return 2;
                    }
                    manager.saveConfigs(configs);
                    System.out.println("已移除 " + args.get(1));
                    return 0;
                }
                default -> {
                    System.err.println("未知子命令: " + action);
                    return 2;
                }
            }
        }
    }

    // ---------------- permissions ----------------

    @Command(name = "permissions", description = "查看路径权限规则 / 检查路径命中")
    public static final class PermissionsCmd implements Callable<Integer> {

        @Parameters(arity = "0..2", description = "[check <path> [read|write|delete]]")
        List<String> args;

        @Option(names = "--config", description = "指定 config.yaml 路径")
        Path config;

        @Override
        public Integer call() {
            AppConfig cfg = loadConfig(config);
            com.mkr.guard.PathPolicy policy = com.mkr.guard.PathPolicy.of(
                    cfg.permissions.allow, cfg.permissions.ask, cfg.permissions.deny);
            com.mkr.guard.ProtectedDirs protectedDirs = new com.mkr.guard.ProtectedDirs(cfg.permissions.protectedDirs);
            com.mkr.guard.FileAccessGuard guard = new com.mkr.guard.FileAccessGuard(
                    cwd(), policy, protectedDirs, true, true);
            if (args == null || args.isEmpty() || !"check".equals(args.get(0))) {
                System.out.println("权限模式默认: " + cfg.agent.permissionMode);
                System.out.println("路径规则（优先级 deny > ask > allow > 默认）:");
                policy.describe().forEach(r -> System.out.println("  " + r));
                System.out.println("受保护（默认拒绝/强制授权）: " + String.join(", ", protectedDirs.rules()));
                return 0;
            }
            if (args.size() < 2) {
                System.err.println("用法: mkr permissions check <path> [read|write|delete]");
                return 2;
            }
            String path = args.get(1);
            String op = args.size() > 2 ? args.get(2) : "read";
            var decision = guard.check(op, path, null, com.mkr.guard.PermissionMode.DEFAULT);
            System.out.println(op + " " + path + " → " + decision.outcome() + "  " + decision.reason());
            return decision.denied() ? 1 : 0;
        }
    }

    // ---------------- repl ----------------

    @Command(name = "repl", description = "交互式 REPL（默认命令）")
    public static final class ReplCmd implements Callable<Integer> {

        @Option(names = "--config", description = "指定 config.yaml 路径")
        Path config;

        @Override
        public Integer call() {
            AppConfig cfg = loadConfig(config);
            new Repl(cwd(), cfg).run();
            return 0;
        }
    }

    /** 工具 schema 导出（调试用）。 */
    static Map<String, Object> exportTool(Tool t) {
        return ToolSchemaExporter.mcp(t);
    }
}
