package com.flyingmouse.format.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 输出保存器：将转换结果写入公共下载目录 FlyingMouseFormat/
 * - API 29+ 走 MediaStore.Downloads（无需权限）
 * - API 26-28 写 Environment.DIRECTORY_DOWNLOADS 下同目录（需 WRITE_EXTERNAL_STORAGE）
 */
public final class OutputSaver {

    public static final String SUB_DIR = "FlyingMouseFormat";

    private static final Map<String, String> MIME = new HashMap<>();

    static {
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("jpeg", "image/jpeg");
        MIME.put("webp", "image/webp");
        MIME.put("bmp", "image/bmp");
        MIME.put("pdf", "application/pdf");
        MIME.put("txt", "text/plain");
        MIME.put("md", "text/markdown");
        MIME.put("markdown", "text/markdown");
        MIME.put("html", "text/html");
        MIME.put("htm", "text/html");
        MIME.put("json", "application/json");
        MIME.put("xml", "application/xml");
        MIME.put("csv", "text/csv");
        MIME.put("zip", "application/zip");
        MIME.put("epub", "application/epub+zip");
    }

    private OutputSaver() {
    }

    public static String mimeOf(String ext) {
        String m = MIME.get(ext.toLowerCase(Locale.ROOT));
        return m == null ? "application/octet-stream" : m;
    }

    /** 保存单文件，返回 MediaStore/Uri 或文件 Uri。 */
    public static Uri save(Context ctx, byte[] data, String fileName, String ext) throws Exception {
        String mime = mimeOf(ext);
        ContentResolver cr = ctx.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new Exception("无法在下载目录创建文件");
            try (OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new Exception("无法写入输出文件");
                os.write(data);
            }
            return uri;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUB_DIR);
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建输出目录");
            File f = new File(dir, fileName);
            try (OutputStream os = new java.io.FileOutputStream(f)) {
                os.write(data);
            }
            return Uri.fromFile(f);
        }
    }
}
