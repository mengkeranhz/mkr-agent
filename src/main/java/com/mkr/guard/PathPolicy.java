package com.mkr.guard;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 路径规则（permissions.allow/ask/deny）。决策优先级硬编码：deny ＞ ask ＞ allow ＞ 默认，
 * deny 永远最高，对齐 Claude Code settings.json。
 *
 * <p>规则语法：支持 {@code **}（跨目录）、{@code *}（单段）、{@code ?}；
 * {@code ~} 展开用户 home；{@code ./} 相对 cwd；不以 / 开头且不含 / 的纯文件名、
 * 以及以 {@code **} 加 / 开头的模式按「任意深度」匹配。</p>
 */
public final class PathPolicy {

    public enum Decision {ALLOW, ASK, DENY, DEFAULT}

    private final List<Pattern> allow;
    private final List<Pattern> ask;
    private final List<Pattern> deny;

    private PathPolicy(List<Pattern> allow, List<Pattern> ask, List<Pattern> deny) {
        this.allow = allow;
        this.ask = ask;
        this.deny = deny;
    }

    public static PathPolicy of(List<String> allowGlobs, List<String> askGlobs, List<String> denyGlobs) {
        Path home = Path.of(System.getProperty("user.home"));
        Path cwd = Path.of(System.getProperty("user.dir"));
        return new PathPolicy(compileAll(allowGlobs, home, cwd), compileAll(askGlobs, home, cwd),
                compileAll(denyGlobs, home, cwd));
    }

    /** 优先级：deny ＞ ask ＞ allow ＞ DEFAULT。 */
    public Decision decide(Path absolutePath) {
        String p = absolutePath.toString();
        for (Pattern re : deny) {
            if (re.matcher(p).matches()) {
                return Decision.DENY;
            }
        }
        for (Pattern re : ask) {
            if (re.matcher(p).matches()) {
                return Decision.ASK;
            }
        }
        for (Pattern re : allow) {
            if (re.matcher(p).matches()) {
                return Decision.ALLOW;
            }
        }
        return Decision.DEFAULT;
    }

    public List<String> describe() {
        List<String> out = new ArrayList<>();
        deny.forEach(g -> out.add("deny  " + g));
        ask.forEach(g -> out.add("ask   " + g));
        allow.forEach(g -> out.add("allow " + g));
        return out;
    }

    private static List<Pattern> compileAll(List<String> globs, Path home, Path cwd) {
        List<Pattern> out = new ArrayList<>();
        if (globs == null) {
            return out;
        }
        for (String g : globs) {
            if (g == null || g.isBlank()) {
                continue;
            }
            out.add(compile(g, home, cwd));
        }
        return out;
    }

    /** glob → 全匹配正则。包内复用（ProtectedDirs）。 */
    static Pattern compile(String glob, Path home, Path cwd) {
        String g = glob.trim();
        boolean anywhere = false;
        if (g.equals("~") || g.startsWith("~/")) {
            g = home.toString() + g.substring(1);
        } else if (g.equals(".") || g.startsWith("./")) {
            g = cwd.toString() + (g.length() > 1 ? g.substring(1) : "");
        } else if (g.startsWith("**/") || g.equals("**")) {
            anywhere = true;
            g = g.equals("**") ? "**" : g.substring(3);
        } else if (!g.startsWith("/") && !g.contains("/")) {
            anywhere = true; // 纯文件名（如 .env）任意深度
        }
        // 末尾 /**：目录本身与其下全部内容（"/etc/**" 覆盖 "/etc" 与 "/etc/passwd"）
        if (g.endsWith("/**")) {
            String prefix = bodyRegex(g.substring(0, g.length() - 3));
            String regex = (anywhere ? "(^|.*/)" : "") + prefix + "(/.*)?";
            return Pattern.compile(regex);
        }
        String body = bodyRegex(g);
        boolean hasWildcard = glob.contains("*") || glob.contains("?");
        String regex = (anywhere ? "(^|.*/)" : "") + body
                + (anywhere || hasWildcard ? "" : "(/.*)?"); // 无通配的目录/文件前缀 → 覆盖其下内容
        return Pattern.compile(regex);
    }

    private static String bodyRegex(String g) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i++;
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
