package com.flyingmouse.format.convert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * P4 Office 可用子集：docx/xlsx/pptx → 文本/csv/html。
 * 零依赖（不引 Apache POI）：docx 取 document.xml 段落与表格文本；
 * xlsx 取 sharedStrings + 各 sheet 单元格；pptx 取各 slide 文本。
 * 对齐桌面版（LibreOffice）的"文本类可用子集"，复杂版式/图表不做。
 */
public final class OfficeReader {

    private OfficeReader() {
    }

    /** docx → 纯文本（段落换行；表格单元格以 \t 分隔）。 */
    public static String docxToText(byte[] zip) throws IOException {
        String xml = entryText(zip, "word/document.xml");
        if (xml == null) throw new IOException("未找到 word/document.xml");
        // 表格单元格分隔：<w:tc> 前补制表（表格每行单元格边界）
        xml = xml.replaceAll("(?i)<w:tc[ >]", "\t<w:tc>");
        // 换行类：手动换行/回车/制表位 → 转义占位
        xml = xml.replaceAll("(?i)<w:tab\\s*/>", "\u0001");
        xml = xml.replaceAll("(?i)<w:tab[^>]*>.*?</w:tab>", "\u0001");
        xml = xml.replaceAll("(?i)<w:br\\s*/>", "\u0002");
        xml = xml.replaceAll("(?i)<w:cr\\s*/>", "\u0002");
        StringBuilder sb = new StringBuilder();
        int i = 0;
        boolean inT = false;
        while (i < xml.length()) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                if (inT) sb.append(xml, i, xml.length());
                break;
            }
            if (lt > i && inT) sb.append(xml, i, lt);
            int gt = xml.indexOf('>', lt);
            if (gt < 0) break;
            String tag = xml.substring(lt, gt + 1);
            if (tag.startsWith("</w:t>") || tag.startsWith("</w:r>")) inT = false;
            else if (tag.startsWith("<w:t") && !tag.startsWith("</")) inT = true;
            else if (tag.startsWith("</w:p>")) {
                // 段落结束：剥掉刚可能带的行内前导空格，追加换行
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) != '\n' && sb.charAt(len - 1) != '\u0002') sb.append('\n');
            } else if (tag.startsWith("</w:tr>")) {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\t') sb.setLength(len - 1);
                // 去掉行首制表（首个单元格前加的）
                int ls = sb.lastIndexOf("\n");
                int rowStart = ls < 0 ? 0 : ls + 1;
                if (rowStart < sb.length() && sb.charAt(rowStart) == '\t') sb.deleteCharAt(rowStart);
                sb.append('\n');
            } else if (tag.startsWith("</w:tc>")) {
                sb.append('\t');
            }
            i = gt + 1;
        }
        return clean(sb.toString());
    }

    /** xlsx → CSV 文本（含多 sheet，sheet 间空行）。 */
    public static String xlsxToCsv(byte[] zip) throws IOException {
        String shared = entryText(zip, "xl/sharedStrings.xml");
        List<String> sharedStrs = new ArrayList<>();
        if (shared != null) sharedStrs = extractSharedStrings(shared);
        List<String> sheetNames = listEntries(zip, "xl/worksheets/");
        StringBuilder out = new StringBuilder();
        for (String sheetEntry : sheetNames) {
            if (!sheetEntry.endsWith(".xml") || sheetEntry.contains("_rels")) continue;
            String sheetXml = entryText(zip, sheetEntry);
            if (sheetXml == null) continue;
            String csv = sheetXmlToCsv(sheetXml, sharedStrs);
            if (csv != null && !csv.trim().isEmpty()) {
                if (out.length() > 0) out.append("\n");
                out.append(csv.trim()).append('\n');
            }
        }
        if (out.length() == 0) throw new IOException("未在 xlsx 中提取到任何单元格");
        return out.toString();
    }

    /** pptx → 纯文本：每页标题/正文段落；页间空行。 */
    public static String pptxToText(byte[] zip) throws IOException {
        List<String> slides = listEntries(zip, "ppt/slides/slide");
        StringBuilder out = new StringBuilder();
        int page = 0;
        for (String entry : slides) {
            String xml = entryText(zip, entry);
            if (xml == null) continue;
            page++;
            // 抽取 <a:t> 与 <a:p> 结构：<a:p> 段落结束换行
            List<String> texts = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            int i = 0;
            boolean inT = false;
            while (i < xml.length()) {
                int lt = xml.indexOf('<', i);
                if (lt < 0) {
                    if (inT) cur.append(xml, i, xml.length());
                    break;
                }
                if (lt > i && inT) cur.append(xml, i, lt);
                int gt = xml.indexOf('>', lt);
                if (gt < 0) break;
                String tag = xml.substring(lt, gt + 1);
                if (tag.startsWith("</a:p>")) {
                    if (cur.length() > 0) texts.add(cur.toString().trim());
                    cur.setLength(0);
                } else if (tag.startsWith("<a:t") && !tag.startsWith("</")) {
                    inT = true;
                } else if (tag.startsWith("</a:t>")) {
                    inT = false;
                }
                i = gt + 1;
            }
            if (cur.length() > 0) texts.add(cur.toString().trim());
            if (texts.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append("--- 第 ").append(page).append(" 页 ---\n");
            out.append(String.join("\n", texts)).append('\n');
        }
        if (out.length() == 0) throw new IOException("未在 pptx 中提取到任何文本");
        return out.toString();
    }

    // ---------- xlsx 内部 ----------
    private static List<String> extractSharedStrings(String xml) {
        List<String> res = new ArrayList<>();
        // 每个 <si>…</si> 是一个字符串（可含多个 <t>，runs 合并）
        int from = 0;
        while (true) {
            int s = xml.indexOf("<si", from);
            if (s < 0) break;
            int e = xml.indexOf("</si>", s);
            if (e < 0) break;
            res.add(collectT(xml.substring(s, e + 5)));
            from = e + 5;
        }
        return res;
    }

    private static String collectT(String xml) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        boolean inT = false;
        while (i < xml.length()) {
            int lt = xml.indexOf('<', i);
            if (lt < 0) {
                if (inT) sb.append(xml, i, xml.length());
                break;
            }
            if (lt > i && inT) sb.append(xml, i, lt);
            int gt = xml.indexOf('>', lt);
            if (gt < 0) break;
            String tag = xml.substring(lt, gt + 1);
            if (tag.startsWith("</a:t>") || tag.startsWith("</t>")) inT = false;
            else if ((tag.startsWith("<a:t") || tag.startsWith("<t")) && !tag.startsWith("</")) inT = true;
            i = gt + 1;
        }
        return sb.toString().trim();
    }

    private static String sheetXmlToCsv(String sheetXml, List<String> sharedStrs) {
        // 行 <row …> … </row>；单元格 <c r="A1" t="s"><v>0</v></c> / <is><t>内联</t></is>
        StringBuilder csv = new StringBuilder();
        int from = 0;
        while (true) {
            int rs = sheetXml.indexOf("<row", from);
            if (rs < 0) break;
            int re = sheetXml.indexOf("</row>", rs);
            if (re < 0) break;
            String rowXml = sheetXml.substring(rs, re + 6);
            csv.append(rowToCsv(rowXml, sharedStrs)).append('\n');
            from = re + 6;
        }
        return csv.toString();
    }

    private static String rowToCsv(String rowXml, List<String> sharedStrs) {
        List<String> cells = new ArrayList<>();
        int from = 0;
        int maxCol = -1;
        while (true) {
            int cs = rowXml.indexOf("<c ", from);
            if (cs < 0) break;
            int ce = rowXml.indexOf("</c>", cs);
            int selfEnd = rowXml.indexOf("/>", cs);
            int end = (ce < 0) ? -1 : ce;
            if (end < 0 && selfEnd >= 0 && (ce < 0 || selfEnd < ce)) end = selfEnd + 2;
            if (end < 0) break;
            String cell = rowXml.substring(cs, end);
            int rPos = cell.indexOf(" r=\"");
            int colIdx = -1;
            if (rPos >= 0) {
                int q = cell.indexOf('"', rPos + 4);
                if (q > rPos) colIdx = colLettersToIndex(cell.substring(rPos + 4, q));
            }
            String val = cellValue(cell, sharedStrs);
            if (colIdx < 0) colIdx = cells.size();
            // 补齐缺口
            while (cells.size() < colIdx) cells.add("");
            cells.add(val);
            if (colIdx > maxCol) maxCol = colIdx;
            from = end;
        }
        if (cells.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= maxCol; i++) {
            if (i > 0) sb.append(',');
            String v = i < cells.size() ? cells.get(i) : "";
            sb.append(csvField(v));
        }
        return sb.toString();
    }

    private static String cellValue(String cell, List<String> sharedStrs) {
        boolean isShared = cell.contains("t=\"s\"");
        boolean isInline = cell.contains("t=\"inlineStr\"");
        String v = extractV(cell);
        if (isShared) {
            try {
                int idx = Integer.parseInt(v.trim());
                return idx >= 0 && idx < sharedStrs.size() ? sharedStrs.get(idx) : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        if (isInline) return collectT(cell);
        if (v == null) return "";
        // 数值（去掉科学计数法尾 .0）
        String t = v.trim();
        if (t.endsWith(".0") && t.indexOf('.') == t.length() - 2) t = t.substring(0, t.length() - 2);
        return t;
    }

    private static String extractV(String cell) {
        int vs = cell.indexOf("<v>");
        if (vs < 0) return null;
        int ve = cell.indexOf("</v>", vs);
        if (ve < 0) return null;
        return cell.substring(vs + 3, ve);
    }

    private static int colLettersToIndex(String letters) {
        int idx = 0;
        for (int i = 0; i < letters.length(); i++) {
            char c = letters.charAt(i);
            if (c < 'A' || c > 'Z') break; // 引用形如 A1/AB12，只取字母前缀
            idx = idx * 26 + (c - 'A' + 1);
        }
        return idx - 1;
    }

    private static String csvField(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v + "\"" : v;
    }

    // ---------- 通用 zip/XML 工具 ----------
    private static String entryText(byte[] zip, String name) throws IOException {
        String target = name.endsWith("/") ? name : name + "/";
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName().replace('\\', '/');
                if (n.equals(name) || n.endsWith(target) || n.startsWith(target)) {
                    return readAllUtf8(zis);
                }
            }
        }
        return null;
    }

    /** 按 zip 内路径前缀收集条目名（含子目录下同名，如 worksheets/sheet1.xml…） */
    private static List<String> listEntries(byte[] zip, String prefix) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = e.getName().replace('\\', '/');
                if (n.startsWith(prefix) && !n.endsWith("/")) names.add(n);
            }
        }
        java.util.Collections.sort(names);
        return names;
    }

    private static String readAllUtf8(ZipInputStream zis) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = zis.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > 64 * 1024 * 1024) throw new IOException("文档 XML 过大");
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String clean(String s) {
        // 制表占位 → tab，换行占位 → newline
        s = s.replace('\u0001', '\t').replace('\u0002', '\n');
        // 行内空白折叠为单空格
        StringBuilder sb = new StringBuilder(s.length());
        boolean ws = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t') {
                sb.append(c);
                ws = false;
            } else if (c == ' ' || c == '\r') {
                if (!ws) {
                    sb.append(' ');
                    ws = true;
                }
            } else {
                sb.append(c);
                ws = false;
            }
        }
        return sb.toString();
    }
}
