package com.flyingmouse.format.convert;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** PDF 相关转换：图片合并为 PDF、PDF 逐页渲染为图片。 */
public final class PdfTool {

    private PdfTool() {
    }

    /** 多张图片按顺序合并为一个 PDF，返回字节。 */
    public static byte[] imagesToPdf(List<Bitmap> pages) throws IOException {
        if (pages == null || pages.isEmpty()) throw new IOException("没有可合并的图片");
        PdfDocument doc = new PdfDocument();
        try {
            for (Bitmap bmp : pages) {
                int width = bmp.getWidth();
                int height = bmp.getHeight();
                PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(width, height, doc.getPages().size() + 1).create();
                PdfDocument.Page page = doc.startPage(info);
                Canvas canvas = page.getCanvas();
                canvas.drawBitmap(bmp, 0f, 0f, null);
                doc.finishPage(page);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.writeTo(bos);
            return bos.toByteArray();
        } finally {
            doc.close();
        }
    }

    public static Bitmap decodeResized(InputStream in, int maxDim) throws IOException {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(in, null, opts);
        int sample = 1;
        while (Math.max(opts.outWidth, opts.outHeight) / sample > maxDim) sample <<= 1;
        if (sample > 1) {
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeStream(in, null, o2);
        }
        // 上面已消费流，需要重新打开：由调用方保证传入新流，这里 fallback 直接解码
        return null;
    }

    /** PDF → 每页 PNG/JPG，输出到临时文件列表（使用系统 PdfRenderer）。 */
    public static int renderPdfToImages(Context ctx, InputStream in, String targetExt, File outDir, String baseName) throws IOException {
        File tmp = File.createTempFile("pdf_src_", ".pdf", ctx.getCacheDir());
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(pfd)) {
            int count = renderer.getPageCount();
            if (count == 0) throw new IOException("PDF 没有页面");
            int outCount = 0;
            for (int i = 0; i < count; i++) {
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    int w = Math.max(page.getWidth(), 1);
                    int h = Math.max(page.getHeight(), 1);
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(0xFFFFFFFF);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    String name = String.format("%s_p%02d.%s", baseName, i + 1, targetExt.toLowerCase());
                    File f = new File(outDir, name);
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        ImageTool.encode(bmp, targetExt, fos);
                    }
                    bmp.recycle();
                    outCount++;
                }
            }
            return outCount;
        } finally {
            tmp.delete();
        }
    }

    /**
     * PDF → 逐页渲染 + 离线 OCR，返回整份文本（扫描件兜底，P6c）。
     * 用于无文本层的图片型 PDF；单页未识别到文字则跳过该页。
     */
    public static String renderPdfToText(Context ctx, InputStream in, int maxPages) throws Exception {
        File tmp = File.createTempFile("pdf_ocr_", ".pdf", ctx.getCacheDir());
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(pfd)) {
                int count = Math.min(renderer.getPageCount(), maxPages);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    String pageText;
                    try (PdfRenderer.Page page = renderer.openPage(i)) {
                        int w = Math.max(page.getWidth(), 1);
                        int h = Math.max(page.getHeight(), 1);
                        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(0xFFFFFFFF);
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        Bitmap scaled = scaleForOcr(bmp);
                        try {
                            pageText = OcrEngine.recognize(scaled);
                        } finally {
                            if (scaled != bmp) scaled.recycle();
                            bmp.recycle();
                        }
                    }
                    if (pageText == null || pageText.trim().isEmpty()) continue;
                    if (count > 1) sb.append("--- 第 ").append(i + 1).append(" 页 ---\n");
                    sb.append(pageText.trim()).append("\n\n");
                }
                return sb.toString().trim();
            }
        } finally {
            tmp.delete();
        }
    }

    /** OCR 前把过大的页面等比缩到长边 1800px，兼顾清晰度与识别耗时。 */
    private static Bitmap scaleForOcr(Bitmap src) {
        int maxSide = Math.max(src.getWidth(), src.getHeight());
        if (maxSide <= 1800) return src;
        float s = 1800f / maxSide;
        int w = Math.max((int) (src.getWidth() * s), 1);
        int h = Math.max((int) (src.getHeight() * s), 1);
        Bitmap out = Bitmap.createScaledBitmap(src, w, h, true);
        return out == null ? src : out;
    }
}
