package com.flyingmouse.format;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.flyingmouse.format.convert.ConversionEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Debug-only P4 Office 冒烟：合成 docx/xlsx/pptx（zip+XML）→ 走真实 ConversionEngine.office 分支。
 * adb 启动后看 logcat tag OfficeSmoke。
 * 启动组件：com.flyingmouse.format.debug/com.flyingmouse.format.OfficeSmokeActivity
 */
public class OfficeSmokeActivity extends Activity {
    private static final String TAG = "OfficeSmoke";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
        new Thread(() -> {
            try {
                File dir = getCacheDir();
                File docx = new File(dir, "smoke.docx");
                File xlsx = new File(dir, "smoke.xlsx");
                File pptx = new File(dir, "smoke.pptx");
                writeZip(docx, new String[][]{
                        {"word/document.xml", DOCX_XML}
                });
                writeZip(xlsx, new String[][]{
                        {"xl/sharedStrings.xml", XLSX_STRINGS},
                        {"xl/worksheets/sheet1.xml", XLSX_SHEET1},
                        {"xl/worksheets/sheet2.xml", XLSX_SHEET2},
                        {"xl/worksheets/_rels/sheet1.xml.rels", "<dummy/>"}
                });
                writeZip(pptx, new String[][]{
                        {"ppt/slides/slide1.xml", SLIDE1},
                        {"ppt/slides/slide2.xml", SLIDE2}
                });

                run(docx, "txt", "Hello World", "第二行中文", "c11", "after table");
                run(docx, "md", "Hello World", "第二行中文");
                run(docx, "html", "<table", "Hello World");
                run(xlsx, "csv", "姓名,年龄", "张三,25");
                run(xlsx, "md", "| 姓名 | 年龄 |");
                run(xlsx, "html", "<table", "姓名");
                run(pptx, "txt", "第 1 页", "Slide One Title", "第 2 页", "Second Slide Body");
                run(pptx, "md", "第 2 页");
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
            Log.w(TAG, in.getName() + "→" + target + " RESULT name=" + o.fileName
                    + " bytes=" + o.data.length + " contains=" + ok
                    + " head=" + head(text));
        } catch (Throwable t) {
            Log.w(TAG, in.getName() + "→" + target + " FAIL " + t);
        }
    }

    private static String head(String s) {
        String flat = s.replace("\n", "\\n");
        return flat.length() > 200 ? flat.substring(0, 200) : flat;
    }

    private static void writeZip(File f, String[][] entries) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(f))) {
            for (String[] e : entries) {
                zos.putNextEntry(new ZipEntry(e[0]));
                zos.write(e[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }

    private static final String DOCX_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:body>"
            + "<w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>World</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>第二行中文</w:t></w:r></w:p>"
            + "<w:tbl>"
            + "<w:tr><w:tc><w:p><w:r><w:t>c11</w:t></w:r></w:p></w:tc>"
            + "<w:tc><w:p><w:r><w:t>c12</w:t></w:r></w:p></w:tc></w:tr>"
            + "<w:tr><w:tc><w:p><w:r><w:t>c21</w:t></w:r></w:p></w:tc></w:tr>"
            + "</w:tbl>"
            + "<w:p><w:r><w:t>after table</w:t></w:r></w:p>"
            + "</w:body></w:document>";

    private static final String XLSX_STRINGS =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<si><t>姓名</t></si><si><t>年龄</t></si><si><t>张三</t></si><si><t>李四</t></si>"
            + "</sst>";

    private static final String XLSX_SHEET1 =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<sheetData>"
            + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>"
            + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"B2\"><v>25</v></c></row>"
            + "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>3</v></c><c r=\"C3\" t=\"s\"><v>1</v></c></row>"
            + "</sheetData></worksheet>";

    private static final String XLSX_SHEET2 =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<sheetData>"
            + "<row r=\"1\"><c r=\"A1\"><v>9</v></c><c r=\"B1\"><v>10</v></c></row>"
            + "</sheetData></worksheet>";

    private static final String SLIDE1 =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
            + " xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
            + "<p:cSld><p:spTree>"
            + "<p:sp><p:txBody><a:p><a:r><a:t>Slide One Title</a:t></a:r></a:p>"
            + "<a:p><a:r><a:t>First body line</a:t></a:r></a:p></p:txBody></p:sp>"
            + "</p:spTree></p:cSld></p:sld>";

    private static final String SLIDE2 =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
            + " xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
            + "<p:cSld><p:spTree>"
            + "<p:sp><p:txBody><a:p><a:r><a:t>Second Slide Body</a:t></a:r></a:p></p:txBody></p:sp>"
            + "</p:spTree></p:cSld></p:sld>";
}
