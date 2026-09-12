package com.flyingmouse.format.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 格式分类与目标格式判定（对齐桌面版 utils.targetsForExt 的简化移动端子集）。
 * 移动端仅提供可离线、纯本地闭环的转换：图片/PDF/文本数据/EPUB/ZIP 解压。
 */
public final class FormatKit {

    public static final class Target {
        public final String id;      // 唯一标识，也作为扩展名/输出文件名后缀
        public final String label;   // UI 显示
        public Target(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final Map<String, String> EXT_CATEGORY = new LinkedHashMap<>();
    private static final Map<String, List<Target>> EXT_TARGETS = new LinkedHashMap<>();
    /** MIME → 扩展名映射（外部 Uri 无文件名可用时按 MIME 推断类型） */
    private static final Map<String, String> MIME_EXT = new LinkedHashMap<>();

    static {
        List<String> images = Arrays.asList("png", "jpg", "jpeg", "webp", "bmp", "avif", "heic", "heif", "ico", "tga");
        for (String e : images) {
            EXT_CATEGORY.put(e, "image");
        }
        EXT_CATEGORY.put("pdf", "pdf");
        EXT_CATEGORY.put("txt", "text");
        EXT_CATEGORY.put("md", "text");
        EXT_CATEGORY.put("markdown", "text");
        EXT_CATEGORY.put("html", "text");
        EXT_CATEGORY.put("htm", "text");
        EXT_CATEGORY.put("json", "data");
        EXT_CATEGORY.put("xml", "data");
        EXT_CATEGORY.put("csv", "data");
        EXT_CATEGORY.put("zip", "zip");
        EXT_CATEGORY.put("epub", "ebook");
        EXT_CATEGORY.put("mobi", "ebook");
        // Office（P4 可用子集，零依赖 zip+XML 提取）
        EXT_CATEGORY.put("docx", "office");
        EXT_CATEGORY.put("xlsx", "office");
        EXT_CATEGORY.put("pptx", "office");
        // 加密音乐（解锁为目标）
        EXT_CATEGORY.put("ncm", "music");
        EXT_CATEGORY.put("kgm", "music");
        EXT_CATEGORY.put("kgma", "music");
        EXT_CATEGORY.put("vpr", "music");
        EXT_CATEGORY.put("kwm", "music");
        // 普通音频（互转，走 FFmpegKit）
        EXT_CATEGORY.put("mp3", "audio");
        EXT_CATEGORY.put("wav", "audio");
        EXT_CATEGORY.put("flac", "audio");
        EXT_CATEGORY.put("m4a", "audio");
        EXT_CATEGORY.put("aac", "audio");
        EXT_CATEGORY.put("ogg", "audio");
        EXT_CATEGORY.put("opus", "audio");
        EXT_CATEGORY.put("wma", "audio");

        // 图片（P6 起统一追加 OCR 文本目标）
        EXT_TARGETS.put("png", imgTargets(new Target("jpg", "JPG"), new Target("webp", "WEBP"), new Target("bmp", "BMP"), new Target("pdf", "转 PDF")));
        EXT_TARGETS.put("jpg", imgTargets(new Target("png", "PNG"), new Target("webp", "WEBP"), new Target("bmp", "BMP"), new Target("pdf", "转 PDF")));
        EXT_TARGETS.put("jpeg", imgTargets(new Target("png", "PNG"), new Target("webp", "WEBP"), new Target("bmp", "BMP"), new Target("pdf", "转 PDF")));
        EXT_TARGETS.put("webp", imgTargets(new Target("png", "PNG"), new Target("jpg", "JPG"), new Target("bmp", "BMP"), new Target("pdf", "转 PDF")));
        EXT_TARGETS.put("bmp", imgTargets(new Target("png", "PNG"), new Target("jpg", "JPG"), new Target("webp", "WEBP"), new Target("pdf", "转 PDF")));
        // P3 图片扩展输入（avif/heic 由系统解码；ico/tga 纯 Java 解码）
        EXT_TARGETS.put("avif", imgTargets(new Target("png", "PNG"), new Target("jpg", "JPG"), new Target("webp", "WEBP"), new Target("pdf", "转 PDF")));
        EXT_TARGETS.put("heic", imgTargets(new Target("png", "PNG"), new Target("jpg", "JPG"), new Target("webp", "WEBP"), new Target("pdf", "转 PDF")));
        EXT_TARGETS.put("heif", imgTargets(new Target("png", "PNG"), new Target("jpg", "JPG"), new Target("webp", "WEBP"), new Target("pdf", "转 PDF")));
        EXT_TARGETS.put("ico", imgTargets(new Target("png", "PNG"), new Target("jpg", "JPG"), new Target("webp", "WEBP"), new Target("pdf", "转 PDF")));
        EXT_TARGETS.put("tga", imgTargets(new Target("png", "PNG"), new Target("jpg", "JPG"), new Target("webp", "WEBP"), new Target("pdf", "转 PDF")));
        // PDF
        EXT_TARGETS.put("pdf", targets(new Target("png", "PNG（逐页）"), new Target("jpg", "JPG（逐页）"),
                new Target("txt", "TXT 文本"), new Target("md", "MD 文本"),
                new Target("xlsx", "Excel 表格"), new Target("docx", "Word 文档")));
        // 文本
        EXT_TARGETS.put("txt", targets(new Target("md", "Markdown"), new Target("html", "HTML"), new Target("epub", "EPUB")));
        EXT_TARGETS.put("md", targets(new Target("html", "HTML"), new Target("txt", "TXT"), new Target("epub", "EPUB")));
        EXT_TARGETS.put("markdown", targets(new Target("html", "HTML"), new Target("txt", "TXT"), new Target("epub", "EPUB")));
        EXT_TARGETS.put("html", targets(new Target("md", "Markdown"), new Target("txt", "TXT")));
        EXT_TARGETS.put("htm", targets(new Target("md", "Markdown"), new Target("txt", "TXT")));
        // 数据
        EXT_TARGETS.put("json", targets(new Target("xml", "XML"), new Target("csv", "CSV"), new Target("json_pretty", "JSON 美化")));
        EXT_TARGETS.put("xml", targets(new Target("json", "JSON"), new Target("xml_pretty", "XML 美化")));
        EXT_TARGETS.put("csv", targets(new Target("json", "JSON"), new Target("md", "Markdown 表格"), new Target("html", "HTML 表格")));
        // ZIP / 电子书
        EXT_TARGETS.put("zip", targets(new Target("unzip", "解压 ZIP")));
        EXT_TARGETS.put("epub", targets(new Target("txt", "提取 TXT"), new Target("md", "转 Markdown")));
        EXT_TARGETS.put("mobi", targets(new Target("txt", "提取 TXT"), new Target("md", "转 Markdown"), new Target("epub", "转 EPUB")));
        // Office（P4）：docx/pptx → 文本类；xlsx → 表格类
        EXT_TARGETS.put("docx", targets(new Target("txt", "提取 TXT"), new Target("md", "转 Markdown"), new Target("html", "转 HTML")));
        EXT_TARGETS.put("pptx", targets(new Target("txt", "提取 TXT"), new Target("md", "转 Markdown"), new Target("html", "转 HTML")));
        EXT_TARGETS.put("xlsx", targets(new Target("csv", "转 CSV"), new Target("md", "转 Markdown 表格"), new Target("html", "转 HTML 表格")));
        // 加密音乐 → 解锁原始音频容器
        EXT_TARGETS.put("ncm", targets(new Target("unlock", "解锁音频")));
        EXT_TARGETS.put("kgm", targets(new Target("unlock", "解锁音频")));
        EXT_TARGETS.put("kgma", targets(new Target("unlock", "解锁音频")));
        EXT_TARGETS.put("vpr", targets(new Target("unlock", "解锁音频")));
        EXT_TARGETS.put("kwm", targets(new Target("unlock", "解锁音频")));
        // 普通音频互转：对齐桌面版 mediaAudioTargets，目标 = 其余 7 种格式
        String[] audios = {"mp3", "wav", "flac", "m4a", "ogg", "aac", "opus", "wma"};
        for (int i = 0; i < audios.length; i++) {
            List<Target> list = new ArrayList<>();
            for (int j = 0; j < audios.length; j++) {
                if (i == j) continue;
                list.add(new Target(audios[j], audios[j].toUpperCase(Locale.ROOT)));
            }
            EXT_TARGETS.put(audios[i], Collections.unmodifiableList(list));
        }

        // MIME → 扩展名（外部 Uri 拿不到文件名/扩展名时的兜底依据，见 extOfMime）
        MIME_EXT.put("image/jpeg", "jpg");
        MIME_EXT.put("image/jpg", "jpg");
        MIME_EXT.put("image/png", "png");
        MIME_EXT.put("image/webp", "webp");
        MIME_EXT.put("image/bmp", "bmp");
        MIME_EXT.put("image/x-ms-bmp", "bmp");
        MIME_EXT.put("image/avif", "avif");
        MIME_EXT.put("image/heic", "heic");
        MIME_EXT.put("image/heif", "heif");
        MIME_EXT.put("image/x-icon", "ico");
        MIME_EXT.put("image/vnd.microsoft.icon", "ico");
        MIME_EXT.put("image/x-tga", "tga");
        MIME_EXT.put("application/pdf", "pdf");
        MIME_EXT.put("text/plain", "txt");
        MIME_EXT.put("text/markdown", "md");
        MIME_EXT.put("text/x-markdown", "md");
        MIME_EXT.put("text/html", "html");
        MIME_EXT.put("text/xml", "xml");
        MIME_EXT.put("text/csv", "csv");
        MIME_EXT.put("application/json", "json");
        MIME_EXT.put("application/xml", "xml");
        MIME_EXT.put("application/zip", "zip");
        MIME_EXT.put("application/x-zip-compressed", "zip");
        MIME_EXT.put("application/epub+zip", "epub");
        MIME_EXT.put("application/x-mobipocket-ebook", "mobi");
        MIME_EXT.put("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
        MIME_EXT.put("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
        MIME_EXT.put("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
        MIME_EXT.put("audio/mpeg", "mp3");
        MIME_EXT.put("audio/mp4", "m4a");
        MIME_EXT.put("audio/x-m4a", "m4a");
        MIME_EXT.put("audio/wav", "wav");
        MIME_EXT.put("audio/x-wav", "wav");
        MIME_EXT.put("audio/flac", "flac");
        MIME_EXT.put("audio/x-flac", "flac");
        MIME_EXT.put("audio/aac", "aac");
        MIME_EXT.put("audio/ogg", "ogg");
        MIME_EXT.put("audio/opus", "opus");
        MIME_EXT.put("audio/x-ms-wma", "wma");
    }

    /** 汇总页分组：标题 + 若干行说明 */
    public static final class SummaryGroup {
        public final String title;
        public final List<String> lines;

        public SummaryGroup(String title, List<String> lines) {
            this.title = title;
            this.lines = lines;
        }
    }

    /** 全量支持格式汇总（供"可转换格式"页展示），由格式表动态生成，避免手写漂移 */
    public static List<SummaryGroup> summaries() {
        List<SummaryGroup> groups = new ArrayList<>();

        List<String> imgLines = new ArrayList<>();
        imgLines.add(line("png"));
        imgLines.add(line("jpg", "jpeg"));
        imgLines.add(line("webp"));
        imgLines.add(line("bmp"));
        imgLines.add(line("avif", "heic", "heif", "ico", "tga"));
        imgLines.add("多张图片 → 合并 PDF");
        imgLines.add("图片 → OCR 提取 TXT / Markdown（离线识别中英文）");
        groups.add(new SummaryGroup("图片", imgLines));

        List<String> pdfLines = new ArrayList<>();
        pdfLines.add(line("pdf"));
        pdfLines.add("PDF → Excel 表格 / Word 文档（结构还原：表格 + 段落/标题层级，离线可用）");
        groups.add(new SummaryGroup("PDF", pdfLines));
        groups.add(new SummaryGroup("文本", Arrays.asList(line("txt"), line("md", "markdown"), line("html", "htm"))));
        groups.add(new SummaryGroup("数据", Arrays.asList(line("json"), line("xml"), line("csv"))));
        groups.add(new SummaryGroup("压缩包", Collections.singletonList(line("zip"))));
        groups.add(new SummaryGroup("电子书",
                Arrays.asList(line("epub"), line("mobi"))));
        groups.add(new SummaryGroup("Office 文档",
                Arrays.asList(line("docx"), line("xlsx"), line("pptx"))));
        groups.add(new SummaryGroup("音乐解锁",
                Arrays.asList(line("ncm"), line("kgm", "kgma"), line("vpr"), line("kwm"))));
        groups.add(new SummaryGroup("音频互转",
                Arrays.asList(line("mp3"), line("wav"), line("flac"), line("m4a"), line("aac"), line("ogg"), line("opus"), line("wma"))));
        return groups;
    }

    /** 生成一行 ".xxx/.yyy → 目标 / 目标" 文本，目标取自首扩展名 */
    private static String line(String... exts) {
        StringBuilder src = new StringBuilder();
        for (String e : exts) {
            if (src.length() > 0) src.append('/');
            src.append('.').append(e);
        }
        List<Target> ts = EXT_TARGETS.get(exts[0]);
        StringBuilder tg = new StringBuilder();
        if (ts != null) {
            for (Target t : ts) {
                if (tg.length() > 0) tg.append(" / ");
                tg.append(t.label);
            }
        }
        return src + " → " + tg;
    }

    private static List<Target> targets(Target... t) {
        return Collections.unmodifiableList(Arrays.asList(t));
    }

    /** 图片目标 = 常规图像目标 + OCR 文本目标（P6）。 */
    private static List<Target> imgTargets(Target... t) {
        List<Target> list = new ArrayList<>(Arrays.asList(t));
        list.add(new Target("txt", "OCR 提取 TXT"));
        list.add(new Target("md", "OCR → Markdown"));
        return Collections.unmodifiableList(list);
    }

    private FormatKit() {
    }

    /** 规范化扩展名（不含点，小写）；未知返回空串 */
    public static String extOf(String fileName) {
        if (fileName == null) return "";
        String name = fileName.trim();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isSupportedExt(String ext) {
        return EXT_TARGETS.containsKey(ext.toLowerCase(Locale.ROOT));
    }

    /**
     * 由 MIME 推断扩展名（不含点，小写）；推断不出返回空串。
     * 优先查映射表，其次按下级子类型猜（如 image/webp → webp、application/epub+zip → epub），
     * 结果仅在 {@link #isSupportedExt(String)} 认可时才有意义。
     */
    public static String extOfMime(String mime) {
        if (mime == null) return "";
        String m = mime.trim().toLowerCase(Locale.ROOT);
        int semi = m.indexOf(';');
        if (semi >= 0) m = m.substring(0, semi).trim();
        if (m.isEmpty() || "*".equals(m)) return "";
        String mapped = MIME_EXT.get(m);
        if (mapped != null) return mapped;
        int slash = m.indexOf('/');
        if (slash < 0 || slash == m.length() - 1) return "";
        String sub = m.substring(slash + 1);
        int plus = sub.indexOf('+');
        if (plus > 0) sub = sub.substring(0, plus);
        if (sub.startsWith("x-")) sub = sub.substring(2);
        if (isSupportedExt(sub)) return sub;
        if ("jpeg".equals(sub)) return "jpg";
        return "";
    }

    public static String categoryOf(String ext) {
        String c = EXT_CATEGORY.get(ext.toLowerCase(Locale.ROOT));
        return c == null ? "unknown" : c;
    }

    /** 某扩展名支持的目标格式 */
    public static List<Target> targetsOf(String ext) {
        List<Target> list = EXT_TARGETS.get(ext.toLowerCase(Locale.ROOT));
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    /** 去掉扩展名的基名（安全：处理特殊字符） */
    public static String baseNameOf(String fileName) {
        String name = fileName == null ? "file" : fileName.trim();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return name.isEmpty() ? "file" : name;
    }
}
