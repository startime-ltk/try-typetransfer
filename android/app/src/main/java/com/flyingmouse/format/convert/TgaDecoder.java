package com.flyingmouse.format.convert;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * TGA 纯 Java 解码器（子集）。
 * 支持：类型 2（未压缩真彩）/ 类型 10（RLE 真彩），24/32 位；
 * 8 位灰度（类型 3/11）一并对齐为灰度图。原点按 descriptor 位 5 处理（默认左下）。
 */
public final class TgaDecoder {

    private TgaDecoder() {
    }

    public static Bitmap decode(InputStream in) throws IOException {
        byte[] h = readBytes(in, 18);
        int idLen = h[0] & 0xFF;
        int colorMapType = h[1] & 0xFF;
        int imageType = h[2] & 0xFF;
        if (colorMapType != 0 && colorMapType != 1) throw new IOException("TGA 调色板类型异常: " + colorMapType);
        int w = (h[12] & 0xFF) | ((h[13] & 0xFF) << 8);
        int ht = (h[14] & 0xFF) | ((h[15] & 0xFF) << 8);
        int depth = h[16] & 0xFF;
        int descriptor = h[17] & 0xFF;
        boolean topOrigin = (descriptor & 0x20) != 0;
        if (w <= 0 || w > 16384 || ht <= 0 || ht > 16384) throw new IOException("TGA 尺寸异常: " + w + "x" + ht);
        if (idLen > 0) readBytes(in, idLen);
        // 跳过调色板数据（真彩图通常无调色板；有则跳过）
        int cmapEntries = (h[5] & 0xFF) | ((h[6] & 0xFF) << 8);
        int cmapEntrySize = h[7] & 0xFF;
        if (colorMapType == 1 && cmapEntries > 0 && cmapEntrySize > 0) {
            readBytes(in, cmapEntries * ((cmapEntrySize + 7) / 8));
        }

        int[] pixels = new int[w * ht];
        switch (imageType) {
            case 2:
                readUncompressed(in, pixels, w, ht, depth, topOrigin);
                break;
            case 10:
                readRle(in, pixels, w, ht, depth, topOrigin);
                break;
            case 3:
            case 11:
                readGray(in, pixels, w, ht, depth, imageType, topOrigin);
                break;
            default:
                throw new IOException("暂不支持 TGA 类型: " + imageType);
        }
        return Bitmap.createBitmap(pixels, w, ht, Bitmap.Config.ARGB_8888);
    }

    private static void readUncompressed(InputStream in, int[] pixels, int w, int h, int depth, boolean topOrigin) throws IOException {
        int bytes = depth / 8;
        int total = w * h;
        byte[] row = new byte[w * bytes];
        for (int y = 0; y < h; y++) {
            readFully(in, row);
            int dstY = topOrigin ? y : (h - 1 - y);
            fillPixels(pixels, row, dstY * w, w, bytes);
        }
        if (bytes < 1) throw new IOException("TGA 位深异常: " + depth);
    }

    private static void readRle(InputStream in, int[] pixels, int w, int h, int depth, boolean topOrigin) throws IOException {
        int bytes = depth / 8;
        if (bytes < 1) throw new IOException("TGA 位深异常: " + depth);
        int total = w * h;
        int idx = 0;
        byte[] px = new byte[bytes];
        while (idx < total) {
            int packet = in.read();
            if (packet < 0) throw new IOException("TGA RLE 数据截断");
            int count = (packet & 0x7F) + 1;
            if (idx + count > total) count = total - idx;
            if ((packet & 0x80) != 0) {
                readFully(in, px);
                for (int i = 0; i < count; i++) setPixel(pixels, idx + i, w, h, px, bytes, topOrigin);
            } else {
                for (int i = 0; i < count; i++) {
                    readFully(in, px);
                    setPixel(pixels, idx + i, w, h, px, bytes, topOrigin);
                }
            }
            idx += count;
        }
    }

    private static void readGray(InputStream in, int[] pixels, int w, int h, int depth, int imageType, boolean topOrigin) throws IOException {
        int bytes = depth / 8;
        if (bytes != 1) throw new IOException("灰度 TGA 位深异常: " + depth);
        int total = w * h;
        byte[] row = new byte[w];
        for (int y = 0; y < h; y++) {
            if (imageType == 3) {
                readFully(in, row);
            } else {
                // RLE 灰度
                int x = 0;
                while (x < w) {
                    int packet = in.read();
                    if (packet < 0) throw new IOException("TGA RLE 数据截断");
                    int count = (packet & 0x7F) + 1;
                    if (x + count > w) count = w - x;
                    if ((packet & 0x80) != 0) {
                        int g = in.read();
                        if (g < 0) throw new IOException("TGA 数据截断");
                        for (int i = 0; i < count; i++) row[x + i] = (byte) g;
                    } else {
                        for (int i = 0; i < count; i++) {
                            int g = in.read();
                            if (g < 0) throw new IOException("TGA 数据截断");
                            row[x + i] = (byte) g;
                        }
                    }
                    x += count;
                }
            }
            int dstY = topOrigin ? y : (h - 1 - y);
            for (int x = 0; x < w; x++) {
                int g = row[x] & 0xFF;
                pixels[dstY * w + x] = (0xFF << 24) | (g << 16) | (g << 8) | g;
            }
        }
    }

    private static void fillPixels(int[] pixels, byte[] row, int start, int w, int bytes) {
        for (int x = 0; x < w; x++) {
            int o = x * bytes;
            int b = row[o] & 0xFF;
            int g = bytes > 1 ? (row[o + 1] & 0xFF) : b;
            int r = bytes > 2 ? (row[o + 2] & 0xFF) : b;
            int a = bytes > 3 ? (row[o + 3] & 0xFF) : 0xFF;
            pixels[start + x] = (a << 24) | (r << 16) | (g << 8) | b;
        }
    }

    private static void setPixel(int[] pixels, int idx, int w, int h, byte[] px, int bytes, boolean topOrigin) {
        int y = idx / w;
        int x = idx % w;
        int dstY = topOrigin ? y : (h - 1 - y);
        int b = px[0] & 0xFF;
        int g = bytes > 1 ? (px[1] & 0xFF) : b;
        int r = bytes > 2 ? (px[2] & 0xFF) : b;
        int a = bytes > 3 ? (px[3] & 0xFF) : 0xFF;
        pixels[dstY * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static byte[] readBytes(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        readFully(in, b);
        return b;
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int r = in.read(b, off, b.length - off);
            if (r < 0) throw new IOException("TGA 数据截断");
            off += r;
        }
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > max) throw new IOException("TGA 文件过大");
        }
        return bos.toByteArray();
    }
}
