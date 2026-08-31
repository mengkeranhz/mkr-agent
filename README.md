# mkr-agent · Agent Harness

生产级通用 Agent Harness，Java 21 实现。终端 CLI 交互（类 Claude Code 体验），单 jar 部署，默认无需 Docker 或外部数据库。

> 命名：产品名 **mkr-agent**，命令行 **`mkr`**（命令名 = 产品名小写，业界惯例同 claude / codex / gemini）。mkr 取自作者姓名缩写。

## 它是什么

一个通用的 Agent 运行时外壳（Harness），围绕 LLM 提供「上下文管理 + 工具系统 + 护栏 + 验证 + 恢复」五层能力，让 Agent 在生产环境稳定、安全、可观测地运行。

**核心能力**
- 四种思考模式：ReAct / Plan-and-Solve / Reflection / FunctionCall
- 多模型接入：OpenAI、Anthropic、智谱 GLM、豆包、Ollama、vLLM（OpenAI 兼容协议为主）
- **SOUL.md 人格文件**：agent 身份/价值观/沟通风格定义，跨项目持久，system prompt 最前置层
- 内置工具：WebSearch、WebFetch、Bash、Read/Write/Edit/Grep/Glob/LS、Calculator、Memory、RAG、SubAgent、MCP、Soul 等
- 安全护栏：权限模式（default / accept-edits / plan / auto-approve）、路径级文件访问控制（allow/ask/deny + 受保护目录）、可插拔沙箱（本机 / Docker / bwrap / 云）
- 记忆系统：默认文件系统 md + 混合检索，向量存储可插拔
- 自进化：Agent 可自主创建/修改 skills 与记忆
- 可观测：OpenTelemetry Trace、成本追踪、评估集回归

## 5 分钟快速了解

### 架构（四层）

```
┌─────────────────────────────────────────────┐
│  接入层  CLI（picocli+JLine）/ REPL / 单次执行  │
├─────────────────────────────────────────────┤
│  编排层  AgentLoop（ReAct / Plan / Reflect / Func）│
│           多 Agent（Manager/Worker、消息总线）       │
├─────────────────────────────────────────────┤
│  Harness 核心层                                │
│  上下文引擎 │ 工具系统 │ 护栏 │ 验证器 │ 恢复    │
├─────────────────────────────────────────────┤
│  基础设施  LLM Provider │ 记忆/RAG │ 沙箱 │ OTel │
└─────────────────────────────────────────────┘
```

核心公式：`Agent = LLM + [上下文 + 工具 + 约束 + 验证 + 纠正] = Model + Harness`。最小可用 = LLM + 上下文 + 工具；生产形态再补全约束/验证/纠正三层保障。

### 能力（一句话一项）

- **思考模式**：ReAct（推理-行动-观察循环）、Plan-and-Solve（先计划后逐步执行）、Reflection（自我批评修正）、FunctionCall（原生工具调用）
- **模型接入**：OpenAI / Anthropic / 智谱 GLM / 豆包 / Ollama / vLLM（OpenAI 兼容协议为主，Anthropic 原生协议为辅）
- **内置工具**：WebSearch、WebFetch、Bash、Read/Write/Edit/Grep/Glob/LS、Calculator、Memory、RAG、SubAgent、MCP 适配
- **上下文管理**：KV Cache 友好布局（静态前缀不动、动态追加末尾）、Agent 状态栏（代码维护）、分层压缩、Skills 渐进式披露、**SOUL.md 人格文件**（agent 身份/价值观/沟通风格，system prompt 最前置层）
- **记忆系统**：默认文件系统 md + 混合检索（全文+向量 RRF 融合），向量存储可插拔（内存/pgvector）
- **安全护栏**：权限模式（default/accept-edits/plan/auto-approve）、路径级文件访问控制（allow/ask/deny + 受保护目录）、可插拔沙箱（本机/Docker/bwrap/云）、Sidecar 独立审查
- **错误恢复**：四层故障分类（API/工具/上下文/控制流）、指数退避重试、熔断、看门狗、轨迹修复
- **多 Agent**：spawn_subagent 创建子 Agent、Manager/Worker 模式、结构化摘要回传、消息总线（内存/Redis）
- **自进化**：Agent 可自主创建/修改/删除 skills 与记忆（经审批+可回滚）
- **可观测**：OpenTelemetry Trace/Span、成本追踪、评估集回归、特性开关（消融）

### 执行流程（单轮循环）

1. **状态栏注入**：代码维护 TODO/工具计数/时间/环境状态，注入上下文末尾
2. **上下文预算检查**：超预算则分层压缩（工具输出写盘→噪声删除→归档摘要→LLM 全量压缩）
3. **LLM 流式调用**：按思考模式推理，输出 tool_call 或 final_answer
4. **护栏检查**：风险评级 + 权限模式 + 路径规则判定；HIGH/ask 命中则暂停等待人工审批（显示 diff 预览）
5. **沙箱执行**：本机/Docker/bwrap/云，限网+限额+超时
6. **验证/恢复**：编译/测试验证；失败则结构化错误回灌模型自纠；连续失败触发熔断升级
7. **结果入轨迹**：成功则工具结果回灌，进入下一轮循环；final_answer 经 CompletionJudge 独立判定后结束

### 约束（安全底线）

- **文件访问**：deny 永远最高优先；`~/.ssh`、`~/.aws`、`.env`、`/etc` 等受保护目录默认拒绝；覆盖/删除操作需授权；`..`/symlink 越权被拦截
- **权限模式**：默认 HIGH 需审批；plan 模式只读；auto-approve 仅限隔离环境显式开启
- **沙箱**：默认本机执行+危险命令检测；Docker 模式默认断外网
- **完成判定**：模型不能自我批准"完成"，由独立 CompletionJudge 判定
- **自进化**：skill/记忆/SOUL.md 写入经信任审查+审批，可 git diff 审计、可回滚

### 选型（为什么是这些）

| 维度 | 选型 | 理由 |
|---|---|---|
| 语言 | JDK 21 LTS | 虚拟线程（高并发 I/O）、生态成熟、生产稳定 |
| CLI | picocli + JLine | 命令解析+交互式 REPL，类 Claude Code 体验 |
| LLM | 自研 LlmClient（OpenAI 兼容为主） | 精细控制工具调用/流式/thinking/KV Cache 前缀 |
| 沙箱 | 可插拔（本机默认 / Docker 可选） | 默认零依赖，需要强隔离时切 Docker |
| 记忆 | 文件 md + 混合检索 | 人类可读、可 git、可审计；向量为备选 |
| 可观测 | OpenTelemetry | 标准协议，复用现有监控体系 |
| 构建 | Maven + shade 单 jar | 简单、可安装到系统路径 |

## 快速开始

```bash
# 环境：JDK 21+、Maven 3.9+
mvn -q clean package -DskipTests

# 初始化配置（生成 config/config.yaml + .env + permissions 模板）
java -jar target/mkr.jar init

# 安装到系统路径（可选）
cp target/mkr.jar /usr/local/lib/
printf '#!/bin/sh\nexec java -jar /usr/local/lib/mkr.jar "$@"\n' > /usr/local/bin/mkr
chmod +x /usr/local/bin/mkr
```

### 常用命令

```bash
mkr                                 # 进入交互式 REPL
mkr run "搜索并总结 Java 27 的新特性"    # 单次执行，流式输出
mkr run --mode plan "设计一个订单系统"
mkr eval --set eval/tasks.json        # 跑评估集
mkr skills install ./my-skill         # 安装 skill
mkr mcp add github -- npx -y @modelcontextprotocol/server-github
mkr cost / mkr tools list / mkr model list
```

### REPL 内命令

| 命令 | 作用 |
|---|---|
| 直接输入 | 作为任务执行 |
| `/model <name>` | 切换模型 |
| `/mode <react\|plan\|reflect\|func>` | 切换思考模式 |
| `/permissions` | 切换权限模式 / 查看规则 |
| `/tools` `/skills` `/status` `/plan` | 查看工具 / skills / 状态栏 / 计划 |
| `/approve` `/deny` | 审批响应（高危工具 / 文件写入） |
| `/cost` `/reset` `/exit` | 费用 / 清空会话 / 退出 |

## 技术栈

JDK 21（虚拟线程）· Maven · picocli + JLine（CLI）· Jackson · jsoup · OpenTelemetry · docker-java / Playwright / mcp-java-sdk（可选）· SQLite / pgvector（可选）

## 文档与配置（三层指令文件体系，对齐业界标准）

| 层 | 文件 | 描述什么 | 位置 | 读者/加载方式 |
|---|---|---|---|---|
| 身份层 | `SOUL.md` | agent 是谁（人格/价值观/沟通风格/边界） | `~/.mkr/SOUL.md`（全局），项目根可覆盖 | 运行时加载为 system prompt 最前置层 |
| 项目层 | `AGENTS.md` | 在这个项目里怎么干活（构建/测试/代码规范/边界） | 项目根（cwd），子目录覆盖父目录 | 运行时加载为 system prompt 中间层；本仓库的 AGENTS.md 同时是 mkr-agent 自身的实现规格（给编码 Agent 读） |
| 配置层 | `config.yaml` | 技术参数（模型/api-key/沙箱/工具/记忆/评估） | `config/config.yaml`（或项目根 `config.yaml`） | 运行时读取，不进 prompt |
| 概览层 | `README.md` | 项目概览、快速开始（本文件） | 项目根 | 人类阅读 |

> 业界标准分工：`SOUL.md` = agent 身份（社区规范 RFC-1，Hermes/OpenClaw 生产实践）；`AGENTS.md` = 项目指令（Linux 基金会 AAIF 治理的开放标准）；`config.yaml` = 技术配置（Hermes 等框架通用）。

## 目录结构

```
mkr/
├── pom.xml
├── AGENTS.md              # 实现规格（给编码 Agent 读）
├── SOUL.md                # 人格/身份文件（项目级，覆盖全局 ~/.mkr/SOUL.md）
├── config/                # config.yaml / permissions.yml / .env
├── skills/                # skills（含 Agent 自进化创建）
├── workspace/
│   ├── memories/          # 记忆（md 文件）
│   └── .agents/           # 子 Agent 工作区
├── eval/tasks.json
└── src/main/java/com/mkr/
    ├── cli/  api/  core/  context/  tools/  mcp/
    ├── guard/  verify/  recovery/  event/  memory/
    ├── multiagent/  obs/  config/
    └── Main.java
```
