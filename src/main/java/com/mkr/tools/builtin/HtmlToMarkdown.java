package com.mkr.tools.builtin;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * HTML → markdown 正文转换（标题/段落/列表/代码/链接），web_fetch 与 browser 共用。
 */
public final class HtmlToMarkdown {

    private HtmlToMarkdown() {
    }

    /** 清噪（script/style/nav 等）后整体转 markdown，含 # 标题行。 */
    public static String convert(Document doc) {
        doc.select("script,style,noscript,nav,footer,header,aside,form,iframe").remove();
        StringBuilder md = new StringBuilder();
        String title = doc.title();
        if (!title.isBlank()) {
            md.append("# ").append(title).append("\n\n");
        }
        appendElement(doc.body(), md, 0);
        return md.toString();
    }

    private static void appendElement(Element root, StringBuilder md, int depth) {
        if (root == null || md.length() > 200_000) {
            return;
        }
        for (Element el : root.children()) {
            String tag = el.tagName().toLowerCase();
            switch (tag) {
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    int level = Math.min(6, tag.charAt(1) - '0');
                    String text = el.text().strip();
                    if (!text.isEmpty()) {
                        md.append("\n").append("#".repeat(level)).append(" ").append(text).append("\n\n");
                    }
                }
                case "p" -> appendText(el.text(), md);
                case "li" -> appendText("- " + el.text(), md);
                case "pre" -> md.append("```\n").append(el.wholeText().strip()).append("\n```\n\n");
                case "code" -> md.append("`").append(el.text()).append("`");
                case "blockquote" -> appendText("> " + el.text(), md);
                case "table" -> appendText(el.text().replaceAll("\\s{2,}", " | "), md);
                case "a" -> {
                    String text = el.text().strip();
                    if (!text.isEmpty() && el.hasAttr("href")) {
                        md.append("[").append(text).append("](").append(el.absUrl("href")).append(") ");
                    }
                }
                case "img" -> {
                    if (el.hasAttr("alt") && el.hasAttr("src")) {
                        md.append("![image: ").append(el.attr("alt")).append("] ");
                    }
                }
                default -> {
                    if (el.children().isEmpty()) {
                        appendText(el.text(), md);
                    } else {
                        appendElement(el, md, depth + 1);
                    }
                }
            }
        }
    }

    private static void appendText(String text, StringBuilder md) {
        if (text == null) {
            return;
        }
        String t = text.strip();
        if (!t.isEmpty()) {
            md.append(t).append("\n\n");
        }
    }
}
