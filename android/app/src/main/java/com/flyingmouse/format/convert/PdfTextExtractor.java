package com.flyingmouse.format.convert;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * 纯 Java PDF 文本提取（P6 零依赖子集）。
 *
 * 覆盖：对象索引 → Pages/Kids 页序 → 页面资源字体 → /ToUnicode CMap 映射
 * → 内容流 FlateDecode 解压 → Tj/TJ/'/" 文本操作符 + Td/TD/T*、Tm 换行。
 *
 * 局限（与 PC 端 Poppler 的能力差距）：不还原版面坐标与表格结构、不处理 OCR 扫描件、
 * 不解析 Type3/复杂 CJK 编码；无 ToUnicode 的字体按 Latin-1 直读。
 */
public final class PdfTextExtractor {

    private PdfTextExtractor() {
    }

    /** 单字体的解码信息：字符码 → Unicode 映射 + 是否双字节码（Identity-H 等 CID 字体）。 */
    static final class FontInfo {
        final Map<Integer, String> cmap;
        final boolean twoByte;

        FontInfo(Map<Integer, String> cmap, boolean twoByte) {
            this.cmap = cmap;
            this.twoByte = twoByte;
        }
    }

    public static String extractText(byte[] pdf) throws Exception {
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        Map<Integer, String> objs = indexObjects(raw);
        if (objs.isEmpty()) throw new Exception("不是有效的 PDF：未找到任何对象。");
        List<Integer> pages = findPages(objs);
        if (pages.isEmpty()) throw new Exception("PDF 中没有可解析的页面。");
        StringBuilder out = new StringBuilder();
        for (int p : pages) {
            String pageBody = objs.get(p);
            if (pageBody == null) continue;
            Map<String, FontInfo> fonts = fontMaps(objs, pageBody);
            String content = contentOf(objs, pageBody);
            if (content == null) continue;
            out.append(parseContent(content, fonts)).append('\n');
        }
        String text = out.toString().replace("\r\n", "\n").replace("\r", "\n");
        text = text.replaceAll("(?m)[ \t]+\n", "\n").replaceAll("\n{3,}", "\n\n");
        return text.trim();
    }

    // ---------- 对象索引 ----------

    private static Map<Integer, String> indexObjects(String s) {
        Map<Integer, String> map = new LinkedHashMap<>();
        Matcher m = Pattern.compile("(\\d+)\\s+\\d+\\s+obj\\b").matcher(s);
        List<Integer> nums = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        while (m.find()) {
            nums.add(Integer.parseInt(m.group(1)));
            starts.add(m.end());
        }
        for (int i = 0; i < nums.size(); i++) {
            int from = starts.get(i);
            int to = s.indexOf("endobj", from);
            if (to < 0) to = s.length();
            map.put(nums.get(i), s.substring(from, to));
        }
        return map;
    }

    private static List<Integer> findPages(Map<Integer, String> objs) {
        for (Map.Entry<Integer, String> e : objs.entrySet()) {
            if (e.getValue().contains("/Kids")) {
                List<Integer> pages = new ArrayList<>();
                collectKids(objs, e.getValue(), pages, 0);
                if (!pages.isEmpty()) return pages;
            }
        }
        List<Integer> fallback = new ArrayList<>();
        for (Map.Entry<Integer, String> e : new TreeMap<>(objs).entrySet()) {
            if (isPage(e.getValue())) fallback.add(e.getKey());
        }
        return fallback;
    }

    private static void collectKids(Map<Integer, String> objs, String body, List<Integer> out, int depth) {
        if (depth > 8) return;
        String kids = balanced(body, "/Kids");
        if (kids == null) return;
        Matcher m = Pattern.compile("(\\d+)\\s+\\d+\\s+R").matcher(kids);
        while (m.find()) {
            int ref = Integer.parseInt(m.group(1));
            String child = objs.get(ref);
            if (child == null) continue;
            if (isPage(child)) out.add(ref);
            else if (child.contains("/Kids")) collectKids(objs, child, out, depth + 1);
        }
    }

    private static boolean isPage(String body) {
        return body.contains("/Type") && body.matches("(?s).*/Type\\s*/Page(?!s)\\b.*");
    }

    // ---------- 页面资源 / 字体 ----------

    private static String contentOf(Map<Integer, String> objs, String pageBody) {
        String v = valueAfterRef(pageBody, "/Contents");
        if (v == null) return null;
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("(\\d+)\\s+\\d+\\s+R").matcher(v);
        while (m.find()) {
            byte[] data = streamBytes(objs.get(Integer.parseInt(m.group(1))));
            if (data != null) sb.append(new String(data, StandardCharsets.ISO_8859_1)).append('\n');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 页面的 字体名 → 字体信息。 */
    private static Map<String, FontInfo> fontMaps(Map<Integer, String> objs, String pageBody) {
        Map<String, FontInfo> out = new LinkedHashMap<>();
        String res = valueAfterRef(pageBody, "/Resources");
        if (res == null) return out;
        res = res.trim();
        if (!res.startsWith("<<")) {
            Matcher r = Pattern.compile("^(\\d+)\\s+\\d+\\s+R").matcher(res);
            if (!r.find()) return out;
            res = objs.get(Integer.parseInt(r.group(1)));
            if (res == null) return out;
        }
        String fontDict = balanced(res, "/Font");
        if (fontDict == null) return out;
        Matcher fm = Pattern.compile("/([A-Za-z0-9#+._-]+)\\s+(\\d+)\\s+\\d+\\s+R").matcher(fontDict);
        while (fm.find()) {
            String name = fm.group(1);
            String fontBody = objs.get(Integer.parseInt(fm.group(2)));
            if (fontBody == null) continue;
            boolean twoByte = fontBody.contains("/Identity-H") || fontBody.contains("/Identity-V")
                    || (fontBody.contains("/Type0") && !fontBody.contains("/Type1"));
            Map<Integer, String> cmap = toUnicodeMap(objs, fontBody);
            out.put(name, new FontInfo(cmap, twoByte));
        }
        return out;
    }

    private static Map<Integer, String> toUnicodeMap(Map<Integer, String> objs, String fontBody) {
        String v = valueAfterRef(fontBody, "/ToUnicode");
        if (v == null) return null;
        Matcher m = Pattern.compile("(\\d+)\\s+\\d+\\s+R").matcher(v);
        if (!m.find()) return null;
        byte[] cmapBytes = streamBytes(objs.get(Integer.parseInt(m.group(1))));
        if (cmapBytes == null) return null;
        String cmap = new String(cmapBytes, StandardCharsets.ISO_8859_1);
        Map<Integer, String> map = new LinkedHashMap<>();
        Matcher bfchar = Pattern.compile("(?s)beginbfchar(.*?)endbfchar").matcher(cmap);
        while (bfchar.find()) {
            Matcher pair = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").matcher(bfchar.group(1));
            while (pair.find()) {
                map.put(Integer.parseInt(pair.group(1), 16), hexToUtf16(pair.group(2)));
            }
        }
        Matcher bfrange = Pattern.compile("(?s)beginbfrange(.*?)endbfrange").matcher(cmap);
        while (bfrange.find()) {
            Matcher r = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>")
                    .matcher(bfrange.group(1));
            while (r.find()) {
                int lo = Integer.parseInt(r.group(1), 16);
                int hi = Integer.parseInt(r.group(2), 16);
                int dst = Integer.parseInt(r.group(3), 16);
                if (hi - lo > 65535) continue;
                for (int c = lo; c <= hi; c++) {
                    map.put(c, new String(Character.toChars(dst + (c - lo))));
                }
            }
        }
        return map.isEmpty() ? null : map;
    }

    private static String hexToUtf16(String hex) {
        return new String(hexToBytes(hex), StandardCharsets.UTF_16BE);
    }

    /** 取 /Key 之后的值：字典返回 <<...>>，数组返回 [...]，其它返回其后 token。 */
    private static String valueAfterRef(String body, String key) {
        int i = body.indexOf(key);
        if (i < 0) return null;
        int j = i + key.length();
        while (j < body.length() && Character.isWhitespace(body.charAt(j))) j++;
        if (j >= body.length()) return null;
        char c = body.charAt(j);
        if (c == '<' && j + 1 < body.length() && body.charAt(j + 1) == '<') return balanced(body, key);
        if (c == '[') return balanced(body, key);
        Matcher ref = Pattern.compile("^(\\d+)\\s+\\d+\\s+R\\b").matcher(body.substring(j));
        if (ref.find()) return ref.group();
        int k = j;
        while (k < body.length() && !Character.isWhitespace(body.charAt(k)) && body.charAt(k) != '/') k++;
        return body.substring(j, k);
    }

    /** 返回 /key 后第一个平衡的 <<>> 或 [] 内容（含定界符），失败返回 null。 */
    private static String balanced(String body, String key) {
        int i = body.indexOf(key);
        if (i < 0) return null;
        int j = i + key.length();
        while (j < body.length() && Character.isWhitespace(body.charAt(j))) j++;
        if (j >= body.length()) return null;
        if (body.charAt(j) == '<') {
            int depth = 0;
            for (int k = j; k < body.length() - 1; k++) {
                if (body.charAt(k) == '<' && body.charAt(k + 1) == '<') {
                    depth++;
                    k++;
                } else if (body.charAt(k) == '>' && body.charAt(k + 1) == '>') {
                    depth--;
                    k++;
                    if (depth == 0) return body.substring(j, k + 1);
                }
            }
        } else if (body.charAt(j) == '[') {
            int depth = 0;
            for (int k = j; k < body.length(); k++) {
                char ch = body.charAt(k);
                if (ch == '[') depth++;
                else if (ch == ']') {
                    depth--;
                    if (depth == 0) return body.substring(j, k + 1);
                }
            }
        }
        return null;
    }

    // ---------- 流解码 ----------

    private static byte[] streamBytes(String objBody) {
        if (objBody == null) return null;
        int si = objBody.indexOf("stream");
        if (si < 0) return null;
        String dict = objBody.substring(0, si);
        int start = si + 6;
        if (start < objBody.length() && objBody.charAt(start) == '\r') start++;
        if (start < objBody.length() && objBody.charAt(start) == '\n') start++;
        byte[] raw;
        Matcher lm = Pattern.compile("/Length\\s+(\\d+)").matcher(dict);
        if (lm.find()) {
            int len = Integer.parseInt(lm.group(1));
            int end = Math.min(start + len, objBody.length());
            raw = latin1Bytes(objBody.substring(start, end));
        } else {
            int ei = objBody.indexOf("endstream", start);
            if (ei < 0) ei = objBody.length();
            raw = latin1Bytes(objBody.substring(start, ei));
        }
        if (dict.contains("FlateDecode")) {
            try {
                return inflate(raw);
            } catch (Exception e) {
                return raw;
            }
        }
        return raw;
    }

    private static byte[] inflate(byte[] data) throws Exception {
        Inflater inf = new Inflater();
        inf.setInput(data);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length * 3));
        byte[] buf = new byte[8192];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0) {
                if (inf.needsInput() || inf.needsDictionary()) break;
            }
            bos.write(buf, 0, n);
        }
        inf.end();
        return bos.toByteArray();
    }

    // ---------- 内容流解析 ----------

    private static String parseContent(String content, Map<String, FontInfo> fonts) {
        StringBuilder sb = new StringBuilder();
        List<String> stack = new ArrayList<>();
        String curFont = null;
        boolean pendingLine = false;

        int i = 0;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '%') {
                while (i < n && content.charAt(i) != '\n' && content.charAt(i) != '\r') i++;
                continue;
            }
            if (c == '(') {
                int[] end = new int[1];
                stack.add(readLiteral(content, i, end));
                i = end[0];
                continue;
            }
            if (c == '<' && i + 1 < n && content.charAt(i + 1) != '<') {
                int close = content.indexOf('>', i);
                if (close < 0) break;
                String hex = content.substring(i + 1, close).replaceAll("[^0-9A-Fa-f]", "");
                stack.add(new String(hexToBytes(hex), StandardCharsets.ISO_8859_1));
                i = close + 1;
                continue;
            }
            if (c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}') {
                i++;
                continue;
            }
            if (c == '/') {
                int j = i + 1;
                while (j < n && !isDelim(content.charAt(j)) && !Character.isWhitespace(content.charAt(j))) j++;
                stack.add("/" + content.substring(i + 1, j));
                i = j;
                continue;
            }
            if (c == '+' || c == '-' || c == '.' || Character.isDigit(c)) {
                int j = i;
                while (j < n && (Character.isDigit(content.charAt(j)) || content.charAt(j) == '.'
                        || content.charAt(j) == '-' || content.charAt(j) == '+')) j++;
                stack.add(content.substring(i, j));
                i = j;
                continue;
            }
            if (Character.isLetter(c) || c == '\'' || c == '"') {
                int j = i;
                while (j < n && !Character.isWhitespace(content.charAt(j)) && !isDelim(content.charAt(j))) j++;
                String op = content.substring(i, j);
                i = j;
                FontInfo font = fonts == null ? null : fonts.get(curFont);
                if (op.equals("Tf")) {
                    for (int k = stack.size() - 1; k >= 0; k--) {
                        String f = stack.get(k);
                        if (f != null && f.startsWith("/")) {
                            curFont = f.substring(1);
                            break;
                        }
                    }
                } else {
                    pendingLine = applyOp(op, stack, sb, font, pendingLine);
                }
                stack.clear();
                continue;
            }
            i++;
        }
        return sb.toString();
    }

    private static boolean applyOp(String op, List<String> stack, StringBuilder sb,
                                   FontInfo font, boolean pendingLine) {
        switch (op) {
            case "Tj":
            case "'":
            case "\"":
                pendingLine = emit(textArg(stack), sb, font, pendingLine,
                        op.equals("'") || op.equals("\""));
                break;
            case "TJ":
                for (String tok : stack) {
                    if (tok.startsWith("/")) continue;
                    if (isNumber(tok)) {
                        try {
                            if (Double.parseDouble(tok) <= -120) sb.append(' ');
                        } catch (NumberFormatException ignored) {
                        }
                        continue;
                    }
                    pendingLine = emit(tok, sb, font, pendingLine, false);
                }
                break;
            case "Td":
            case "TD":
            case "Tm":
            case "T*":
                pendingLine = true;
                break;
            default:
                break;
        }
        return pendingLine;
    }

    private static boolean emit(String raw, StringBuilder sb, FontInfo font,
                                boolean pendingLine, boolean forceNewline) {
        if (raw == null || raw.isEmpty()) return pendingLine;
        String decoded = decode(raw, font);
        if (decoded.isEmpty()) return pendingLine;
        if ((pendingLine || forceNewline) && sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        sb.append(decoded);
        return false;
    }

    private static String decode(String raw, FontInfo font) {
        byte[] b = latin1Bytes(raw);
        if (font == null || font.cmap == null || font.cmap.isEmpty()) {
            return new String(b, StandardCharsets.ISO_8859_1);
        }
        StringBuilder sb = new StringBuilder();
        if (font.twoByte) {
            for (int i = 0; i + 1 < b.length; i += 2) {
                int code = ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
                String v = font.cmap.get(code);
                if (v != null) sb.append(v);
            }
        } else {
            for (byte value : b) {
                String v = font.cmap.get(value & 0xFF);
                sb.append(v != null ? v : new String(new byte[]{value}, StandardCharsets.ISO_8859_1));
            }
        }
        return sb.toString();
    }

    private static String textArg(List<String> stack) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (!isNumber(stack.get(i)) && !stack.get(i).startsWith("/")) return stack.get(i);
        }
        return null;
    }

    private static boolean isNumber(String s) {
        if (s == null || s.isEmpty()) return false;
        char c = s.charAt(0);
        return Character.isDigit(c) || c == '-' || c == '+' || c == '.';
    }

    private static boolean isDelim(char c) {
        return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}';
    }

    /** 读取 (...) 字面量字符串，处理转义与嵌套括号；end[0] 为结束位置。 */
    private static String readLiteral(String s, int start, int[] end) {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
                if (i >= s.length()) break;
                char e = s.charAt(i);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '(': sb.append('('); break;
                    case ')': sb.append(')'); break;
                    case '\\': sb.append('\\'); break;
                    default:
                        if (e >= '0' && e <= '7') {
                            int val = 0;
                            int cnt = 0;
                            while (cnt < 3 && i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '7') {
                                val = val * 8 + (s.charAt(i) - '0');
                                i++;
                                cnt++;
                            }
                            i--;
                            sb.append((char) val);
                        } else {
                            sb.append(e);
                        }
                }
                i++;
                continue;
            }
            if (c == '(') depth++;
            if (c == ')') {
                depth--;
                if (depth == 0) break;
            }
            sb.append(c);
            i++;
        }
        end[0] = i + 1;
        return sb.toString();
    }

    // ---------- 小工具 ----------

    private static byte[] latin1Bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
