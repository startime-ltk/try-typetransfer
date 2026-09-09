package com.flyingmouse.format;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.arthenica.ffmpegkit.FFmpegKit;
import com.arthenica.ffmpegkit.FFmpegKitConfig;
import com.arthenica.ffmpegkit.FFmpegSession;
import com.arthenica.ffmpegkit.ReturnCode;
import com.flyingmouse.format.convert.ConversionEngine;

import java.io.File;
import java.io.FileOutputStream;

/** Debug-only FFmpegKit 冒烟验证：合成 WAV → 转 MP3。adb 启动后看 logcat tag FfmpegSmoke。 */
public class FfmpegSmokeActivity extends Activity {
    private static final String TAG = "FfmpegSmoke";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new Thread(() -> {
            try {
                File in = new File(getCacheDir(), "smoke_in.wav");
                writeSineWav(in, 440, 2);
                Log.w(TAG, "version=" + FFmpegKitConfig.getFFmpegVersion());
                String cmd = "-y -i " + in.getAbsolutePath() + " " + new File(getCacheDir(), "smoke_out.mp3").getAbsolutePath();
                Log.w(TAG, "cmd=" + cmd);
                FFmpegSession session = FFmpegKit.execute(cmd);
                boolean ok = session != null && ReturnCode.isSuccess(session.getReturnCode());
                File out = new File(getCacheDir(), "smoke_out.mp3");
                Log.w(TAG, "RESULT success=" + ok + " outLen=" + (out.isFile() ? out.length() : -1)
                        + (session != null && session.getFailStackTrace() != null ? " err=" + session.getFailStackTrace() : ""));

                // 走真实 ConversionEngine.audio 分支（wav -> mp3）
                java.util.List<ConversionEngine.Output> outs =
                        ConversionEngine.convert(this, Uri.fromFile(in), "smoke_in.wav", "mp3");
                Log.w(TAG, "ENGINE_RESULT count=" + outs.size()
                        + " name=" + (outs.isEmpty() ? "?" : outs.get(0).fileName)
                        + " bytes=" + (outs.isEmpty() ? -1 : outs.get(0).data.length));
            } catch (Throwable t) {
                Log.e(TAG, "RESULT exception", t);
            }
        }).start();
        finish();
    }

    private static void writeSineWav(File f, int freqHz, int seconds) throws Exception {
        int sampleRate = 44100;
        int n = sampleRate * seconds;
        short[] pcm = new short[n];
        for (int i = 0; i < n; i++) {
            pcm[i] = (short) (Math.sin(2 * Math.PI * freqHz * i / sampleRate) * 12000);
        }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            writeAscii(fos, "RIFF");
            writeIntLE(fos, 36 + n * 2);
            writeAscii(fos, "WAVE");
            writeAscii(fos, "fmt ");
            writeIntLE(fos, 16);
            writeShortLE(fos, 1);
            writeShortLE(fos, 1);
            writeIntLE(fos, sampleRate);
            writeIntLE(fos, sampleRate * 2);
            writeShortLE(fos, 2);
            writeShortLE(fos, 16);
            writeAscii(fos, "data");
            writeIntLE(fos, n * 2);
            for (short s : pcm) writeShortLE(fos, s);
        }
    }

    private static void writeAscii(FileOutputStream fos, String s) throws Exception {
        fos.write(s.getBytes("US-ASCII"));
    }

    private static void writeIntLE(FileOutputStream fos, int v) throws Exception {
        fos.write(v & 0xFF);
        fos.write((v >> 8) & 0xFF);
        fos.write((v >> 16) & 0xFF);
        fos.write((v >> 24) & 0xFF);
    }

    private static void writeShortLE(FileOutputStream fos, int v) throws Exception {
        fos.write(v & 0xFF);
        fos.write((v >> 8) & 0xFF);
    }
}
