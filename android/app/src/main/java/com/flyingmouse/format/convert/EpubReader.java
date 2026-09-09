package com.flyingmouse.format.convert;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** EPUB（本质 zip）内容提取：读取第一个 xhtml 文档全文。 */
public final class EpubReader {

    private EpubReader() {
    }

    public static String extractFirstXhtml(InputStream in) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            StringBuilder first = new StringBuilder();
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String lower = entry.getName().toLowerCase();
                if (lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        first.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        if (first.length() > 16 * 1024 * 1024) break;
                    }
                    break;
                }
            }
            if (first.length() == 0) throw new IOException("EPUB 中没有可读的正文");
            return first.toString();
        }
    }
}
