package com.mkr.guard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 受保护目录/文件（默认 deny）：~/.ssh、~/.aws、~/.gnupg、.env、*.pem（任意深度）、
 * id_rsa（任意深度）、/etc、/usr、/bin、/sbin、/var、/System、/Library（macOS）。
 * 宽松/auto-approve 模式下写入仍强制授权（由 FileAccessGuard 升级为 ASK）。
 */
public final class ProtectedDirs {

    public static final List<String> DEFAULTS = List.of(
            "~/.ssh", "~/.aws", "~/.gnupg",
            ".env", "**/*.pem", "**/id_rsa",
            "/etc", "/usr", "/bin", "/sbin", "/var",
            "/System", "/Library");

    private final List<Path> dirPrefixes = new ArrayList<>();
    private final List<Pattern> patterns = new ArrayList<>();
    private final List<String> raw;

    public ProtectedDirs(List<String> rules) {
        this.raw = (rules == null || rules.isEmpty()) ? DEFAULTS : rules;
        Path home = Path.of(System.getProperty("user.home"));
        Path cwd = Path.of(System.getProperty("user.dir"));
        for (String r : raw) {
            String t = r.trim();
            if (t.equals("~") || t.startsWith("~/")) {
                dirPrefixes.add(home.resolve(t.substring(2)));
            } else if (t.startsWith("/") && !t.contains("*") && !t.contains("?")) {
                dirPrefixes.add(Path.of(t));
            } else {
                patterns.add(PathPolicy.compile(t, home, cwd));
            }
        }
    }

    /** 命中任一受保护规则（真实路径与原始路径都检查，防 symlink 绕过）。 */
    public boolean isProtected(Path realPath, Path givenPath) {
        for (Path p : new Path[]{realPath, givenPath}) {
            if (p == null) {
                continue;
            }
            for (Path prefix : dirPrefixes) {
                if (p.startsWith(prefix)) {
                    return true;
                }
            }
            String s = p.toString();
            for (Pattern re : patterns) {
                if (re.matcher(s).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<String> rules() {
        return raw;
    }
}
