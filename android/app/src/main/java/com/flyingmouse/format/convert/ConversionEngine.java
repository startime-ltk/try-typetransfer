package com.flyingmouse.format.convert;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;

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

    /** 与 MainActivity 同名，便于 adb logcat -s FMFormat 一次性取全链路 */
    private static final String TAG = "FMFormat";

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
            case "ebook":
                return convertEbook(ctx, uri, ext, base, targetId);
            case "office":
                return convertOffice(ctx, uri, ext, base, targetId);
            case "music":
                return convertMusic(ctx, uri, ext, base, targetId);
            case "audio":
                return convertAudio(ctx, uri, ext, base, targetId);
            default:
                throw new Exception("暂不支持该文件类型 (. " + ext + ")");
        }
    }

    /**
     * 多张图片合并 PDF 的结果明细。
     * output 为 null 表示所有图片都不可用（没有任何可合并的页）。
     * okIndexes / failedIndexes 与入参 uris 下标一一对应，failedReasons 与 failedIndexes 等长，
     * 便于调用方按"哪一张失败、为什么失败"逐张标记队列状态，而不是把整批一刀切成失败。
     */
    public static final class MergeResult {
        public final Output output;
        public final List<Integer> okIndexes = new ArrayList<>();
        public final List<Integer> failedIndexes = new ArrayList<>();
        public final List<String> failedReasons = new ArrayList<>();

        MergeResult(Output output) {
            this.output = output;
        }
    }

    /**
     * 多张图片合并为一个 PDF（vc8 容错版）。
     * 单张图片读取/解码失败只记录到 failedIndexes/failedReasons，不再把整批标记为失败：
     * 其余可正常解码的图片照常合并产出 PDF，结果弹窗按成功/失败明细呈现。
     */
    public static MergeResult imagesToPdfTolerant(Context ctx, List<Uri> uris, List<String> names, String baseName)
            throws Exception {
        MergeResult mr = new MergeResult(null);
        List<Bitmap> pages = new ArrayList<>();
        try {
            for (int i = 0; i < uris.size(); i++) {
                String nm = (i < names.size() && names.get(i) != null) ? names.get(i) : ("第 " + (i + 1) + " 张");
                try (InputStream in = ctx.getContentResolver().openInputStream(uris.get(i))) {
                    if (in == null) throw new IOException("无法读取图片（输入流为空）");
                    Bitmap bmp = ImageTool.decodeByExt(in, FormatKit.extOf(nm));
                    pages.add(bmp);
                    mr.okIndexes.add(i);
                    Log.i(TAG, "合并PDF 读图成功 [" + (i + 1) + "/" + uris.size() + "] " + nm);
                } catch (Throwable t) {
                    mr.failedIndexes.add(i);
                    mr.failedReasons.add(nm + "：" + brief(t));
                    Log.e(TAG, "合并PDF 读图失败 [" + (i + 1) + "/" + uris.size() + "] " + nm
                            + " | " + t.getClass().getName() + ": " + t.getMessage(), t);
                }
            }
            if (pages.isEmpty()) {
                Log.e(TAG, "合并PDF 中止：共 " + uris.size() + " 张图片，全部读取/解码失败");
                return mr;
            }
            if (pages.size() > 30) {
                throw new Exception("一次最多合并 30 张图片（可用 " + pages.size() + " 张）");
            }
            String fileName = baseName + "_合并" + pages.size() + "图.pdf";
            byte[] pdf = PdfTool.imagesToPdf(pages);
            Log.i(TAG, "合并PDF 合成完成 " + fileName + " 页数=" + pages.size()
                    + " 失败=" + mr.failedIndexes.size());
            return new MergeResult(new Output(fileName, pdf));
        } finally {
            for (Bitmap b : pages) b.recycle();
        }
    }

    /** 异常信息压缩成一行，用于失败明细（与 MainActivity.briefMessage 口径一致）。 */
    private static String brief(Throwable t) {
        if (t == null) return "未知错误";
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) m = t.getClass().getSimpleName();
        m = m.replace('\n', ' ').replace('\r', ' ').trim();
        return m.length() > 80 ? m.substring(0, 80) + "…" : m;
    }

    // ---------- 图片 ----------
    private static List<Output> convertImage(Context ctx, Uri uri, String ext, String base, String targetId) throws Exception {
        if ("pdf".equals(targetId)) {
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("无法读取图片");
                Bitmap bmp = ImageTool.decodeByExt(in, ext);
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
        // P6：图片 OCR → 文本
        if ("txt".equals(targetId) || "md".equals(targetId)) {
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("无法读取图片");
                Bitmap bmp = ImageTool.decodeByExt(in, ext);
                try {
                    String text = OcrEngine.recognize(bmp);
                    if (text.trim().isEmpty()) throw new Exception("未识别到文字（OCR 结果为空）");
                    List<Output> outs = new ArrayList<>();
                    outs.add(new Output(base + "." + targetId, text.getBytes(StandardCharsets.UTF_8)));
                    return outs;
                } finally {
                    bmp.recycle();
                }
            }
        }
        String outExt = targetId;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取图片");
            Bitmap bmp = ImageTool.decodeByExt(in, ext);
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

    // ---------- PDF → 逐页 PNG/JPG（打包 zip）/ 文本提取（txt、md） ----------
    private static List<Output> convertPdf(Context ctx, Uri uri, String base, String targetId) throws Exception {
        if ("txt".equals(targetId) || "md".equals(targetId)) {
            byte[] pdf;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("无法读取 PDF");
                pdf = readAllBytes(in);
            }
            String text = PdfTextExtractor.extractText(pdf);
            if (text.isEmpty()) {
                // 扫描件兜底（P6c）：逐页渲染 + 离线 OCR（最多 30 页）
                try (InputStream in2 = ctx.getContentResolver().openInputStream(uri)) {
                    if (in2 == null) throw new Exception("无法读取 PDF");
                    text = PdfTool.renderPdfToText(ctx, in2, 30);
                }
                if (text.isEmpty()) {
                    throw new Exception("该 PDF 无文本层，OCR 也未识别到文字（可能是空白页或过模糊的扫描件）");
                }
            }
            List<Output> outs = new ArrayList<>();
            outs.add(new Output(base + "." + targetId, text.getBytes(StandardCharsets.UTF_8)));
            return outs;
        }
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

    // ---------- 电子书（EPUB / MOBI） ----------
    /** EPUB → txt/md（按 spine 逐章）；MOBI → txt/md/epub。对齐桌面版 convertEbook。 */
    private static List<Output> convertEbook(Context ctx, Uri uri, String ext, String base, String targetId) throws Exception {
        if (ext.equals("epub")) {
            List<String> xhtmls;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("无法读取 EPUB");
                xhtmls = EpubReader.extractSpineXhtml(in);
            }
            StringBuilder merged = new StringBuilder();
            for (String xhtml : xhtmls) {
                String part;
                if ("txt".equals(targetId)) part = TextTool.htmlToText(xhtml);
                else if ("md".equals(targetId)) part = TextTool.htmlToMarkdown(xhtml);
                else throw new Exception("不支持的目标: " + targetId);
                if (part != null && !part.trim().isEmpty()) {
                    if (merged.length() > 0) merged.append("\n\n");
                    merged.append(part.trim());
                }
            }
            if (merged.length() == 0) throw new Exception("EPUB 解析失败：未提取到任何内容。");
            List<Output> outs = new ArrayList<>();
            outs.add(new Output(base + "." + targetId, merged.toString().getBytes(StandardCharsets.UTF_8)));
            return outs;
        }
        if (ext.equals("mobi")) {
            byte[] raw;
            try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new Exception("无法读取 MOBI");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    if (bos.size() > 128 * 1024 * 1024) throw new Exception("文件过大（>128MB）");
                }
                raw = bos.toByteArray();
            }
            String html = MobiReader.parseMobiText(raw);
            if ("txt".equals(targetId)) {
                String text = TextTool.htmlToText(html);
                if (text.trim().isEmpty()) throw new Exception("MOBI 解析失败：未提取到任何文本。");
                List<Output> outs = new ArrayList<>();
                outs.add(new Output(base + ".txt", text.getBytes(StandardCharsets.UTF_8)));
                return outs;
            }
            if ("md".equals(targetId)) {
                String markdown = TextTool.htmlToMarkdown(html);
                if (markdown.trim().isEmpty()) throw new Exception("MOBI 解析失败：未提取到任何内容。");
                List<Output> outs = new ArrayList<>();
                outs.add(new Output(base + ".md", markdown.getBytes(StandardCharsets.UTF_8)));
                return outs;
            }
            if ("epub".equals(targetId)) {
                String text = TextTool.htmlToText(html);
                if (text.trim().isEmpty()) throw new Exception("MOBI 解析失败：未提取到任何文本。");
                byte[] epub = EpubTool.textToEpub(base, bodyForEpub("txt", text));
                List<Output> outs = new ArrayList<>();
                outs.add(new Output(base + ".epub", epub));
                return outs;
            }
            throw new Exception("不支持的目标: " + targetId);
        }
        throw new Exception("不支持的电子书类型: ." + ext);
    }

    // ---------- Office 可用子集（P4） ----------
    private static List<Output> convertOffice(Context ctx, Uri uri, String ext, String base, String targetId) throws Exception {
        byte[] zip;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取文档");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 128 * 1024 * 1024) throw new Exception("文件过大（>128MB）");
            }
            zip = bos.toByteArray();
        }
        String outExt;
        String out;
        if (ext.equals("xlsx")) {
            String csv = OfficeReader.xlsxToCsv(zip);
            if ("csv".equals(targetId)) {
                out = csv;
                outExt = "csv";
            } else if ("md".equals(targetId)) {
                out = TextTool.csvToMarkdown(csv);
                outExt = "md";
            } else if ("html".equals(targetId)) {
                out = TextTool.csvToHtmlTable(csv);
                outExt = "html";
            } else {
                throw new Exception("不支持的目标: " + targetId);
            }
        } else {
            String text = ext.equals("docx") ? OfficeReader.docxToText(zip) : OfficeReader.pptxToText(zip);
            if (text.trim().isEmpty()) throw new Exception("文档解析失败：未提取到任何文本。");
            if ("txt".equals(targetId)) {
                out = text;
                outExt = "txt";
            } else if ("md".equals(targetId)) {
                out = text;
                outExt = "md";
            } else if ("html".equals(targetId)) {
                out = TextTool.txtToHtml(text);
                outExt = "html";
            } else {
                throw new Exception("不支持的目标: " + targetId);
            }
        }
        List<Output> outs = new ArrayList<>();
        outs.add(new Output(base + "." + outExt, out.getBytes(StandardCharsets.UTF_8)));
        return outs;
    }

    // ---------- 加密音乐解锁 ----------
    private static List<Output> convertMusic(Context ctx, Uri uri, String ext, String base, String targetId) throws Exception {
        if (!"unlock".equals(targetId)) {
            throw new Exception("不支持的目标: " + targetId);
        }
        byte[] raw;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取音乐文件");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 512 * 1024 * 1024) throw new Exception("文件过大（>512MB）");
            }
            raw = bos.toByteArray();
        }
        byte[] audio;
        switch (ext) {
            case "ncm":
                audio = MusicDecryptor.decryptNcm(raw);
                break;
            case "kgm":
            case "kgma":
                audio = MusicDecryptor.decryptKgm(raw, false);
                break;
            case "vpr":
                audio = MusicDecryptor.decryptKgm(raw, true);
                break;
            case "kwm":
                audio = MusicDecryptor.decryptKwm(raw);
                break;
            default:
                throw new Exception("不支持的音乐类型: ." + ext);
        }
        if (audio.length == 0) throw new Exception("解密结果为空，文件可能损坏");
        String outExt = MusicDecryptor.sniffAudioExt(audio);
        List<Output> outs = new ArrayList<>();
        outs.add(new Output(base + "_解锁." + outExt, audio));
        return outs;
    }

    // ---------- 普通音频互转（FFmpegKit） ----------
    private static List<Output> convertAudio(Context ctx, Uri uri, String ext, String base, String targetId) throws Exception {
        String outExt = targetId;
        File inFile = new File(ctx.getCacheDir(), "ffmpeg_in_" + System.currentTimeMillis() + "." + ext);
        File outFile = new File(ctx.getCacheDir(), "ffmpeg_out_" + System.currentTimeMillis() + "." + outExt);
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception("无法读取音频文件");
            try (FileOutputStream fos = new FileOutputStream(inFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            }
        }
        try {
            String cmd = "-y -i " + quote(inFile.getAbsolutePath()) + " " + quote(outFile.getAbsolutePath());
            com.arthenica.ffmpegkit.FFmpegSession session = com.arthenica.ffmpegkit.FFmpegKit.execute(cmd);
            if (session == null || !com.arthenica.ffmpegkit.ReturnCode.isSuccess(session.getReturnCode())) {
                throw new Exception("音频转码失败，请确认源文件未损坏");
            }
            if (!outFile.isFile() || outFile.length() == 0) throw new Exception("音频转码结果为空");
            byte[] data = new byte[(int) Math.min(outFile.length(), Integer.MAX_VALUE)];
            try (FileInputStream fis = new FileInputStream(outFile)) {
                int off = 0;
                while (off < data.length) {
                    int r = fis.read(data, off, data.length - off);
                    if (r < 0) break;
                    off += r;
                }
            }
            List<Output> outs = new ArrayList<>();
            outs.add(new Output(base + "." + outExt, data));
            return outs;
        } finally {
            inFile.delete();
            outFile.delete();
        }
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
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

    private static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            if (bos.size() > 64 * 1024 * 1024) throw new Exception("PDF 过大（>64MB）");
        }
        return bos.toByteArray();
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
