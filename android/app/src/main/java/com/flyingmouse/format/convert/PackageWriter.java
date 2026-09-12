package com.flyingmouse.format.convert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 最小 OOXML 包写入 / 校验工具：xlsx 与 docx 均按 OPC（zip + XML）规范手工封装，
 * 零第三方依赖，离线可用。
 */
final class PackageWriter implements Closeable {

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
    private final ZipOutputStream zip = new ZipOutputStream(buffer);
    private int entries;

    PackageWriter() {
        zip.setLevel(java.util.zip.Deflater.BEST_SPEED);
    }

    /** 写入一个 UTF-8 文本条目（XML）。 */
    void text(String name, String content) throws IOException {
        raw(name, content.getBytes(StandardCharsets.UTF_8));
    }

    void raw(String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0L);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
        entries++;
    }

    int entryCount() {
        return entries;
    }

    /** 结束写入并返回完整包字节。 */
    byte[] finish() throws IOException {
        zip.finish();
        zip.close();
        return buffer.toByteArray();
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }

    // ================= 校验 =================

    /** 读取包内某个条目的文本；不存在返回 null。 */
    static String readEntry(byte[] pkg, String name) {
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(pkg))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (name.equals(entry.getName())) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = zin.read(chunk)) > 0) out.write(chunk, 0, read);
                    return new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException ignored) {
            return null;
        }
        return null;
    }

    static Set<String> entryNames(byte[] pkg) {
        Set<String> names = new HashSet<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(pkg))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) names.add(entry.getName());
        } catch (IOException ignored) {
            return names;
        }
        return names;
    }

    /** 完整性校验：关键条目齐全、无空条目、包非空。 */
    static boolean isPackageValid(byte[] pkg, String... requiredEntries) {
        if (pkg == null || pkg.length < 512) return false;
        Set<String> names = entryNames(pkg);
        for (String name : requiredEntries) {
            if (!names.contains(name)) return false;
            String content = readEntry(pkg, name);
            if (content == null || content.isEmpty()) return false;
        }
        return names.contains("[Content_Types].xml");
    }

    /** XML 文本转义（含属性场景）。 */
    static String xml(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 判断字符串是否可安全写成 xlsx 数值单元格。 */
    static boolean isNumeric(String text) {
        if (text == null) return false;
        String s = text.trim();
        if (s.isEmpty() || s.length() > 24) return false;
        if (s.indexOf(',') >= 0) s = s.replace(",", "");
        boolean hasDot = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' && i == 0) continue;
            if (c == '.') {
                if (hasDot) return false;
                hasDot = true;
                continue;
            }
            if (c < '0' || c > '9') return false;
        }
        try {
            double value = Double.parseDouble(s);
            if (!Double.isFinite(value)) return false;
        } catch (NumberFormatException e) {
            return false;
        }
        // 纯整数且位数较多（如手机号 / 长编号）保留文本，避免被科学计数或丢前导信息
        if (!hasDot && s.replace("-", "").length() > 11) return false;
        if (!hasDot && s.length() > 1 && s.charAt(0) == '0') return false;
        return true;
    }
}
