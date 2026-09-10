package com.flyingmouse.format;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.flyingmouse.format.convert.ConversionEngine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Debug-only 电子书（EPUB/MOBI）冒烟：合成样本 → 走真实 ConversionEngine.ebook 分支。
 * 覆盖 mobi→txt/md/epub、epub→txt/md。adb 启动后看 logcat tag EbookSmoke。
 * 启动组件：com.flyingmouse.format.debug/com.flyingmouse.format.EbookSmokeActivity
 */
public class EbookSmokeActivity extends Activity {
    private static final String TAG = "EbookSmoke";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // NoDisplay activity 必须在 onResume 前 finish，否则抛异常杀进程
        finish();
        new Thread(() -> {
            try {
                File dir = getCacheDir();
                File epub = new File(dir, "smoke_book.epub");
                File mobi = new File(dir, "smoke_book.mobi");
                writeSampleEpub(epub);
                writeSampleMobi(mobi);
                Log.w(TAG, "samples epub=" + epub.length() + " mobi=" + mobi.length());

                // 解析器直查：定位 mobi 文本是否被正确解出
                String mobiHtml = com.flyingmouse.format.convert.MobiReader.parseMobiText(
                        readBytes(mobi));
                Log.w(TAG, "MOBI_RAW len=" + mobiHtml.length() + " head=" + head(mobiHtml));

                run("epub→txt", epub, "txt", "Chapter One", "Alice");
                run("epub→md", epub, "md", "Chapter One", "Alice");
                run("mobi→txt", mobi, "txt", "Chapter Alpha", "Alice");
                run("mobi→md", mobi, "md", "Chapter Alpha", "Alice");
                run("mobi→epub", mobi, "epub", "PK", "Alice");
                Log.w(TAG, "ALL_DONE");
            } catch (Throwable t) {
                Log.w(TAG, "FATAL " + t);
            }
        }).start();
    }

    private static String head(String s) {
        String flat = s.replace("\n", "\\n");
        return flat.length() > 150 ? flat.substring(0, 150) : flat;
    }

    private static byte[] readBytes(File f) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private void run(String label, File in, String target, String mustContain, String mustContain2) {
        try {
            List<ConversionEngine.Output> outs =
                    ConversionEngine.convert(this, Uri.fromFile(in), in.getName(), target);
            ConversionEngine.Output o = outs.get(0);
            String text = new String(o.data, StandardCharsets.UTF_8);
            boolean ok = text.contains(mustContain) && text.contains(mustContain2) && o.data.length > 0;
            Log.w(TAG, label + " RESULT name=" + o.fileName + " bytes=" + o.data.length
                    + " contains(" + mustContain + "/" + mustContain2 + ")=" + ok
                    + " head=" + head(text));
        } catch (Throwable t) {
            Log.w(TAG, label + " FAIL " + t);
        }
    }

    // ---- 合成 EPUB3：mimetype 缺失仅用于自测解析，容器结构规范（container/opf/spine 两章） ----
    private static void writeSampleEpub(File out) throws IOException {
        String chapter1 = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Chapter One</title></head>"
                + "<body><h1>Chapter One</h1><p>Alice was beginning to get very tired.</p></body></html>";
        String chapter2 = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Chapter Two</title></head>"
                + "<body><h1>Chapter Two</h1><p>Bob looked at the empty bottle.</p></body></html>";
        String container = "<?xml version=\"1.0\"?>\n<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
                + "</rootfiles></container>";
        String opf = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\">"
                + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Smoke Book</dc:title></metadata>"
                + "<manifest><item id=\"c1\" href=\"chapter-1.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"c2\" href=\"chapter-2.xhtml\" media-type=\"application/xhtml+xml\"/>"
                + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/></manifest>"
                + "<spine toc=\"ncx\"><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>";
        String ncx = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\"/>";
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            put(zos, "mimetype", "application/epub+zip");
            put(zos, "META-INF/container.xml", container);
            put(zos, "OEBPS/content.opf", opf);
            put(zos, "OEBPS/toc.ncx", ncx);
            put(zos, "OEBPS/chapter-1.xhtml", chapter1);
            put(zos, "OEBPS/chapter-2.xhtml", chapter2);
        }
    }

    private static void put(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    // ---- 合成 MOBI（PalmDB + PalmDOC + zlib 文本记录，结构与真实 mobi 一致） ----
    private static void writeSampleMobi(File out) throws Exception {
        String body = "<html><head><guide><reference type=\"text\" title=\"text\" filepos=0000000000/></guide></head>"
                + "<body><h1>Chapter Alpha</h1><p>Alice fell down the rabbit hole.</p>"
                + "<h2>Chapter Beta</h2><p>Bob discovered the key.</p></body></html>";
        // 切成两条记录再 zlib
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        int mid = b.length / 2;
        byte[] rec1 = deflate(b, 0, mid);
        byte[] rec2 = deflate(b, mid, b.length - mid);
        byte[] pdb = new byte[78];
        byte[] name = "smoke_book".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, pdb, 0, Math.min(name.length, 32));
        writeAscii(pdb, 60, "BOOK");
        writeAscii(pdb, 64, "MOBI");
        // numRecords = 2（记录 0 + 两条文本记录）
        pdb[76] = 0;
        pdb[77] = 2;
        // record0：PalmDOC header 16B（compression=2 zlib, textLength, recordCount=2, recordStart=1）
        int rec0Len = 16;
        int listBytes = 3 * 8; // 偏移表仅 3 项：record0 + 两条文本记录（无哨兵，最后一条以 EOF 为界）
        int off0 = 78 + listBytes;
        int off1 = off0 + rec0Len;
        int off2 = off1 + rec1.length;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(pdb);
        putU32(bos, off0); bos.write(0); bos.write(0); bos.write(0); bos.write(0);
        putU32(bos, off1); bos.write(0); bos.write(0); bos.write(0); bos.write(0);
        putU32(bos, off2); bos.write(0); bos.write(0); bos.write(0); bos.write(0);
        // record0：PalmDOC header 14B + 2B 填充，随后是 MOBI 头占位（保持 rec0 总长 16B 即可）
        writeU16(bos, 2); writeU16(bos, 0); writeU32(bos, b.length); writeU16(bos, 2); writeU16(bos, 1); writeU16(bos, 0);
        writeU16(bos, 0); // PalmDOC 尾部 2B（encrypted+unknown 之后），凑满 16B
        bos.write(rec1);
        bos.write(rec2);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(bos.toByteArray());
        }
    }

    private static byte[] deflate(byte[] src, int off, int len) throws Exception {
        Deflater def = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        def.setInput(src, off, len);
        def.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!def.finished()) {
            int n = def.deflate(buf);
            if (n > 0) bos.write(buf, 0, n);
        }
        def.end();
        return bos.toByteArray();
    }

    private static void putU32(ByteArrayOutputStream bos, int v) {
        bos.write((v >>> 24) & 0xFF);
        bos.write((v >>> 16) & 0xFF);
        bos.write((v >>> 8) & 0xFF);
        bos.write(v & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream bos, int v) {
        bos.write((v >>> 8) & 0xFF);
        bos.write(v & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream bos, long v) {
        bos.write((int) ((v >>> 24) & 0xFF));
        bos.write((int) ((v >>> 16) & 0xFF));
        bos.write((int) ((v >>> 8) & 0xFF));
        bos.write((int) (v & 0xFF));
    }

    private static void writeAscii(byte[] dst, int off, String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, dst, off, Math.min(b.length, dst.length - off));
    }
}
