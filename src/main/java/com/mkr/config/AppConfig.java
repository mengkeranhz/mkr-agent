package com.mkr.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * config.yaml 技术参数（不进 prompt）。三层指令文件体系中的「配置层」。
 *
 * <p>加载顺序：--config 指定路径 ＞ ./config/config.yaml ＞ ./config.yaml ＞ ~/.mkr/config.yaml ＞ 全默认。
 * 支持 ${VAR} 与 ${VAR:-default} 环境变量替换；.env（项目根 / ~/.mkr/.env）参与解析。</p>
 */
public final class AppConfig {

    public final Llm llm = new Llm();
    public final Tools tools = new Tools();
    public final AgentCfg agent = new AgentCfg();
    public final Permissions permissions = new Permissions();
    public final Memory memory = new Memory();
    public final Eval eval = new Eval();
    public final Obs obs = new Obs();
    public final Multiagent multiagent = new Multiagent();
    public final McpCfg mcp = new McpCfg();
    public Map<String, Boolean> features = defaultFeatures();
    /** 配置来源路径（null=全默认），诊断用。 */
    public Path configPath;

    // ---------------- 加载 ----------------

    public static AppConfig load(Path cwd, Path explicit) {
        Map<String, String> env = dotEnv(cwd);
        for (Path p : candidates(cwd, explicit)) {
            if (Files.isRegularFile(p)) {
                try {
                    String raw = Files.readString(p);
                    Map<String, Object> map = new Yaml().load(substituteEnv(raw, env));
                    AppConfig cfg = fromMap(map == null ? Map.of() : map);
                    cfg.configPath = p;
                    mergePermissionFiles(cwd, cfg);
                    return cfg;
                } catch (IOException e) {
                    throw new IllegalStateException("读取配置失败: " + p, e);
                }
            }
        }
        AppConfig cfg = new AppConfig();
        cfg.applyProviderDefaults();
        mergePermissionFiles(cwd, cfg);
        return cfg;
    }

    /**
     * 规则层级（对齐 Claude Code）：用户级 ~/.mkr/permissions.yml ＜ 项目级
     * （config/permissions.yml 与 config.yaml 内嵌 permissions 同为项目级，文件优先）。
     * 项目级覆盖 allow/ask；deny 与 protected-dirs 跨层取并集且永远最高优先。
     */
    @SuppressWarnings("unchecked")
    private static void mergePermissionFiles(Path cwd, AppConfig cfg) {
        Map<String, Object> homeRoot = loadYamlPermissions(
                Path.of(System.getProperty("user.home"), ".mkr", "permissions.yml"));
        Map<String, Object> projRoot = loadYamlPermissions(
                cwd.resolve("config").resolve("permissions.yml"));
        // deny / protected-dirs：跨层并集
        for (Map<String, Object> root : List.of(homeRoot, projRoot)) {
            unionInto(cfg.permissions.deny, l(root, "deny"));
            unionInto(cfg.permissions.protectedDirs, l(root, "protected-dirs"));
        }
        // allow / ask：项目级（permissions.yml → config.yaml 内嵌）优先于全局
        applyPrecedence(cfg.permissions, projRoot, homeRoot, cfg.permissions.allow, "allow");
        applyPrecedence(cfg.permissions, projRoot, homeRoot, cfg.permissions.ask, "ask");
    }

    private static Map<String, Object> loadYamlPermissions(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            Map<String, Object> root = new Yaml().load(Files.readString(file));
            return root == null ? Map.of() : root;
        } catch (IOException e) {
            return Map.of();
        }
    }

    private static void applyPrecedence(Permissions target, Map<String, Object> project,
                                        Map<String, Object> home, List<String> embedded, String key) {
        List<String> fromProject = l(project, key);
        if (!fromProject.isEmpty()) {
            setList(target, key, fromProject);
            return;
        }
        if (embedded != null && !embedded.isEmpty()) {
            return; // config.yaml 内嵌（项目级）保留
        }
        List<String> fromHome = l(home, key);
        if (!fromHome.isEmpty()) {
            setList(target, key, fromHome);
        }
    }

    private static void setList(Permissions target, String key, List<String> value) {
        switch (key) {
            case "allow" -> target.allow = new ArrayList<>(value);
            case "ask" -> target.ask = new ArrayList<>(value);
            case "deny" -> target.deny = new ArrayList<>(value);
            case "protected-dirs" -> target.protectedDirs = new ArrayList<>(value);
        }
    }

    private static void unionInto(List<String> target, List<String> additions) {
        if (additions.isEmpty()) {
            return;
        }
        for (String d : additions) {
            if (!target.contains(d)) {
                target.add(d);
            }
        }
    }

    private static List<Path> candidates(Path cwd, Path explicit) {
        List<Path> list = new ArrayList<>();
        if (explicit != null) {
            list.add(explicit.toAbsolutePath().normalize());
        }
        list.add(cwd.resolve("config/config.yaml"));
        list.add(cwd.resolve("config.yaml"));
        list.add(Path.of(System.getProperty("user.home"), ".mkr", "config.yaml"));
        return list;
    }

    @SuppressWarnings("unchecked")
    static AppConfig fromMap(Map<String, Object> root) {
        AppConfig c = new AppConfig();
        Map<String, Object> llm = m(root, "llm");
        c.llm.provider = s(llm, "provider", c.llm.provider);
        c.llm.baseUrl = s(llm, "base-url", null);
        c.llm.model = s(llm, "model", null);
        c.llm.apiKey = s(llm, "api-key", "");
        c.llm.maxTokens = i(llm, "max-tokens", c.llm.maxTokens);
        c.llm.temperature = d(llm, "temperature", c.llm.temperature);
        c.llm.streaming = b(llm, "streaming", c.llm.streaming);
        c.llm.thinkingBudget = i(llm, "thinking-budget", 0);
        c.llm.timeoutMs = i(llm, "timeout-ms", c.llm.timeoutMs);
        c.llm.watchdogIdleMs = i(llm, "watchdog-idle-ms", c.llm.watchdogIdleMs);

        Map<String, Object> tools = m(root, "tools");
        Map<String, Object> ws = m(tools, "web-search");
        c.tools.webSearchProvider = s(ws, "provider", c.tools.webSearchProvider);
        c.tools.webSearchApiKey = s(ws, "api-key", "");
        c.tools.webSearchMaxResults = i(ws, "max-results", c.tools.webSearchMaxResults);
        Map<String, Object> wf = m(tools, "web-fetch");
        c.tools.webFetchProvider = s(wf, "provider", c.tools.webFetchProvider);
        Map<String, Object> sb = m(tools, "sandbox");
        c.tools.sandboxType = s(sb, "type", c.tools.sandboxType);
        c.tools.sandboxImage = s(sb, "image", c.tools.sandboxImage);
        c.tools.sandboxMemory = s(sb, "memory-limit", c.tools.sandboxMemory);
        c.tools.sandboxCpus = d(sb, "cpus", c.tools.sandboxCpus);
        c.tools.sandboxPidsLimit = i(sb, "pids-limit", c.tools.sandboxPidsLimit);
        c.tools.sandboxTimeoutMs = i(sb, "timeout-ms", c.tools.sandboxTimeoutMs);
        c.tools.networkAllowlist = l(sb, "network-allowlist");
        c.tools.pythonBin = s(tools, "python-bin", c.tools.pythonBin);
        c.tools.cloudSandboxUrl = s(tools, "cloud-sandbox-url", "");
        c.tools.cloudSandboxKey = s(tools, "cloud-sandbox-key", "");

        Map<String, Object> agent = m(root, "agent");
        c.agent.maxIterations = i(agent, "max-iterations", c.agent.maxIterations);
        c.agent.contextMaxTokens = i(agent, "context-max-tokens", c.agent.contextMaxTokens);
        c.agent.statusBar = b(agent, "status-bar", c.agent.statusBar);
        c.agent.compression = b(agent, "compression", c.agent.compression);
        c.agent.permissionMode = s(agent, "permission-mode", c.agent.permissionMode);
        c.agent.soulFile = s(agent, "soul-file", c.agent.soulFile);
        c.agent.profile = s(agent, "profile", c.agent.profile);
        c.agent.defaultMode = s(agent, "mode", c.agent.defaultMode);
        c.agent.consecutiveErrorMax = i(agent, "consecutive-error-max", c.agent.consecutiveErrorMax);
        c.agent.compressionFailMax = i(agent, "compression-fail-max", c.agent.compressionFailMax);
        c.agent.watchdogIdleMs = i(agent, "watchdog-idle-ms", c.agent.watchdogIdleMs);
        c.agent.toolOutputThreshold = i(agent, "tool-output-threshold", c.agent.toolOutputThreshold);

        Map<String, Object> perms = m(root, "permissions");
        c.permissions.allow = l(perms, "allow");
        c.permissions.ask = l(perms, "ask");
        c.permissions.deny = l(perms, "deny");
        c.permissions.protectedDirs = l(perms, "protected-dirs");
        Map<String, Object> wr = m(perms, "write-risk");
        c.permissions.riskRead = s(wr, "read", c.permissions.riskRead);
        c.permissions.riskCreate = s(wr, "create", c.permissions.riskCreate);
        c.permissions.riskOverwrite = s(wr, "overwrite", c.permissions.riskOverwrite);
        c.permissions.riskDelete = s(wr, "delete", c.permissions.riskDelete);

        Map<String, Object> mem = m(root, "memory");
        c.memory.storage = s(mem, "storage", c.memory.storage);
        c.memory.root = s(mem, "root", c.memory.root);
        c.memory.metadataDb = s(mem, "metadata-db", c.memory.metadataDb);
        c.memory.vectorStore = s(mem, "vector-store", c.memory.vectorStore);
        c.memory.hybridRetrieval = b(mem, "hybrid-retrieval", c.memory.hybridRetrieval);
        Map<String, Object> emb = m(mem, "embedding");
        c.memory.embeddingBaseUrl = s(emb, "base-url", "");
        c.memory.embeddingApiKey = s(emb, "api-key", "");
        c.memory.embeddingModel = s(emb, "model", c.memory.embeddingModel);

        Map<String, Object> ev = m(root, "eval");
        c.eval.judge = s(ev, "judge", c.eval.judge);
        c.eval.dataset = s(ev, "dataset", c.eval.dataset);

        Map<String, Object> obs = m(root, "obs");
        c.obs.otlpEndpoint = s(obs, "otlp-endpoint", "");
        Map<String, Object> prices = m(obs, "prices");
        Map<String, double[]> pm = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : prices.entrySet()) {
            if (e.getValue() instanceof List<?> list && list.size() >= 2) {
                pm.put(e.getKey(), new double[]{num(list.get(0)), num(list.get(1))});
            }
        }
        c.obs.prices.putAll(pm);
        c.obs.regressionDir = s(obs, "regression-dir", c.obs.regressionDir);

        Map<String, Object> ma = m(root, "multiagent");
        c.multiagent.enabled = b(ma, "enabled", c.multiagent.enabled);
        c.multiagent.manager = b(ma, "manager", c.multiagent.manager);
        c.multiagent.bus = s(ma, "bus", c.multiagent.bus);
        c.multiagent.redisUrl = s(ma, "redis-url", c.multiagent.redisUrl);

        Map<String, Object> mcp = m(root, "mcp");
        c.mcp.configFile = s(mcp, "config-file", c.mcp.configFile);
        c.mcp.autoStart = b(mcp, "auto-start", c.mcp.autoStart);

        Map<String, Object> features = m(root, "features");
        for (Map.Entry<String, Object> e : features.entrySet()) {
            if (e.getValue() instanceof Boolean bo) {
                c.features.put(e.getKey(), bo);
            }
        }
        c.applyProviderDefaults();
        return c;
    }

    /** provider 已知但 base-url/model 缺省时补默认（智谱 GLM / 豆包 / Ollama / vLLM / OpenRouter）。 */
    public void applyProviderDefaults() {
        String p = llm.provider == null ? "openai" : llm.provider;
        if (llm.baseUrl == null || llm.baseUrl.isBlank()) {
            llm.baseUrl = switch (p) {
                case "anthropic" -> "https://api.anthropic.com";
                case "zhipu" -> "https://open.bigmodel.cn/api/paas/v4";
                case "doubao" -> "https://ark.cn-beijing.volces.com/api/v3";
                case "ollama" -> "http://localhost:11434/v1";
                case "vllm" -> "http://localhost:8000/v1";
                case "openrouter" -> "https://openrouter.ai/api/v1";
                case "deepseek" -> "https://api.deepseek.com/v1";
                default -> "https://api.openai.com/v1";
            };
        }
        if (llm.model == null || llm.model.isBlank()) {
            llm.model = switch (p) {
                case "anthropic" -> "claude-sonnet-4-5";
                case "zhipu" -> "glm-4-plus";
                case "doubao" -> "doubao-seed-1-6";
                case "ollama" -> "qwen2.5:7b";
                default -> "gpt-4o";
            };
        }
    }

    // ---------------- 嵌套配置 ----------------

    public static final class Llm {
        public String provider = "openai";
        public String baseUrl = null;
        public String model = null;
        public String apiKey = "";
        public int maxTokens = 8192;
        public double temperature = 0;
        public boolean streaming = true;
        public int thinkingBudget = 0;
        public int timeoutMs = 120_000;
        public int watchdogIdleMs = 60_000;
    }

    public static final class Tools {
        public String webSearchProvider = "tavily";
        public String webSearchApiKey = "";
        public int webSearchMaxResults = 5;
        public String webFetchProvider = "local";
        public String sandboxType = "local";
        public String sandboxImage = "alpine:3.20";
        public String sandboxMemory = "2g";
        public double sandboxCpus = 1.0;
        public int sandboxPidsLimit = 256;
        public int sandboxTimeoutMs = 60_000;
        public List<String> networkAllowlist = new ArrayList<>();
        public String pythonBin = "python3";
        public String cloudSandboxUrl = "";
        public String cloudSandboxKey = "";
    }

    public static final class AgentCfg {
        public int maxIterations = 30;
        public int contextMaxTokens = 64_000;
        public boolean statusBar = true;
        public boolean compression = true;
        public String permissionMode = "default";
        public String soulFile = Path.of(System.getProperty("user.home"), ".mkr", "SOUL.md").toString();
        public String profile = "default";
        public String defaultMode = "react";
        public int consecutiveErrorMax = 5;
        public int compressionFailMax = 3;
        public int watchdogIdleMs = 60_000;
        public int toolOutputThreshold = 8_000; // 字符（约 2000 token）
    }

    public static final class Permissions {
        public List<String> allow = new ArrayList<>();
        public List<String> ask = new ArrayList<>();
        public List<String> deny = new ArrayList<>();
        public List<String> protectedDirs = new ArrayList<>();
        public String riskRead = "LOW";
        public String riskCreate = "MEDIUM";
        public String riskOverwrite = "HIGH";
        public String riskDelete = "HIGH";

        public boolean writeRiskDefaultOn() {
            return "HIGH".equalsIgnoreCase(riskOverwrite) && "HIGH".equalsIgnoreCase(riskDelete);
        }
    }

    public static final class Memory {
        public String storage = "filesystem";
        public String root = "workspace/memories";
        public String metadataDb = "none";
        public String vectorStore = "in-memory";
        public boolean hybridRetrieval = true;
        public String embeddingBaseUrl = "";
        public String embeddingApiKey = "";
        public String embeddingModel = "embedding-3";
    }

    public static final class Eval {
        public String judge = "llm-as-judge";
        public String dataset = "eval/tasks.json";
    }

    public static final class Obs {
        public String otlpEndpoint = "";
        public Map<String, double[]> prices = defaultPrices();
        public String regressionDir = "eval/regression";
    }

    public static final class Multiagent {
        public boolean enabled = true;
        public boolean manager = false;
        public String bus = "memory";
        public String redisUrl = "redis://localhost:6379";
    }

    public static final class McpCfg {
        public String configFile = "config/mcp.yml";
        public boolean autoStart = false;
    }

    public static Map<String, Boolean> defaultFeatures() {
        Map<String, Boolean> f = new LinkedHashMap<>();
        f.put("status-bar", true);
        f.put("compression", true);
        f.put("sidecar", true);
        f.put("progressive-tools", false); // spec 默认：>7 工具仅暴露 read_tool_defs+索引；此处默认关闭以保可用性
        f.put("otel", true);
        f.put("memory-hybrid", true);
        f.put("subagents", true);
        return f;
    }

    private static Map<String, double[]> defaultPrices() {
        // [输入, 输出] USD / 1M tokens（可被 config 覆盖）
        Map<String, double[]> p = new LinkedHashMap<>();
        p.put("gpt-4o", new double[]{2.5, 10});
        p.put("gpt-4o-mini", new double[]{0.15, 0.6});
        p.put("gpt-4.1", new double[]{2, 8});
        p.put("claude-sonnet-4-5", new double[]{3, 15});
        p.put("claude-haiku-4-5", new double[]{0.8, 4});
        p.put("glm-4-plus", new double[]{0.7, 0.7});
        p.put("glm-4-flash", new double[]{0, 0});
        p.put("doubao-seed-1-6", new double[]{0.11, 0.28});
        p.put("deepseek-chat", new double[]{0.14, 0.28});
        return p;
    }

    // ---------------- YAML 取值辅助 ----------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Map<String, Object> root, String key) {
        Object v = root.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static List<String> l(Map<String, Object> root, String key) {
        Object v = root.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        } else if (v instanceof String s) {
            out.add(s);
        }
        return out;
    }

    private static String s(Map<String, Object> root, String key, String dft) {
        Object v = root.get(key);
        return v == null ? dft : String.valueOf(v);
    }

    private static int i(Map<String, Object> root, String key, int dft) {
        Object v = root.get(key);
        return v instanceof Number n ? n.intValue() : dft;
    }

    private static double d(Map<String, Object> root, String key, double dft) {
        Object v = root.get(key);
        return v instanceof Number n ? n.doubleValue() : dft;
    }

    private static boolean b(Map<String, Object> root, String key, boolean dft) {
        Object v = root.get(key);
        return v instanceof Boolean bo ? bo : dft;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    // ---------------- 环境变量 / .env ----------------

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");

    /** .env（项目根 → ~/.mkr/.env）+ 系统环境变量，系统环境优先。 */
    public static Map<String, String> dotEnv(Path cwd) {
        Map<String, String> env = new LinkedHashMap<>();
        for (Path p : List.of(cwd.resolve(".env"),
                Path.of(System.getProperty("user.home"), ".mkr", ".env"))) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(p)) {
                    String t = line.trim();
                    if (t.isEmpty() || t.startsWith("#")) {
                        continue;
                    }
                    t = t.startsWith("export ") ? t.substring(7) : t;
                    int eq = t.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    String k = t.substring(0, eq).trim();
                    String v = t.substring(eq + 1).trim();
                    if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"")
                            || v.startsWith("'") && v.endsWith("'"))) {
                        v = v.substring(1, v.length() - 1);
                    }
                    env.putIfAbsent(k, v);
                }
            } catch (IOException ignored) {
                // .env 可选
            }
        }
        System.getenv().forEach(env::putIfAbsent);
        return env;
    }

    static String substituteEnv(String raw, Map<String, String> env) {
        Matcher matcher = ENV_PATTERN.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String var = matcher.group(1);
            String dft = matcher.group(2);
            String val = env.get(var);
            if (val == null || val.isBlank()) {
                val = dft == null ? "" : dft;
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(val));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
