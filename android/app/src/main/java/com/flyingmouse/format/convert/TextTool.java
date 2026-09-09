package com.flyingmouse.format.convert;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.Document;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * 文本/数据格式转换：
 * - TXT → HTML / Markdown / EPUB
 * - Markdown → HTML / TXT / EPUB
 * - HTML → TXT（去标签）/ Markdown（基础）
 * - JSON ↔ XML（基于 org.json）、JSON 美化
 * - CSV → JSON / Markdown 表格 / HTML 表格
 * 移动端子集，产出与桌面版同源同构但覆盖范围更小。
 */
public final class TextTool {

    private TextTool() {
    }

    // ---------- 通用 ----------
    public static String readAll(BufferedReader reader) throws Exception {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }

    // ---------- TXT → HTML ----------
    public static String txtToHtml(String txt) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>转换文本</title>\n</head>\n<body>\n");
        String[] lines = txt.split("\\r?\\n");
        StringBuilder para = new StringBuilder();
        for (String raw : lines) {
            String line = escapeHtml(raw).trim();
            if (line.isEmpty()) {
                if (para.length() > 0) {
                    sb.append("<p>").append(para).append("</p>\n");
                    para.setLength(0);
                }
            } else {
                if (para.length() > 0) para.append("<br>\n");
                para.append(line);
            }
        }
        if (para.length() > 0) sb.append("<p>").append(para).append("</p>\n");
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    public static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- HTML → TXT ----------
    public static String htmlToText(String html) {
        if (html == null) return "";
        String s = html;
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|h[1-6]|li|tr|pre|blockquote)>", "\n");
        s = s.replaceAll("(?i)<td[^>]*>", "\t");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
        s = s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        return s;
    }

    // ---------- Markdown → HTML（基础块级支持） ----------
    public static String markdownToHtml(String md) {
        StringBuilder out = new StringBuilder("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n<title>转换文档</title>\n</head>\n<body>\n");
        String[] lines = md.split("\\r?\\n");
        boolean inCode = false;
        StringBuilder codeBuf = new StringBuilder();
        boolean inUl = false;
        boolean inOl = false;

        for (String raw : lines) {
            String line = raw;
            if (line.trim().startsWith("```")) {
                if (inCode) {
                    out.append("<pre><code>").append(escapeHtml(codeBuf.toString())).append("</code></pre>\n");
                    codeBuf.setLength(0);
                    inCode = false;
                } else {
                    flushList(out, inUl, inOl);
                    inUl = inOl = false;
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                codeBuf.append(line).append('\n');
                continue;
            }

            String t = line.trim();
            if (t.isEmpty()) {
                flushList(out, inUl, inOl);
                inUl = inOl = false;
                continue;
            }
            // 标题
            if (t.matches("^#{1,6}\\s.*")) {
                flushList(out, inUl, inOl); inUl = inOl = false;
                int level = 0;
                while (level < t.length() && t.charAt(level) == '#') level++;
                out.append("<h").append(level).append(">").append(inlineMd(t.substring(level).trim())).append("</h").append(level).append(">\n");
                continue;
            }
            // 无序/有序列表
            if (t.matches("^[-*+]\\s.*")) {
                if (!inUl) { if (inOl) out.append("</ol>\n"); out.append("<ul>\n"); inUl = true; inOl = false; }
                out.append("<li>").append(inlineMd(t.substring(1).trim())).append("</li>\n");
                continue;
            }
            if (t.matches("^\\d+\\.\\s.*")) {
                if (!inOl) { if (inUl) out.append("</ul>\n"); out.append("<ol>\n"); inOl = true; inUl = false; }
                out.append("<li>").append(inlineMd(t.replaceFirst("^\\d+\\.\\s*", ""))).append("</li>\n");
                continue;
            }
            // 引用
            if (t.startsWith(">")) {
                flushList(out, inUl, inOl); inUl = inOl = false;
                out.append("<blockquote>").append(inlineMd(t.substring(1).trim())).append("</blockquote>\n");
                continue;
            }
            // 分隔线
            if (t.matches("^([-*_]\\s*){3,}$")) {
                flushList(out, inUl, inOl); inUl = inOl = false;
                out.append("<hr>\n");
                continue;
            }
            // 段落
            flushList(out, inUl, inOl); inUl = inOl = false;
            out.append("<p>").append(inlineMd(t)).append("</p>\n");
        }
        flushList(out, inUl, inOl);
        if (inCode) out.append("<pre><code>").append(escapeHtml(codeBuf.toString())).append("</code></pre>\n");
        out.append("</body>\n</html>\n");
        return out.toString();
    }

    private static void flushList(StringBuilder out, boolean inUl, boolean inOl) {
        if (inUl) out.append("</ul>\n");
        if (inOl) out.append("</ol>\n");
    }

    /** 行内 Markdown：先转义防注入，再对模式做标签替换。 */
    private static String inlineMd(String s) {
        s = escapeHtml(s);
        s = s.replaceAll("`([^`]+)`", "<code>$1</code>");
        s = s.replaceAll("\\[([^\\]]+)\\]\\(([^)]+)\\)", "<a href=\"$2\">$1</a>");
        s = s.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        s = s.replaceAll("__([^_]+)__", "<strong>$1</strong>");
        s = s.replaceAll("\\*([^*]+)\\*", "<em>$1</em>");
        s = s.replaceAll("_([^_]+)_", "<em>$1</em>");
        return s;
    }

    // ---------- HTML → Markdown（基础） ----------
    public static String htmlToMarkdown(String html) {
        String s = html;
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "");
        s = s.replaceAll("(?i)<h1[^>]*>", "\n\n# ").replaceAll("(?i)</h1>", "\n");
        s = s.replaceAll("(?i)<h2[^>]*>", "\n\n## ").replaceAll("(?i)</h2>", "\n");
        s = s.replaceAll("(?i)<h3[^>]*>", "\n\n### ").replaceAll("(?i)</h3>", "\n");
        s = s.replaceAll("(?i)<h4[^>]*>", "\n\n#### ").replaceAll("(?i)</h4>", "\n");
        s = s.replaceAll("(?i)<h5[^>]*>", "\n\n##### ").replaceAll("(?i)</h5>", "\n");
        s = s.replaceAll("(?i)<h6[^>]*>", "\n\n###### ").replaceAll("(?i)</h6>", "\n");
        s = s.replaceAll("(?i)<li[^>]*>", "\n- ");
        s = s.replaceAll("(?i)</(ul|ol)>", "\n");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|pre|blockquote|table|tr)>", "\n\n");
        s = s.replaceAll("(?i)<strong[^>]*>", "**").replaceAll("(?i)</strong>", "**");
        s = s.replaceAll("(?i)<b[^>]*>", "**").replaceAll("(?i)</b>", "**");
        s = s.replaceAll("(?i)<em[^>]*>", "*").replaceAll("(?i)</em>", "*");
        s = s.replaceAll("(?i)<i[^>]*>", "*").replaceAll("(?i)</i>", "*");
        s = s.replaceAll("(?i)<code[^>]*>", "`").replaceAll("(?i)</code>", "`");
        s = s.replaceAll("(?i)<pre[^>]*>", "\n```\n").replaceAll("(?i)</pre>", "\n```\n");
        s = s.replaceAll("(?i)<a href=\"([^\"]+)\"[^>]*>([^<]*)</a>", "[$2]($1)");
        s = s.replaceAll("(?s)<[^>]+>", "");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
        s = s.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        return s + "\n";
    }

    // ---------- JSON ↔ XML ----------
    public static String jsonToXml(String json) throws Exception {
        try {
            return JsonXml.toXml(json);
        } catch (JSONException e) {
            throw new Exception("JSON 解析失败: " + e.getMessage());
        }
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static String xmlToJson(String xml) throws Exception {
        try {
            return JsonXml.fromXml(xml);
        } catch (JSONException e) {
            throw new Exception("XML 解析失败: " + e.getMessage());
        }
    }

    public static String prettyJson(String json) throws Exception {
        try {
            String t = json.trim();
            if (t.startsWith("[")) return new JSONArray(t).toString(2);
            return new JSONObject(t).toString(2);
        } catch (JSONException e) {
            throw new Exception("JSON 解析失败: " + e.getMessage());
        }
    }

    public static String prettyXml(String xml) throws Exception {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new org.xml.sax.InputSource(new StringReader(xml)));
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (Exception e) {
            throw new Exception("XML 解析失败: " + e.getMessage());
        }
    }

    // ---------- CSV ----------
    public static String csvToJson(String csv) throws Exception {
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) throw new Exception("CSV 为空");
        List<String> header = rows.get(0);
        JSONArray arr = new JSONArray();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            JSONObject obj = new JSONObject();
            for (int c = 0; c < header.size(); c++) {
                String key = header.get(c).trim();
                String val = c < row.size() ? row.get(c) : "";
                if (key.isEmpty()) key = "col" + (c + 1);
                obj.put(key, val);
            }
            arr.put(obj);
        }
        return arr.toString(2);
    }

    public static String csvToMarkdown(String csv) throws Exception {
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) throw new Exception("CSV 为空");
        List<String> header = rows.get(0);
        StringBuilder sb = new StringBuilder("| ");
        for (String h : header) sb.append(escapePipe(h)).append(" | ");
        sb.append("\n|");
        for (int i = 0; i < header.size(); i++) sb.append(" --- |");
        sb.append("\n");
        for (int i = 1; i < Math.min(rows.size(), 200); i++) {
            sb.append("| ");
            for (int c = 0; c < header.size(); c++) {
                String v = c < rows.get(i).size() ? rows.get(i).get(c) : "";
                sb.append(escapePipe(v)).append(" | ");
            }
            sb.append("\n");
        }
        if (rows.size() > 200) sb.append("| ...（截断） |\n");
        return sb.toString();
    }

    public static String csvToHtmlTable(String csv) throws Exception {
        List<List<String>> rows = parseCsv(csv);
        if (rows.isEmpty()) throw new Exception("CSV 为空");
        StringBuilder sb = new StringBuilder("<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"><title>CSV 表格</title></head><body>\n<table border=\"1\">\n<thead><tr>");
        for (String h : rows.get(0)) sb.append("<th>").append(escapeHtml(h)).append("</th>");
        sb.append("</tr></thead>\n<tbody>\n");
        for (int i = 1; i < rows.size(); i++) {
            sb.append("<tr>");
            for (int c = 0; c < rows.get(0).size(); c++) {
                String v = c < rows.get(i).size() ? rows.get(i).get(c) : "";
                sb.append("<td>").append(escapeHtml(v)).append("</td>");
            }
            sb.append("</tr>\n");
        }
        sb.append("</tbody></table>\n</body></html>\n");
        return sb.toString();
    }

    private static String escapePipe(String s) {
        return s.replace("|", "\\|").replace("\n", " ");
    }

    /** 简易 RFC4180 解析（支持引号包裹）。 */
    public static List<List<String>> parseCsv(String csv) throws Exception {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < csv.length()) {
            char ch = csv.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
                i++;
            } else if (ch == '"') {
                inQuotes = true;
                i++;
            } else if (ch == ',') {
                row.add(field.toString().trim());
                field.setLength(0);
                i++;
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(field.toString().trim());
                field.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                row = new ArrayList<>();
                i++;
            } else {
                field.append(ch);
                i++;
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString().trim());
            rows.add(row);
        }
        return rows;
    }
}
