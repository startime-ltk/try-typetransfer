package com.flyingmouse.format;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.flyingmouse.format.convert.ConversionEngine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Debug-only PDF OCR smoke test（P6c）。
 * 合成"图片型 PDF"（文字先画在 Bitmap 上、再贴进页面，故无文本层），
 * 验证 ConversionEngine 的 pdf -> txt / md 在无文本层时自动回退到 渲染 + 离线 OCR：
 *   - 命中关键字：你好 / SCAN
 * 启动组件：com.flyingmouse.format.debug/com.flyingmouse.format.PdfOcrSmokeActivity
 */
public class PdfOcrSmokeActivity extends Activity {
    private static final String TAG = "PdfOcrSmoke";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
        new Thread(() -> {
            try {
                File pdf = new File(getCacheDir(), "smoke-scan.pdf");
                try (FileOutputStream fos = new FileOutputStream(pdf)) {
                    fos.write(buildImagePdf());
                }
                Log.w(TAG, "pdf bytes=" + pdf.length());
                run(pdf, "txt", "你好", "SCAN");
                run(pdf, "md", "你好", "SCAN");
                Log.w(TAG, "ALL_DONE");
            } catch (Throwable t) {
                Log.w(TAG, "FATAL " + t);
            }
        }).start();
    }

    private void run(File in, String target, String... mustContain) {
        try {
            List<ConversionEngine.Output> outs =
                    ConversionEngine.convert(this, Uri.fromFile(in), in.getName(), target);
            ConversionEngine.Output o = outs.get(0);
            String text = new String(o.data, StandardCharsets.UTF_8);
            boolean ok = true;
            for (String m : mustContain) ok = ok && text.contains(m);
            String flat = text.replace("\n", "\\n");
            Log.w(TAG, in.getName() + "->" + target + " RESULT name=" + o.fileName
                    + " bytes=" + o.data.length + " contains=" + ok
                    + " head=" + (flat.length() > 160 ? flat.substring(0, 160) : flat));
        } catch (Throwable t) {
            Log.w(TAG, in.getName() + "->" + target + " FAIL " + t);
        }
    }

    /** 生成单页图片型 PDF（A4，150dpi 位图直接贴页），文件中不含可提取文本层。 */
    private static byte[] buildImagePdf() throws Exception {
        int w = 1240, h = 1754;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.BLACK);
        p.setTextSize(72);
        c.drawText("扫描件 OCR 测试", 90, 300, p);
        p.setTextSize(60);
        c.drawText("你好 OCR 2026", 90, 430, p);
        p.setTextSize(56);
        c.drawText("SCAN PAGE FlyingMouse Format", 90, 560, p);

        PdfDocument doc = new PdfDocument();
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(595, 842, 1).create();
        PdfDocument.Page page = doc.startPage(info);
        page.getCanvas().drawBitmap(bmp, 0f, 0f, new Paint(Paint.FILTER_BITMAP_FLAG));
        doc.finishPage(page);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.writeTo(bos);
        doc.close();
        bmp.recycle();
        return bos.toByteArray();
    }
}
