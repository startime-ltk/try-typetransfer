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

    static {
        List<String> images = Arrays.asList("png", "jpg", "jpeg", "webp", "bmp");
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
        EXT_CATEGORY.put("epub", "epub");

        // 图片
        EXT_TARGETS.put("png", targets(new Target("jpg", "JPG"), new Target("webp", "WEBP"), new Target("bmp", "BMP"), new Target("pdf", "转 PDF")));
        EXT_TARGETS.put("jpg", targets(new Target("png", "PNG"), new Target("webp", "WEBP"), new Target("bmp", "BMP"), new Target("pdf", "转 PDF")));
        EXT_TARGETS.put("jpeg", targets(new Target("png", "PNG"), new Target("webp", "WEBP"), new Target("bmp", "BMP"), new Target("pdf", "转 PDF")));
        EXT_TARGETS.put("webp", targets(new Target("png", "PNG"), new Target("jpg", "JPG"), new Target("bmp", "BMP"), new Target("pdf", "转 PDF")));
        EXT_TARGETS.put("bmp", targets(new Target("png", "PNG"), new Target("jpg", "JPG"), new Target("webp", "WEBP"), new Target("pdf", "转 PDF")));
        // PDF
        EXT_TARGETS.put("pdf", targets(new Target("png", "PNG（逐页）"), new Target("jpg", "JPG（逐页）")));
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
        // ZIP / EPUB
        EXT_TARGETS.put("zip", targets(new Target("unzip", "解压 ZIP")));
        EXT_TARGETS.put("epub", targets(new Target("txt", "提取 TXT")));
    }

    private static List<Target> targets(Target... t) {
        return Collections.unmodifiableList(Arrays.asList(t));
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
