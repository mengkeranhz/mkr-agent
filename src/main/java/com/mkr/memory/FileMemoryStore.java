package com.mkr.memory;

import com.mkr.guard.InjectionSanitizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 文件系统记忆：workspace/memories/&lt;scope&gt;.md，段落式追加（## id | 时间 | 元数据）。
 * 写入经 InjectionSanitizer 信任审查 + 长度上限；同文件读写加锁（多 Agent 并发安全）。
 */
public final class FileMemoryStore implements MemoryStore {

    /** section 头：`## id | 时间 | 元数据`（禁止跨行，防贪婪吞掉后续 section）。 */
    private static final Pattern SECTION =
            Pattern.compile("^##[ \\t]+(\\S+)[ \\t]*\\|([^|\\n]*)\\|?(.*)$", Pattern.MULTILINE);
    private static final String SCOPE_SAFE = "[a-z0-9][a-z0-9_-]{0,63}";

    private final Path root;
    private final InjectionSanitizer sanitizer;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    public FileMemoryStore(Path root, InjectionSanitizer sanitizer) {
        this.root = root;
        this.sanitizer = sanitizer;
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public String save(String scope, String text, Map<String, Object> metadata) {
        String safeScope = (scope == null || !scope.matches(SCOPE_SAFE)) ? "general" : scope;
        String cleaned = sanitizer.sanitizeMemory(text == null ? "" : text);
        String id = "mem-" + UUID.randomUUID().toString().substring(0, 8);
        lockFor(safeScope).lock();
        try {
            Files.createDirectories(root);
            Path file = root.resolve(safeScope + ".md");
            String tags = metadata == null || metadata.isEmpty() ? "" : " | " + metadata;
            String section = "\n## " + id + " | " + LocalDateTime.now() + tags + "\n" + cleaned + "\n";
            Files.writeString(file, Files.exists(file) ? Files.readString(file) + section : ("# 记忆 · " + safeScope + "\n" + section),
                    StandardCharsets.UTF_8);
            return id;
        } catch (IOException e) {
            throw new IllegalStateException("记忆写入失败: " + e.getMessage(), e);
        } finally {
            lockFor(safeScope).unlock();
        }
    }

    @Override
    public List<MemoryEntry> list() {
        List<MemoryEntry> out = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (Stream<Path> s = Files.list(root)) {
            s.filter(p -> p.getFileName().toString().endsWith(".md")).forEach(p -> out.addAll(parse(p)));
        } catch (IOException ignored) {
        }
        return out;
    }

    @Override
    public Optional<MemoryEntry> get(String id) {
        return list().stream().filter(e -> e.id().equals(id)).findFirst();
    }

    @Override
    public boolean delete(String id) {
        lock.lock();
        try {
            for (Path p : memoryFiles()) {
                List<MemoryEntry> entries = parse(p);
                boolean changed = false;
                StringBuilder sb = new StringBuilder();
                for (MemoryEntry e : entries) {
                    if (e.id().equals(id)) {
                        changed = true;
                        continue;
                    }
                    sb.append(sectionText(e));
                }
                if (changed) {
                    Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            lock.unlock();
        }
    }

    private List<Path> memoryFiles() {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".md")).toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private List<MemoryEntry> parse(Path file) {
        List<MemoryEntry> out = new ArrayList<>();
        try {
            String md = Files.readString(file, StandardCharsets.UTF_8);
            String scope = file.getFileName().toString().replaceAll("\\.md$", "");
            Matcher m = SECTION.matcher(md);
            List<int[]> marks = new ArrayList<>();
            while (m.find()) {
                marks.add(new int[]{m.start(), m.end()});
            }
            for (int i = 0; i < marks.size(); i++) {
                int bodyStart = marks.get(i)[1];
                int bodyEnd = i + 1 < marks.size() ? marks.get(i + 1)[0] : md.length();
                String header = md.substring(marks.get(i)[0], marks.get(i)[1]);
                String id = header.replaceFirst("^##\\s+", "").split("\\|")[0].trim();
                String body = md.substring(bodyStart, bodyEnd).trim();
                Map<String, Object> meta = new LinkedHashMap<>();
                out.add(new MemoryEntry(id, scope, file, body, meta));
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static String sectionText(MemoryEntry e) {
        return "\n## " + e.id() + " | " + LocalDateTime.now() + "\n" + e.text() + "\n";
    }

    private ReentrantLock lockFor(String scope) {
        return fileLocks.computeIfAbsent(scope, k -> new ReentrantLock());
    }
}
