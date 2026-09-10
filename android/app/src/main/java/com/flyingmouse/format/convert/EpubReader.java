package com.flyingmouse.format.convert;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * EPUB（本质 zip）内容提取：按 spine 顺序合并全部正文章节，
 * 对齐桌面版 ebook.js 的 epubSpineXhtml（container.xml → content.opf → spine → item href）。
 */
public final class EpubReader {

    private EpubReader() {
    }

    /** 按 spine 顺序读取全部章节 xhtml（保留原始 html 头尾，由 TextTool 逐章转 txt/md）。 */
    public static List<String> extractSpineXhtml(InputStream in) throws IOException {
        Map<String, byte[]> entries = readAllEntries(in);
        byte[] container = findEntry(entries, "META-INF/container.xml");
        if (container == null) {
            // 兼容某些不规范 EPUB：直接找第一个 container.xml
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                if (e.getKey().toLowerCase().endsWith("container.xml")) {
                    container = e.getValue();
                    break;
                }
            }
        }
        if (container == null) throw new IOException("EPUB 解析失败：缺少 META-INF/container.xml。");
        String containerText = new String(container, StandardCharsets.UTF_8);
        String opfPath = firstGroup(containerText, "full-path=\"([^\"]+)\"");
        if (opfPath == null) throw new IOException("EPUB 解析失败：container.xml 缺少 rootfile。");
        byte[] opfBytes = entries.get(opfPath);
        if (opfBytes == null) throw new IOException("EPUB 解析失败：找不到 " + opfPath + "。");
        String opfText = new String(opfBytes, StandardCharsets.UTF_8);

        String baseDir = "";
        int slash = opfPath.lastIndexOf('/');
        if (slash >= 0) baseDir = opfPath.substring(0, slash + 1);

        // spine itemref 的 idref 顺序
        List<String> spineIds = allGroups(opfText, "<itemref[^>]*idref=\"([^\"]+)\"");
        // item 标签 id → href（属性顺序不定，逐个标签解析）
        Map<String, String> idHref = new HashMap<>();
        Matcher itemMatcher = Pattern.compile("<item\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(opfText);
        while (itemMatcher.find()) {
            String tag = itemMatcher.group();
            String id = firstGroup(tag, "id=\"([^\"]+)\"");
            String href = firstGroup(tag, "href=\"([^\"]+)\"");
            if (id != null && href != null) idHref.put(id, href);
        }

        List<String> xhtmls = new ArrayList<>();
        for (String id : spineIds) {
            String href = idHref.get(id);
            if (href == null) continue;
            byte[] entry = entries.get(href);
            if (entry == null && !baseDir.isEmpty()) entry = entries.get(baseDir + href);
            if (entry != null) {
                xhtmls.add(new String(entry, StandardCharsets.UTF_8));
            }
        }
        if (xhtmls.isEmpty()) throw new IOException("EPUB 解析失败：spine 中没有可读内容。");
        return xhtmls;
    }

    /** 读取 zip 全部条目到内存（文件名小写归一，便于兼容大小写差异）。 */
    private static Map<String, byte[]> readAllEntries(InputStream in) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        long total = 0;
        try (ZipInputStream zis = new ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = zis.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                    total += n;
                    if (total > 256 * 1024 * 1024) throw new IOException("EPUB 过大（>256MB）");
                }
                byte[] data = bos.toByteArray();
                String name = entry.getName();
                if (!entries.containsKey(name)) entries.put(name, data);
                String lower = name.toLowerCase();
                if (!entries.containsKey(lower)) entries.put(lower, data);
            }
        }
        if (entries.isEmpty()) throw new IOException("EPUB 不是有效的 zip 包");
        return entries;
    }

    private static byte[] findEntry(Map<String, byte[]> entries, String exactName) {
        byte[] hit = entries.get(exactName);
        if (hit != null) return hit;
        return entries.get(exactName.toLowerCase());
    }

    private static String firstGroup(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static List<String> allGroups(String text, String regex) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
