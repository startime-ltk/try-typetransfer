package com.flyingmouse.format.convert;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

/**
 * 纯 Java PDF 版面提取（P6 后半：PDF→xlsx/docx 智能结构还原的底层引擎）。
 *
 * 与 {@link PdfTextExtractor}（P6 文本子集）的分工：
 * <ul>
 *   <li>PdfTextExtractor 只输出阅读顺序的纯文本，不还原坐标，用于 PDF→txt/md；</li>
 *   <li>本类输出「带坐标的文本片段 + 矢量线段」，用于推断段落与表格结构。</li>
 * </ul>
 *
 * 覆盖范围（零依赖，不引 PDFBox / iText / Poppler）：
 * 对象索引（含 /ObjStm 对象流）→ Pages/Kids 页序 → 页面资源字体（简单字体 + Type0/CID）
 * → /Widths、/W、/DW 字宽 → /ToUnicode 映射 → 内容流 FlateDecode 解压
 * → 图形状态 q/Q/cm；文本矩阵 BT/Tm/Td/TD/T*、Tf/Tz/Tc/Tw/TL/Tr、文本显示 Tj/TJ
 * → 路径 re/m/l/c/v/y 与描边 S/s/B/b（表格框线）。
 *
 * 坐标系约定：输出的 Run/Rule 坐标已归一化为「展示坐标」——左上角为原点、y 轴向下，
 * 且已按 /Rotate 旋转到阅读方向，调用方（PdfStructureTool）无需再关心旋转与翻转。
 *
 * 已知局限（在 README 中同步声明）：不解析 Form XObject 内嵌内容、不处理 Type3 字体、
 * 不还原图片/矢量图形对象本身、加密 PDF 需先解密。
 */
public final class PdfLayoutExtractor {

    /** 页面处理上限：超出的页面直接跳过（避免超大文件拖垮移动端）。 */
    public static final int MAX_PAGES = 200;

    private PdfLayoutExtractor() {
    }

    /** 一段带版面的文本（一次 Tj/TJ 显示操作对应一个 Run）。 */
    public static final class Run {
        public final float x;      // 起点 x（展示坐标）
        public final float y;      // 基线 y（展示坐标，向下为正）
        public final float width;  // 文本横向长度
        public final float size;   // 有效字号（pt）
        public final String text;

        Run(float x, float y, float width, float size, String text) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.size = size;
            this.text = text;
        }
    }

    /** 一条矢量线段（表格框线候选）。 */
    public static final class Rule {
        public final float x0;
        public final float y0;
        public final float x1;
        public final float y1;
        public final boolean horizontal;

        Rule(float x0, float y0, float x1, float y1, boolean horizontal) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
            this.horizontal = horizontal;
        }

        public float length() {
            return horizontal ? Math.abs(x1 - x0) : Math.abs(y1 - y0);
        }
    }

    /** 单页版面。 */
    public static final class Page {
        public final int number;
        public final float width;
        public final float height;
        public final int rotation;
        public final List<Run> runs = new ArrayList<>();
        public final List<Rule> rules = new ArrayList<>();

        Page(int number, float width, float height, int rotation) {
            this.number = number;
            this.width = width;
            this.height = height;
            this.rotation = rotation;
        }
    }

    /** 提取整个 PDF 的版面信息；无文本层时返回的 Page 里 runs 为空。 */
    public static List<Page> extract(byte[] pdf) throws Exception {
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        Map<Integer, String> objs = indexObjects(raw);
        if (objs.isEmpty()) throw new Exception("不是有效的 PDF：未找到任何对象。");
        List<Integer> pages = findPages(objs);
        if (pages.isEmpty()) throw new Exception("PDF 中没有可解析的页面。");
        List<Page> out = new ArrayList<>();
        int index = 0;
        for (int ref : pages) {
            index++;
            if (out.size() >= MAX_PAGES) break;
            String pageBody = objs.get(ref);
            if (pageBody == null) continue;
            try {
                Page page = parsePage(objs, pageBody, index);
                if (page != null) out.add(page);
            } catch (Throwable ignored) {
                // 单页解析异常不影响其余页面（与批量转换的失败隔离口径一致）
            }
        }
        return out;
    }

    // ================= 对象索引 =================

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
        expandObjectStreams(s, map);
        return map;
    }

    /** 解析 /Type /ObjStm 压缩对象流：把内部对象补进索引（现代生成器常用）。 */
    private static void expandObjectStreams(String s, Map<Integer, String> map) {
        List<Integer> streams = new ArrayList<>();
        for (Map.Entry<Integer, String> e : map.entrySet()) {
            String body = e.getValue();
            if (body.startsWith("<<") && body.contains("/ObjStm") && body.contains("stream")) {
                streams.add(e.getKey());
            }
        }
        for (int ref : streams) {
            try {
                byte[] data = streamBytes(map.get(ref));
                if (data == null) continue;
                String text = new String(data, StandardCharsets.ISO_8859_1);
                String head = text.length() > 4096 ? text.substring(0, 4096) : text;
                Matcher nm = Pattern.compile("^\\s*(\\d+)\\s+(\\d+)").matcher(head);
                if (!nm.find()) continue;
                int count = Integer.parseInt(nm.group(1));
                int first = Integer.parseInt(nm.group(2));
                Matcher pairs = Pattern.compile("(\\d+)\\s+(\\d+)").matcher(head.substring(nm.end()));
                List<int[]> list = new ArrayList<>();
                for (int k = 0; k < count && pairs.find(); k++) {
                    list.add(new int[]{Integer.parseInt(pairs.group(1)), Integer.parseInt(pairs.group(2))});
                }
                for (int i = 0; i < list.size(); i++) {
                    int from = first + list.get(i)[1];
                    int to = i + 1 < list.size() ? first + list.get(i + 1)[1] : text.length();
                    if (from < 0 || to > text.length() || from >= to) continue;
                    map.putIfAbsent(list.get(i)[0], text.substring(from, to));
                }
            } catch (Throwable ignored) {
            }
        }
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

    // ================= 页面解析 =================

    private static Page parsePage(Map<Integer, String> objs, String pageBody, int number) {
        float[] box = pageBox(objs, pageBody);
        int rotation = intAfter(objs, pageBody, "/Rotate", 0);
        boolean landscape = rotation == 90 || rotation == 270;
        float userWidth = landscape ? box[3] - box[1] : box[2] - box[0];
        float userHeight = landscape ? box[2] - box[0] : box[3] - box[1];
        Page page = new Page(number, userWidth, userHeight, rotation);

        Map<String, FontInfo> fonts = fontMaps(objs, pageBody);
        String content = contentOf(objs, pageBody);
        if (content == null) return page;

        List<Tok> tokens = new Lexer(content).tokens();
        // 第一遍只探测坐标朝向（内容流通常按阅读顺序自上而下绘制），第二遍按正确朝向提取
        ContentParser probe = new ContentParser(fonts, tokens, box, 0, false, true);
        probe.run(null);
        boolean yDown = probe.yDownDecided();
        ContentParser parser = new ContentParser(fonts, tokens, box, rotation, yDown, false);
        parser.run(page);
        return page;
    }

    // ================= 内容流解释 =================

    private static final class ContentParser {
        private final Map<String, FontInfo> fonts;
        private final List<Tok> tokens;
        private final float[] box;
        private final int rotation;
        private final boolean yDown;
        private final boolean probeOnly;
        private final List<Float> probeY = new ArrayList<>();

        private float[] ctm = {1, 0, 0, 1, 0, 0};
        private float fontSize = 12;
        private float charSpacing;
        private float wordSpacing;
        private float horizScale = 1;
        private float leading;
        private String fontName;

        private float[] tm = {1, 0, 0, 1, 0, 0};

        private final Deque<Object[]> gsStack = new ArrayDeque<>();
        private final List<float[]> path = new ArrayList<>();
        private Page page;

        ContentParser(Map<String, FontInfo> fonts, List<Tok> tokens, float[] box, int rotation,
                      boolean yDown, boolean probeOnly) {
            this.fonts = fonts;
            this.tokens = tokens;
            this.box = box;
            this.rotation = rotation;
            this.yDown = yDown;
            this.probeOnly = probeOnly;
        }

        /** 探测结论：内容流 y 递增方向是否与展示方向一致（true = 原始 y 向下增）。 */
        boolean yDownDecided() {
            int increase = 0;
            int decrease = 0;
            for (int i = 1; i < probeY.size(); i++) {
                float d = probeY.get(i) - probeY.get(i - 1);
                if (d > 1.5f) increase++;
                else if (d < -1.5f) decrease++;
            }
            return decrease > increase;
        }


        void run(Page page) {
            this.page = page;
            List<Tok> operands = new ArrayList<>();
            for (Tok t : tokens) {
                if (t.kind == Tok.OP) {
                    try {
                        apply(t.op, operands);
                    } catch (Throwable ignored) {
                    }
                    operands.clear();
                } else {
                    operands.add(t);
                }
            }
        }

        private void apply(String op, List<Tok> args) {
            switch (op) {
                case "q":
                    gsStack.push(new Object[]{ctm.clone(), fontSize, charSpacing, wordSpacing, horizScale, leading, fontName});
                    break;
                case "Q":
                    if (!gsStack.isEmpty()) restore(gsStack.pop());
                    break;
                case "cm":
                    if (countNum(args) >= 6) {
                        float[] m = lastSix(args);
                        concat(m);
                    }
                    break;
                case "BT":
                    tm = new float[]{1, 0, 0, 1, 0, 0};
                    break;
                case "Tf":
                    fontSize = lastNum(args, fontSize);
                    fontName = lastName(args);
                    break;
                case "Tc":
                    charSpacing = lastNum(args, charSpacing);
                    break;
                case "Tw":
                    wordSpacing = lastNum(args, wordSpacing);
                    break;
                case "Tz":
                    horizScale = lastNum(args, 100f) / 100f;
                    break;
                case "TL":
                    leading = lastNum(args, leading);
                    break;
                case "Td":
                case "TD": {
                    float ty = lastNum(args, 0);
                    float tx = secondLastNum(args, 0);
                    if ("TD".equals(op)) leading = -ty;
                    translateLine(tx, ty);
                    break;
                }
                case "Tm": {
                    if (countNum(args) >= 6) tm = lastSix(args);
                    break;
                }
                case "T*":
                    translateLine(0, -leading);
                    break;
                case "Tj":
                case "'":
                case "\"":
                    if ("'".equals(op) || "\"".equals(op)) {
                        if ("\"".equals(op)) {
                            float ws = lastNum(args, wordSpacing);
                            float cs = secondLastNum(args, charSpacing);
                            wordSpacing = ws;
                            charSpacing = cs;
                        }
                        translateLine(0, -leading);
                    }
                    show(lastString(args));
                    break;
                case "TJ":
                    for (Tok item : arrayItems(args)) {
                        if (item.kind == Tok.NUM) {
                            // 负值右移（PDF 规范：tx = -n/1000 * Tfs * Th）
                            float shift = -item.num / 1000f * fontSize * horizScale;
                            advance(shift);
                        } else if (item.kind == Tok.STR) {
                            show(item.text);
                        }
                    }
                    break;
                case "re": {
                    if (countNum(args) >= 4) {
                        float[] a = lastFour(args);
                        pathRect(a[0], a[1], a[2], a[3]);
                    }
                    break;
                }
                case "m": {
                    if (countNum(args) >= 2) {
                        float[] a = lastTwo(args);
                        pathMove(a[0], a[1]);
                    }
                    break;
                }
                case "l": {
                    if (countNum(args) >= 2) {
                        float[] a = lastTwo(args);
                        pathLine(a[0], a[1]);
                    }
                    break;
                }
                case "c":
                    if (countNum(args) >= 6) {
                        float[] a = lastSixPoint(args);
                        pathLine(a[4], a[5]);
                    }
                    break;
                case "v":
                case "y":
                    if (countNum(args) >= 4) {
                        float[] a = lastFour(args);
                        pathLine(a[2], a[3]);
                    }
                    break;
                case "h":
                    if (!path.isEmpty()) {
                        float[] first = path.get(0);
                        float[] last = path.get(path.size() - 1);
                        pathLine(first[0], first[1]);
                        if (Math.abs(last[0] - first[0]) < 0.01f && Math.abs(last[1] - first[1]) < 0.01f) {
                            path.remove(path.size() - 1);
                        }
                    }
                    break;
                case "S":
                case "s":
                case "B":
                case "B*":
                case "b":
                case "b*":
                    paint(true);
                    break;
                case "f":
                case "F":
                case "f*":
                case "n":
                case "W":
                case "W*":
                    paint(false);
                    break;
                default:
                    break;
            }
        }

        private void restore(Object[] st) {
            ctm = (float[]) st[0];
            fontSize = (Float) st[1];
            charSpacing = (Float) st[2];
            wordSpacing = (Float) st[3];
            horizScale = (Float) st[4];
            leading = (Float) st[5];
            fontName = (String) st[6];
        }

        /** ctm = m × ctm（PDF：新矩阵左乘当前） */
        private void concat(float[] m) {
            float a = m[0] * ctm[0] + m[1] * ctm[2];
            float b = m[0] * ctm[1] + m[1] * ctm[3];
            float c = m[2] * ctm[0] + m[3] * ctm[2];
            float d = m[2] * ctm[1] + m[3] * ctm[3];
            float e = m[4] * ctm[0] + m[5] * ctm[2] + ctm[4];
            float f = m[4] * ctm[1] + m[5] * ctm[3] + ctm[5];
            ctm = new float[]{a, b, c, d, e, f};
        }

        /** tm = translate(tx,ty) × tm */
        private void translateLine(float tx, float ty) {
            float e = tx * tm[0] + ty * tm[2] + tm[4];
            float f = tx * tm[1] + ty * tm[3] + tm[5];
            tm = new float[]{tm[0], tm[1], tm[2], tm[3], e, f};
        }

        /** tm = translate(tx,0) × tm（文本推进，tx 为未缩放文本空间长度） */
        private void advance(float tx) {
            tm = new float[]{tm[0], tm[1], tm[2], tm[3], tm[4] + tx * tm[0], tm[5] + tx * tm[1]};
        }

        private void show(String raw) {
            if (raw == null || raw.isEmpty()) return;
            byte[] bytes = raw.getBytes(StandardCharsets.ISO_8859_1);
            FontInfo font = fonts == null ? null : fonts.get(fontName);
            float[] m = mul(tm, ctm);
            float size = fontSize * (float) Math.hypot(m[2], m[3]);
            float scaleX = (float) Math.hypot(m[0], m[1]);
            if (size <= 0.01f) size = fontSize;
            float[] pt = toDisplay(m[4], m[5]);
            StringBuilder sb = new StringBuilder();
            float advance = 0;
            if (font != null && font.twoByte) {
                for (int i = 0; i + 1 < bytes.length; i += 2) {
                    int code = ((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF);
                    String u = font.decode(code);
                    if (u == null || u.isEmpty()) continue;
                    sb.append(u);
                    advance += (glyphAdvance(font, code, u) / 1000f * fontSize + charSpacing) * horizScale;
                }
            } else {
                for (byte value : bytes) {
                    int code = value & 0xFF;
                    String u = font == null ? null : font.decode(code);
                    if (u == null || u.isEmpty()) continue;
                    sb.append(u);
                    advance += (glyphAdvance(font, code, u) / 1000f * fontSize + charSpacing) * horizScale;
                    if (code == 32) advance += wordSpacing * horizScale;
                }
            }
            if (sb.length() > 0) {
                if (probeOnly) {
                    probeY.add(pt[1]);
                } else if (page != null && (rotation != 0 || Math.abs(m[0]) >= 0.5f)) {
                    // 竖排文本（内容流内纵排且页面未旋转）不参与横排结构推断
                    page.runs.add(new Run(pt[0], pt[1], advance * scaleX, size, sb.toString()));
                }
            }
            advance(advance);
        }

        private float glyphAdvance(FontInfo font, int code, String decoded) {
            if (font == null) return 500f;
            return font.widthOf(code, decoded);
        }

        private void paint(boolean stroked) {
            if (page == null) {
                path.clear();
                return;
            }
            if (stroked && !path.isEmpty()) {
                for (int i = 0; i + 1 < path.size(); i++) {
                    float[] p = path.get(i);
                    float[] q = path.get(i + 1);
                    float[] pd = transform(p[0], p[1]);
                    float[] qd = transform(q[0], q[1]);
                    float[] pa = toDisplay(pd[0], pd[1]);
                    float[] pb = toDisplay(qd[0], qd[1]);
                    float dx = Math.abs(pb[0] - pa[0]);
                    float dy = Math.abs(pb[1] - pa[1]);
                    if (dy <= 1.2f && dx >= 8f) {
                        page.rules.add(new Rule(Math.min(pa[0], pb[0]), pa[1], Math.max(pa[0], pb[0]), pa[1], true));
                    } else if (dx <= 1.2f && dy >= 6f) {
                        page.rules.add(new Rule(pa[0], Math.min(pa[1], pb[1]), pa[0], Math.max(pa[1], pb[1]), false));
                    }
                }
            }
            path.clear();
        }

        private void pathRect(float x, float y, float w, float h) {
            pathMove(x, y);
            pathLine(x + w, y);
            pathLine(x + w, y + h);
            pathLine(x, y + h);
            pathLine(x, y);
        }

        private void pathMove(float x, float y) {
            path.clear();
            path.add(new float[]{x, y});
        }

        private void pathLine(float x, float y) {
            if (path.isEmpty()) path.add(new float[]{x, y});
            else path.add(new float[]{x, y});
        }

        private float[] transform(float x, float y) {
            return new float[]{x * ctm[0] + y * ctm[2] + ctm[4], x * ctm[1] + y * ctm[3] + ctm[5]};
        }

        /** 用户坐标 → 展示坐标（左上原点、y 向下、已按 /Rotate 旋转）。 */
        private float[] toDisplay(float x, float y) {
            float w = box[2] - box[0];
            float h = box[3] - box[1];
            float v = yDown ? (y - box[1]) : (box[3] - y); // v：自上而下
            float u = x - box[0];
            switch (rotation) {
                case 90:
                    return new float[]{h - v, u};
                case 180:
                    return new float[]{w - u, v};
                case 270:
                    return new float[]{v, w - u};
                default:
                    return new float[]{u, v};
            }
        }
    }

    private static float[] mul(float[] m, float[] n) {
        return new float[]{
                m[0] * n[0] + m[1] * n[2],
                m[0] * n[1] + m[1] * n[3],
                m[2] * n[0] + m[3] * n[2],
                m[2] * n[1] + m[3] * n[3],
                m[4] * n[0] + m[5] * n[2] + n[4],
                m[4] * n[1] + m[5] * n[3] + n[5]};
    }

    // ================= 页面框 / 旋转 =================

    private static float[] pageBox(Map<Integer, String> objs, String pageBody) {
        float[] box = parseBox(valueAfterRef(pageBody, "/CropBox"));
        if (box == null) box = parseBox(valueAfterRef(pageBody, "/MediaBox"));
        if (box == null) {
            String parent = valueAfterRef(pageBody, "/Parent");
            if (parent != null) {
                Matcher m = Pattern.compile("(\\d+)\\s+\\d+\\s+R").matcher(parent);
                if (m.find()) {
                    String pb = objs.get(Integer.parseInt(m.group(1)));
                    if (pb != null) {
                        box = parseBox(valueAfterRef(pb, "/CropBox"));
                        if (box == null) box = parseBox(valueAfterRef(pb, "/MediaBox"));
                    }
                }
            }
        }
        if (box == null || box[2] - box[0] < 10 || box[3] - box[1] < 10) {
            box = new float[]{0, 0, 595, 842};
        }
        return box;
    }

    private static float[] parseBox(String v) {
        if (v == null) return null;
        if (!v.startsWith("[")) {
            Matcher r = Pattern.compile("^\\s*(\\d+)\\s+\\d+\\s+R").matcher(v);
            if (!r.find()) return null;
            v = r.group();
            return null;
        }
        List<Float> nums = new ArrayList<>();
        Matcher m = Pattern.compile("-?\\d*\\.?\\d+").matcher(v);
        while (m.find() && nums.size() < 4) {
            try {
                nums.add(Float.parseFloat(m.group()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (nums.size() < 4) return null;
        return new float[]{nums.get(0), nums.get(1), nums.get(2), nums.get(3)};
    }

    private static int intAfter(Map<Integer, String> objs, String body, String key, int fallback) {
        String v = valueAfterRef(body, key);
        if (v == null) return fallback;
        Matcher m = Pattern.compile("-?\\d+").matcher(v);
        if (!m.find()) return fallback;
        try {
            return Integer.parseInt(m.group());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ================= 资源 / 字体 =================

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

    private static Map<String, FontInfo> fontMaps(Map<Integer, String> objs, String pageBody) {
        Map<String, FontInfo> out = new LinkedHashMap<>();
        String res = resourceDict(objs, pageBody, 0);
        if (res == null) return out;
        String fontDict = balanced(res, "/Font");
        if (fontDict == null) {
            // /Font 也可能是间接引用（如 /Font 1 0 R，ReportLab 常用写法）
            String ref = valueAfterRef(res, "/Font");
            if (ref != null) {
                Matcher fr = Pattern.compile("^(\\d+)\\s+\\d+\\s+R").matcher(ref.trim());
                if (fr.find()) fontDict = objs.get(Integer.parseInt(fr.group(1)));
            }
        }
        if (fontDict == null) return out;
        Matcher fm = Pattern.compile("/([A-Za-z0-9#+._-]+)\\s+(\\d+)\\s+\\d+\\s+R").matcher(fontDict);
        while (fm.find()) {
            String name = fm.group(1);
            String fontBody = objs.get(Integer.parseInt(fm.group(2)));
            if (fontBody == null) continue;
            out.put(name, readFont(objs, fontBody));
        }
        return out;
    }

    /** 取页面（或祖先 Pages 节点）的 /Resources 字典，最多向上找 4 层。 */
    private static String resourceDict(Map<Integer, String> objs, String body, int depth) {
        if (depth > 4) return null;
        String res = valueAfterRef(body, "/Resources");
        if (res == null) return null;
        res = res.trim();
        if (res.startsWith("<<")) return res;
        Matcher r = Pattern.compile("^(\\d+)\\s+\\d+\\s+R").matcher(res);
        if (r.find()) {
            String target = objs.get(Integer.parseInt(r.group(1)));
            if (target != null) return target;
        }
        Matcher p = Pattern.compile("/Parent\\s+(\\d+)\\s+\\d+\\s+R").matcher(body);
        if (p.find()) {
            String parent = objs.get(Integer.parseInt(p.group(1)));
            if (parent != null) return resourceDict(objs, parent, depth + 1);
        }
        return null;
    }

    private static FontInfo readFont(Map<Integer, String> objs, String body) {
        FontInfo info = new FontInfo();
        boolean type0 = body.contains("/Type0") || body.contains("/Identity-H") || body.contains("/Identity-V");
        info.twoByte = type0;
        info.cmap = toUnicodeMap(objs, body);
        if (type0) {
            String df = valueAfterRef(body, "/DescendantFonts");
            String desc = null;
            if (df != null) {
                Matcher m = Pattern.compile("(\\d+)\\s+\\d+\\s+R").matcher(df);
                if (m.find()) desc = objs.get(Integer.parseInt(m.group(1)));
            }
            if (desc != null) readCidWidths(desc, info);
            else readCidWidths(df, info);
        } else {
            readSimpleWidths(body, info);
        }
        return info;
    }

    private static void readSimpleWidths(String body, FontInfo info) {
        Matcher mw = Pattern.compile("/MissingWidth\\s+(-?\\d*\\.?\\d+)").matcher(body);
        if (mw.find()) info.defaultWidth = parseFloat(mw.group(1), 0f);
        Matcher fc = Pattern.compile("/FirstChar\\s+(\\d+)").matcher(body);
        int first = fc.find() ? (int) parseFloat(fc.group(1), 0f) : 0;
        String widths = balanced(body, "/Widths");
        if (widths == null) return;
        Matcher m = Pattern.compile("-?\\d*\\.?\\d+").matcher(widths);
        int code = first;
        while (m.find()) {
            info.widths.put(code++, parseFloat(m.group(), 500f));
        }
    }

    private static void readCidWidths(String desc, FontInfo info) {
        Matcher dw = Pattern.compile("/DW\\s+(-?\\d*\\.?\\d+)").matcher(desc);
        if (dw.find()) info.defaultWidth = parseFloat(dw.group(1), 1000f);
        else info.defaultWidth = 1000f;
        String w = balanced(desc, "/W");
        if (w == null) return;
        List<String> parts = new ArrayList<>();
        Matcher m = Pattern.compile("\\[|\\]|-?\\d*\\.?\\d+").matcher(w);
        while (m.find()) parts.add(m.group());
        int i = 0;
        while (i < parts.size()) {
            String tok = parts.get(i);
            if ("[".equals(tok) || "]".equals(tok)) {
                i++;
                continue;
            }
            int firstCode = (int) parseFloat(tok, 0f);
            if (i + 1 < parts.size() && "[".equals(parts.get(i + 1))) {
                int k = i + 2;
                int code = firstCode;
                while (k < parts.size() && !"]".equals(parts.get(k))) {
                    info.widths.put(code++, parseFloat(parts.get(k), 1000f));
                    k++;
                }
                i = k + 1;
            } else if (i + 2 < parts.size()) {
                int lastCode = (int) parseFloat(parts.get(i + 1), 0f);
                float width = parseFloat(parts.get(i + 2), 1000f);
                if (lastCode - firstCode > 65535) lastCode = firstCode;
                for (int code = firstCode; code <= lastCode; code++) info.widths.put(code, width);
                i += 3;
            } else {
                i++;
            }
        }
    }

    private static Map<Integer, String> toUnicodeMap(Map<Integer, String> objs, String fontBody) {
        String v = valueAfterRef(fontBody, "/ToUnicode");
        if (v == null) return null;
        Matcher m = Pattern.compile("(\\d+)\\s+\\d+\\s+R").matcher(v);
        if (!m.find()) return null;
        byte[] cmapBytes = streamBytes(objs.get(Integer.parseInt(m.group(1))));
        if (cmapBytes == null) return null;
        String cmap = new String(cmapBytes, StandardCharsets.ISO_8859_1);
        Map<Integer, String> map = new HashMap<>();
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

    /** 单字体的解码与字宽信息。 */
    private static final class FontInfo {
        Map<Integer, String> cmap;
        final Map<Integer, Float> widths = new HashMap<>();
        boolean twoByte;
        float defaultWidth = 500f;

        String decode(int code) {
            if (cmap != null) {
                String v = cmap.get(code);
                if (v != null) return v;
                if (!twoByte && code < 32) return "";
            }
            if (twoByte) {
                // 无 ToUnicode 的 CID 字体：UniGB-UCS2-H / UniJIS-UCS2-H / Identity-H(常为 UCS-2) 均按码位还原
                if (code == 0 || code == 0xFFFD) return "";
                return new String(Character.toChars(code));
            }
            return new String(new byte[]{(byte) code}, StandardCharsets.ISO_8859_1);
        }

        float widthOf(int code, String decoded) {
            Float w = widths.get(code);
            if (w != null) return w;
            if (!widths.isEmpty()) return defaultWidth;
            return isWide(decoded) ? 1000f : 500f;
        }

        private static boolean isWide(String s) {
            if (s == null || s.isEmpty()) return false;
            int cp = s.codePointAt(0);
            return cp >= 0x1100 && (cp <= 0x115F || cp == 0x2329 || cp == 0x232A
                    || (cp >= 0x2E80 && cp <= 0xA4CF)
                    || (cp >= 0xAC00 && cp <= 0xD7A3)
                    || (cp >= 0xF900 && cp <= 0xFAFF)
                    || (cp >= 0xFE30 && cp <= 0xFE6F)
                    || (cp >= 0xFF00 && cp <= 0xFF60)
                    || (cp >= 0xFFE0 && cp <= 0xFFE6)
                    || (cp >= 0x20000 && cp <= 0x3FFFD));
        }
    }

    // ================= 流解码 =================

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
        return applyFilters(dict, raw);
    }

    /** 按 /Filter 顺序逐层解码（支持 ASCII85 / ASCIIHex / Flate / RunLength，覆盖 ReportLab、Ghostscript 等常见输出）。 */
    private static byte[] applyFilters(String dict, byte[] data) {
        List<String> filters = new ArrayList<>();
        Matcher fm = Pattern.compile("/(ASCII85Decode|ASCIIHexDecode|FlateDecode|RunLengthDecode|LZWDecode)")
                .matcher(dict);
        while (fm.find()) filters.add(fm.group(1));
        byte[] out = data;
        for (String f : filters) {
            try {
                if ("ASCII85Decode".equals(f)) out = ascii85(out);
                else if ("ASCIIHexDecode".equals(f)) out = asciiHex(out);
                else if ("FlateDecode".equals(f)) out = inflate(out);
                else if ("RunLengthDecode".equals(f)) out = runLength(out);
                else break;
            } catch (Exception e) {
                return out;
            }
        }
        return out;
    }

    private static byte[] ascii85(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, data.length));
        long tuple = 0;
        int count = 0;
        for (byte value : data) {
            int c = value & 0xFF;
            if (c <= 32) continue;
            if (c == '~') break;
            if (c == 'z' && count == 0) {
                bos.write(0);
                bos.write(0);
                bos.write(0);
                bos.write(0);
                continue;
            }
            if (c < '!' || c > 'u') continue;
            tuple = tuple * 85 + (c - '!');
            if (++count == 5) {
                for (int k = 3; k >= 0; k--) bos.write((int) ((tuple >>> (8 * k)) & 0xFF));
                tuple = 0;
                count = 0;
            }
        }
        if (count > 1) {
            for (int k = count; k < 5; k++) tuple = tuple * 85 + 84;
            long value = tuple;
            for (int k = 0; k < count - 1; k++) {
                bos.write((int) ((value >>> (8 * (3 - k))) & 0xFF));
            }
        }
        return bos.toByteArray();
    }

    private static byte[] asciiHex(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(32, data.length / 2));
        int hi = -1;
        for (byte value : data) {
            int c = value & 0xFF;
            if (c == '>') break;
            int d = Character.digit((char) c, 16);
            if (d < 0) continue;
            if (hi < 0) hi = d;
            else {
                bos.write((hi << 4) | d);
                hi = -1;
            }
        }
        if (hi >= 0) bos.write(hi << 4);
        return bos.toByteArray();
    }

    private static byte[] runLength(byte[] data) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(32, data.length));
        int i = 0;
        while (i < data.length) {
            int len = data[i++] & 0xFF;
            if (len == 128) break;
            if (len < 128) {
                for (int k = 0; k <= len && i < data.length; k++) bos.write(data[i++]);
            } else {
                if (i >= data.length) break;
                byte b = data[i++];
                for (int k = 0; k < 257 - len; k++) bos.write(b);
            }
        }
        return bos.toByteArray();
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

    // ================= 字典取值小工具 =================

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

    // ================= 操作符行列式取值 =================

    private static int countNum(List<Tok> args) {
        int n = 0;
        for (Tok t : args) if (t.kind == Tok.NUM) n++;
        return n;
    }

    private static float[] lastSix(List<Tok> args) {
        List<Float> nums = lastNums(args);
        int n = nums.size();
        return new float[]{nums.get(n - 6), nums.get(n - 5), nums.get(n - 4), nums.get(n - 3), nums.get(n - 2), nums.get(n - 1)};
    }

    private static float[] lastSixPoint(List<Tok> args) {
        return lastSix(args);
    }

    private static float[] lastFour(List<Tok> args) {
        List<Float> nums = lastNums(args);
        int n = nums.size();
        return new float[]{nums.get(n - 4), nums.get(n - 3), nums.get(n - 2), nums.get(n - 1)};
    }

    private static float[] lastTwo(List<Tok> args) {
        List<Float> nums = lastNums(args);
        int n = nums.size();
        return new float[]{nums.get(n - 2), nums.get(n - 1)};
    }

    private static List<Float> lastNums(List<Tok> args) {
        List<Float> nums = new ArrayList<>();
        for (Tok t : args) if (t.kind == Tok.NUM) nums.add(t.num);
        return nums;
    }

    private static float lastNum(List<Tok> args, float fallback) {
        for (int i = args.size() - 1; i >= 0; i--) {
            if (args.get(i).kind == Tok.NUM) return args.get(i).num;
        }
        return fallback;
    }

    private static float secondLastNum(List<Tok> args, float fallback) {
        int seen = 0;
        for (int i = args.size() - 1; i >= 0; i--) {
            if (args.get(i).kind == Tok.NUM) {
                seen++;
                if (seen == 2) return args.get(i).num;
            }
        }
        return fallback;
    }

    private static String lastName(List<Tok> args) {
        for (int i = args.size() - 1; i >= 0; i--) {
            if (args.get(i).kind == Tok.NAME) return args.get(i).text;
        }
        return null;
    }

    private static String lastString(List<Tok> args) {
        for (int i = args.size() - 1; i >= 0; i--) {
            if (args.get(i).kind == Tok.STR) return args.get(i).text;
        }
        return null;
    }

    private static List<Tok> arrayItems(List<Tok> args) {
        List<Tok> items = new ArrayList<>();
        for (Tok t : args) {
            if (t.kind == Tok.ARR && t.items != null) items.addAll(t.items);
        }
        return items;
    }

    private static float parseFloat(String s, float fallback) {
        try {
            return Float.parseFloat(s);
        } catch (Exception e) {
            return fallback;
        }
    }

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

    // ================= 词法分析 =================

    /** 内容流 token：数值 / 字符串 / 名称 / 数组 / 操作符 / 字典（忽略内容）。 */
    private static final class Tok {
        static final int NUM = 0;
        static final int STR = 1;
        static final int NAME = 2;
        static final int ARR = 3;
        static final int OP = 4;

        final int kind;
        final float num;
        final String text;
        final String op;
        final List<Tok> items;

        private Tok(int kind, float num, String text, String op, List<Tok> items) {
            this.kind = kind;
            this.num = num;
            this.text = text;
            this.op = op;
            this.items = items;
        }

        static Tok of(float v) {
            return new Tok(NUM, v, null, null, null);
        }

        static Tok str(String s) {
            return new Tok(STR, 0, s, null, null);
        }

        static Tok name(String s) {
            return new Tok(NAME, 0, s, null, null);
        }

        static Tok op(String s) {
            return new Tok(OP, 0, null, s, null);
        }

        static Tok arr(List<Tok> items) {
            return new Tok(ARR, 0, null, null, items);
        }
    }

    /** 内容流词法分析器（含内联图像 BI…EI 跳过）。 */
    private static final class Lexer {
        private final String s;
        private int i;

        Lexer(String s) {
            this.s = s;
        }

        List<Tok> tokens() {
            List<Tok> out = new ArrayList<>();
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c) || c == '\0') {
                    i++;
                    continue;
                }
                if (c == '%') {
                    while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') i++;
                    continue;
                }
                if (c == '(') {
                    out.add(Tok.str(readLiteral()));
                    continue;
                }
                if (c == '<' && i + 1 < n && s.charAt(i + 1) == '<') {
                    skipDict();
                    continue;
                }
                if (c == '<') {
                    int close = s.indexOf('>', i);
                    if (close < 0) break;
                    String hex = s.substring(i + 1, close).replaceAll("[^0-9A-Fa-f]", "");
                    if (hex.length() % 2 == 1) hex = hex + "0";
                    out.add(Tok.str(new String(hexToBytes(hex), StandardCharsets.ISO_8859_1)));
                    i = close + 1;
                    continue;
                }
                if (c == '[') {
                    i++;
                    List<Tok> items = new ArrayList<>();
                    while (i < n && s.charAt(i) != ']') {
                        char d = s.charAt(i);
                        if (Character.isWhitespace(d)) {
                            i++;
                            continue;
                        }
                        if (d == '(') {
                            items.add(Tok.str(readLiteral()));
                            continue;
                        }
                        if (d == '<') {
                            int close = s.indexOf('>', i);
                            if (close < 0) break;
                            String hex = s.substring(i + 1, close).replaceAll("[^0-9A-Fa-f]", "");
                            if (hex.length() % 2 == 1) hex = hex + "0";
                            items.add(Tok.str(new String(hexToBytes(hex), StandardCharsets.ISO_8859_1)));
                            i = close + 1;
                            continue;
                        }
                        if (d == '/') {
                            items.add(Tok.name(readName()));
                            continue;
                        }
                        if (d == '<' || d == '[') {
                            i++;
                            continue;
                        }
                        if (isNumberStart(d)) {
                            items.add(Tok.of(readNumber()));
                            continue;
                        }
                        i++;
                    }
                    if (i < n) i++;
                    out.add(Tok.arr(items));
                    continue;
                }
                if (c == ']' || c == '>' || c == ')' || c == '{' || c == '}') {
                    i++;
                    continue;
                }
                if (c == '/') {
                    String name = readName();
                    out.add(Tok.name(name));
                    if ("BI".equals(name)) {
                        skipInlineImage();
                    }
                    continue;
                }
                if (isNumberStart(c)) {
                    out.add(Tok.of(readNumber()));
                    continue;
                }
                String op = readOperator();
                if (op.isEmpty()) {
                    i++;
                    continue;
                }
                out.add(Tok.op(op));
                if ("BI".equals(op)) skipInlineImage();
            }
            return out;
        }

        private boolean isNumberStart(char c) {
            return Character.isDigit(c) || c == '-' || c == '+' || c == '.';
        }

        private String readName() {
            i++; // skip '/'
            int start = i;
            int n = s.length();
            while (i < n && !Character.isWhitespace(s.charAt(i)) && !isDelim(s.charAt(i))) i++;
            return s.substring(start, i);
        }

        private float readNumber() {
            int start = i;
            int n = s.length();
            if (i < n && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
            int dots = 0;
            while (i < n) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) {
                    i++;
                } else if (c == '.' && dots == 0) {
                    dots++;
                    i++;
                } else {
                    break;
                }
            }
            try {
                return Float.parseFloat(s.substring(start, i));
            } catch (NumberFormatException e) {
                return 0f;
            }
        }

        private String readOperator() {
            int start = i;
            int n = s.length();
            while (i < n && !Character.isWhitespace(s.charAt(i)) && !isDelim(s.charAt(i))) i++;
            return s.substring(start, i);
        }

        private String readLiteral() {
            StringBuilder sb = new StringBuilder();
            int depth = 1;
            i++;
            int n = s.length();
            while (i < n) {
                char c = s.charAt(i);
                if (c == '\\') {
                    i++;
                    if (i >= n) break;
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
                                while (cnt < 3 && i < n && s.charAt(i) >= '0' && s.charAt(i) <= '7') {
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
            i++;
            return sb.toString();
        }

        private void skipDict() {
            int depth = 0;
            int n = s.length();
            while (i < n - 1) {
                if (s.charAt(i) == '<' && s.charAt(i + 1) == '<') {
                    depth++;
                    i += 2;
                } else if (s.charAt(i) == '>' && s.charAt(i + 1) == '>') {
                    depth--;
                    i += 2;
                    if (depth == 0) return;
                } else {
                    i++;
                }
            }
        }

        /** BI … ID <binary> EI：内联图像数据可能含任意字节，整体跳过。 */
        private void skipInlineImage() {
            int n = s.length();
            while (i < n) {
                if (s.charAt(i) == 'E' && i + 1 < n && s.charAt(i + 1) == 'I'
                        && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) {
                    i += 2;
                    return;
                }
                i++;
            }
        }

        private boolean isDelim(char c) {
            return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}';
        }
    }

    /** 供 PdfStructureTool 复用的排序器：按 y 升序（自上而下）。 */
    static final Comparator<Run> BY_Y_THEN_X = (a, b) -> {
        if (Math.abs(a.y - b.y) > 0.6f) return Float.compare(a.y, b.y);
        return Float.compare(a.x, b.x);
    };
}
