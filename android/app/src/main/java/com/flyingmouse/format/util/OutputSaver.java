package com.flyingmouse.format.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出保存器：将转换结果写入公共下载目录 FlyingMouseFormat/
 * - API 29+ 走 MediaStore.Downloads（无需权限）
 * - API 26-28 写 Environment.DIRECTORY_DOWNLOADS 下同目录（需 WRITE_EXTERNAL_STORAGE）
 */
public final class OutputSaver {

    public static final String SUB_DIR = "FlyingMouseFormat";

    /**
     * 分享/打开前把产物 Uri 统一转成可外发的 content://：
     * - API 29+ 保存时已经是 MediaStore 的 content://media Uri，原样返回；
     * - API 26-28 保存时是 file:// Uri，而 API 24 起把它放进 EXTRA_STREAM / ACTION_VIEW
     *   会抛 FileUriExposedException，这里统一经 FileProvider
     *   （authorities = &lt;applicationId&gt;.fileprovider）转换。
     */
    public static Uri shareableUri(Context ctx, Uri uri) {
        if (uri == null || !ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme())) {
            return uri;
        }
        String path = uri.getPath();
        if (path == null) {
            return uri;
        }
        return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", new File(path));
    }

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
        MIME.put("mp3", "audio/mpeg");
        MIME.put("flac", "audio/flac");
        MIME.put("ogg", "audio/ogg");
        MIME.put("oga", "audio/ogg");
        MIME.put("m4a", "audio/mp4");
        MIME.put("aac", "audio/aac");
        MIME.put("wav", "audio/wav");
    }

    private OutputSaver() {
    }

    public static String mimeOf(String ext) {
        String m = MIME.get(ext.toLowerCase(Locale.ROOT));
        return m == null ? "application/octet-stream" : m;
    }

    /** 结尾「 (n)」形式的重复后缀（含连续叠加，如「a (1) (2).png」中的「 (1) (2)」） */
    private static final Pattern DEDUP_SUFFIX = Pattern.compile("\\s*\\(\\d+\\)$");

    /** 重名序号探测上限：超出后退化为时间戳命名，避免死循环 */
    private static final int MAX_PROBE = 200;

    /** 保存单文件，返回 MediaStore/Uri 或文件 Uri。 */
    public static Uri save(Context ctx, byte[] data, String fileName, String ext) throws Exception {
        String mime = mimeOf(ext);
        ContentResolver cr = ctx.getContentResolver();
        // 先算出不会层层叠加的落盘名，再交给 MediaStore / 文件系统写入
        String finalName = uniqueName(ctx, cr, fileName, ext);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, finalName);
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
            File f = new File(dir, finalName);
            try (OutputStream os = new java.io.FileOutputStream(f)) {
                os.write(data);
            }
            return Uri.fromFile(f);
        }
    }

    /**
     * 计算最终落盘文件名，保证反复转换同一个源文件时只出现「单层序号」，
     * 不会堆成 a (1) (1).png：
     * 1) 请求名本身空闲 → 原样使用；
     * 2) 已存在 → 剥掉结尾所有「 (n)」得到稳定基名 a，再按 a (1)、a (2)、a (3) … 取第一个空闲名；
     * 3) 探测超过 {@link #MAX_PROBE} 个 → 退化为 a (时间戳).ext。
     */
    static String uniqueName(Context ctx, ContentResolver cr, String fileName, String ext) {
        String stem = fileName;
        String suffix = "." + ext;
        int dot = fileName.lastIndexOf('.');
        if (dot > 0 && dot < fileName.length() - 1) {
            stem = fileName.substring(0, dot);
            suffix = fileName.substring(dot);
        }
        if (!isTaken(ctx, cr, fileName)) return fileName;
        String root = stripDedupSuffix(stem);
        for (int n = 1; n <= MAX_PROBE; n++) {
            String candidate = root + " (" + n + ")" + suffix;
            if (!isTaken(ctx, cr, candidate)) return candidate;
        }
        return root + " (" + System.currentTimeMillis() + ")" + suffix;
    }

    /** 剥掉结尾全部「 (n)」后缀：a (1) (2) → a；若会剥成空串则返回原值 */
    static String stripDedupSuffix(String stem) {
        String s = stem;
        Matcher m = DEDUP_SUFFIX.matcher(s);
        while (m.find()) {
            String next = s.substring(0, m.start());
            if (next.isEmpty()) return stem;
            s = next;
            m = DEDUP_SUFFIX.matcher(s);
        }
        return s;
    }

    /** 目标目录（下载/FlyingMouseFormat）下是否已有同名条目 */
    private static boolean isTaken(Context ctx, ContentResolver cr, String displayName) {
        if (Build.VERSION.SDK_INT >= 29) {
            String relPath = Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR + "/";
            try (Cursor c = cr.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{displayName, relPath},
                    null)) {
                return c != null && c.getCount() > 0;
            } catch (Exception ignored) {
                return false;
            }
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUB_DIR);
        return new File(dir, displayName).exists();
    }

    /**
     * 读取产物在系统里的真实文件名（被 MediaStore/SAF 改名时与请求名可能不同），
     * 查询失败则返回 fallback，用于界面/分享时展示与实际落盘一致的名字。
     */
    public static String displayNameOf(Context ctx, Uri uri, String fallback) {
        if (uri == null) return fallback;
        if (ContentResolver.SCHEME_CONTENT.equalsIgnoreCase(uri.getScheme())) {
            try (Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String n = c.getString(0);
                    if (n != null && !n.isEmpty()) return n;
                }
            } catch (Exception ignored) {
                // 查询失败按 fallback 处理
            }
        }
        String last = uri.getLastPathSegment();
        return (last == null || last.isEmpty()) ? fallback : last;
    }
}
