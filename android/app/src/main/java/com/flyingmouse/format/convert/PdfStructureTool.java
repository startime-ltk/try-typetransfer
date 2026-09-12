package com.flyingmouse.format.convert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * PDF 智能结构还原（P6 后半）——从 PDF 版面推断「段落 / 标题 / 表格」，输出稳定结构清单。
 *
 * 流水线：{@link PdfLayoutExtractor} 提供带坐标的文本片段与矢量线段
 * → 行聚合（同基线 + 水平间距阈值）→ 表格识别（框线网格优先，其次多列对齐的行块）
 * → 非表格行做段落 / 标题归并 → 产出 {@link Document}（可导出 JSON 清单供校验）。
 *
 * 与 PC 端 pdf-structure-contract.js 的错误码口径保持一致：
 * PDF_NOT_READABLE / PDF_NO_TEXT_LAYER / PDF_TABLE_NOT_DETECTED
 * / PDF_TABLE_OCR_LOW_QUALITY / PDF_DOCX_NO_EDITABLE_CONTENT。
 */
public final class PdfStructureTool {

    /** 结构清单 Schema 版本（与 PC 端 STRUCTURE_SCHEMA_VERSION 对齐）。 */
    public static final String SCHEMA_VERSION = "1";

    public static final String E_NOT_READABLE = "PDF_NOT_READABLE";
    public static final String E_NO_TEXT_LAYER = "PDF_NO_TEXT_LAYER";
    public static final String E_TABLE_NOT_DETECTED = "PDF_TABLE_NOT_DETECTED";
    public static final String E_LOW_QUALITY_TABLE = "PDF_TABLE_OCR_LOW_QUALITY";
    public static final String E_DOCX_NO_CONTENT = "PDF_DOCX_NO_EDITABLE_CONTENT";

    /** 表格可信度硬门槛（与 PC 端 pdf-office-xlsx.js 的 HARD_TABLE_CONFIDENCE 对齐）。 */
    public static final float HARD_TABLE_CONFIDENCE = 0.65f;
    /** 低于该可信度的单元格标记为「需人工复核」（与 PC 端 REVIEW_CELL_CONFIDENCE 对齐，Excel 填充复核底色）。 */
    public static final float REVIEW_CELL_CONFIDENCE = 0.85f;

    private PdfStructureTool() {
    }

    /** 带稳定错误码的结构化异常，便于上层给出精确失败提示。 */
    public static final class StructureException extends Exception {
        public final String code;

        public StructureException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    // ================= 清单模型 =================

    public static final class Cell {
        public int row;
        public int col;
        public int rowSpan = 1;
        public int colSpan = 1;
        public String text = "";
    }

    public static final class Block {
        public String type = "paragraph"; // heading | paragraph | table
        public int level;                 // heading 级别 1..3
        public String text = "";
        public List<Cell> cells;          // 仅 table 生效
        public int rows;
        public int cols;
        public boolean hasHeader;
        public float confidence = 1f;
        float top;                        // 版面 y 上边界（排序用）
    }

    public static final class PageStruct {
        public int number;
        public float width;
        public float height;
        public final List<Block> blocks = new ArrayList<>();
    }

    public static final class Document {
        public String sourceName = "";
        public int pageCount;
        public float width;
        public float height;
        public final List<PageStruct> pages = new ArrayList<>();
        public int paragraphCount;
        public int headingCount;
        public int tableCount;
        public int cellCount;
        public boolean hasReviewTable;
        public final List<String> warnings = new ArrayList<>();
    }

    // ================= 对外入口 =================

    /** 解析 PDF 并还原结构；失败时抛出带错误码的 {@link StructureException}。 */
    public static Document analyze(byte[] pdf, String sourceName) throws StructureException {
        List<PdfLayoutExtractor.Page> pages;
        try {
            pages = PdfLayoutExtractor.extract(pdf);
        } catch (Exception e) {
            throw new StructureException(E_NOT_READABLE, "无法解析 PDF：" + safeMsg(e));
        }
        Document doc = new Document();
        doc.sourceName = sourceName == null ? "" : sourceName;
        doc.pageCount = pages.size();

        int textRunCount = 0;
        for (PdfLayoutExtractor.Page page : pages) textRunCount += page.runs.size();
        if (textRunCount == 0) {
            throw new StructureException(E_NO_TEXT_LAYER,
                    "该 PDF 没有可提取的文字层（可能是扫描件或纯图片 PDF）。");
        }

        boolean first = true;
        for (PdfLayoutExtractor.Page page : pages) {
            PageStruct ps = buildPage(page);
            doc.pages.add(ps);
            if (first) {
                doc.width = page.width;
                doc.height = page.height;
                first = false;
            }
            for (Block block : ps.blocks) {
                if ("table".equals(block.type)) {
                    doc.tableCount++;
                    doc.cellCount += block.rows * block.cols;
                    if (block.confidence < REVIEW_CELL_CONFIDENCE) doc.hasReviewTable = true;
                } else if ("heading".equals(block.type)) {
                    doc.headingCount++;
                } else {
                    doc.paragraphCount++;
                }
            }
        }
        if (isBlank(doc)) {
            throw new StructureException(E_DOCX_NO_CONTENT, "PDF 中没有可还原的文本内容。");
        }
        return doc;
    }

    private static boolean isBlank(Document doc) {
        for (PageStruct page : doc.pages) {
            for (Block block : page.blocks) {
                if ("table".equals(block.type)) {
                    for (Cell cell : block.cells) if (!isBlank(cell.text)) return false;
                } else if (!isBlank(block.text)) {
                    return false;
                }
            }
        }
        return true;
    }

    // ================= 单页结构还原 =================

    private static final class Line {
        float x0;
        float x1;
        float baseline;
        float size;
        final StringBuilder text = new StringBuilder();

        boolean hasVisible() {
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isWhitespace(text.charAt(i))) return true;
            }
            return false;
        }

        String value() {
            return text.toString().trim();
        }
    }

    /** 表格命中：块本身 + 被消费的行下标 + 版面纵向区间。 */
    private static final class TableHit {
        final Block block;
        final List<Integer> lineIndexes = new ArrayList<>();
        float y0;
        float y1;

        TableHit(Block block) {
            this.block = block;
        }
    }

    private static PageStruct buildPage(PdfLayoutExtractor.Page page) {
        PageStruct out = new PageStruct();
        out.number = page.number;
        out.width = page.width;
        out.height = page.height;

        List<Line> lines = groupLines(page);
        float baseSize = medianSize(lines);
        boolean[] consumed = new boolean[lines.size()];

        List<TableHit> hits = detectRuledTables(page, lines, baseSize);
        for (TableHit hit : hits) markConsumed(consumed, hit.lineIndexes);
        for (TableHit hit : detectBorderlessTables(lines, consumed)) {
            hits.add(hit);
            markConsumed(consumed, hit.lineIndexes);
        }

        List<Block> blocks = new ArrayList<>();
        for (TableHit hit : hits) blocks.add(hit.block);
        for (int i = 0; i < lines.size(); i++) {
            if (consumed[i]) continue;
            consumed[i] = true;
            List<Line> group = new ArrayList<>();
            group.add(lines.get(i));
            int j = i + 1;
            while (j < lines.size() && !consumed[j] && continuesBlock(group.get(group.size() - 1), lines.get(j))) {
                group.add(lines.get(j));
                consumed[j] = true;
                j++;
            }
            Block block = makeTextBlock(group, baseSize, page.width);
            if (block != null) blocks.add(block);
            i = j - 1;
        }
        Collections.sort(blocks, (a, b) -> Float.compare(a.top, b.top));
        out.blocks.addAll(blocks);
        return out;
    }

    private static void markConsumed(boolean[] consumed, List<Integer> indexes) {
        for (int index : indexes) {
            if (index >= 0 && index < consumed.length) consumed[index] = true;
        }
    }

    /** 行聚合：同基线且水平间距不过大视为同一行；列间距过大则拆成同基线的多个行片段。 */
    private static List<Line> groupLines(PdfLayoutExtractor.Page page) {
        List<PdfLayoutExtractor.Run> runs = new ArrayList<>(page.runs);
        runs.sort(PdfLayoutExtractor.BY_Y_THEN_X);
        List<Line> lines = new ArrayList<>();
        for (PdfLayoutExtractor.Run run : runs) {
            if (run.text == null || run.text.trim().isEmpty()) continue;
            Line target = null;
            int from = -1;
            for (int i = lines.size() - 1; i >= 0 && i >= lines.size() - 4; i--) {
                Line line = lines.get(i);
                float tol = Math.max(2.2f, 0.38f * Math.min(line.size, run.size));
                if (Math.abs(line.baseline - run.y) <= tol) {
                    from = i;
                    boolean overlapping = run.x <= line.x1 + 0.05f;
                    boolean closeEnough = run.x - line.x1 <= Math.max(2.4f * line.size, 22f);
                    if (overlapping || closeEnough) target = line;
                    break;
                }
            }
            if (target == null) {
                Line line = new Line();
                line.baseline = run.y;
                line.size = run.size;
                line.x0 = run.x;
                line.x1 = run.x + Math.max(run.width, 0f);
                line.text.append(run.text);
                if (from >= 0) lines.add(from + 1, line);
                else lines.add(line);
                continue;
            }
            float gap = run.x - target.x1;
            if (target.text.length() > 0 && gap > 0.16f * target.size
                    && needSpace(target.text.charAt(target.text.length() - 1), run.text.charAt(0))) {
                target.text.append(' ');
            }
            target.text.append(run.text);
            target.x0 = Math.min(target.x0, run.x);
            target.x1 = Math.max(target.x1, run.x + Math.max(run.width, 0f));
            target.size = Math.max(target.size, run.size);
        }
        List<Line> visible = new ArrayList<>();
        for (Line line : lines) if (line.hasVisible()) visible.add(line);
        visible.sort((a, b) -> Float.compare(a.baseline, b.baseline));
        return visible;
    }

    /** 西文相邻词之间补空格；中文之间不补。 */
    private static boolean needSpace(char left, char right) {
        return isAsciiWord(left) && isAsciiWord(right);
    }

    private static boolean isAsciiWord(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static float medianSize(List<Line> lines) {
        List<Float> sizes = new ArrayList<>();
        for (Line line : lines) if (line.value().length() >= 4) sizes.add(line.size);
        if (sizes.isEmpty()) for (Line line : lines) sizes.add(line.size);
        if (sizes.isEmpty()) return 12f;
        Collections.sort(sizes);
        return sizes.get(sizes.size() / 2);
    }

    // ================= 框线表格识别 =================

    private static List<TableHit> detectRuledTables(PdfLayoutExtractor.Page page, List<Line> lines, float baseSize) {
        List<TableHit> hits = new ArrayList<>();
        List<PdfLayoutExtractor.Rule> hRules = new ArrayList<>();
        List<PdfLayoutExtractor.Rule> vRules = new ArrayList<>();
        for (PdfLayoutExtractor.Rule rule : page.rules) {
            if (rule.length() < 10f) continue;
            if (rule.horizontal) hRules.add(rule);
            else vRules.add(rule);
        }
        for (int guard = 0; guard < 12; guard++) {
            TableHit hit = pickGrid(page, hRules, vRules, lines, baseSize);
            if (hit == null) break;
            hits.add(hit);
            dropRulesInRange(hRules, hit.y0, hit.y1, true);
            dropRulesInRange(vRules, hit.y0, hit.y1, false);
        }
        return hits;
    }

    private static void dropRulesInRange(List<PdfLayoutExtractor.Rule> rules, float y0, float y1, boolean horizontal) {
        for (int i = rules.size() - 1; i >= 0; i--) {
            PdfLayoutExtractor.Rule r = rules.get(i);
            float v = horizontal ? r.y0 : r.x0;
            if (v >= y0 - 1f && v <= y1 + 1f) rules.remove(i);
        }
    }

    /** 从候选框线里挑一组构成最可信网格；成功时返回命中（含被消费的行）。 */
    private static TableHit pickGrid(PdfLayoutExtractor.Page page, List<PdfLayoutExtractor.Rule> hRules,
                                     List<PdfLayoutExtractor.Rule> vRules, List<Line> lines, float baseSize) {
        if (hRules.size() < 2 || vRules.size() < 2) return null;
        List<float[]> hClusters = cluster(hRules, true);
        List<float[]> vClusters = cluster(vRules, false);
        if (hClusters.size() < 2 || vClusters.size() < 2) return null;

        float top = hClusters.get(0)[0];
        float bottom = hClusters.get(hClusters.size() - 1)[0];
        float left = vClusters.get(0)[0];
        float right = vClusters.get(vClusters.size() - 1)[0];
        if (bottom - top < 12f || right - left < 24f) return null;

        // 仅保留真正跨越表格区域的框线，避免正文分隔线被误判为表格
        List<float[]> hKeep = new ArrayList<>();
        for (float[] c : hClusters) if (c[1] <= left + 2f && c[2] >= right - 2f) hKeep.add(c);
        List<float[]> vKeep = new ArrayList<>();
        for (float[] c : vClusters) if (c[1] <= top + 2f && c[2] >= bottom - 2f) vKeep.add(c);
        if (hKeep.size() < 2 || vKeep.size() < 2) return null;

        int rows = hKeep.size() - 1;
        int cols = vKeep.size() - 1;
        if (rows < 1 || cols < 1 || rows * cols > 4000) return null;

        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.baseline >= top - 1.5f && line.baseline <= bottom + 1.5f
                    && line.x1 >= left - 2f && line.x0 <= right + 2f) {
                indexes.add(i);
            }
        }
        if (indexes.isEmpty()) return null;

        Block block = new Block();
        block.type = "table";
        block.top = top;
        block.rows = rows;
        block.cols = cols;
        block.hasHeader = rows >= 2;
        block.cells = fillGridCells(page.runs, hKeep, vKeep, rows, cols);
        block.confidence = scoreTable(block);

        TableHit hit = new TableHit(block);
        hit.lineIndexes.addAll(indexes);
        hit.y0 = top;
        hit.y1 = bottom;
        return hit;
    }

    private static List<float[]> cluster(List<PdfLayoutExtractor.Rule> rules, boolean horizontal) {
        List<float[]> points = new ArrayList<>();
        for (PdfLayoutExtractor.Rule r : rules) {
            float v = horizontal ? r.y0 : r.x0;
            float lo = horizontal ? Math.min(r.x0, r.x1) : Math.min(r.y0, r.y1);
            float hi = horizontal ? Math.max(r.x0, r.x1) : Math.max(r.y0, r.y1);
            points.add(new float[]{v, lo, hi});
        }
        points.sort((a, b) -> Float.compare(a[0], b[0]));
        List<float[]> out = new ArrayList<>();
        for (float[] p : points) {
            if (!out.isEmpty() && Math.abs(out.get(out.size() - 1)[0] - p[0]) <= 2.6f) {
                float[] last = out.get(out.size() - 1);
                last[0] = (last[0] + p[0]) / 2f;
                last[1] = Math.min(last[1], p[1]);
                last[2] = Math.max(last[2], p[2]);
            } else {
                out.add(new float[]{p[0], p[1], p[2]});
            }
        }
        return out;
    }

    /**
     * 按「文本片段」而不是「聚合行」填格：同一基线上的相邻列文本不会被误并入同一单元格。
     */
    private static List<Cell> fillGridCells(List<PdfLayoutExtractor.Run> runs, List<float[]> hKeep,
                                            List<float[]> vKeep, int rows, int cols) {
        List<List<PdfLayoutExtractor.Run>> buckets = new ArrayList<>();
        for (int i = 0; i < rows * cols; i++) buckets.add(new ArrayList<>());
        for (PdfLayoutExtractor.Run run : runs) {
            if (run.text == null || run.text.trim().isEmpty()) continue;
            int row = -1;
            for (int r = 0; r < rows; r++) {
                if (run.y >= hKeep.get(r)[0] - 1f && run.y <= hKeep.get(r + 1)[0] + 1f) {
                    row = r;
                    break;
                }
            }
            if (row < 0) continue;
            int col = -1;
            for (int c = 0; c < cols; c++) {
                if (run.x >= vKeep.get(c)[0] - 4f && run.x < vKeep.get(c + 1)[0]) {
                    col = c;
                    break;
                }
            }
            if (col < 0) continue;
            buckets.get(row * cols + col).add(run);
        }
        List<Cell> cells = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Cell cell = new Cell();
                cell.row = r;
                cell.col = c;
                cell.text = joinRuns(buckets.get(r * cols + c));
                cells.add(cell);
            }
        }
        return cells;
    }

    /** 单元格内文本：按基线分行、同基线按 x 顺序拼接（西文相邻补空格，中文不补）。 */
    private static String joinRuns(List<PdfLayoutExtractor.Run> items) {
        if (items.isEmpty()) return "";
        items.sort((a, b) -> Math.abs(a.y - b.y) > 1f ? Float.compare(a.y, b.y) : Float.compare(a.x, b.x));
        StringBuilder sb = new StringBuilder();
        PdfLayoutExtractor.Run prev = null;
        char lastChar = 0;
        for (PdfLayoutExtractor.Run run : items) {
            String text = run.text.trim();
            if (text.isEmpty()) continue;
            if (prev != null) {
                float tol = Math.max(2.2f, 0.38f * Math.min(prev.size, run.size));
                if (Math.abs(prev.y - run.y) <= tol) {
                    if (lastChar != 0 && needSpace(lastChar, text.charAt(0))) sb.append(' ');
                } else {
                    sb.append('\n');
                }
            }
            sb.append(text);
            lastChar = text.charAt(text.length() - 1);
            prev = run;
        }
        return sb.toString();
    }

    /** 表格可信度：结构完整度 + 文本填充率。 */
    private static float scoreTable(Block block) {
        float base = block.hasHeader && block.rows >= 2 ? 0.92f : 0.86f;
        if (block.cells == null || block.cells.isEmpty()) return base;
        int empty = 0;
        for (Cell cell : block.cells) if (isBlank(cell.text)) empty++;
        float emptyRatio = empty / (float) block.cells.size();
        float score = base - Math.min(0.22f, emptyRatio * 0.35f);
        return Math.max(0f, Math.min(1f, score));
    }

    // ================= 无框线表格识别 =================

    /** 多列对齐行块识别：连续多行都呈现 >=3 个列且列位对齐 → 判定为表格。 */
    private static List<TableHit> detectBorderlessTables(List<Line> lines, boolean[] consumed) {
        List<TableHit> hits = new ArrayList<>();
        List<List<Integer>> rows = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (consumed[i]) {
                if (!current.isEmpty()) {
                    rows.add(current);
                    current = new ArrayList<>();
                }
                continue;
            }
            if (!current.isEmpty()) {
                Line last = lines.get(current.get(current.size() - 1));
                float tol = Math.max(2.2f, 0.4f * lines.get(i).size);
                if (Math.abs(last.baseline - lines.get(i).baseline) > tol) {
                    rows.add(current);
                    current = new ArrayList<>();
                }
            }
            current.add(i);
        }
        if (!current.isEmpty()) rows.add(current);

        int i = 0;
        while (i < rows.size()) {
            List<Integer> row = rows.get(i);
            if (row.size() < 3) {
                i++;
                continue;
            }
            List<List<Integer>> block = new ArrayList<>();
            block.add(row);
            int j = i + 1;
            while (j < rows.size() && rows.get(j).size() >= 3 && columnsAligned(lines, row, rows.get(j))) {
                block.add(rows.get(j));
                j++;
            }
            if (block.size() >= 3) {
                int cols = 3;
                for (List<Integer> r : block) cols = Math.max(cols, r.size());
                cols = Math.min(cols, 12);
                Block table = new Block();
                table.type = "table";
                table.top = lines.get(block.get(0).get(0)).baseline - 8f;
                table.rows = block.size();
                table.cols = cols;
                table.hasHeader = true;
                table.cells = fillBorderlessCells(lines, block, table.rows, table.cols);
                table.confidence = Math.min(0.80f, scoreTable(table));
                TableHit hit = new TableHit(table);
                for (List<Integer> r : block) hit.lineIndexes.addAll(r);
                hit.y0 = table.top;
                hit.y1 = lines.get(block.get(block.size() - 1).get(0)).baseline;
                hits.add(hit);
                i = j;
                continue;
            }
            i++;
        }
        return hits;
    }

    private static boolean columnsAligned(List<Line> lines, List<Integer> a, List<Integer> b) {
        int compared = Math.min(a.size(), b.size());
        if (compared < 3) return false;
        int hits = 0;
        for (int i = 0; i < compared; i++) {
            if (Math.abs(lines.get(a.get(i)).x0 - lines.get(b.get(i)).x0) <= 12f) hits++;
        }
        return hits >= Math.ceil(compared * 0.66);
    }

    private static List<Cell> fillBorderlessCells(List<Line> lines, List<List<Integer>> block, int rows, int cols) {
        List<Cell> cells = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            List<Integer> row = block.get(r);
            for (int c = 0; c < cols; c++) {
                Cell cell = new Cell();
                cell.row = r;
                cell.col = c;
                if (c < row.size()) cell.text = lines.get(row.get(c)).value();
                cells.add(cell);
            }
        }
        return cells;
    }

    // ================= 段落 / 标题 =================

    private static boolean continuesBlock(Line prev, Line next) {
        float gap = next.baseline - prev.baseline;
        float sizeJump = Math.abs(next.size - prev.size);
        if (gap > 1.75f * Math.max(prev.size, 8f)) return false;
        if (gap <= 0f) return false;
        if (sizeJump > 1.6f) return false;
        float indentDelta = next.x0 - prev.x0;
        if (indentDelta > 1.15f * prev.size) return false;
        if (indentDelta < -1.15f * prev.size) return false;
        return true;
    }

    private static Block makeTextBlock(List<Line> group, float baseSize, float pageWidth) {
        StringBuilder sb = new StringBuilder();
        for (Line line : group) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line.value());
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) return null;
        Line first = group.get(0);
        Block block = new Block();
        block.top = first.baseline - first.size;
        block.text = text;

        boolean single = group.size() == 1;
        float ratio = first.size / Math.max(baseSize, 0.01f);
        boolean centered = isCentered(group, pageWidth);
        boolean numbered = isNumberedHeading(text);
        boolean endsSentence = endsWithSentence(text);
        int level = 0;
        if (ratio >= 1.45f) level = 1;
        else if (ratio >= 1.16f) level = 2;
        else if (numbered && single && !endsSentence && text.length() <= 48) level = headingLevelByNumber(text);
        else if (centered && single && !endsSentence && text.length() <= 40) level = 3;

        if (level > 0) {
            block.type = "heading";
            block.level = Math.max(1, Math.min(3, level));
        } else {
            block.type = "paragraph";
            block.level = 0;
        }
        return block;
    }

    private static boolean isCentered(List<Line> group, float pageWidth) {
        float minLeft = Float.MAX_VALUE;
        float maxRight = -Float.MAX_VALUE;
        for (Line line : group) {
            minLeft = Math.min(minLeft, line.x0);
            maxRight = Math.max(maxRight, line.x1);
        }
        float center = (minLeft + maxRight) / 2f;
        return Math.abs(center - pageWidth / 2f) <= pageWidth * 0.06f
                && minLeft > pageWidth * 0.18f
                && (maxRight - minLeft) < pageWidth * 0.7f;
    }

    private static boolean endsWithSentence(String text) {
        if (text.isEmpty()) return false;
        char c = text.charAt(text.length() - 1);
        return c == '。' || c == '！' || c == '？' || c == '；' || c == '：'
                || c == '.' || c == '!' || c == '?' || c == ';' || c == ':' || c == '，' || c == ',';
    }

    private static boolean isNumberedHeading(String text) {
        if (text.isEmpty()) return false;
        if (text.matches("^第[一二三四五六七八九十百零〇\\d]+[章篇部分节節].*")) return true;
        if (text.matches("^[一二三四五六七八九十]+[、.．].*")) return true;
        if (text.matches("^\\d+(\\.\\d+)*[、.．]?\\s*\\S.*")) return true;
        if (text.matches("^[（(][一二三四五六七八九十\\d]+[）)].*")) return true;
        return false;
    }

    private static int headingLevelByNumber(String text) {
        if (text.matches("^第[一二三四五六七八九十百零〇\\d]+[章篇部分].*")) return 1;
        if (text.matches("^第[一二三四五六七八九十百零〇\\d]+[节節].*")) return 2;
        if (text.matches("^\\d+\\.\\d+.*")) return 3;
        if (text.matches("^\\d+[、.．].*")) return 2;
        if (text.matches("^[一二三四五六七八九十]+[、.．].*")) return 2;
        if (text.matches("^[（(].*")) return 3;
        return 2;
    }

    // ================= 清单 JSON =================

    /** 输出与 PC 端约定一致的稳定结构清单（UTF-8，可直接落盘校验）。 */
    public static String manifestJson(Document doc) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n");
        sb.append("  \"schemaVersion\": \"").append(SCHEMA_VERSION).append("\",\n");
        sb.append("  \"source\": \"").append(esc(doc.sourceName)).append("\",\n");
        sb.append("  \"generator\": \"flyingmouse-android\",\n");
        sb.append("  \"pageCount\": ").append(doc.pageCount).append(",\n");
        sb.append("  \"pageSize\": {");
        sb.append("\"width\": ").append(num(doc.width)).append(", ");
        sb.append("\"height\": ").append(num(doc.height)).append("},\n");
        sb.append("  \"stats\": {");
        sb.append("\"paragraphs\": ").append(doc.paragraphCount).append(", ");
        sb.append("\"headings\": ").append(doc.headingCount).append(", ");
        sb.append("\"tables\": ").append(doc.tableCount).append(", ");
        sb.append("\"cells\": ").append(doc.cellCount).append("},\n");
        sb.append("  \"warnings\": [");
        List<String> warnings = new ArrayList<>(doc.warnings);
        if (doc.hasReviewTable) {
            warnings.add("存在低可信度表格，已按复核预览方式导出（对应单元格填充复核底色），请人工核对。");
        }
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(esc(warnings.get(i))).append("\"");
        }
        sb.append("],\n");
        sb.append("  \"pages\": [\n");
        for (int p = 0; p < doc.pages.size(); p++) {
            PageStruct page = doc.pages.get(p);
            sb.append("    {\"number\": ").append(page.number).append(", \"blocks\": [\n");
            for (int b = 0; b < page.blocks.size(); b++) {
                sb.append("      ");
                appendBlock(sb, page.blocks.get(b));
                sb.append(b + 1 < page.blocks.size() ? ",\n" : "\n");
            }
            sb.append("    ]}");
            sb.append(p + 1 < doc.pages.size() ? ",\n" : "\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendBlock(StringBuilder sb, Block block) {
        sb.append("{\"type\": \"").append(block.type).append("\"");
        if ("table".equals(block.type)) {
            sb.append(", \"rows\": ").append(block.rows);
            sb.append(", \"cols\": ").append(block.cols);
            sb.append(", \"hasHeader\": ").append(block.hasHeader);
            sb.append(", \"confidence\": ").append(num(block.confidence));
            sb.append(", \"cells\": [");
            for (int r = 0; r < block.rows; r++) {
                if (r > 0) sb.append(", ");
                sb.append("[");
                for (int c = 0; c < block.cols; c++) {
                    if (c > 0) sb.append(", ");
                    Cell cell = cellAt(block, r, c);
                    sb.append("{\"text\": \"").append(esc(cell == null ? "" : cell.text)).append("\"}");
                }
                sb.append("]");
            }
            sb.append("]}");
            return;
        }
        if ("heading".equals(block.type)) {
            sb.append(", \"level\": ").append(Math.max(1, block.level));
        }
        sb.append(", \"text\": \"").append(esc(block.text)).append("\"}");
    }

    public static Cell cellAt(Block table, int row, int col) {
        if (table.cells == null) return null;
        int index = row * table.cols + col;
        if (index >= 0 && index < table.cells.size()) return table.cells.get(index);
        return null;
    }

    public static String num(float value) {
        String s = String.format(Locale.ROOT, "%.2f", value);
        if (s.endsWith("00")) s = s.substring(0, s.length() - 3);
        else if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safeMsg(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
