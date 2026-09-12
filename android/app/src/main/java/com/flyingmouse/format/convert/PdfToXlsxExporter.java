package com.flyingmouse.format.convert;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF → xlsx（结构化表格）导出器：把 {@link PdfStructureTool} 还原出的表格写入多工作表工作簿，
 * 每张表一个 sheet（命名 P{页码}-T{序号}），表头加粗、全表细边框、文本自动换行，
 * 低可信度单元格填充复核底色。零第三方依赖（自带 OOXML 封装）。
 *
 * 错误码与 PC 端 pdf-office-xlsx.js 对齐：
 * PDF_TABLE_NOT_DETECTED / PDF_TABLE_OCR_LOW_QUALITY / PDF_OFFICE_OUTPUT_INVALID。
 */
public final class PdfToXlsxExporter {

    public static final String E_OUTPUT_INVALID = "PDF_OFFICE_OUTPUT_INVALID";

    private static final String NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships";

    private static final int STYLE_PLAIN = 0;
    private static final int STYLE_HEADER = 1;
    private static final int STYLE_BODY = 2;
    private static final int STYLE_REVIEW = 3;

    private PdfToXlsxExporter() {
    }

    private static final class SheetSpec {
        String name;
        PdfStructureTool.Block table;
        int number;
    }

    public static byte[] export(PdfStructureTool.Document doc) throws PdfStructureTool.StructureException {
        List<SheetSpec> sheets = new ArrayList<>();
        for (PdfStructureTool.PageStruct page : doc.pages) {
            int tableIndex = 0;
            for (PdfStructureTool.Block block : page.blocks) {
                if (!"table".equals(block.type)) continue;
                tableIndex++;
                SheetSpec spec = new SheetSpec();
                spec.table = block;
                spec.number = sheets.size() + 1;
                spec.name = sheetName(page.number, tableIndex);
                sheets.add(spec);
            }
        }
        if (sheets.isEmpty()) {
            throw new PdfStructureTool.StructureException(PdfStructureTool.E_TABLE_NOT_DETECTED,
                    "未检测到可用表格，无法生成结构化 Excel。该 PDF 可能只有段落文本，可改用 PDF→Word 或 PDF→TXT。");
        }
        float lowest = 1f;
        for (SheetSpec spec : sheets) lowest = Math.min(lowest, spec.table.confidence);
        if (lowest < PdfStructureTool.HARD_TABLE_CONFIDENCE) {
            throw new PdfStructureTool.StructureException(PdfStructureTool.E_LOW_QUALITY_TABLE,
                    "表格识别质量低于安全阈值（可信度 " + PdfStructureTool.num(lowest) + "），请改用更清晰的源文件后重试。");
        }

        try {
            return buildPackage(sheets);
        } catch (IOException e) {
            throw new PdfStructureTool.StructureException(E_OUTPUT_INVALID, "生成 Excel 失败：" + e.getMessage());
        }
    }

    private static byte[] buildPackage(List<SheetSpec> sheets) throws IOException, PdfStructureTool.StructureException {
        PackageWriter pack = new PackageWriter();
        StringBuilder contentTypes = new StringBuilder();
        contentTypes.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
                .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
                .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
                .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
                .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        StringBuilder rels = new StringBuilder();
        rels.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<Relationships xmlns=\"").append(NS_PKG_REL).append("\">")
                .append("<Relationship Id=\"rIdWorkbook\" Type=\"").append(NS_REL_DOC).append("/officeDocument\" Target=\"xl/workbook.xml\"/>")
                .append("</Relationships>");

        StringBuilder workbook = new StringBuilder();
        workbook.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<workbook xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(NS_REL_DOC).append("\"><sheets>");
        StringBuilder workbookRels = new StringBuilder();
        workbookRels.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<Relationships xmlns=\"").append(NS_PKG_REL).append("\">");

        for (SheetSpec spec : sheets) {
            String part = "xl/worksheets/sheet" + spec.number + ".xml";
            workbook.append("<sheet name=\"").append(PackageWriter.xml(spec.name))
                    .append("\" sheetId=\"").append(spec.number)
                    .append("\" r:id=\"rId").append(spec.number).append("\"/>");
            workbookRels.append("<Relationship Id=\"rId").append(spec.number)
                    .append("\" Type=\"").append(NS_REL_DOC).append("/worksheet\" Target=\"worksheets/sheet")
                    .append(spec.number).append(".xml\"/>");
            contentTypes.append("<Override PartName=\"/").append(part)
                    .append("\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            pack.text(part, sheetXml(spec.table));
        }
        workbook.append("</sheets></workbook>");
        workbookRels.append("</Relationships>");
        contentTypes.append("</Types>");

        pack.text("[Content_Types].xml", contentTypes.toString());
        pack.text("_rels/.rels", rels.toString());
        pack.text("xl/workbook.xml", workbook.toString());
        pack.text("xl/_rels/workbook.xml.rels", workbookRels.toString());
        pack.text("xl/styles.xml", stylesXml());

        byte[] pkg = pack.finish();
        List<String> required = new ArrayList<>();
        required.add("xl/workbook.xml");
        required.add("xl/styles.xml");
        for (SheetSpec spec : sheets) required.add("xl/worksheets/sheet" + spec.number + ".xml");
        if (!PackageWriter.isPackageValid(pkg, required.toArray(new String[0]))) {
            throw new PdfStructureTool.StructureException(E_OUTPUT_INVALID,
                    "生成的 Excel 文件未通过完整性检查，已放弃写入。");
        }
        return pkg;
    }

    private static String sheetName(int page, int table) {
        String name = "P" + page + "-T" + table;
        name = name.replaceAll("[\\[\\]:*?/\\\\]", "-");
        if (name.length() > 31) name = name.substring(0, 31);
        return name;
    }

    private static String sheetXml(PdfStructureTool.Block table) {
        StringBuilder sb = new StringBuilder(1024 + table.rows * table.cols * 96);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"").append(NS_MAIN).append("\">");
        sb.append("<sheetViews><sheetView workbookViewId=\"0\">");
        if (table.rows > 1) {
            sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>");
        }
        sb.append("</sheetView></sheetViews>");
        sb.append("<sheetFormatPr defaultRowHeight=\"15\"/>");
        sb.append("<cols>");
        for (int c = 0; c < table.cols; c++) {
            int width = columnWidth(table, c);
            sb.append("<col min=\"").append(c + 1).append("\" max=\"").append(c + 1)
                    .append("\" width=\"").append(width).append("\" customWidth=\"1\"/>");
        }
        sb.append("</cols><sheetData>");
        for (int r = 0; r < table.rows; r++) {
            sb.append("<row r=\"").append(r + 1).append("\"");
            if (r == 0 && table.hasHeader) sb.append(" ht=\"20\" customHeight=\"1\"");
            sb.append(">");
            for (int c = 0; c < table.cols; c++) {
                PdfStructureTool.Cell cell = PdfStructureTool.cellAt(table, r, c);
                String text = cell == null ? "" : cell.text;
                String ref = columnName(c + 1) + (r + 1);
                boolean header = r == 0 && table.hasHeader;
                int style = header ? STYLE_HEADER
                        : (table.confidence < PdfStructureTool.REVIEW_CELL_CONFIDENCE
                        && !PdfStructureTool.isBlank(text) ? STYLE_REVIEW : STYLE_BODY);
                if (PdfStructureTool.isBlank(text)) {
                    if (style != STYLE_BODY) {
                        sb.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"/>");
                    }
                    continue;
                }
                if (!header && PackageWriter.isNumeric(text.trim())) {
                    String value = text.trim().replace(",", "");
                    sb.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"><v>")
                            .append(value).append("</v></c>");
                } else {
                    sb.append("<c r=\"").append(ref).append("\" s=\"").append(style)
                            .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(PackageWriter.xml(text)).append("</t></is></c>");
                }
            }
            sb.append("</row>");
        }
        sb.append("</sheetData>");
        sb.append("<autoFilter ref=\"A1:").append(columnName(table.cols)).append(Math.max(1, table.rows)).append("\"/>");
        sb.append("</worksheet>");
        return sb.toString();
    }

    /** 列宽按最长内容估算（中文按 2 个字符宽）。 */
    private static int columnWidth(PdfStructureTool.Block table, int col) {
        int best = 8;
        for (int r = 0; r < table.rows; r++) {
            PdfStructureTool.Cell cell = PdfStructureTool.cellAt(table, r, col);
            if (cell == null) continue;
            String text = cell.text == null ? "" : cell.text;
            String[] lines = text.split("\n");
            int longest = 0;
            for (String line : lines) {
                int width = 0;
                for (int i = 0; i < line.length(); i++) {
                    width += line.charAt(i) > 0x2E80 ? 2 : 1;
                }
                longest = Math.max(longest, width);
            }
            best = Math.max(best, Math.min(longest + 3, 60));
        }
        return best;
    }

    private static String columnName(int index) {
        StringBuilder sb = new StringBuilder();
        int value = index;
        while (value > 0) {
            int rem = (value - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            value = (value - 1) / 26;
        }
        return sb.toString();
    }

    private static String stylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"" + NS_MAIN + "\">"
                + "<fonts count=\"3\">"
                + "<font><sz val=\"10\"/><name val=\"Microsoft YaHei\"/><color rgb=\"FF1F2937\"/></font>"
                + "<font><b/><sz val=\"10\"/><name val=\"Microsoft YaHei\"/><color rgb=\"FFFFFFFF\"/></font>"
                + "<font><sz val=\"10\"/><name val=\"Microsoft YaHei\"/><color rgb=\"FF7C3F00\"/></font>"
                + "</fonts>"
                + "<fills count=\"4\">"
                + "<fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF2F5597\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFE2A8\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                + "</fills>"
                + "<borders count=\"2\">"
                + "<border><left/><right/><top/><bottom/><diagonal/></border>"
                + "<border><left style=\"thin\"><color rgb=\"FFD9E2F3\"/></left>"
                + "<right style=\"thin\"><color rgb=\"FFD9E2F3\"/></right>"
                + "<top style=\"thin\"><color rgb=\"FFD9E2F3\"/></top>"
                + "<bottom style=\"thin\"><color rgb=\"FFD9E2F3\"/></bottom><diagonal/></border>"
                + "</borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"4\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
                + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";
    }
}
