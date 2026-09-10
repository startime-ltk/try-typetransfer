package com.flyingmouse.format.convert;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 图片格式互转（png/jpg/webp/bmp）。BMP 输出为 24 位非压缩，纯 Java 编码。 */
public final class ImageTool {

    private ImageTool() {
    }

    public static Bitmap decode(InputStream in) throws IOException {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
        if (bmp == null) throw new IOException("无法解码该图片（可能损坏或格式不支持）");
        return bmp;
    }

    /** 按扩展名解码；avif/heic 走系统解码，ico/tga 走纯 Java 解码器。 */
    public static Bitmap decodeByExt(InputStream in, String ext) throws IOException {
        String e = ext == null ? "" : ext.toLowerCase(java.util.Locale.ROOT);
        switch (e) {
            case "ico":
                return IcoDecoder.decode(in);
            case "tga":
                return TgaDecoder.decode(in);
            case "avif":
            case "heic":
            case "heif":
                if (android.os.Build.VERSION.SDK_INT < 28) {
                    throw new IOException("该系统版本不支持解码 ." + e + "（需 Android 9+）");
                }
                try {
                    Bitmap b = decode(in);
                    if (b != null) return b;
                    throw new IOException("系统无法解码 ." + e + " 图片");
                } catch (IOException ex) {
                    throw ex;
                } catch (Exception ex) {
                    throw new IOException("系统无法解码 ." + e + " 图片：" + ex.getMessage(), ex);
                }
            default:
                return decode(in);
        }
    }

    /** 按目标扩展名编码并写出；bmp 由内部编码器处理。 */
    public static void encode(Bitmap src, String targetExt, OutputStream out) throws IOException {
        switch (targetExt.toLowerCase()) {
            case "png":
                src.compress(Bitmap.CompressFormat.PNG, 100, out);
                break;
            case "jpg":
            case "jpeg":
                // 白底填充，避免 JPEG 无 alpha 时黑底
                Bitmap flat = flattenWhite(src);
                flat.compress(Bitmap.CompressFormat.JPEG, 92, out);
                if (flat != src) flat.recycle();
                break;
            case "webp":
                src.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, out);
                break;
            case "bmp":
                writeBmp24(src, out);
                break;
            default:
                throw new IOException("不支持的图片输出格式: " + targetExt);
        }
    }

    public static byte[] encodeToBytes(Bitmap src, String targetExt) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        encode(src, targetExt, bos);
        return bos.toByteArray();
    }

    public static Bitmap flattenWhite(Bitmap src) {
        if (!src.hasAlpha()) return src;
        Bitmap flat = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        flat.eraseColor(Color.WHITE);
        android.graphics.Canvas c = new android.graphics.Canvas(flat);
        c.drawBitmap(src, 0f, 0f, null);
        return flat;
    }

    /** 写 24 位 BMP（从下到上 BGR）。 */
    public static void writeBmp24(Bitmap src, OutputStream out) throws IOException {
        int w = src.getWidth();
        int h = src.getHeight();
        int rowBytes = (w * 3 + 3) & ~3;
        int pixelArraySize = rowBytes * h;
        int fileSize = 54 + pixelArraySize;

        writeLeInt(out, 0x4D42); // 'BM' (2字节)
        writeLeIntFull(out, fileSize);
        writeLeInt(out, 0);      // 保留
        writeLeIntFull(out, 54); // 像素数据偏移

        // BITMAPINFOHEADER (40)
        writeLeIntFull(out, 40);
        writeLeIntFull(out, w);
        writeLeIntFull(out, h); // 正值 = 自下而上
        writeLeInt(out, 1);     // planes
        writeLeInt(out, 24);    // bpp
        writeLeIntFull(out, 0); // compression = BI_RGB
        writeLeIntFull(out, pixelArraySize);
        writeLeIntFull(out, 2835); // 水平分辨率 72dpi
        writeLeIntFull(out, 2835);
        writeLeIntFull(out, 0);
        writeLeIntFull(out, 0);

        byte[] row = new byte[rowBytes];
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);
        for (int y = h - 1; y >= 0; y--) {
            int p = 0;
            for (int x = 0; x < w; x++) {
                int c = pixels[y * w + x];
                row[p++] = (byte) (c & 0xFF);        // B
                row[p++] = (byte) ((c >> 8) & 0xFF); // G
                row[p++] = (byte) ((c >> 16) & 0xFF);// R
            }
            out.write(row, 0, rowBytes);
        }
    }

    private static void writeLeInt(OutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
    }

    private static void writeLeIntFull(OutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }
}
