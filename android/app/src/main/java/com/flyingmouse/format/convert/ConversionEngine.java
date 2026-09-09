package com.flyingmouse.format.convert;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;

import com.flyingmouse.format.util.FormatKit;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 转换调度引擎：Uri 输入 → 内存输出（List&lt;Output&gt;），落盘由 OutputSaver 处理。
 * 纯本地、无网络，覆盖移动端能力子集。
 */
public final class ConversionEngine {

    public static final class Output {
        public final String fileName;
        public final byte[] data;

        public Output(String fileName, byte[] data) {
            this.fileName = fileName;
            this.data = data;
        }
    }

    private ConversionEngine() {
    }

    /** 单文件转换。targetId 对应 FormatKit.Target.id。 */
    public static List<Output> convert(Context ctx, Uri uri, String displayName, String targetId) throws Exception {
        String ext = FormatKit.extOf(displayName);
        String base = FormatKit.baseNameOf(displayName);
        String cat = FormatKit.categoryOf(ext);
        switch (cat) {
            case "image":
                return convertImage(ctx, uri, ext, base, targetId);
            case "pdf":
                return convertPdf(ctx, uri, base, targetId);
            case "text":
                return convertText(ctx, uri, ext, base, targetId);
            case "data":
                return convertData(ctx, uri, ext, base, targetId);
            case "zip":
                return convertZip(ctx, uri, base);
            case "epub":
                return convertEpub(ctx, uri, base);
            default:
                throw new Exception("暂不支持该文件类型 (. " + ext + ")");
        }
    }

    /** 多张图片合并为一个 PDF。 */
    public static Output imagesToPdf(Context ctx, List<Uri> uris, List<String> names, String baseName) throws Exception {
        List<Bitmap> pages = new ArrayList<>();
        try {
            for (Uri uri : uris) {
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new Exception("无法读取图片");
                    Bitmap bmp = ImageTool.decode(in);
                    pages.add(bmp);
                }
            }
            if (pages.size() > 30) throw new Exception("一次最多合并 30 张图片");
            byte[] pdf = PdfTool.imagesToPdf(pages);
            return new Output(baseName + ".pdf", pdf);
        } finally {
            for (Bitmap b : pages) b.recycle();
        }
    }

    // ---------- 图片 ----------
    private static List<Output> convertImage(Context ctx, Uri uri, String ext, String base, String targetId) throws Exception {
        if ("pdf".equals(targetId)) {
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("无法读取图片");
                Bitmap bmp = ImageTool.decode(in);
                List<Bitmap> one = new ArrayList<>(1);
                one.add(bmp);
                try {
                    byte[] pdf = PdfTool.imagesToPdf(one);
                    List<Output> outs = new ArrayList<>();
                    outs.add(new Output(base + ".pdf", pdf));
                    return outs;
                } finally {
                    bmp.recycle();
                }
            }
        }
        String outExt = targetId;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取图片");
            Bitmap bmp = ImageTool.decode(in);
            try {
                byte[] out = ImageTool.encodeToBytes(bmp, outExt);
                List<Output> outs = new ArrayList<>();
                outs.add(new Output(base + "." + outExt, out));
                return outs;
            } finally {
                bmp.recycle();
            }
        }
    }

    // ---------- PDF → 逐页 PNG/JPG（打包 zip） ----------
    private static List<Output> convertPdf(Context ctx, Uri uri, String base, String targetId) throws Exception {
        String outExt = targetId; // png / jpg
        File outDir = new File(ctx.getCacheDir(), "pdf_pages_" + System.currentTimeMillis());
        if (!outDir.mkdirs()) throw new Exception("无法创建临时目录");
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取 PDF");
            int count = PdfTool.renderPdfToImages(ctx, in, outExt, outDir, base);
            if (count == 0) throw new Exception("PDF 中没有页面");
            byte[] zipBytes = zipDir(outDir);
            List<Output> outs = new ArrayList<>();
            outs.add(new Output(base + "_" + count + "页." + outExt + ".zip", zipBytes));
            return outs;
        } finally {
            deleteRecursive(outDir);
        }
    }

    // ---------- 文本类 ----------
    private static List<Output> convertText(Context ctx, Uri uri, String ext, String base, String targetId) throws Exception {
        String src = readText(ctx, uri);
        String outExt = targetId;
        String out;
        switch (outExt) {
            case "md":
                out = ext.equals("txt") ? src : htmlToMdLike(src, ext);
                break;
            case "html":
                if (ext.equals("txt")) out = TextTool.txtToHtml(src);
                else if (ext.equals("md") || ext.equals("markdown")) out = TextTool.markdownToHtml(src);
                else out = src;
                break;
            case "txt":
                if (ext.equals("html") || ext.equals("htm")) out = TextTool.htmlToText(src);
                else if (ext.equals("md") || ext.equals("markdown")) out = TextTool.htmlToText(TextTool.markdownToHtml(src));
                else out = src;
                break;
            case "epub":
                String body = bodyForEpub(ext, src);
                byte[] epub = EpubTool.textToEpub(base, body);
                List<Output> outs = new ArrayList<>();
                outs.add(new Output(base + ".epub", epub));
                return outs;
            default:
                throw new Exception("不支持的目标: " + targetId);
        }
        List<Output> outs = new ArrayList<>();
        outs.add(new Output(base + "." + outExt, out.getBytes(StandardCharsets.UTF_8)));
        return outs;
    }

    private static String htmlToMdLike(String src, String ext) {
        if (ext.equals("html") || ext.equals("htm")) return TextTool.htmlToMarkdown(src);
        return src;
    }

    private static String bodyForEpub(String ext, String src) {
        String html;
        if (ext.equals("txt")) {
            html = TextTool.txtToHtml(src);
        } else if (ext.equals("md") || ext.equals("markdown")) {
            html = TextTool.markdownToHtml(src);
        } else {
            html = src;
        }
        int b = html.toLowerCase().indexOf("<body");
        if (b >= 0) {
            int g = html.indexOf('>', b);
            int e = html.toLowerCase().lastIndexOf("</body>");
            if (g >= 0 && e > g) return html.substring(g + 1, e);
        }
        return html;
    }

    // ---------- 数据 ----------
    private static List<Output> convertData(Context ctx, Uri uri, String ext, String base, String targetId) throws Exception {
        String src = readText(ctx, uri);
        String outExt;
        String out;
        switch (targetId) {
            case "xml":
                out = TextTool.jsonToXml(src);
                outExt = "xml";
                break;
            case "csv":
                out = toCsv(src);
                outExt = "csv";
                break;
            case "json_pretty":
                out = TextTool.prettyJson(src);
                outExt = "json";
                break;
            case "json":
                out = TextTool.xmlToJson(src);
                outExt = "json";
                break;
            case "xml_pretty":
                out = TextTool.prettyXml(src);
                outExt = "xml";
                break;
            case "md":
                out = TextTool.csvToMarkdown(src);
                outExt = "md";
                break;
            case "html":
                out = TextTool.csvToHtmlTable(src);
                outExt = "html";
                break;
            default:
                throw new Exception("不支持的目标: " + targetId);
        }
        List<Output> outs = new ArrayList<>();
        outs.add(new Output(base + "." + outExt, out.getBytes(StandardCharsets.UTF_8)));
        return outs;
    }

    /** JSON 数组 → CSV（一维数组走单列；对象数组按键表）。 */
    private static String toCsv(String src) throws Exception {
        String t = src.trim();
        if (!t.startsWith("[")) throw new Exception("仅 JSON 数组可转 CSV");
        org.json.JSONArray arr = new org.json.JSONArray(t);
        if (arr.length() == 0) throw new Exception("JSON 数组为空");
        StringBuilder sb = new StringBuilder();
        boolean anyObj = arr.opt(0) instanceof org.json.JSONObject;
        if (anyObj) {
            java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) throw new Exception("JSON 数组元素必须是对象");
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) keys.add(it.next());
            }
            sb.append(String.join(",", keys)).append("\n");
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                List<String> vals = new ArrayList<>();
                for (String k : keys) vals.add(csvField(o.opt(k)));
                sb.append(String.join(",", vals)).append("\n");
            }
        } else {
            sb.append("value\n");
            for (int i = 0; i < arr.length(); i++) sb.append(csvField(arr.opt(i))).append("\n");
        }
        return sb.toString();
    }

    private static String csvField(Object o) {
        if (o == null) return "";
        String s = o.toString().replace("\"", "\"\"");
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s + "\"" : s;
    }

    // ---------- ZIP / EPUB ----------
    private static List<Output> convertZip(Context ctx, Uri uri, String base) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取 ZIP");
            String text = ZipTool.extractFirstText(in, base);
            List<Output> outs = new ArrayList<>();
            outs.add(new Output(base + "_提取.txt", text.getBytes(StandardCharsets.UTF_8)));
            return outs;
        }
    }

    private static List<Output> convertEpub(Context ctx, Uri uri, String base) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取 EPUB");
            String xhtml = EpubReader.extractFirstXhtml(in);
            String text = TextTool.htmlToText(xhtml);
            List<Output> outs = new ArrayList<>();
            outs.add(new Output(base + ".txt", text.getBytes(StandardCharsets.UTF_8)));
            return outs;
        }
    }

    // ---------- 工具 ----------
    public static String readText(Context ctx, Uri uri) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取文件");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 16 * 1024 * 1024) throw new Exception("文件过大（>16MB）");
            }
            byte[] bytes = bos.toByteArray();
            // 去除 UTF-8 BOM
            int off = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
            return new String(bytes, off, bytes.length - off, StandardCharsets.UTF_8);
        }
    }

    private static byte[] zipDir(File dir) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            File[] files = dir.listFiles();
            if (files != null) {
                java.util.Arrays.sort(files);
                for (File f : files) {
                    if (!f.isFile()) continue;
                    zos.putNextEntry(new ZipEntry(f.getName()));
                    try (FileInputStream fis = new FileInputStream(f)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                    }
                    zos.closeEntry();
                }
            }
        }
        return bos.toByteArray();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        f.delete();
    }
}
