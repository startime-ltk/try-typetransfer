package com.flyingmouse.format.convert;

import android.graphics.Bitmap;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.concurrent.TimeUnit;

/**
 * P6：图片离线 OCR（ML Kit 内置模型，中英文混排，断网可用）。
 * 识别器构造耗时，进程内复用；调用方负责传入已解码的 Bitmap。
 */
public final class OcrEngine {

    /** 单张图片识别超时（秒）。 */
    private static final long TIMEOUT_SECONDS = 60;

    private static volatile TextRecognizer recognizer;

    private OcrEngine() {
    }

    /** 识别图片中的文字，按行返回纯文本（段落间空行）；未识别到内容时返回空串。 */
    public static String recognize(Bitmap bmp) throws Exception {
        InputImage image = InputImage.fromBitmap(bmp, 0);
        Text text = Tasks.await(client().process(image), TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return flatten(text);
    }

    private static String flatten(Text text) {
        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : text.getTextBlocks()) {
            if (block.getLines().isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n').append('\n');
            boolean first = true;
            for (Text.Line line : block.getLines()) {
                if (!first) sb.append('\n');
                sb.append(line.getText());
                first = false;
            }
        }
        return sb.toString();
    }

    private static TextRecognizer client() {
        if (recognizer == null) {
            synchronized (OcrEngine.class) {
                if (recognizer == null) {
                    recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
                }
            }
        }
        return recognizer;
    }
}
