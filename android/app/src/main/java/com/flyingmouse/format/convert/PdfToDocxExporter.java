package com.flyingmouse.format.convert;

import java.io.IOException;
import java.util.List;

/**
 * PDF → docx（版式文本）导出器：把 {@link PdfStructureTool} 还原出的段落 / 标题 / 表格写成 Word 文档，
 * 保留原始分页（页间插入分页符）、标题层级（Heading 1–3）与表格结构（细边框表格）。
 * 零第三方依赖（自带 OOXML 封装），离线可用。
 *
 * 错误码与 PC 端 pdf-office-docx.js 对齐：PDF_DOCX_NO_EDITABLE_CONTENT / PDF_OFFICE_OUTPUT_INVALID。
 */
public final class PdfToDocxExporter {

    public static final String E_OUTPUT_INVALID = "PDF_OFFICE_OUTPUT_INVALID";

    private static final String NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String NS_REL_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships";

    private static final int TABLE_TOTAL_WIDTH = 9360; // 页面可用宽度（twips），A4 左右边距 1440

    private PdfToDocxExporter() {
    }

    public static byte[] export(PdfStructureTool.Document doc) throws PdfStructureTool.StructureException {
        boolean hasContent = false;
        for (PdfStructureTool.PageStruct page : doc.pages) {
            for (PdfStructureTool.Block block : page.blocks) {
                if (PdfStructureTool.isBlank(block.text) && PdfStructureTool.isBlank(blockCsv(block))) continue;
                hasContent = true;
                break;
            }
            if (hasContent) break;
        }
        if (!hasContent) {
            throw new PdfStructureTool.StructureException(PdfStructureTool.E_DOCX_NO_CONTENT,
                    "未检测到可编辑的文字或表格，无法生成有效的 Word 文档。");
        }
        try {
            return buildPackage(doc);
        } catch (IOException e) {
            throw new PdfStructureTool.StructureException(E_OUTPUT_INVALID, "生成 Word 失败：" + e.getMessage());
        }
    }

    private static String blockCsv(PdfStructureTool.Block block) {
        if (!"table".equals(block.type) || block.cells == null) return "";
        StringBuilder sb = new StringBuilder();
        for (PdfStructureTool.Cell cell : block.cells) sb.append(cell.text);
        return sb.toString();
    }

    private static byte[] buildPackage(PdfStructureTool.Document doc) throws IOException, PdfStructureTool.StructureException {
        PackageWriter pack = new PackageWriter();
        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "</Types>";
        String rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"" + NS_PKG_REL + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + NS_REL_DOC + "/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>";
        String docRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"" + NS_PKG_REL + "\">"
                + "<Relationship Id=\"rId1\" Type=\"" + NS_REL_DOC + "/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>";

        pack.text("[Content_Types].xml", contentTypes);
        pack.text("_rels/.rels", rootRels);
        pack.text("word/_rels/document.xml.rels", docRels);
        pack.text("word/styles.xml", stylesXml());
        pack.text("word/document.xml", documentXml(doc));

        byte[] pkg = pack.finish();
        if (!PackageWriter.isPackageValid(pkg, "word/document.xml", "word/styles.xml")) {
            throw new PdfStructureTool.StructureException(E_OUTPUT_INVALID,
                    "生成的 Word 文件未通过完整性检查，已放弃写入。");
        }
        return pkg;
    }

    private static String documentXml(PdfStructureTool.Document doc) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<w:document xmlns:w=\"").append(NS_W).append("\"><w:body>");
        for (int p = 0; p < doc.pages.size(); p++) {
            PdfStructureTool.PageStruct page = doc.pages.get(p);
            if (p > 0) sb.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
            for (PdfStructureTool.Block block : page.blocks) {
                if ("table".equals(block.type)) appendTable(sb, block);
                else appendParagraph(sb, block);
            }
        }
        sb.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
                .append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/>")
                .append("</w:sectPr>");
        sb.append("</w:body></w:document>");
        return sb.toString();
    }

    private static void appendParagraph(StringBuilder sb, PdfStructureTool.Block block) {
        String text = block.text == null ? "" : block.text;
        if (PdfStructureTool.isBlank(text)) return;
        String heading = null;
        if ("heading".equals(block.type)) {
            int level = Math.max(1, Math.min(3, block.level));
            heading = "Heading" + level;
        }
        sb.append("<w:p><w:pPr>");
        if (heading != null) sb.append("<w:pStyle w:val=\"").append(heading).append("\"/>");
        else sb.append("<w:spacing w:after=\"120\" w:line=\"320\" w:lineRule=\"auto\"/>");
        sb.append("</w:pPr><w:r>");
        appendRuns(sb, text);
        sb.append("</w:r></w:p>");
    }

    /** 行内换行保留为显式换行符，维持原版式行断。 */
    private static void appendRuns(StringBuilder sb, String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("<w:br/>");
            if (!lines[i].isEmpty()) {
                sb.append("<w:t xml:space=\"preserve\">").append(PackageWriter.xml(lines[i])).append("</w:t>");
            }
        }
    }

    private static void appendTable(StringBuilder sb, PdfStructureTool.Block table) {
        int cols = Math.max(1, table.cols);
        int colWidth = Math.max(600, TABLE_TOTAL_WIDTH / cols);
        sb.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/>")
                .append("<w:tblW w:w=\"").append(colWidth * cols).append("\" w:type=\"dxa\"/>")
                .append("<w:tblBorders>")
                .append("<w:top w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>")
                .append("<w:left w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>")
                .append("<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>")
                .append("<w:right w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>")
                .append("<w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>")
                .append("<w:insideV w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>")
                .append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/></w:tblPr><w:tblGrid>");
        for (int c = 0; c < cols; c++) sb.append("<w:gridCol w:w=\"").append(colWidth).append("\"/>");
        sb.append("</w:tblGrid>");

        boolean header = table.hasHeader && table.rows > 1;
        for (int r = 0; r < table.rows; r++) {
            sb.append("<w:tr>");
            for (int c = 0; c < cols; c++) {
                PdfStructureTool.Cell cell = PdfStructureTool.cellAt(table, r, c);
                String text = cell == null || cell.text == null ? "" : cell.text;
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(colWidth).append("\" w:type=\"dxa\"/>");
                if (header && r == 0) {
                    sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"2F5597\"/>");
                } else if (table.confidence < PdfStructureTool.REVIEW_CELL_CONFIDENCE
                        && !PdfStructureTool.isBlank(text)) {
                    sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"FFE2A8\"/>");
                }
                sb.append("</w:tcPr><w:p><w:pPr><w:spacing w:after=\"0\"/></w:pPr><w:r>");
                if (header && r == 0) {
                    sb.append("<w:rPr><w:b/><w:color w:val=\"FFFFFF\"/></w:rPr>");
                }
                appendRuns(sb, text);
                sb.append("</w:r></w:p></w:tc>");
            }
            sb.append("</w:tr>");
        }
        sb.append("</w:tbl>");
    }

    private static String stylesXml() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<w:styles xmlns:w=\"").append(NS_W).append("\">");
        sb.append("<w:docDefaults><w:rPrDefault><w:rPr>")
                .append("<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"宋体\" w:cs=\"Calibri\"/>")
                .append("<w:sz w:val=\"21\"/><w:szCs w:val=\"21\"/></w:rPr></w:rPrDefault>")
                .append("<w:pPrDefault><w:pPr><w:spacing w:after=\"100\" w:line=\"300\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>")
                .append("</w:docDefaults>");
        sb.append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">")
                .append("<w:name w:val=\"Normal\"/><w:qFormat/></w:style>");
        sb.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading1\">")
                .append("<w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>")
                .append("<w:pPr><w:keepNext/><w:spacing w:before=\"320\" w:after=\"160\"/><w:outlineLvl w:val=\"0\"/></w:pPr>")
                .append("<w:rPr><w:b/><w:sz w:val=\"32\"/><w:szCs w:val=\"32\"/></w:rPr></w:style>");
        sb.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading2\">")
                .append("<w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>")
                .append("<w:pPr><w:keepNext/><w:spacing w:before=\"260\" w:after=\"120\"/><w:outlineLvl w:val=\"1\"/></w:pPr>")
                .append("<w:rPr><w:b/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/></w:rPr></w:style>");
        sb.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading3\">")
                .append("<w:name w:val=\"heading 3\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>")
                .append("<w:pPr><w:keepNext/><w:spacing w:before=\"220\" w:after=\"100\"/><w:outlineLvl w:val=\"2\"/></w:pPr>")
                .append("<w:rPr><w:b/><w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/></w:rPr></w:style>");
        sb.append("<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/>")
                .append("<w:tblPr><w:tblBorders>")
                .append("<w:top w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>")
                .append("<w:left w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>")
                .append("<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>")
                .append("<w:right w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>")
                .append("<w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>")
                .append("<w:insideV w:val=\"single\" w:sz=\"4\" w:color=\"BFBFBF\"/>")
                .append("</w:tblBorders></w:tblPr></w:style>");
        sb.append("</w:styles>");
        return sb.toString();
    }

    /** 便于日志统计的粗粒度内容摘要。 */
    public static String summarize(PdfStructureTool.Document doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("pages=").append(doc.pageCount)
                .append(" headings=").append(doc.headingCount)
                .append(" paragraphs=").append(doc.paragraphCount)
                .append(" tables=").append(doc.tableCount)
                .append(" cells=").append(doc.cellCount);
        List<PdfStructureTool.PageStruct> pages = doc.pages;
        if (!pages.isEmpty()) {
            sb.append(" blocks0=").append(pages.get(0).blocks.size());
        }
        return sb.toString();
    }
}
