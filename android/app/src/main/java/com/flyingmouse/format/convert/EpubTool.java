package com.flyingmouse.format.convert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 极简 EPUB3 生成器：txt / markdown / html 内容包成 EPUB。
 * 结构与桌面版 ebook.js 同构（mimetype + container.xml + content.opf + xhtml）。
 */
public final class EpubTool {

    private EpubTool() {
    }

    public static byte[] textToEpub(String title, String bodyXhtml) throws IOException {
        String uuid = UUID.randomUUID().toString();
        String date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8);

        ZipEntry mt = new ZipEntry("mimetype");
        mt.setMethod(ZipEntry.STORED);
        byte[] mimeBytes = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(mimeBytes);
        mt.setSize(mimeBytes.length);
        mt.setCompressedSize(mimeBytes.length);
        mt.setCrc(crc.getValue()); // 用实际 CRC，避免 Android libcore 对 STORED 的 CRC 校验失败
        zip.putNextEntry(mt);
        zip.write(mimeBytes);
        zip.closeEntry();

        writeEntry(zip, "META-INF/container.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                "  <rootfiles>\n" +
                "    <rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n" +
                "  </rootfiles>\n" +
                "</container>\n");

        String chapter = bodyXhtml;
        writeEntry(zip, "OEBPS/chapter1.xhtml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE html>\n" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\" xml:lang=\"zh\">\n" +
                "<head><title>" + escXml(title) + "</title></head>\n<body>\n" + chapter + "\n</body>\n</html>\n");

        String opf =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\">\n" +
                "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n" +
                "    <dc:identifier id=\"uid\">urn:uuid:" + uuid + "</dc:identifier>\n" +
                "    <dc:title>" + escXml(title) + "</dc:title>\n" +
                "    <dc:language>zh-CN</dc:language>\n" +
                "    <dc:date>" + date + "</dc:date>\n" +
                "    <meta property=\"dcterms:modified\">" + date + "</meta>\n" +
                "  </metadata>\n" +
                "  <manifest>\n" +
                "    <item id=\"nav\" href=\"chapter1.xhtml\" media-type=\"application/xhtml+xml\"/>\n" +
                "  </manifest>\n" +
                "  <spine>\n" +
                "    <itemref idref=\"nav\"/>\n" +
                "  </spine>\n" +
                "</package>\n";
        writeEntry(zip, "OEBPS/content.opf", opf);

        zip.finish();
        return bos.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String escXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
