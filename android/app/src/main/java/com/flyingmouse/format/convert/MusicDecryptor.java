package com.flyingmouse.format.convert;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 加密音乐本地解锁（对齐 PC 端 music-platform-convert.js，MIT unlock-music 算法）。
 * 支持：网易云 .ncm、酷狗 .kgm/.kgma/.vpr、酷我 .kwm。
 * 仅做解密与容器嗅探，不引入转码引擎；输出为解密后的原始音频字节。
 */
public final class MusicDecryptor {

    public static final class DecryptException extends Exception {
        public DecryptException(String message) {
            super(message);
        }
    }

    // ---- NCM ----
    private static final byte[] NCM_CORE_KEY = hex("687a4852416d736f356b496e62617857");
    private static final byte[] NCM_MAGIC = hex("4354454e4644414d");

    // ---- KGM ----
    private static final byte[] KGM_HEADER = hex("7cd532eb86027f4ba8afa68e0fff9914");
    private static final byte[] VPR_HEADER = hex("0528bc96e9e45a4391aabdd07af53631");

    // ---- KWM ----
    private static final byte[] KWM_MAGIC1 = hex("7965656c696f6e2d6b75776f2d746d65");
    private static final byte[] KWM_MAGIC2 = hex("7965656c696f6e2d6b75776f00000000");
    private static final String KWM_PREDEFINED_KEY = "MoOtOiTvINGwd2E6n0E1i7L5t2IoOoNk";

    private static final int[] KGM_TABLE1 = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 1, 33, 1, 97, 1, 33, 1, 225, 1, 33, 1, 97, 1, 33, 1,
        210, 35, 2, 2, 66, 66, 2, 2, 194, 194, 2, 2, 66, 66, 2, 2,
        211, 211, 2, 3, 99, 67, 99, 3, 227, 195, 227, 3, 99, 67, 99, 3,
        148, 180, 148, 101, 4, 4, 4, 4, 132, 132, 132, 132, 4, 4, 4, 4,
        149, 149, 149, 149, 4, 5, 37, 5, 229, 133, 165, 133, 229, 5, 37, 5,
        214, 182, 150, 182, 214, 39, 6, 6, 198, 198, 134, 134, 198, 198, 6, 6,
        215, 215, 151, 151, 215, 215, 6, 7, 231, 199, 231, 135, 231, 199, 231, 7,
        24, 56, 24, 120, 24, 56, 24, 233, 8, 8, 8, 8, 8, 8, 8, 8,
        25, 25, 25, 25, 25, 25, 25, 25, 8, 9, 41, 9, 105, 9, 41, 9,
        218, 58, 26, 58, 90, 58, 26, 58, 218, 43, 10, 10, 74, 74, 10, 10,
        219, 219, 27, 27, 91, 91, 27, 27, 219, 219, 10, 11, 107, 75, 107, 11,
        156, 188, 156, 124, 28, 60, 28, 124, 156, 188, 156, 109, 12, 12, 12, 12,
        157, 157, 157, 157, 29, 29, 29, 29, 157, 157, 157, 157, 12, 13, 45, 13,
        222, 190, 158, 190, 222, 62, 30, 62, 222, 190, 158, 190, 222, 47, 14, 14,
        223, 223, 159, 159, 223, 223, 31, 31, 223, 223, 159, 159, 223, 223, 14, 15,
        0, 32, 0, 96, 0, 32, 0, 224, 0, 32, 0, 96, 0, 32, 0, 241
    };

    private static final int[] KGM_TABLE2 = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 1, 35, 1, 103, 1, 35, 1, 239, 1, 35, 1, 103, 1, 35, 1,
        223, 33, 2, 2, 70, 70, 2, 2, 206, 206, 2, 2, 70, 70, 2, 2,
        222, 222, 2, 3, 101, 71, 101, 3, 237, 207, 237, 3, 101, 71, 101, 3,
        157, 191, 157, 99, 4, 4, 4, 4, 140, 140, 140, 140, 4, 4, 4, 4,
        156, 156, 156, 156, 4, 5, 39, 5, 235, 141, 175, 141, 235, 5, 39, 5,
        219, 189, 159, 189, 219, 37, 6, 6, 202, 202, 142, 142, 202, 202, 6, 6,
        218, 218, 158, 158, 218, 218, 6, 7, 233, 203, 233, 143, 233, 203, 233, 7,
        25, 59, 25, 127, 25, 59, 25, 231, 8, 8, 8, 8, 8, 8, 8, 8,
        24, 24, 24, 24, 24, 24, 24, 24, 8, 9, 43, 9, 111, 9, 43, 9,
        215, 57, 27, 57, 95, 57, 27, 57, 215, 41, 10, 10, 78, 78, 10, 10,
        214, 214, 26, 26, 94, 94, 26, 26, 214, 214, 10, 11, 109, 79, 109, 11,
        149, 183, 149, 123, 29, 63, 29, 123, 149, 183, 149, 107, 12, 12, 12, 12,
        148, 148, 148, 148, 28, 28, 28, 28, 148, 148, 148, 148, 12, 13, 47, 13,
        211, 181, 151, 181, 211, 61, 31, 61, 211, 181, 151, 181, 211, 45, 14, 14,
        210, 210, 150, 150, 210, 210, 30, 30, 210, 210, 150, 150, 210, 210, 14, 15,
        0, 34, 0, 102, 0, 34, 0, 238, 0, 34, 0, 102, 0, 34, 0, 254
    };

    private static final int[] KGM_MASK_V2_PREDEF = {
        184, 213, 61, 178, 233, 175, 120, 140, 131, 51, 113, 81, 118, 160, 205, 55,
        47, 62, 53, 141, 169, 190, 152, 183, 231, 140, 34, 206, 90, 97, 223, 104,
        105, 137, 254, 165, 182, 222, 169, 119, 252, 200, 189, 189, 229, 109, 62, 90,
        54, 239, 105, 78, 190, 225, 233, 102, 28, 243, 217, 2, 182, 242, 18, 155,
        68, 208, 111, 185, 53, 137, 182, 70, 109, 115, 130, 6, 105, 193, 237, 215,
        133, 194, 48, 223, 162, 98, 190, 121, 45, 98, 98, 61, 13, 126, 190, 72,
        137, 35, 2, 160, 228, 213, 117, 81, 50, 2, 83, 253, 22, 58, 33, 59,
        22, 15, 195, 178, 187, 179, 226, 186, 58, 61, 19, 236, 246, 1, 69, 132,
        165, 112, 15, 147, 73, 12, 100, 205, 49, 213, 204, 76, 7, 1, 158, 0,
        26, 35, 144, 191, 136, 30, 59, 171, 166, 62, 196, 115, 71, 16, 126, 59,
        94, 188, 227, 0, 132, 255, 9, 212, 224, 137, 15, 91, 88, 112, 79, 251,
        101, 216, 92, 83, 27, 211, 200, 198, 191, 239, 152, 176, 80, 79, 15, 234,
        229, 131, 88, 140, 40, 44, 132, 103, 205, 208, 158, 71, 219, 39, 80, 202,
        244, 99, 99, 232, 151, 127, 27, 75, 12, 194, 193, 33, 76, 204, 88, 245,
        148, 82, 163, 243, 211, 224, 104, 244, 0, 35, 243, 94, 10, 123, 147, 221,
        171, 18, 178, 19, 232, 132, 215, 167, 159, 15, 50, 76, 85, 29, 4, 54,
        82, 220, 3, 243, 249, 78, 66, 233, 61, 97, 239, 124, 182, 179, 147, 80
    };

    private static final int[] VPR_MASK_DIFF = {
        37, 223, 232, 166, 117, 30, 117, 14, 47, 128, 243, 45, 184, 182, 227, 17,
        0
    };

    private MusicDecryptor() {
    }

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) return false;
        }
        return true;
    }

    // ================= NCM =================
    public static byte[] decryptNcm(byte[] data) throws DecryptException {
        if (data.length < 10 || !startsWith(data, NCM_MAGIC)) {
            throw new DecryptException("此 NCM 文件已损坏或不是有效的网易云音乐文件。");
        }
        int offset = 10;
        int keyLen = readIntLE(data, offset);
        offset += 4;
        if (offset + keyLen > data.length) {
            throw new DecryptException("NCM 文件密钥段越界，文件可能损坏。");
        }
        byte[] keyCipher = Arrays.copyOfRange(data, offset, offset + keyLen);
        offset += keyLen;
        for (int i = 0; i < keyCipher.length; i++) keyCipher[i] ^= 0x64;
        byte[] keyPlain = aesEcbDecrypt(keyCipher, NCM_CORE_KEY);
        byte[] keyData = Arrays.copyOfRange(keyPlain, 17, keyPlain.length);
        if (keyData.length == 0) {
            throw new DecryptException("NCM 文件密钥解析失败。");
        }
        byte[] keyBox = ncmKeyBox(keyData);

        int metaLen = readIntLE(data, offset);
        offset += 4;
        if (metaLen > 0 && offset + metaLen <= data.length) {
            offset += metaLen; // 元数据仅用于封面/标题，移动端无需解析
        }

        if (offset + 9 > data.length) {
            throw new DecryptException("NCM 文件音频段越界，文件可能损坏。");
        }
        offset += readIntLE(data, offset + 5) + 13;
        byte[] audio = Arrays.copyOfRange(data, offset, data.length);
        for (int i = 0; i < audio.length; i++) audio[i] ^= keyBox[i & 0xFF];
        return audio;
    }

    private static byte[] aesEcbDecrypt(byte[] data, byte[] key) throws DecryptException {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new DecryptException("NCM 密钥解密失败，文件可能损坏。");
        }
    }

    private static byte[] ncmKeyBox(byte[] keyData) {
        int[] box = new int[256];
        for (int i = 0; i < 256; i++) box[i] = i;
        int j = 0;
        for (int i = 0; i < 256; i++) {
            j = (box[i] + j + (keyData[i % keyData.length] & 0xFF)) & 0xFF;
            int t = box[i];
            box[i] = box[j];
            box[j] = t;
        }
        byte[] keyBox = new byte[256];
        for (int i = 0; i < 256; i++) {
            int ii = (i + 1) & 0xFF;
            int si = box[ii];
            int sj = box[(ii + si) & 0xFF];
            keyBox[i] = (byte) box[(si + sj) & 0xFF];
        }
        return keyBox;
    }

    // ================= KGM / KGMA / VPR =================
    public static byte[] decryptKgm(byte[] data, boolean isVpr) throws DecryptException {
        boolean okHeader = isVpr ? startsWith(data, VPR_HEADER) : startsWith(data, KGM_HEADER);
        if (!okHeader) {
            throw new DecryptException(isVpr
                    ? "此 VPR 文件已损坏或不是有效的酷狗音乐文件。"
                    : "此 KGM 文件已损坏或不是有效的酷狗音乐文件。");
        }
        int headerLen = readIntLE(data, 0x10);
        if (headerLen < 0 || headerLen >= data.length) {
            throw new DecryptException("KGM 文件头长度异常，文件可能损坏。");
        }
        byte[] key = new byte[17];
        int copyLen = Math.min(16, data.length - 0x1c);
        if (copyLen > 0) System.arraycopy(data, 0x1c, key, 0, copyLen);

        byte[] body = Arrays.copyOfRange(data, headerLen, data.length);
        byte[] out = new byte[body.length];
        for (int i = 0; i < body.length; i++) {
            int med8 = (key[i % 17] & 0xFF) ^ (body[i] & 0xFF);
            med8 ^= (med8 & 0xF) << 4;
            int msk8 = kgmGetMask(i);
            msk8 ^= (msk8 & 0xF) << 4;
            int result = med8 ^ msk8;
            if (isVpr) result ^= VPR_MASK_DIFF[i % 17];
            out[i] = (byte) (result & 0xFF);
        }
        return out;
    }

    private static int kgmGetMask(int pos) {
        int offset = pos >> 4;
        int value = 0;
        while (offset >= 0x11) {
            value ^= KGM_TABLE1[offset % 272];
            offset >>= 4;
            value ^= KGM_TABLE2[offset % 272];
        }
        return (KGM_MASK_V2_PREDEF[pos % 272] ^ value) & 0xFF;
    }

    // ================= KWM =================
    public static byte[] decryptKwm(byte[] data) throws DecryptException {
        boolean magicOk = startsWith(data, KWM_MAGIC1) || startsWith(data, KWM_MAGIC2);
        if (!magicOk) {
            throw new DecryptException("此 KWM 文件已损坏或不是有效的酷我音乐文件。");
        }
        if (data.length < 0x400 + 16) {
            throw new DecryptException("KWM 文件数据段过短，文件可能损坏。");
        }
        byte[] mask = kwmCreateMask(Arrays.copyOfRange(data, 0x18, 0x20));
        byte[] audio = Arrays.copyOfRange(data, 0x400, data.length);
        for (int i = 0; i < audio.length; i++) audio[i] ^= mask[i & 0x1F];
        return audio;
    }

    private static byte[] kwmCreateMask(byte[] fileKey) {
        long v = 0;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (fileKey[i] & 0xFFL);
        }
        String keyStr = Long.toUnsignedString(v);
        if (keyStr.length() > 32) keyStr = keyStr.substring(0, 32);
        else if (keyStr.length() < 32) {
            StringBuilder sb = new StringBuilder(keyStr);
            while (sb.length() < 32) sb.append(keyStr);
            keyStr = sb.substring(0, 32);
        }
        byte[] mask = new byte[32];
        byte[] pre = KWM_PREDEFINED_KEY.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 32; i++) {
            mask[i] = (byte) (pre[i] ^ keyStr.charAt(i));
        }
        return mask;
    }

    // ================= 容器嗅探 =================
    public static String sniffAudioExt(byte[] b) {
        if (b.length >= 3 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') return "mp3";
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return "mp3";
        if (b.length >= 4 && b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') return "flac";
        if (b.length >= 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return "ogg";
        if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return "m4a";
        return "mp3";
    }
}
