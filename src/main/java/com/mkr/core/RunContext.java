package com.mkr.core;

import com.mkr.api.LlmClient;
import com.mkr.config.AppConfig;
import com.mkr.context.Compressor;
import com.mkr.context.MessageList;
import com.mkr.context.PromptRegistry;
import com.mkr.context.SkillsLoader;
import com.mkr.context.StatusBar;
import com.mkr.event.EventLoop;
import com.mkr.event.TimerScheduler;
import com.mkr.guard.ApprovalGate;
import com.mkr.guard.FileAccessGuard;
import com.mkr.guard.Guardrail;
import com.mkr.guard.PermissionMode;
import com.mkr.guard.Sandbox;
import com.mkr.memory.HybridRetriever;
import com.mkr.memory.MemoryStore;
import com.mkr.multiagent.MessageBus;
import com.mkr.multiagent.SubAgentManager;
import com.mkr.obs.CostTracker;
import com.mkr.obs.FeatureFlag;
import com.mkr.obs.TraceExporter;
import com.mkr.tools.ToolRegistry;
import com.mkr.verify.CompletionJudge;
import com.mkr.verify.Verifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单 Agent 会话上下文：组件引用（装配层注入）+ 会话态（消息/状态栏/审批记忆/已写文件）。
 * 子 Agent 经 {@link #child} 派生，共享组件但独立消息与预算。
 */
public final class RunContext implements FileAccessGuard.ApprovalMemo {

    private final AppConfig config;
    private final Path projectRoot;
    private final Path workspaceRoot;
    private final String sessionId;
    private final String agentName;
    private final Path sessionDir; // 可空（子 Agent / headless eval）
    private final String task;
    private final String rolePrompt; // 子 Agent 角色注入
    private final RunContext parent;

    // 组件（AppAssembler 注入）
    private final LlmClient llm;
    private final ToolRegistry tools;
    private final Guardrail guard;
    private final ApprovalGate approvals;
    private final Sandbox sandbox;
    private final EventLoop events;
    private final TimerScheduler timers;
    private final MemoryStore memory;
    private final HybridRetriever retriever;
    private final SubAgentManager subAgents;
    private final MessageBus bus;
    private final CostTracker cost;
    private final TraceExporter trace;
    private final SkillsLoader skills;
    private final PromptRegistry prompts;
    private final Compressor compressor;
    private final FeatureFlag flags;
    private final CompletionJudge judge;
    private final List<Verifier> verifiers;

    // 会话态
    private final MessageList messages = new MessageList();
    private final StatusBar status;
    private final AtomicReference<AgentState> state = new AtomicReference<>(AgentState.READY);
    private final List<LoopListener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> approvalsGranted = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Path> writtenFiles = new ConcurrentLinkedQueue<>();
    private volatile PermissionMode permissionMode;
    private volatile String mode;
    private volatile String model;
    private volatile boolean cancelled;
    private volatile String expectedAnswer;
    private volatile String[] rubric;

    private RunContext(Builder b) {
        this.config = b.config;
        this.projectRoot = b.projectRoot;
        this.workspaceRoot = b.workspaceRoot;
        this.sessionId = b.sessionId == null ? UUID.randomUUID().toString().substring(0, 12) : b.sessionId;
        this.agentName = b.agentName;
        this.sessionDir = b.sessionDir;
        this.task = b.task;
        this.rolePrompt = b.rolePrompt;
        this.parent = b.parent;
        this.llm = b.llm;
        this.tools = b.tools;
        this.guard = b.guard;
        this.approvals = b.approvals;
        this.sandbox = b.sandbox;
        this.events = b.events;
        this.timers = b.timers;
        this.memory = b.memory;
        this.retriever = b.retriever;
        this.subAgents = b.subAgents;
        this.bus = b.bus;
        this.cost = b.cost;
        this.trace = b.trace;
        this.skills = b.skills;
        this.prompts = b.prompts;
        this.compressor = b.compressor;
        this.flags = b.flags;
        this.judge = b.judge;
        this.verifiers = List.copyOf(b.verifiers);
        this.permissionMode = b.permissionMode;
        this.mode = b.mode;
        this.model = b.model;
        this.status = new StatusBar(b.agentName + "@" + b.projectRoot);
        this.status.setPendingEventsSupplier(() -> events == null ? 0 : events.pending());
    }

    public static Builder builder(AppConfig config, Path projectRoot) {
        return new Builder(config, projectRoot);
    }

    /** 派生子 Agent：共享组件，独立 MessageList/StatusBar/预算/工作区。 */
    public RunContext child(String name, String task, String rolePrompt) {
        Path childWorkspace = workspaceRoot.resolve(".agents").resolve(sanitize(name));
        Builder b = builder(config, projectRoot)
                .agentName(name)
                .task(task)
                .rolePrompt(rolePrompt == null || rolePrompt.isBlank()
                        ? "你是子 Agent「" + name + "」，专注完成委派的任务并返回结构化结论。" : rolePrompt)
                .workspaceRoot(childWorkspace)
                .parent(this)
                .sessionDir(null)
                .permissionMode(permissionMode)
                .mode(mode)
                .model(model);
        b.copyComponents(this);
        return b.build();
    }

    private static String sanitize(String name) {
        return name == null ? "agent" : name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    // ---------------- 会话操作 ----------------

    /** 恢复历史轨迹（--resume）。 */
    public void adoptHistory(List<com.mkr.api.Message> history) {
        messages.reset(history);
    }

    @Override
    public boolean isApproved(String key) {
        return approvalsGranted.contains(key);
    }

    public void markApproved(String key) {
        approvalsGranted.add(key);
    }

    public void recordWritten(Path file) {
        if (file != null) {
            writtenFiles.add(file.toAbsolutePath().normalize());
        }
    }

    /** 取走（消费）指定后缀的新写文件，供 CompileVerifier 验证。 */
    public List<Path> drainWrittenFiles(String suffix) {
        List<Path> out = new ArrayList<>();
        List<Path> keep = new ArrayList<>();
        Path p;
        while ((p = writtenFiles.poll()) != null) {
            if (suffix == null || p.toString().endsWith(suffix)) {
                out.add(p);
            } else {
                keep.add(p);
            }
        }
        writtenFiles.addAll(keep);
        return out;
    }

    public List<Path> writtenFilesView() {
        return List.copyOf(writtenFiles);
    }

    public void addListener(LoopListener l) {
        listeners.add(l);
    }

    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setState(AgentState s) {
        state.set(s);
        listeners.forEach(l -> l.onState(s));
    }

    public AgentState state() {
        return state.get();
    }

    // fire 便捷方法（AgentLoop 调用）
    public void fireToken(String text) {
        listeners.forEach(l -> l.onToken(text));
    }

    public void fireThinking(String text) {
        listeners.forEach(l -> l.onThinking(text));
    }

    public void fireToolStart(com.mkr.api.ToolCall call) {
        listeners.forEach(l -> l.onToolStart(call));
    }

    public void fireToolEnd(com.mkr.api.ToolCall call, com.mkr.tools.ToolResult result) {
        listeners.forEach(l -> l.onToolEnd(call, result));
    }

    public void fireRound(int round) {
        listeners.forEach(l -> l.onRound(round));
    }

    public void fireApprovalPending(String reason) {
        listeners.forEach(l -> l.onApprovalPending(reason));
    }

    public void fireAnswer(String answer) {
        listeners.forEach(l -> l.onAnswer(answer));
    }

    public void fireMessage(com.mkr.api.Message message) {
        listeners.forEach(l -> l.onMessage(message));
    }

    // ---------------- getter ----------------

    public AppConfig config() {
        return config;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public Path artifactsDir() {
        return workspaceRoot.resolve(".artifacts");
    }

    public String sessionId() {
        return sessionId;
    }

    public String agentName() {
        return agentName;
    }

    public Path sessionDir() {
        return sessionDir;
    }

    public String task() {
        return task;
    }

    public String rolePrompt() {
        return rolePrompt;
    }

    public RunContext parent() {
        return parent;
    }

    public LlmClient llm() {
        return llm;
    }

    public ToolRegistry tools() {
        return tools;
    }

    public Guardrail guard() {
        return guard;
    }

    public ApprovalGate approvals() {
        return approvals;
    }

    public Sandbox sandbox() {
        return sandbox;
    }

    public EventLoop events() {
        return events;
    }

    public TimerScheduler timers() {
        return timers;
    }

    public MemoryStore memory() {
        return memory;
    }

    public HybridRetriever retriever() {
        return retriever;
    }

    public SubAgentManager subAgents() {
        return subAgents;
    }

    public MessageBus bus() {
        return bus;
    }

    public CostTracker cost() {
        return cost;
    }

    public TraceExporter trace() {
        return trace;
    }

    public SkillsLoader skills() {
        return skills;
    }

    public PromptRegistry prompts() {
        return prompts;
    }

    public Compressor compressor() {
        return compressor;
    }

    public FeatureFlag flags() {
        return flags;
    }

    public CompletionJudge judge() {
        return judge;
    }

    public List<Verifier> verifiers() {
        return verifiers;
    }

    public MessageList messages() {
        return messages;
    }

    public StatusBar status() {
        return status;
    }

    public PermissionMode permissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(PermissionMode mode) {
        this.permissionMode = mode;
    }

    public String mode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String model() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String expectedAnswer() {
        return expectedAnswer;
    }

    public void setExpectedAnswer(String expectedAnswer) {
        this.expectedAnswer = expectedAnswer;
    }

    public String[] rubric() {
        return rubric;
    }

    public void setRubric(String[] rubric) {
        this.rubric = rubric;
    }

    // ---------------- Builder ----------------

    public static final class Builder {
        private final AppConfig config;
        private final Path projectRoot;
        private Path workspaceRoot;
        private String sessionId;
        private String agentName = "mkr";
        private Path sessionDir;
        private String task = "";
        private String rolePrompt;
        private RunContext parent;
        private LlmClient llm;
        private ToolRegistry tools;
        private Guardrail guard;
        private ApprovalGate approvals;
        private Sandbox sandbox;
        private EventLoop events;
        private TimerScheduler timers;
        private MemoryStore memory;
        private HybridRetriever retriever;
        private SubAgentManager subAgents;
        private MessageBus bus;
        private CostTracker cost;
        private TraceExporter trace;
        private SkillsLoader skills;
        private PromptRegistry prompts;
        private Compressor compressor;
        private FeatureFlag flags;
        private CompletionJudge judge;
        private List<Verifier> verifiers = new ArrayList<>();
        private PermissionMode permissionMode = PermissionMode.DEFAULT;
        private String mode = "react";
        private String model;

        private Builder(AppConfig config, Path projectRoot) {
            this.config = config;
            this.projectRoot = projectRoot.toAbsolutePath().normalize();
            this.workspaceRoot = this.projectRoot.resolve("workspace");
            this.model = config.llm.model;
        }

        void copyComponents(RunContext src) {
            this.llm = src.llm;
            this.tools = src.tools;
            this.guard = src.guard;
            this.approvals = src.approvals;
            this.sandbox = src.sandbox;
            this.events = src.events;
            this.timers = src.timers;
            this.memory = src.memory;
            this.retriever = src.retriever;
            this.subAgents = src.subAgents;
            this.bus = src.bus;
            this.cost = src.cost;
            this.trace = src.trace;
            this.skills = src.skills;
            this.prompts = src.prompts;
            this.compressor = src.compressor;
            this.flags = src.flags;
            this.judge = src.judge;
            this.verifiers = new ArrayList<>(src.verifiers);
        }

        public Builder workspaceRoot(Path p) {
            this.workspaceRoot = p;
            return this;
        }

        public Builder sessionId(String s) {
            this.sessionId = s;
            return this;
        }

        public Builder agentName(String s) {
            this.agentName = s;
            return this;
        }

        public Builder sessionDir(Path p) {
            this.sessionDir = p;
            return this;
        }

        public Builder task(String s) {
            this.task = s;
            return this;
        }

        public Builder rolePrompt(String s) {
            this.rolePrompt = s;
            return this;
        }

        public Builder parent(RunContext p) {
            this.parent = p;
            return this;
        }

        public Builder llm(LlmClient v) {
            this.llm = v;
            return this;
        }

        public Builder tools(ToolRegistry v) {
            this.tools = v;
            return this;
        }

        public Builder guard(Guardrail v) {
            this.guard = v;
            return this;
        }

        public Builder approvals(ApprovalGate v) {
            this.approvals = v;
            return this;
        }

        public Builder sandbox(Sandbox v) {
            this.sandbox = v;
            return this;
        }

        public Builder events(EventLoop v) {
            this.events = v;
            return this;
        }

        public Builder timers(TimerScheduler v) {
            this.timers = v;
            return this;
        }

        public Builder memory(MemoryStore v) {
            this.memory = v;
            return this;
        }

        public Builder retriever(HybridRetriever v) {
            this.retriever = v;
            return this;
        }

        public Builder subAgents(SubAgentManager v) {
            this.subAgents = v;
            return this;
        }

        public Builder bus(MessageBus v) {
            this.bus = v;
            return this;
        }

        public Builder cost(CostTracker v) {
            this.cost = v;
            return this;
        }

        public Builder trace(TraceExporter v) {
            this.trace = v;
            return this;
        }

        public Builder skills(SkillsLoader v) {
            this.skills = v;
            return this;
        }

        public Builder prompts(PromptRegistry v) {
            this.prompts = v;
            return this;
        }

        public Builder compressor(Compressor v) {
            this.compressor = v;
            return this;
        }

        public Builder flags(FeatureFlag v) {
            this.flags = v;
            return this;
        }

        public Builder judge(CompletionJudge v) {
            this.judge = v;
            return this;
        }

        public Builder verifier(Verifier v) {
            this.verifiers.add(v);
            return this;
        }

        public Builder permissionMode(PermissionMode v) {
            this.permissionMode = v;
            return this;
        }

        public Builder mode(String v) {
            this.mode = v;
            return this;
        }

        public Builder model(String v) {
            this.model = v;
            return this;
        }

        public RunContext build() {
            return new RunContext(this);
        }
    }
}
