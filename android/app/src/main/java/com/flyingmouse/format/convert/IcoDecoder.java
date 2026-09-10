package com.flyingmouse.format.convert;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * ICO 纯 Java 解码器。
 * 支持目录内嵌 PNG 条目（现代图标主流）与经典 BMP(DIB) 条目（32/24 位，非压缩）。
 * 自动选取目录中尺寸最大且可解码的条目。
 */
public final class IcoDecoder {

    private IcoDecoder() {
    }

    public static Bitmap decode(InputStream in) throws IOException {
        byte[] all = readAll(in, 64 * 1024 * 1024);
        if (all.length < 6) throw new IOException("ICO 文件过短");
        if ((all[0] & 0xFF) != 0 || (all[1] & 0xFF) != 0) throw new IOException("非 ICO 文件（保留字段错误）");
        if ((all[2] & 0xFF) != 1 && (all[2] & 0xFF) != 2) throw new IOException("非 ICO 文件（类型错误）");
        int count = ((all[4] & 0xFF)) | ((all[5] & 0xFF) << 8);
        if (count <= 0 || count > 256) throw new IOException("ICO 目录条目数异常: " + count);
        int header = 6 + count * 16;
        if (all.length < header) throw new IOException("ICO 目录越界");

        // 先收集 PNG 条目，再收集 BMP 条目，优先 PNG、其次大尺寸
        int[] widths = new int[count];
        int[] heights = new int[count];
        int[] offsets = new int[count];
        int[] sizes = new int[count];
        for (int i = 0; i < count; i++) {
            int p = 6 + i * 16;
            int w = all[p] & 0xFF;
            int h = all[p + 1] & 0xFF;
            widths[i] = w == 0 ? 256 : w;
            heights[i] = h == 0 ? 256 : h;
            offsets[i] = le32(all, p + 12);
            sizes[i] = le32(all, p + 8);
        }

        IOException last = null;
        // 第一轮：PNG 条目，按尺寸从大到小
        int[] order = orderByArea(count, widths, heights);
        for (int idx : order) {
            byte[] body = slice(all, offsets[idx], sizes[idx]);
            if (isPng(body)) {
                try {
                    Bitmap b = BitmapFactory.decodeByteArray(body, 0, body.length);
                    if (b != null) return b;
                } catch (Exception e) {
                    last = e instanceof IOException ? (IOException) e : new IOException("PNG 条目解码失败", e);
                }
            }
        }
        // 第二轮：BMP(DIB) 条目，按尺寸从大到小
        for (int idx : order) {
            byte[] body = slice(all, offsets[idx], sizes[idx]);
            if (isPng(body)) continue;
            try {
                Bitmap b = decodeDib(body);
                if (b != null) return b;
            } catch (Exception e) {
                last = e instanceof IOException ? (IOException) e : new IOException("BMP 条目解码失败", e);
            }
        }
        if (last != null) throw last;
        throw new IOException("ICO 中没有可解码的图像条目");
    }

    /** ICO 内嵌 DIB：BITMAPINFOHEADER(40) + 调色板(可选) + XOR 像素 + AND 掩码（高度含掩码，实际取半） */
    private static Bitmap decodeDib(byte[] d) throws IOException {
        if (d.length < 40) throw new IOException("DIB 头过短");
        int biSize = le32(d, 0);
        if (biSize < 40) throw new IOException("不支持的 DIB 头大小: " + biSize);
        int w = le32(d, 4);
        int hRaw = le32(d, 8);
        if (w <= 0 || w > 8192) throw new IOException("ICO 宽度异常: " + w);
        int h = Math.abs(hRaw) / 2; // ICO 高度为两倍（含 AND mask）
        if (h <= 0 || h > 8192) throw new IOException("ICO 高度异常: " + h);
        int planes = le16(d, 12);
        int bpp = le16(d, 14);
        int compression = le32(d, 16);
        if (planes != 1) throw new IOException("不支持的 planes: " + planes);
        if (compression != 0) throw new IOException("不支持压缩的 DIB: " + compression);
        if (bpp != 32 && bpp != 24) throw new IOException("暂支持 32/24 位 ICO 条目，实际 " + bpp);

        int headerSize = biSize;
        // 调色板仅 <=8bpp 存在；32/24bpp 无
        int paletteBytes = bpp <= 8 ? (1 << bpp) * 4 : 0;
        int pxOffset = headerSize + paletteBytes;
        int xorStride = ((w * bpp + 31) / 32) * 4;
        int andStride = ((w + 31) / 32) * 4;
        int need = pxOffset + xorStride * h + andStride * h;
        if (d.length < need) throw new IOException("DIB 像素数据越界");

        int[] pixels = new int[w * h];
        boolean bottomUp = hRaw > 0;
        for (int y = 0; y < h; y++) {
            int srcY = bottomUp ? (h - 1 - y) : y;
            int rowStart = pxOffset + srcY * xorStride;
            for (int x = 0; x < w; x++) {
                int pos = rowStart + x * (bpp / 8);
                int b = d[pos] & 0xFF;
                int g = d[pos + 1] & 0xFF;
                int r = d[pos + 2] & 0xFF;
                int a = bpp == 32 ? (d[pos + 3] & 0xFF) : 255;
                pixels[y * w + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
    }

    private static boolean isPng(byte[] b) {
        return b.length >= 8
                && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
    }

    private static int[] orderByArea(int count, int[] ws, int[] hs) {
        Integer[] idx = new Integer[count];
        for (int i = 0; i < count; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> {
            long areaB = (long) ws[b] * hs[b];
            long areaA = (long) ws[a] * hs[a];
            return Long.compare(areaB, areaA);
        });
        int[] r = new int[count];
        for (int i = 0; i < count; i++) r[i] = idx[i];
        return r;
    }

    private static byte[] slice(byte[] all, int off, int len) throws IOException {
        if (off < 0 || len < 0 || off + len > all.length) throw new IOException("ICO 图像块越界");
        byte[] out = new byte[len];
        System.arraycopy(all, off, out, 0, len);
        return out;
    }

    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > max) throw new IOException("ICO 文件过大（>" + (max >> 20) + "MB）");
        }
        return bos.toByteArray();
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    // 供测试复用
    static ByteArrayInputStream toStream(byte[] ico) {
        return new ByteArrayInputStream(ico);
    }
}
