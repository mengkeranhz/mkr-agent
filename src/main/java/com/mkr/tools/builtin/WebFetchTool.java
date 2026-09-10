package com.mkr.tools.builtin;

import com.mkr.core.RunContext;
import com.mkr.guard.InjectionSanitizer;
import com.mkr.tools.AgentTool;
import com.mkr.tools.Risk;
import com.mkr.tools.Tool;
import com.mkr.tools.ToolArgs;
import com.mkr.tools.ToolParam;
import com.mkr.tools.ToolResult;
import org.jsoup.Jsoup;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * WebFetch（web_fetch）：HttpClient 抓取。
 *
 * <ul>
 *   <li>URL 指向文件（pdf/docx/xlsx/zip/图片/音视频等二进制）→ 流式下载到
 *       {@code workspace/.artifacts/web_fetch/}，返回文件路径与元数据（不回灌正文）；</li>
 *   <li>HTML → jsoup 正文转 markdown；纯文本（txt/csv/json…）原样返回；</li>
 *   <li>正文超过 {@code max_chars} 阈值时不再“先截断”，而是全文落盘后回灌路径 + 截断摘要。</li>
 * </ul>
 */
@AgentTool(name = "web_fetch",
        description = "抓取 URL：网页转 markdown 正文；若为文件（pdf/docx/xlsx/zip 等）或正文超长则下载/落盘并返回文件路径。Use when: 读取已知网页/文档/文件；Don't use when: 需要搜索关键词用 web_search。",
        risk = Risk.LOW)
public final class WebFetchTool implements Tool {

    /** 正文默认内联上限（字符）。 */
    private static final int DEFAULT_MAX_CHARS = 20_000;
    /** 下载文件的大小上限（字节），防止磁盘写满。 */
    private static final long MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024;
    /** 文本正文在内存中解码的上限（字节），超出则流式落盘后再摘要。 */
    private static final int MAX_IN_MEMORY_TEXT_BYTES = 8 * 1024 * 1024;

    /** 明确按「文件」下载的二进制扩展名。 */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub", "mobi",
            "zip", "tar", "gz", "tgz", "rar", "7z", "bz2", "xz", "jar", "war",
            "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "tif", "tiff", "avif",
            "mp3", "wav", "flac", "ogg", "m4a", "aac",
            "mp4", "avi", "mov", "mkv", "webm", "wmv", "mpeg", "mpg",
            "exe", "dll", "so", "dylib", "bin", "iso", "dmg", "apk", "deb", "rpm", "msi", "psd");

    /** 明确按「文本」处理的扩展名（即便服务端回 application/octet-stream 也不当二进制下载）。 */
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            "txt", "csv", "tsv", "md", "markdown", "json", "jsonl", "ndjson", "xml", "yaml", "yml", "log",
            "css", "js", "mjs", "ts", "py", "java", "c", "h", "cpp", "go", "rs", "rb", "php", "sh", "sql",
            "properties", "ini", "conf", "toml", "env", "html", "htm", "xhtml", "shtml");

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final InjectionSanitizer sanitizer = new InjectionSanitizer();

    @Override
    public List<ToolParam> parameters() {
        return List.of(
                new ToolParam("url", "string", "目标 URL（http/https）", true),
                new ToolParam("max_chars", "integer", "正文内联最大字符数（默认 20000，超出全文落盘）", false));
    }

    @Override
    public ToolResult run(Map<String, Object> params, RunContext ctx) throws Exception {
        String url = ToolArgs.str(params, "url");
        if (url == null || !url.startsWith("http")) {
            return ToolResult.error("INVALID_ARGS", "缺少合法 url 参数");
        }
        int maxChars = Math.max(1, ToolArgs.Int(params, "max_chars", DEFAULT_MAX_CHARS));
        String provider = ctx.config().tools.webFetchProvider;
        try {
            return "jina-reader".equals(provider) ? fetchJina(url, maxChars, ctx) : fetchLocal(url, maxChars, ctx);
        } catch (Exception e) {
            return ToolResult.error("FETCH_FAILED", url + ": " + e.getMessage());
        }
    }

    // ---------------- 本地抓取（流式，先看响应头再决定下载还是当正文） ----------------

    private ToolResult fetchLocal(String url, int maxChars, RunContext ctx) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("User-Agent", "Mozilla/5.0 (compatible; mkr-agent/0.1)")
                .header("Accept", "text/html,application/xhtml+xml,application/pdf,application/octet-stream,*/*;q=0.5")
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            resp.body().close();
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        String contentType = firstHeader(resp, "Content-Type");
        String disposition = firstHeader(resp, "Content-Disposition");
        String ext = extensionOf(url);
        if (isFileDownload(ext, contentType, disposition)) {
            return downloadFile(url, ext, contentType, resp, ctx);
        }
        return fetchText(url, maxChars, ext, contentType, resp, ctx);
    }

    /** 流式下载二进制/文件到 workspace/.artifacts/web_fetch/，返回路径 + 元数据。 */
    private ToolResult downloadFile(String url, String ext, String contentType,
                                    HttpResponse<InputStream> resp, RunContext ctx) throws IOException {
        long declared = contentLength(resp);
        if (declared > MAX_DOWNLOAD_BYTES) {
            resp.body().close();
            return ToolResult.error("TOO_LARGE", "文件过大（" + (declared / 1024 / 1024) + "MB > "
                    + (MAX_DOWNLOAD_BYTES / 1024 / 1024) + "MB 上限），已拒绝下载: " + url);
        }
        String extForName = ext.isEmpty() ? extensionForContentType(contentType) : ext;
        String name = ensureExt(deriveBaseName(url), extForName);
        Path dir = ctx.artifactsDir().resolve("web_fetch");
        Files.createDirectories(dir);
        Path file = uniquePath(dir, name);
        long written = 0;
        try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                written += n;
                if (written > MAX_DOWNLOAD_BYTES) {
                    throw new TooLargeException();
                }
                out.write(buf, 0, n);
            }
        } catch (TooLargeException e) {
            Files.deleteIfExists(file);
            return ToolResult.error("TOO_LARGE", "下载内容超过 " + (MAX_DOWNLOAD_BYTES / 1024 / 1024)
                    + "MB 上限，已中止并清理: " + url);
        }
        ctx.recordWritten(file);
        long size = Files.size(file);
        String type = contentType == null || contentType.isBlank() ? "unknown" : contentType;
        return ToolResult.ok("已下载文件（" + size + " 字节，类型 " + type + "）:\n" + file
                        + "\n[提示：二进制文件无法直接读取正文，请用 read_file 或相应工具处理]",
                Map.of("path", file.toString(), "bytes", size));
    }

    /** 文本路径：有界读取解码 → HTML 转 markdown / 纯文本；超阈值全文落盘回灌摘要。 */
    private ToolResult fetchText(String url, int maxChars, String ext, String contentType,
                                 HttpResponse<InputStream> resp, RunContext ctx) throws IOException {
        InputStream in = resp.body();
        ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
        byte[] chunk = new byte[8192];
        byte[] carry = null;
        int carryLen = 0;
        boolean overflow = false;
        try {
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (buf.size() + n > MAX_IN_MEMORY_TEXT_BYTES) {
                    carry = chunk;
                    carryLen = n;
                    overflow = true;
                    break;
                }
                buf.write(chunk, 0, n);
            }
        } finally {
            if (!overflow) {
                in.close();
            }
        }
        if (overflow) {
            // 超大正文：流式落盘（不截断），再读文件头做摘要
            return spillToFile(url, maxChars, contentType, buf, carry, carryLen, in, ctx);
        }
        String text = decode(buf.toByteArray(), contentType);
        String body = render(text, url, contentType);
        if (body.isBlank()) {
            return ToolResult.error("FETCH_EMPTY", "页面无有效正文: " + url);
        }
        if (body.length() > maxChars) {
            return saveTextToFile(url, body, maxChars, ext, contentType, ctx);
        }
        return ToolResult.ok(sanitizer.wrap(body, url));
    }

    /** 正文超过阈值：全文落盘，回灌路径 + 截断摘要（不再“先截断再保存”）。 */
    private ToolResult saveTextToFile(String url, String content, int maxChars, String ext,
                                      String contentType, RunContext ctx) {
        try {
            Path dir = ctx.artifactsDir().resolve("web_fetch");
            Files.createDirectories(dir);
            String saveExt = isHtmlContentType(contentType) ? "md"
                    : (TEXT_EXTENSIONS.contains(ext) ? ext : "txt");
            Path file = uniquePath(dir, ensureExt(deriveBaseName(url), saveExt));
            Files.writeString(file, content, StandardCharsets.UTF_8);
            ctx.recordWritten(file);
            String summary = sanitizer.truncate(content, maxChars);
            return ToolResult.ok("[正文 " + content.length() + " 字符超过阈值 " + maxChars
                            + "，已保存全文至:\n" + file + "]\n\n" + sanitizer.wrap(summary, url),
                    Map.of("path", file.toString(), "chars", content.length()));
        } catch (IOException e) {
            return ToolResult.error("SAVE_FAILED", "写盘失败: " + e.getMessage());
        }
    }

    /** 超过内存上限的正文：前缀 + 剩余流写盘，返回路径 + 开头预览。 */
    private ToolResult spillToFile(String url, int maxChars, String contentType,
                                   ByteArrayOutputStream prefix, byte[] carry, int carryLen,
                                   InputStream in, RunContext ctx) throws IOException {
        Path dir = ctx.artifactsDir().resolve("web_fetch");
        Files.createDirectories(dir);
        String saveExt = isHtmlContentType(contentType) ? "md" : "txt";
        Path file = uniquePath(dir, ensureExt(deriveBaseName(url), saveExt));
        try (OutputStream out = Files.newOutputStream(file)) {
            prefix.writeTo(out);
            if (carryLen > 0) {
                out.write(carry, 0, carryLen);
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        ctx.recordWritten(file);
        long size = Files.size(file);
        return ToolResult.ok("[正文过大（" + size + " 字节），已保存全文至:\n" + file + "]\n\n"
                        + sanitizer.wrap(headPreview(file, maxChars), url),
                Map.of("path", file.toString(), "bytes", size));
    }

    // ---------------- Jina Reader（仅文本） ----------------

    private ToolResult fetchJina(String url, int maxChars, RunContext ctx) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://r.jina.ai/" + url))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/plain")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        String markdown = resp.body();
        if (markdown.isBlank()) {
            return ToolResult.error("FETCH_EMPTY", "页面无有效正文: " + url);
        }
        if (markdown.length() > maxChars) {
            return saveTextToFile(url, markdown, maxChars, "", "text/plain", ctx);
        }
        return ToolResult.ok(sanitizer.wrap(markdown, url));
    }

    // ---------------- 分类 / 识别 ----------------

    /** 判断是否应按「文件」下载：二进制扩展名 / 二进制 Content-Type / attachment 处置。 */
    static boolean isFileDownload(String ext, String contentType, String disposition) {
        boolean attachment = disposition != null
                && disposition.toLowerCase(Locale.ROOT).contains("attachment");
        boolean html = isHtmlContentType(contentType);
        if (ext != null && !ext.isEmpty() && BINARY_EXTENSIONS.contains(ext)) {
            return !html; // 二进制扩展名；服务端返回 HTML 预览页则仍按页面处理
        }
        if (ext != null && !ext.isEmpty() && TEXT_EXTENSIONS.contains(ext)) {
            return false; // 明确文本扩展名
        }
        if (attachment && !html) {
            return true;
        }
        return isBinaryContentType(contentType);
    }

    static boolean isBinaryContentType(String ct) {
        if (ct == null) {
            return false;
        }
        String c = ct.toLowerCase(Locale.ROOT).trim();
        if (c.isEmpty()) {
            return false;
        }
        String mime = c.contains(";") ? c.substring(0, c.indexOf(';')).trim() : c;
        if (mime.startsWith("text/")) {
            return false;
        }
        if (mime.equals("application/json") || mime.endsWith("+json")
                || mime.equals("application/xml") || mime.endsWith("+xml")
                || mime.equals("application/javascript") || mime.equals("application/x-javascript")
                || mime.equals("application/x-www-form-urlencoded")) {
            return false;
        }
        if (mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")
                || mime.startsWith("font/") || mime.equals("application/octet-stream")) {
            return true;
        }
        return mime.equals("application/pdf")
                || mime.equals("application/zip") || mime.equals("application/x-zip-compressed")
                || mime.equals("application/x-tar") || mime.equals("application/gzip")
                || mime.equals("application/x-gzip")
                || mime.equals("application/x-7z-compressed") || mime.equals("application/x-rar-compressed")
                || mime.equals("application/x-bzip2") || mime.equals("application/x-xz")
                || mime.equals("application/msword")
                || mime.equals("application/vnd.ms-excel") || mime.equals("application/vnd.ms-powerpoint")
                || mime.equals("application/epub+zip")
                || mime.equals("application/x-msdownload") || mime.equals("application/x-msdos-program")
                || mime.equals("application/x-executable") || mime.equals("application/x-sh")
                || mime.startsWith("application/vnd.openxmlformats-officedocument")
                || mime.startsWith("application/vnd.ms-")
                || mime.startsWith("application/vnd.apple")
                || mime.startsWith("application/vnd.android");
    }

    static boolean isHtmlContentType(String ct) {
        if (ct == null) {
            return false;
        }
        String c = ct.toLowerCase(Locale.ROOT);
        return c.contains("html") || c.contains("xhtml");
    }

    /** 由 Content-Type 映射文件扩展名（URL 无扩展名时兜底）。 */
    static String extensionForContentType(String contentType) {
        if (contentType == null) {
            return "";
        }
        String c = contentType.toLowerCase(Locale.ROOT).trim();
        String mime = c.contains(";") ? c.substring(0, c.indexOf(';')).trim() : c;
        return switch (mime) {
            case "application/pdf" -> "pdf";
            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "application/vnd.ms-excel" -> "xls";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx";
            case "application/vnd.ms-powerpoint" -> "ppt";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx";
            case "application/zip", "application/x-zip-compressed" -> "zip";
            case "application/x-tar" -> "tar";
            case "application/gzip", "application/x-gzip" -> "gz";
            case "application/x-7z-compressed" -> "7z";
            case "application/x-rar-compressed" -> "rar";
            case "application/x-bzip2" -> "bz2";
            case "application/x-xz" -> "xz";
            case "application/epub+zip" -> "epub";
            case "application/json" -> "json";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml" -> "svg";
            case "image/bmp" -> "bmp";
            case "image/x-icon", "image/vnd.microsoft.icon" -> "ico";
            case "audio/mpeg" -> "mp3";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "video/mp4" -> "mp4";
            case "application/octet-stream" -> "bin";
            default -> mime.startsWith("image/") ? mime.substring("image/".length()) : "";
        };
    }

    // ---------------- 文本解码 / 渲染 ----------------

    private static String render(String text, String url, String contentType) {
        if (isHtmlContentType(contentType) || (contentType == null && looksLikeHtml(text))) {
            return HtmlToMarkdown.convert(Jsoup.parse(text, url));
        }
        return InjectionSanitizer.stripControl(text);
    }

    private static boolean looksLikeHtml(String text) {
        String head = text == null ? "" : text.stripLeading().toLowerCase(Locale.ROOT);
        if (head.isEmpty()) {
            return false;
        }
        return head.startsWith("<!doctype") || head.startsWith("<html")
                || head.startsWith("<head") || head.startsWith("<body");
    }

    /** 按 Content-Type 的 charset 解码；缺省 UTF-8，出现替换符则 GBK 兜底（中文站点常见）。 */
    static String decode(byte[] bytes, String contentType) {
        String charset = charsetOf(contentType);
        if (charset != null) {
            try {
                return new String(bytes, Charset.forName(charset));
            } catch (RuntimeException ignored) {
                // 非法 charset 名 → 走默认
            }
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (utf8.indexOf('�') >= 0) {
            try {
                String gbk = new String(bytes, Charset.forName("GBK"));
                if (gbk.indexOf('�') < 0) {
                    return gbk;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return utf8;
    }

    static String charsetOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        int i = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
        if (i < 0) {
            return null;
        }
        String v = contentType.substring(i + "charset=".length()).trim();
        int end = v.indexOf(';');
        if (end >= 0) {
            v = v.substring(0, end);
        }
        v = v.replace("\"", "").replace("'", "").trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() ? null : v;
    }

    // ---------------- 文件名 / 路径 ----------------

    /** URL 路径最后一段（URL 解码 + 防穿越 + 去控制字符 + 限长）。 */
    static String lastPathSegment(String url) {
        String path;
        try {
            path = URI.create(url).getPath();
        } catch (RuntimeException e) {
            path = null;
        }
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        try {
            name = URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (RuntimeException ignored) {
        }
        name = name.replaceAll("[\\x00-\\x1F\\x7F/\\\\]", "_");
        name = name.replaceFirst("^\\.+", ""); // 去前导点（防隐藏文件）
        if (name.isBlank()) {
            return "";
        }
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    /** 文件名基准：URL 末段，空则时间戳兜底。 */
    static String deriveBaseName(String url) {
        String seg = lastPathSegment(url);
        return seg == null || seg.isBlank() ? "download_" + System.currentTimeMillis() : seg;
    }

    static String extensionOf(String url) {
        String name = lastPathSegment(url);
        if (name == null || name.isEmpty()) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    static String ensureExt(String name, String ext) {
        if (ext == null || ext.isBlank()) {
            return name;
        }
        return name.toLowerCase(Locale.ROOT).endsWith("." + ext.toLowerCase(Locale.ROOT))
                ? name : name + "." + ext;
    }

    /** 同名文件追加序号，避免覆盖已有下载。 */
    static Path uniquePath(Path dir, String name) {
        Path candidate = dir.resolve(name);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < Integer.MAX_VALUE; i++) {
            candidate = dir.resolve(base + "-" + i + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return dir.resolve(base + "-" + System.currentTimeMillis() + ext);
    }

    // ---------------- 小工具 ----------------

    private static String firstHeader(HttpResponse<?> resp, String name) {
        return resp.headers().firstValue(name).orElse(null);
    }

    private static long contentLength(HttpResponse<?> resp) {
        try {
            return resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    private static String headPreview(Path file, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder(Math.min(maxChars, 8192));
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            int remaining = maxChars;
            while (remaining > 0) {
                int n = r.read(buf, 0, Math.min(buf.length, remaining));
                if (n < 0) {
                    break;
                }
                sb.append(buf, 0, n);
                remaining -= n;
            }
        }
        sb.append("\n…（预览截断，全文见文件）");
        return sb.toString();
    }

    private static final class TooLargeException extends RuntimeException {
    }
}
