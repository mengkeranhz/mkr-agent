package com.mkr.context;

import com.mkr.api.LlmClient;
import com.mkr.api.LlmResponse;
import com.mkr.api.Message;
import com.mkr.guard.InjectionSanitizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 分层压缩（由便宜到昂贵）：
 * ① 工具输出预算控制（超阈值写盘 workspace/.artifacts/&lt;id&gt;.out，轨迹留摘要行）
 * → ② 噪声删除（旧 ephemeral 状态栏、旧工具输出折叠为 pass/fail 一行）
 * → ③ 归档式摘要（原文追加 workspace/.archive.log，轨迹替换为规则摘要）
 * → ④ LLM 全量压缩（连续失败 compressionFailMax 次抛 CompressionException 升级人工）。
 *
 * <p>保留优先级：架构决策/关键约束 ＞ 变更记录/验证状态/未解决 TODO ＞ 工具输出（只留 pass/fail）；
 * UUID/hash/URL/文件名原样保留（tier④ 提示词要求）。永不触碰 [0, prefixEnd) 前缀。</p>
 */
public final class Compressor {

    /** 压缩 3 次失败 → 升级人工（L3）。 */
    public static class CompressionException extends RuntimeException {
        public CompressionException(String message) {
            super(message);
        }
    }

    private static final int KEEP_FULL_EXCHANGES = 4;   // 保留完整输出的最近工具交换数
    private static final int KEEP_TAIL_MESSAGES = 8;    // tier③/④ 保留的尾部消息数
    private static final int ARCHIVE_TRANSCRIPT_CAP = 120_000;

    private final ContextConfig cfg;
    private final Path artifactsDir;
    private final Path archiveLog;
    private final LlmClient llm;
    private final String model;
    private int llmFailures = 0;

    public Compressor(ContextConfig cfg, Path workspaceRoot, LlmClient llm, String model) {
        this.cfg = cfg;
        this.artifactsDir = workspaceRoot.resolve(".artifacts");
        this.archiveLog = workspaceRoot.resolve(".archive.log");
        this.llm = llm;
        this.model = model;
    }

    /** ① 工具输出上限：超阈值写盘，返回摘要行（含 artifact 路径、首行预览与来源 URL 保底）。 */
    public String capToolOutput(String toolName, String output) {
        if (output == null || output.length() <= cfg.toolOutputThreshold()) {
            return output == null ? "" : output;
        }
        String sources = InjectionSanitizer.retainedSources(output); // 引用链保底：预览外的 source=URL 也不丢
        try {
            Files.createDirectories(artifactsDir);
            String id = toolName.replaceAll("[^a-zA-Z0-9_-]", "_") + "-" + UUID.randomUUID().toString().substring(0, 8);
            Path file = artifactsDir.resolve(id + ".out");
            Files.writeString(file, output, StandardCharsets.UTF_8);
            return "[%s 输出 %d 字符超过预算，已写盘 %s，可用 read_file 查看全文]\n%s%s"
                    .formatted(toolName, output.length(), file, preview(output, 600),
                            sources.isEmpty() ? "" : "\n" + sources);
        } catch (IOException e) {
            // 写盘失败降级为硬截断
            return "[" + toolName + " 输出超预算且写盘失败，硬截断]\n" + preview(output, cfg.toolOutputThreshold())
                    + (sources.isEmpty() ? "" : "\n" + sources);
        }
    }

    private static String preview(String s, int chars) {
        String head = s.substring(0, Math.min(chars, s.length()));
        if (s.length() > chars) {
            head += "\n…（截断预览，全文见 artifact）";
        }
        return head;
    }

    /** 预算检查：超 contextMaxTokens 则依次执行 ②③④。返回是否执行过压缩。 */
    public boolean compressIfNeeded(MessageList messages) {
        if (!cfg.compression() || messages.estimateTokens() <= cfg.contextMaxTokens()) {
            return false;
        }
        List<Message> suffix = messages.suffix();
        List<Message> next = new ArrayList<>(suffix);

        tier2NoiseDeletion(next);
        if (estimate(next) <= cfg.compressTarget()) {
            messages.rewriteSuffix(next);
            return true;
        }
        tier3Archive(messages, next);
        if (estimate(next) <= cfg.compressTarget()) {
            messages.rewriteSuffix(next);
            return true;
        }
        tier4LlmCompress(next);
        messages.rewriteSuffix(next);
        return true;
    }

    private static int estimate(List<Message> msgs) {
        int t = 32;
        for (Message m : msgs) {
            t += m.estimateTokens();
        }
        return t;
    }

    /** ② 噪声删除：旧 ephemeral 全删（最新一条保留）；旧工具输出折叠为一行 pass/fail。 */
    private void tier2NoiseDeletion(List<Message> suffix) {
        int lastEphemeralIdx = -1;
        for (int i = suffix.size() - 1; i >= 0; i--) {
            if (suffix.get(i).ephemeral()) {
                lastEphemeralIdx = i;
                break;
            }
        }
        final int keepIdx = lastEphemeralIdx;
        List<Message> kept = new ArrayList<>();
        for (int i = 0; i < suffix.size(); i++) {
            Message m = suffix.get(i);
            if (!m.ephemeral() || i == keepIdx) {
                kept.add(m);
            }
        }
        suffix.clear();
        suffix.addAll(kept);

        // 最近 KEEP_FULL_EXCHANGES 次 TOOL 消息之外折叠
        int toolSeen = 0;
        for (int i = suffix.size() - 1; i >= 0; i--) {
            Message m = suffix.get(i);
            if (m.role() != Message.Role.TOOL) {
                continue;
            }
            toolSeen++;
            if (toolSeen > KEEP_FULL_EXCHANGES) {
                String folded = fold(m);
                suffix.set(i, Message.toolResult(m.toolCallId(), m.toolName(), folded));
            }
        }
    }

    private static String fold(Message m) {
        String c = m.content();
        boolean failed = c.startsWith("[ERROR") || c.startsWith("[DENIED");
        String head = c.replace("\n", " ").trim();
        if (head.length() > 100) {
            head = head.substring(0, 100) + "…";
        }
        // 折叠不丢引用链：保留全部 source="URL"（供最终答复标注来源）
        String sources = InjectionSanitizer.retainedSources(c);
        return "[tool " + m.toolName() + " " + (failed ? "fail" : "ok") + "] " + head
                + (sources.isEmpty() ? "" : " " + sources);
    }

    /** ③ 归档：中段原文追加 .archive.log，轨迹内替换为规则摘要消息。 */
    private void tier3Archive(MessageList messages, List<Message> suffix) {
        int keep = Math.min(KEEP_TAIL_MESSAGES, suffix.size());
        int boundary = suffix.size() - keep;
        if (boundary <= 0) {
            return;
        }
        List<Message> archived = new ArrayList<>(suffix.subList(0, boundary));
        try {
            Files.writeString(archiveLog, "\n===== " + LocalDateTime.now() + " 归档 " + archived.size() + " 条消息 =====\n"
                            + transcript(archived, ARCHIVE_TRANSCRIPT_CAP) + "\n",
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 归档失败不阻塞内存压缩
        }
        List<Message> kept = new ArrayList<>();
        kept.add(Message.assistant(archiveSummary(archived)));
        kept.addAll(suffix.subList(boundary, suffix.size()));
        suffix.clear();
        suffix.addAll(kept);
        // 归档动过前缀之外的消息，压缩后重算成对性：交由 TrajectoryRepairer 在下一轮修复
    }

    private String archiveSummary(List<Message> archived) {
        List<String> decisions = new ArrayList<>();
        for (Message m : archived) {
            if (m.role() == Message.Role.USER && !m.ephemeral()) {
                decisions.add("任务: " + firstLine(m.content(), 120));
            } else if (m.role() == Message.Role.ASSISTANT && !m.hasToolCalls() && !m.content().isBlank()) {
                decisions.add("结论: " + firstLine(m.content(), 120));
            }
        }
        return "[已归档 " + archived.size() + " 条历史消息（原文见 workspace/.archive.log）]\n"
                + String.join("\n", decisions.stream().limit(10).toList());
    }

    /** ④ LLM 全量压缩：中段请求 LLM 生成摘要；失败计数，超阈值抛 CompressionException。 */
    private void tier4LlmCompress(List<Message> suffix) {
        int keep = Math.min(KEEP_TAIL_MESSAGES, suffix.size());
        int boundary = suffix.size() - keep;
        if (boundary <= 0 || llm == null) {
            if (llm == null && boundary > 0) {
                // 无 LLM 可用时降级为 tier3 规则摘要
                List<Message> archived = new ArrayList<>(suffix.subList(0, boundary));
                List<Message> kept = new ArrayList<>();
                kept.add(Message.assistant(archiveSummary(archived)));
                kept.addAll(suffix.subList(boundary, suffix.size()));
                suffix.clear();
                suffix.addAll(kept);
            }
            return;
        }
        String transcriptText = transcript(suffix.subList(0, boundary), 60_000);
        String prompt = """
                压缩以下 Agent 执行轨迹。要求：
                1. 保留：任务目标、架构决策、关键约束、变更记录、验证状态、未解决 TODO；
                2. 丢弃：工具输出细节（只留 pass/fail 与结论）；
                3. UUID/hash/URL/文件名/命令原样保留，不得改写；<external> 的 source URL 必须逐个保留；
                4. 输出不超过 400 字。

                轨迹:
                %s""".formatted(transcriptText);
        try {
            LlmClient.ChatOptions opts = LlmClient.ChatOptions.of(model, Math.min(2048, cfg.contextMaxTokens() / 8), 0, false);
            LlmResponse resp = llm.chat(List.of(Message.user(prompt)), List.of(), opts);
            String summary = resp.content().trim();
            if (summary.isBlank()) {
                throw new IllegalStateException("空摘要");
            }
            List<Message> kept = new ArrayList<>();
            kept.add(Message.assistant("[LLM 压缩摘要] " + summary));
            kept.addAll(suffix.subList(boundary, suffix.size()));
            suffix.clear();
            suffix.addAll(kept);
        } catch (RuntimeException e) {
            llmFailures++;
            if (llmFailures >= cfg.compressionFailMax()) {
                throw new CompressionException("LLM 压缩连续失败 " + llmFailures + " 次（"
                        + cfg.compressionFailMax() + " 次上限），升级人工处理");
            }
        }
    }

    private static String transcript(List<Message> msgs, int cap) {
        StringBuilder sb = new StringBuilder();
        for (Message m : msgs) {
            sb.append('[').append(m.role()).append(']').append(' ').append(m.content()).append('\n');
        }
        return sb.length() > cap ? sb.substring(0, cap) + "…[截断]" : sb.toString();
    }

    private static String firstLine(String s, int max) {
        String line = s.strip().split("\n", 2)[0];
        return line.length() > max ? line.substring(0, max) + "…" : line;
    }
}
