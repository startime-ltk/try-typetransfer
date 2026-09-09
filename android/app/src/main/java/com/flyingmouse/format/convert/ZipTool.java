package com.flyingmouse.format.convert;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** ZIP 解压：把 zip 中的文本类条目解出为纯文本（移动端子集，用于 zip → 提取）。 */
public final class ZipTool {

    private ZipTool() {
    }

    /** 解压 zip 中第一个可读文本条目为字符串；没有文本则抛出异常。 */
    public static String extractFirstText(InputStream zipIn, String wantedName) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(zipIn, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            StringBuilder best = new StringBuilder();
            String bestName = null;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                String lower = name.toLowerCase();
                if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")
                        || lower.endsWith(".json") || lower.endsWith(".xml") || lower.endsWith(".csv")
                        || lower.endsWith(".html") || lower.endsWith(".htm")) {
                    byte[] buf = new byte[8192];
                    int n;
                    StringBuilder sb = new StringBuilder();
                    while ((n = zis.read(buf)) > 0) {
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        if (sb.length() > 8 * 1024 * 1024) break;
                    }
                    // 优先找与 wantedName 最相近的条目
                    if (bestName == null || (wantedName != null && !bestName.contains(wantedName) && name.contains(wantedName))) {
                        best.setLength(0);
                        best.append(sb);
                        bestName = name;
                        if (wantedName != null && name.contains(wantedName)) break;
                    }
                }
            }
            if (bestName == null) throw new IOException("ZIP 中没有可提取的文本文件");
            return best.toString();
        }
    }
}
