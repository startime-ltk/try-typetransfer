package com.flyingmouse.format.convert;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * MOBI 基础解析（纯 Java，移植桌面版 ebook.js parseMobiText）：
 * PDB 容器（PalmDB header + 记录表）+ PalmDOC header + zlib/raw deflate 文本记录探测解压。
 * 返回清洗后的正文 HTML 片段，交由 TextTool 转 txt/markdown。
 */
public final class MobiReader {

    private MobiReader() {
    }

    public static String parseMobiText(byte[] buffer) throws Exception {
        if (buffer.length < 78) throw new Exception("MOBI 解析失败：文件头不完整。");
        int numRecords = u16be(buffer, 76);
        int recordListOffset = 78;
        if (recordListOffset + (numRecords + 1) * 8 > buffer.length || numRecords <= 0 || numRecords > 20000) {
            throw new Exception("MOBI 解析失败：记录数不合法。");
        }
        int[] offsets = new int[numRecords + 1];
        for (int i = 0; i <= numRecords; i++) {
            offsets[i] = (int) u32be(buffer, recordListOffset + i * 8);
        }
        int record0 = offsets[0];
        if (record0 + 16 > buffer.length) throw new Exception("MOBI 解析失败：PalmDOC 头缺失。");
        int recordCount = u16be(buffer, record0 + 8);
        if (recordCount <= 0 || recordCount > numRecords) throw new Exception("MOBI 解析失败：文本记录数不合法。");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 1; i <= recordCount && i < offsets.length; i++) {
            int start = offsets[i];
            int end = (i + 1 < offsets.length && offsets[i + 1] > start) ? offsets[i + 1] : buffer.length;
            if (start >= end || start >= buffer.length) break;
            int len = Math.min(end, buffer.length) - start;
            byte[] chunk = new byte[len];
            System.arraycopy(buffer, start, chunk, 0, len);
            // 自动探测：zlib（带/不带 2 字节长度前缀）、raw deflate，全部失败按明文追加
            byte[] decompressed = tryInflate(chunk, 0, false);
            if (decompressed == null) decompressed = tryInflate(chunk, 0, true);
            if (decompressed == null && chunk.length > 2) decompressed = tryInflate(chunk, 2, false);
            if (decompressed == null && chunk.length > 2) decompressed = tryInflate(chunk, 2, true);
            out.write(decompressed != null ? decompressed : chunk);
        }

        String html = new String(out.toByteArray(), "UTF-8");
        String cleaned = html
                .replaceAll("(?is)<\\?xml[\\s\\S]*?\\?>", "")
                .replaceAll("(?is)<mbp:[^>]*>[\\s\\S]*?</mbp:[^>]*>", "")
                .replaceAll("(?is)<mbp:[^>]*/?>", "")
                .replaceAll("(?is)<exthml[^>]*>[\\s\\S]*?</exthml>", "")
                .replaceAll("(?is)<html[^>]*>[\\s\\S]*?<body[^>]*>", "")
                .replaceAll("(?is)</body>[\\s\\S]*?</html>", "");
        if (cleaned.replaceAll("<[^>]+>", "").trim().isEmpty()) {
            throw new Exception("MOBI 解析失败：未提取到文本内容。");
        }
        return cleaned;
    }

    /** 尝试解压：skip 为跳过前缀字节数；raw=true 为 deflate 裸流。失败返回 null。 */
    private static byte[] tryInflate(byte[] data, int skip, boolean raw) {
        if (skip >= data.length) return null;
        Inflater inf = new Inflater(raw);
        try {
            inf.setInput(data, skip, data.length - skip);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break;
                    // 输出缓冲不够
                    if (buf.length < 65536) {
                        byte[] big = new byte[buf.length * 2];
                        System.arraycopy(buf, 0, big, 0, buf.length);
                        buf = big;
                        continue;
                    }
                    break;
                }
                bos.write(buf, 0, n);
            }
            inf.end();
            if (bos.size() == 0) return null;
            return bos.toByteArray();
        } catch (DataFormatException e) {
            inf.end();
            return null;
        }
    }

    private static int u16be(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32be(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((long) (b[off + 1] & 0xFF) << 16)
                | ((long) (b[off + 2] & 0xFF) << 8) | (long) (b[off + 3] & 0xFF);
    }
}
