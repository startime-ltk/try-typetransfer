package com.flyingmouse.format;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.flyingmouse.format.convert.ConversionEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * P6 冒烟：合成一张中英文图片，走 ConversionEngine 的图片 OCR 分支（png → txt / md）。
 * 启动：adb shell am start -n com.flyingmouse.format.debug/com.flyingmouse.format.OcrSmokeActivity
 */
public class OcrSmokeActivity extends Activity {

    private static final String TAG = "OcrSmoke";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
        new Thread(this::run, "ocr-smoke").start();
    }

    private void run() {
        try {
            Bitmap bmp = Bitmap.createBitmap(960, 320, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            c.drawColor(Color.WHITE);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.BLACK);
            p.setTextSize(80);
            c.drawText("你好 OCR 2026", 40, 130, p);
            p.setTextSize(56);
            c.drawText("FlyingMouse Format", 40, 240, p);

            File f = new File(getCacheDir(), "ocr-smoke.png");
            try (FileOutputStream out = new FileOutputStream(f)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            bmp.recycle();
            Log.i(TAG, "IMAGE=" + f.getAbsolutePath() + " size=" + f.length());

            List<ConversionEngine.Output> txt = ConversionEngine.convert(this, Uri.fromFile(f), "ocr-smoke.png", "txt");
            Log.i(TAG, "TXT[" + txt.get(0).fileName + "]=" + new String(txt.get(0).data, StandardCharsets.UTF_8).replace("\n", " | "));

            List<ConversionEngine.Output> md = ConversionEngine.convert(this, Uri.fromFile(f), "ocr-smoke.png", "md");
            Log.i(TAG, "MD[" + md.get(0).fileName + "]=" + new String(md.get(0).data, StandardCharsets.UTF_8).replace("\n", " | "));

            Log.i(TAG, "ALL_DONE");
        } catch (Throwable t) {
            Log.e(TAG, "FAILED: " + t, t);
        }
    }
}
