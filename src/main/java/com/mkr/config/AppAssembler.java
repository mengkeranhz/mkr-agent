package com.mkr.config;

import com.mkr.api.AnthropicLlmClient;
import com.mkr.api.LlmClient;
import com.mkr.api.OpenAiLlmClient;
import com.mkr.context.Compressor;
import com.mkr.context.PromptRegistry;
import com.mkr.context.SkillsLoader;
import com.mkr.core.AgentLoop;
import com.mkr.core.RunContext;
import com.mkr.event.EventLoop;
import com.mkr.event.TimerScheduler;
import com.mkr.guard.ApprovalGate;
import com.mkr.guard.BwrapSandbox;
import com.mkr.guard.CloudSandbox;
import com.mkr.guard.DockerSandbox;
import com.mkr.guard.FileAccessGuard;
import com.mkr.guard.Guardrail;
import com.mkr.guard.InjectionSanitizer;
import com.mkr.guard.LocalSandbox;
import com.mkr.guard.PathPolicy;
import com.mkr.guard.PermissionMode;
import com.mkr.guard.ProtectedDirs;
import com.mkr.guard.RiskAssessor;
import com.mkr.guard.Sandbox;
import com.mkr.guard.SidecarVerifier;
import com.mkr.memory.EmbeddingClient;
import com.mkr.memory.FileMemoryStore;
import com.mkr.memory.HybridRetriever;
import com.mkr.memory.InMemoryVectorStore;
import com.mkr.memory.MemoryStore;
import com.mkr.memory.MetadataDb;
import com.mkr.memory.PgVectorStore;
import com.mkr.memory.VectorStore;
import com.mkr.mcp.McpManager;
import com.mkr.multiagent.InMemoryBus;
import com.mkr.multiagent.MessageBus;
import com.mkr.multiagent.RedisBus;
import com.mkr.multiagent.SubAgentManager;
import com.mkr.obs.CostTracker;
import com.mkr.obs.FeatureFlag;
import com.mkr.obs.TraceExporter;
import com.mkr.tools.ToolRegistry;
import com.mkr.verify.CompletionJudge;
import com.mkr.verify.CompileVerifier;
import com.mkr.verify.Verifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用装配：从 AppConfig 构建全部组件并互连。核心不依赖 Spring，
 * 纯手写工厂（CLI 轻启、单 jar）。
 */
public final class AppAssembler {

    /** 装配产物（一次性构建，多个 RunContext 复用）。 */
    public static final class App implements AutoCloseable {
        public final AppConfig config;
        public final Path projectRoot;
        public final Path workspaceRoot;
        public final LlmClient llm;
        public final ToolRegistry tools;
        public final Guardrail guard;
        public final ApprovalGate approvals;
        public final Sandbox sandbox;
        public final MemoryStore memory;
        public final HybridRetriever retriever;
        public final MetadataDb metadataDb;
        public final EventLoop events;
        public final TimerScheduler timers;
        public final MessageBus bus;
        public final SubAgentManager subAgents;
        public final CostTracker cost;
        public final TraceExporter trace;
        public final SkillsLoader skills;
        public final PromptRegistry prompts;
        public final Compressor compressor;
        public final FeatureFlag flags;
        public final CompletionJudge judge;
        public final List<Verifier> verifiers;
        public final McpManager mcp;

        App(AppConfig config, Path projectRoot, Path workspaceRoot, LlmClient llm, ToolRegistry tools,
            Guardrail guard, ApprovalGate approvals, Sandbox sandbox, MemoryStore memory,
            HybridRetriever retriever, MetadataDb metadataDb, EventLoop events, TimerScheduler timers,
            MessageBus bus, SubAgentManager subAgents, CostTracker cost, TraceExporter trace,
            SkillsLoader skills, PromptRegistry prompts, Compressor compressor, FeatureFlag flags,
            CompletionJudge judge, List<Verifier> verifiers, McpManager mcp) {
            this.config = config;
            this.projectRoot = projectRoot;
            this.workspaceRoot = workspaceRoot;
            this.llm = llm;
            this.tools = tools;
            this.guard = guard;
            this.approvals = approvals;
            this.sandbox = sandbox;
            this.memory = memory;
            this.retriever = retriever;
            this.metadataDb = metadataDb;
            this.events = events;
            this.timers = timers;
            this.bus = bus;
            this.subAgents = subAgents;
            this.cost = cost;
            this.trace = trace;
            this.skills = skills;
            this.prompts = prompts;
            this.compressor = compressor;
            this.flags = flags;
            this.judge = judge;
            this.verifiers = verifiers;
            this.mcp = mcp;
        }

        /** 新建会话上下文（sessionDir 可空 = headless/eval）。 */
        public RunContext newSession(String task, String agentName, String mode,
                                     PermissionMode permissionMode, Path sessionDir, String modelOverride) {
            RunContext.Builder b = RunContext.builder(config, projectRoot)
                    .task(task)
                    .agentName(agentName == null ? "mkr" : agentName)
                    .workspaceRoot(workspaceRoot)
                    .sessionDir(sessionDir)
                    .permissionMode(permissionMode == null
                            ? PermissionMode.parse(config.agent.permissionMode) : permissionMode)
                    .mode(mode == null || mode.isBlank() ? config.agent.defaultMode : mode)
                    .model(modelOverride == null || modelOverride.isBlank() ? config.llm.model : modelOverride)
                    .llm(llm).tools(tools).guard(guard).approvals(approvals).sandbox(sandbox)
                    .events(events).timers(timers).memory(memory).retriever(retriever)
                    .subAgents(subAgents).bus(bus).cost(cost).trace(trace).skills(skills)
                    .prompts(prompts).compressor(compressor).flags(flags).judge(judge);
            verifiers.forEach(b::verifier);
            return b.build();
        }

        @Override
        public void close() {
            timers.close();
            trace.close();
            bus.close();
            metadataDb.close();
            if (mcp != null) {
                mcp.close();
            }
        }
    }

    public static App assemble(Path projectRoot, AppConfig cfg, ApprovalGate.Prompter prompter, boolean interactive) {
        projectRoot = projectRoot.toAbsolutePath().normalize();
        Path workspaceRoot = projectRoot.resolve("workspace");

        FeatureFlag flags = new FeatureFlag(cfg.features);
        InjectionSanitizer sanitizer = new InjectionSanitizer();

        // LLM
        LlmClient llm = buildLlm(cfg);

        // 护栏
        PathPolicy policy = PathPolicy.of(cfg.permissions.allow, cfg.permissions.ask, cfg.permissions.deny);
        ProtectedDirs protectedDirs = new ProtectedDirs(cfg.permissions.protectedDirs);
        FileAccessGuard fileGuard = new FileAccessGuard(projectRoot, policy, protectedDirs,
                cfg.permissions.writeRiskDefaultOn(), true);
        RiskAssessor riskAssessor = new RiskAssessor();
        SidecarVerifier sidecar = flags.isEnabled("sidecar") ? new SidecarVerifier(llm, cfg.llm.model) : null;
        Guardrail guardrail = new Guardrail(riskAssessor, fileGuard, sidecar, flags.isEnabled("sidecar"));
        ApprovalGate approvals = new ApprovalGate(prompter, interactive);

        // 工具
        ToolRegistry tools = ToolRegistry.discoverDefaults();
        tools.setProgressive(flags.isEnabled("progressive-tools"));

        // 沙箱
        Sandbox sandbox = buildSandbox(cfg, projectRoot, workspaceRoot);

        // 记忆 / 检索
        MemoryStore memory = new FileMemoryStore(workspaceRoot.resolve(cfg.memory.root), sanitizer);
        VectorStore vectors = buildVectorStore(cfg);
        EmbeddingClient embeddings = new EmbeddingClient(
                cfg.memory.embeddingBaseUrl, cfg.memory.embeddingApiKey, cfg.memory.embeddingModel);
        HybridRetriever retriever = new HybridRetriever(memory, vectors, embeddings,
                cfg.memory.hybridRetrieval && flags.isEnabled("memory-hybrid"));
        MetadataDb metadataDb = MetadataDb.create(cfg.memory.metadataDb, workspaceRoot);

        // 事件 / 定时器 / 总线
        EventLoop events = new EventLoop();
        TimerScheduler timers = new TimerScheduler(events, "mkr");
        MessageBus bus = "redis".equalsIgnoreCase(cfg.multiagent.bus)
                ? new RedisBus(cfg.multiagent.redisUrl)
                : new InMemoryBus();

        // 多 Agent（loop factory 延后注入避免构造环）
        SubAgentManager subAgents = new SubAgentManager((ctx, mode) -> AgentLoop.create(mode, ctx));

        // 可观测
        CostTracker cost = new CostTracker(cfg.obs.prices);
        TraceExporter trace = flags.isEnabled("otel")
                ? TraceExporter.create(cfg.obs.otlpEndpoint, projectRoot.resolve(cfg.obs.regressionDir))
                : TraceExporter.disabled(projectRoot.resolve(cfg.obs.regressionDir));

        // 上下文
        SkillsLoader skills = new SkillsLoader(projectRoot.resolve("skills"));
        PromptRegistry prompts = new PromptRegistry(projectRoot);
        Compressor compressor = new Compressor(
                com.mkr.context.ContextConfig.from(cfg), workspaceRoot, llm, cfg.llm.model);

        // 验证 / 判定
        CompletionJudge judge = new CompletionJudge(llm, cfg.llm.model, cfg.eval.judge);
        List<Verifier> verifiers = new ArrayList<>(List.of(new CompileVerifier(sandbox, projectRoot)));

        // MCP（autoStart 时注册远程工具）
        McpManager mcp = new McpManager(projectRoot.resolve(cfg.mcp.configFile));
        if (cfg.mcp.autoStart) {
            mcp.startAll(tools);
        }

        try {
            Files.createDirectories(workspaceRoot);
        } catch (Exception ignored) {
        }

        return new App(cfg, projectRoot, workspaceRoot, llm, tools, guardrail, approvals, sandbox, memory,
                retriever, metadataDb, events, timers, bus, subAgents, cost, trace, skills, prompts,
                compressor, flags, judge, verifiers, mcp);
    }

    private static LlmClient buildLlm(AppConfig cfg) {
        String apiKey = cfg.llm.apiKey == null ? "" : cfg.llm.apiKey;
        if ("anthropic".equalsIgnoreCase(cfg.llm.provider)) {
            return new AnthropicLlmClient(cfg.llm.baseUrl, apiKey, cfg.llm.timeoutMs, cfg.llm.watchdogIdleMs);
        }
        // OpenAI 兼容：openai / deepseek / doubao / zhipu / ollama / vllm / openrouter
        return new OpenAiLlmClient(cfg.llm.provider, cfg.llm.baseUrl, apiKey,
                cfg.llm.timeoutMs, cfg.llm.watchdogIdleMs);
    }

    private static Sandbox buildSandbox(AppConfig cfg, Path projectRoot, Path workspaceRoot) {
        return switch (cfg.tools.sandboxType == null ? "local" : cfg.tools.sandboxType) {
            case "docker" -> new DockerSandbox(cfg.tools.sandboxImage, cfg.tools.sandboxMemory,
                    cfg.tools.sandboxCpus, cfg.tools.sandboxPidsLimit, cfg.tools.networkAllowlist,
                    workspaceRoot, 100_000);
            case "bwrap" -> new BwrapSandbox(workspaceRoot, 100_000);
            case "cloud" -> new CloudSandbox(cfg.tools.cloudSandboxUrl, cfg.tools.cloudSandboxKey);
            default -> new LocalSandbox(100_000);
        };
    }

    /** pgvector 连接参数取环境变量（PGVECTOR_URL/USER/PASSWORD），未配置回退内存实现。 */
    private static VectorStore buildVectorStore(AppConfig cfg) {
        if ("pgvector".equalsIgnoreCase(cfg.memory.vectorStore)) {
            String url = env("PGVECTOR_URL", "jdbc:postgresql://localhost:5432/mkr");
            try {
                return new PgVectorStore(url, env("PGVECTOR_USER", "mkr"), env("PGVECTOR_PASSWORD", "mkr"));
            } catch (Exception e) {
                System.err.println("[mkr] pgvector 不可用（" + e.getMessage() + "），降级 in-memory");
            }
        }
        return new InMemoryVectorStore();
    }

    private static String env(String key, String dft) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? dft : v;
    }
}
