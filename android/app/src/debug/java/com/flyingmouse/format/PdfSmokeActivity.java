package com.flyingmouse.format;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.flyingmouse.format.convert.ConversionEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Debug-only PDF smoke test.
 * 合成两页 PDF（页1：Type1 Helvetica 的 ASCII 文本；页2：Type0/Identity-H + ToUnicode 的中文），
 * 并附带正确的 xref 表，使系统 PdfRenderer 也能解析：
 *   - pdf -> txt / md 走 PdfTextExtractor
 *   - pdf -> png 走 PdfRenderer 逐页渲染后打包 zip
 * 启动组件：com.flyingmouse.format.debug/com.flyingmouse.format.PdfSmokeActivity
 */
public class PdfSmokeActivity extends Activity {
    private static final String TAG = "PdfSmoke";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
        new Thread(() -> {
            try {
                File pdf = new File(getCacheDir(), "smoke-xref.pdf");
                try (FileOutputStream fos = new FileOutputStream(pdf)) {
                    fos.write(buildPdfWithXref());
                }
                Log.w(TAG, "pdf bytes=" + pdf.length());
                runText(pdf, "txt", "Hello PDF Text", "Second line", "你好");
                runText(pdf, "md", "Hello PDF Text", "你好");
                runImage(pdf, "png");
                Log.w(TAG, "ALL_DONE");
            } catch (Throwable t) {
                Log.w(TAG, "FATAL " + t);
            }
        }).start();
    }

    private void runText(File in, String target, String... mustContain) {
        try {
            List<ConversionEngine.Output> outs =
                    ConversionEngine.convert(this, Uri.fromFile(in), in.getName(), target);
            ConversionEngine.Output o = outs.get(0);
            String text = new String(o.data, StandardCharsets.UTF_8);
            boolean ok = true;
            for (String m : mustContain) ok = ok && text.contains(m);
            Log.w(TAG, in.getName() + "->" + target + " RESULT name=" + o.fileName
                    + " bytes=" + o.data.length + " contains=" + ok + " head=" + head(text));
        } catch (Throwable t) {
            Log.w(TAG, in.getName() + "->" + target + " FAIL " + t);
        }
    }

    private void runImage(File in, String target) {
        try {
            List<ConversionEngine.Output> outs =
                    ConversionEngine.convert(this, Uri.fromFile(in), in.getName(), target);
            ConversionEngine.Output o = outs.get(0);
            Log.w(TAG, in.getName() + "->" + target + " RESULT name=" + o.fileName
                    + " bytes=" + o.data.length);
        } catch (Throwable t) {
            Log.w(TAG, in.getName() + "->" + target + " FAIL " + t);
        }
    }

    private static String head(String s) {
        String flat = s.replace("\n", "\\n");
        return flat.length() > 200 ? flat.substring(0, 200) : flat;
    }

    /** 生成两页 PDF，含正确的 xref 表与 startxref（PdfRenderer 可打开）。 */
    private static byte[] buildPdfWithXref() {
        String c1 = "BT /F1 12 Tf 72 720 Td (Hello PDF Text) Tj 0 -20 Td (Second line) Tj ET\n";
        String c2 = "BT /F2 12 Tf 72 600 Td <00010002> Tj ET\n";
        String cmap = "/CIDInit /ProcSet findresource begin\n"
                + "12 dict begin\n"
                + "begincmap\n"
                + "/CMapName /Test-UCS2 def\n"
                + "/CMapType 2 def\n"
                + "1 begincodespacerange\n"
                + "<0000> <FFFF>\n"
                + "endcodespacerange\n"
                + "2 beginbfchar\n"
                + "<0001> <4F60>\n"
                + "<0002> <597D>\n"
                + "endbfchar\n"
                + "endcmap\n"
                + "CMapName currentdict /CMap defineresource pop\n"
                + "end\n"
                + "end\n";

        String[] dict = new String[11];
        dict[1] = "<< /Type /Catalog /Pages 2 0 R >>";
        dict[2] = "<< /Type /Pages /Kids [3 0 R 7 0 R] /Count 2 >>";
        dict[3] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                + "/Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> /Contents 4 0 R >>";
        dict[4] = "<< /Length " + c1.length() + " >>\nstream\n" + c1 + "endstream";
        dict[5] = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>";
        dict[6] = "<< /Type /Font /Subtype /Type0 /BaseFont /Dummy /Encoding /Identity-H "
                + "/DescendantFonts [8 0 R] /ToUnicode 9 0 R >>";
        dict[7] = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                + "/Resources << /Font << /F1 5 0 R /F2 6 0 R >> >> /Contents 10 0 R >>";
        dict[8] = "<< /Type /Font /Subtype /CIDFontType0 /BaseFont /Dummy >>";
        dict[9] = "<< /Length " + cmap.length() + " >>\nstream\n" + cmap + "endstream";
        dict[10] = "<< /Length " + c2.length() + " >>\nstream\n" + c2 + "endstream";

        StringBuilder sb = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[11];
        for (int n = 1; n <= 10; n++) {
            offsets[n] = sb.length();
            sb.append(n).append(" 0 obj\n").append(dict[n]).append("\nendobj\n");
        }
        int xref = sb.length();
        sb.append("xref\n0 11\n0000000000 65535 f \n");
        for (int n = 1; n <= 10; n++) {
            sb.append(String.format("%010d 00000 n \n", offsets[n]));
        }
        sb.append("trailer\n<< /Size 11 /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }
}
