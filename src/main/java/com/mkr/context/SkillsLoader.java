package com.mkr.context;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Skills 加载器（渐进式披露三层）：
 * ① front-matter 元数据（name/description + Use when / Don't use when）常驻 system prompt；
 * ② read_skill(name) 读正文；③ read_skill(name, file) 深读子文档。
 *
 * <p>目录：项目 skills/（优先）+ ~/.mkr/skills（全局）；同名以项目级为准。
 * 自进化写入只允许项目 skills/（路径白名单），update 前自动备份 .bak 可回滚，
 * delete 移入 skills/.trash/ 可恢复。</p>
 */
public final class SkillsLoader {

    public static final String SKILL_FILE = "SKILL.md";

    public record SkillMeta(String name, String description, String whenToUse, String dontUseWhen, Path dir) {
    }

    private final Path projectDir;
    private final Path globalDir;

    public SkillsLoader(Path projectDir) {
        this.projectDir = projectDir;
        this.globalDir = Path.of(System.getProperty("user.home"), ".mkr", "skills");
    }

    public Path projectDir() {
        return projectDir;
    }

    public List<SkillMeta> list() {
        Map<String, SkillMeta> byName = new LinkedHashMap<>();
        scan(globalDir).forEach(m -> byName.put(m.name(), m));
        scan(projectDir).forEach(m -> byName.put(m.name(), m)); // 项目级覆盖全局
        return byName.values().stream()
                .sorted(Comparator.comparing(SkillMeta::name))
                .toList();
    }

    private List<SkillMeta> scan(Path dir) {
        List<SkillMeta> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isDirectory)
                    .filter(d -> Files.isRegularFile(d.resolve(SKILL_FILE)))
                    .forEach(d -> metaOf(d.getFileName().toString(), d).ifPresent(out::add));
        } catch (IOException ignored) {
        }
        return out;
    }

    private Optional<SkillMeta> metaOf(String name, Path dir) {
        try {
            String md = Files.readString(dir.resolve(SKILL_FILE), StandardCharsets.UTF_8);
            Map<String, Object> fm = parseFrontMatter(md);
            String desc = str(fm.get("description"), "");
            String when = str(fm.get("when-to-use"), str(fm.get("use_when"), ""));
            String dont = str(fm.get("dont-use-when"), str(fm.get("dont_use_when"), ""));
            return Optional.of(new SkillMeta(name, desc, when, dont, dir));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<SkillMeta> find(String name) {
        return list().stream().filter(m -> m.name().equals(name)).findFirst();
    }

    /** SKILL.md 正文（剥离 front-matter）。 */
    public String readBody(String name) throws IOException {
        Path dir = resolveDir(name);
        return stripFrontMatter(Files.readString(dir.resolve(SKILL_FILE), StandardCharsets.UTF_8));
    }

    /** 深读 skill 子文档（路径限制在 skill 目录内）。 */
    public String readFile(String name, String relPath) throws IOException {
        Path dir = resolveDir(name);
        Path target = dir.resolve(relPath).normalize();
        if (!target.startsWith(dir)) {
            throw new IOException("子文档路径越界: " + relPath);
        }
        if (!Files.isRegularFile(target)) {
            throw new IOException("子文档不存在: " + relPath);
        }
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    /** 自进化：创建 skill（仅项目 skills/ 白名单）。 */
    public SkillMeta create(String name, String content) throws IOException {
        if (!name.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IOException("skill 名只允许小写字母/数字/-/_（1-64 位）: " + name);
        }
        Path dir = projectDir.resolve(name);
        if (Files.exists(dir.resolve(SKILL_FILE))) {
            throw new IOException("skill 已存在: " + name + "（更新请用 update_skill）");
        }
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SKILL_FILE), content, StandardCharsets.UTF_8);
        return metaOf(name, dir).orElseThrow();
    }

    /** 自进化：更新 skill（旧版备份 SKILL.md.bak 可回滚）。 */
    public SkillMeta update(String name, String content) throws IOException {
        Path dir = resolveDir(name);
        Path file = dir.resolve(SKILL_FILE);
        Files.copy(file, dir.resolve(SKILL_FILE + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return metaOf(name, dir).orElseThrow();
    }

    /** 自进化：删除 skill（移入 .trash 可恢复）。 */
    public Path delete(String name) throws IOException {
        Optional<SkillMeta> meta = find(name);
        if (meta.isEmpty()) {
            throw new IOException("skill 不存在: " + name);
        }
        if (!meta.get().dir().startsWith(projectDir)) {
            throw new IOException("全局 skill 不允许由 Agent 删除: " + name);
        }
        Path trash = projectDir.resolve(".trash");
        Files.createDirectories(trash);
        Path target = trash.resolve(name + "-" + System.currentTimeMillis());
        Files.move(meta.get().dir(), target);
        return target;
    }

    private Path resolveDir(String name) throws IOException {
        Optional<SkillMeta> meta = find(name);
        if (meta.isEmpty()) {
            throw new IOException("skill 不存在: " + name + "（list_skills 可查看）");
        }
        return meta.get().dir();
    }

    /** ① 常驻 system 的索引（front-matter 层）。 */
    public String indexMarkdown() {
        List<SkillMeta> all = list();
        if (all.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## 可用 Skills（用 read_skill 读正文）\n");
        for (SkillMeta m : all) {
            sb.append("- ").append(m.name()).append(": ").append(m.description());
            if (!m.whenToUse().isBlank()) {
                sb.append(" Use when: ").append(m.whenToUse());
            }
            if (!m.dontUseWhen().isBlank()) {
                sb.append("; Don't use when: ").append(m.dontUseWhen());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String stripFrontMatter(String md) {
        if (md == null) {
            return "";
        }
        if (md.startsWith("---")) {
            int end = md.indexOf("\n---", 3);
            if (end > 0) {
                int after = md.indexOf('\n', end + 4);
                return after > 0 && after < md.length() ? md.substring(after + 1) : "";
            }
        }
        return md;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseFrontMatter(String md) {
        if (md == null || !md.startsWith("---")) {
            return Map.of();
        }
        int end = md.indexOf("\n---", 3);
        if (end <= 0) {
            return Map.of();
        }
        String yaml = md.substring(3, end);
        try {
            Map<String, Object> m = new Yaml().load(yaml);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object o, String dft) {
        return o == null ? dft : String.valueOf(o);
    }
}
