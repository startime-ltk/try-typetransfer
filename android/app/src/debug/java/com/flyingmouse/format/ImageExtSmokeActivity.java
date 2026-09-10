package com.flyingmouse.format;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;

import com.flyingmouse.format.convert.ImageTool;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Debug-only P3 图片扩展输入冒烟：合成 ICO（PNG 条目 + DIB 条目）与 TGA（未压缩/RLE）
 * → 走真实 ImageTool.decodeByExt → PNG/JPG/WEBP 输出校验。
 * adb 启动后看 logcat tag ImageExtSmoke。
 * 启动组件：com.flyingmouse.format.debug/com.flyingmouse.format.ImageExtSmokeActivity
 */
public class ImageExtSmokeActivity extends Activity {
    private static final String TAG = "ImageExtSmoke";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
        new Thread(() -> {
            try {
                File dir = getCacheDir();
                // ---- 样本 1：ICO（PNG 条目 32x32）----
                byte[] png = makePng(32, 32, true);
                byte[] icoPng = makeIcoPngEntry(png);
                File icoFile = new File(dir, "smoke_icon.ico");
                write(icoFile, icoPng);
                Log.w(TAG, "samples ico(png-entry)=" + icoPng.length);

                // ---- 样本 2：ICO（DIB 条目 32bit 16x16）----
                byte[] icoDib = makeIcoDibEntry(16, 16);
                File icoDibFile = new File(dir, "smoke_dib.ico");
                write(icoDibFile, icoDib);
                Log.w(TAG, "samples ico(dib-entry)=" + icoDib.length);

                // ---- 样本 3：TGA 未压缩 24bit 20x10 ----
                File tgaRaw = new File(dir, "smoke_raw.tga");
                write(tgaRaw, makeTga(20, 10, false, 24, false));
                Log.w(TAG, "samples tga(raw24)=" + tgaRaw.length());

                // ---- 样本 4：TGA RLE 32bit 20x10（含透明） ----
                File tgaRle = new File(dir, "smoke_rle.tga");
                write(tgaRle, makeTga(20, 10, true, 32, true));
                Log.w(TAG, "samples tga(rle32)=" + tgaRle.length());

                runFile(icoFile, "ico-png-entry", 32, 32);
                runFile(icoDibFile, "ico-dib-entry", 16, 16);
                runFile(tgaRaw, "tga-raw24", 20, 10);
                runFile(tgaRle, "tga-rle32", 20, 10);

                // 汇总页格式注册自检
                boolean regOk = com.flyingmouse.format.util.FormatKit.isSupportedExt("ico")
                        && com.flyingmouse.format.util.FormatKit.isSupportedExt("tga")
                        && com.flyingmouse.format.util.FormatKit.isSupportedExt("avif")
                        && "image".equals(com.flyingmouse.format.util.FormatKit.categoryOf("heic"));
                Log.w(TAG, "FORMATKIT_REG=" + regOk);
                Log.w(TAG, "ALL_DONE");
            } catch (Throwable t) {
                Log.w(TAG, "FATAL " + t);
            }
        }).start();
    }

    /** decodeByExt 后分别输出 png/jpg/webp，检查非空且尺寸正确 */
    private void runFile(File f, String label, int expectW, int expectH) {
        try {
            byte[] bytes = readBytes(f);
            for (String out : new String[]{"png", "jpg", "webp"}) {
                Bitmap bmp = ImageTool.decodeByExt(new ByteArrayInputStream(bytes), label.startsWith("ico") ? "ico" : "tga");
                int w = bmp.getWidth();
                int h = bmp.getHeight();
                byte[] outData = ImageTool.encodeToBytes(bmp, out);
                bmp.recycle();
                boolean ok = outData.length > 0 && w == expectW && h == expectH;
                Log.w(TAG, label + "→" + out + " RESULT size=" + w + "x" + h
                        + " bytes=" + outData.length + " OK=" + ok);
            }
        } catch (Throwable t) {
            Log.w(TAG, label + " FAIL " + t);
        }
    }

    private static byte[] makePng(int w, int h, boolean alpha) throws IOException {
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (x * 255) / (w - 1);
                int g = (y * 255) / (h - 1);
                int b = 128;
                int a = alpha ? (x % 2 == 0 ? 255 : 120) : 255;
                bmp.setPixel(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        bmp.recycle();
        return bos.toByteArray();
    }

    /** ICO：单个 PNG 条目 */
    private static byte[] makeIcoPngEntry(byte[] png) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(0); bos.write(0); // reserved
        bos.write(1); bos.write(0); // type=1 icon
        bos.write(1); bos.write(0); // count=1
        bos.write(32); bos.write(32); // 32x32
        bos.write(0); bos.write(0);
        bos.write(1); bos.write(0); // planes
        bos.write(32); bos.write(0); // bpp
        bos.write(png.length & 0xFF); bos.write((png.length >> 8) & 0xFF); bos.write((png.length >> 16) & 0xFF); bos.write((png.length >> 24) & 0xFF);
        bos.write(22); bos.write(0); bos.write(0); bos.write(0); // offset = 6 + 16 = 22
        try {
            bos.write(png);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    /** ICO：单个 32bit DIB 条目，底上行序（height=2*h 含 AND mask） */
    private static byte[] makeIcoDibEntry(int w, int h) {
        int xorStride = ((w * 32 + 31) / 32) * 4;
        int andStride = ((w + 31) / 32) * 4;
        int dibHeader = 40;
        int totalDib = dibHeader + xorStride * h + andStride * h;
        byte[] dib = new byte[totalDib];
        putLe32(dib, 0, 40);      // biSize
        putLe32(dib, 4, w);
        putLe32(dib, 8, h * 2);   // ICO DIB 高度 = 2x（含 AND）
        putLe16(dib, 12, 1);      // planes
        putLe16(dib, 14, 32);     // bpp
        putLe32(dib, 16, 0);      // BI_RGB
        putLe32(dib, 20, xorStride * h + andStride * h);
        // XOR 像素（自底向上），BGR + A=255
        int pxStart = dibHeader;
        for (int y = 0; y < h; y++) {
            int rowStart = pxStart + (h - 1 - y) * xorStride;
            for (int x = 0; x < w; x++) {
                int r = (x * 255) / (w - 1);
                int g = (y * 255) / (h - 1);
                int o = rowStart + x * 4;
                dib[o] = (byte) 100;
                dib[o + 1] = (byte) g;
                dib[o + 2] = (byte) r;
                dib[o + 3] = (byte) 255;
            }
        }
        // AND mask 全 0（不透明）
        int andStart = pxStart + xorStride * h;
        for (int y = 0; y < h; y++) {
            // 顶部行序：AND mask 与 XOR 行顺序一致
            int rowStart = andStart + (h - 1 - y) * andStride;
            for (int x = 0; x < andStride; x++) dib[rowStart + x] = 0;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(0); bos.write(0);
        bos.write(1); bos.write(0);
        bos.write(1); bos.write(0);
        bos.write(w); bos.write(h); // 目录项尺寸（实际单倍，0 表示 256）
        bos.write(0); bos.write(0);
        bos.write(1); bos.write(0);
        bos.write(32); bos.write(0);
        int len = dib.length;
        bos.write(len & 0xFF); bos.write((len >> 8) & 0xFF); bos.write((len >> 16) & 0xFF); bos.write((len >> 24) & 0xFF);
        bos.write(22); bos.write(0); bos.write(0); bos.write(0);
        try {
            bos.write(dib);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    /** TGA：type2 未压缩 / type10 RLE，24/32 位；原点左下（descriptor=0）或左上（topLeft） */
    private static byte[] makeTga(int w, int h, boolean rle, int bpp, boolean topLeft) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(0);                    // idLength
        bos.write(0);                    // colorMapType
        bos.write(rle ? 10 : 2);         // imageType
        bos.write(0); bos.write(0); bos.write(0); bos.write(0); bos.write(0); // cmap spec
        bos.write(0); bos.write(0);      // x origin
        bos.write(0); bos.write(0);      // y origin
        bos.write(w & 0xFF); bos.write((w >> 8) & 0xFF);
        bos.write(h & 0xFF); bos.write((h >> 8) & 0xFF);
        bos.write(bpp);                  // pixelDepth
        bos.write(topLeft ? 0x20 : 0x00); // descriptor
        int bppB = bpp / 8;
        byte[] scan = new byte[w * bppB];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (x * 255) / (w - 1);
                int g = (y * 255) / (h - 1);
                int o = x * bppB;
                scan[o] = (byte) 100;
                scan[o + 1] = (byte) g;
                scan[o + 2] = (byte) r;
                if (bppB > 3) scan[o + 3] = (byte) (x % 3 == 0 ? 100 : 255);
            }
            if (!rle) {
                try {
                    bos.write(scan);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                // 简单 RLE：整行作为 literal 包（count = w-1）
                if (w > 1) {
                    bos.write(w - 1); // literal, count-1
                    try {
                        bos.write(scan);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
        return bos.toByteArray();
    }

    private static void putLe16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
    }

    private static byte[] readBytes(File f) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
