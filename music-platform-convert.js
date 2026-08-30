// music-platform-convert.js — 酷狗音乐 / 酷我音乐 / 网易云音乐 加密格式 → MP3 转码模块。
//
// 职责边界：
//   1) 解密：采用开源社区通用算法（参考 unlock-music 项目，MIT 协议）本地完成，
//      面向"个人已购音乐"的格式解锁场景；若平台提供官方授权解密接口，可替换
//      decryptNcm / decryptKgm / decryptKwm 内部实现为接口调用（保留接口形态）。
//   2) 转码：解密后的音频统一用内置 FFmpeg（libmp3lame）转成 MP3。
//
// 与 qqmusic-convert.js 同构：复用 config.FFMPEG_PATH 与 utils.run，错误带 zhCN/enUS 消息。

const fs = require("fs");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const { FFMPEG_PATH } = require("./config");
const { run } = require("./utils");


// ============================================================================
// 网易云音乐 .ncm 解密
// 结构：8 字节魔数 "CTENFDAM" + 2 字节版本
//       [4 字节 keyLen][key 密文(^0x64) → AES-128-ECB(CORE_KEY) → 取 17 字节后作为 RC4 密钥]
//       [4 字节 metaLen][meta 密文(^0x63) → 取 22 字节后 base64 解码 → AES-128-ECB(META_KEY)]
//       [音频区：跳过字段 + RC4 密钥流异或]
// ============================================================================
const NCM_CORE_KEY = Buffer.from("687a4852416d736f356b496e62617857", "hex");
const NCM_META_KEY = Buffer.from("2331346C6A6B5F215C5D2630553C2728", "hex");
const NCM_MAGIC = Buffer.from([0x43, 0x54, 0x45, 0x4e, 0x46, 0x44, 0x41, 0x4d]); // CTENFDAM

function aesEcbDecrypt(data, key) {
  const decipher = crypto.createDecipheriv("aes-128-ecb", key, null);
  return Buffer.concat([decipher.update(data), decipher.final()]);
}

function ncmKeyBox(keyData) {
  // RC4 KSA + PRGA：返回 256 字节密钥流
  const box = new Uint8Array(256);
  for (let i = 0; i < 256; i++) box[i] = i;
  let j = 0;
  for (let i = 0; i < 256; i++) {
    j = (box[i] + j + keyData[i % keyData.length]) & 0xff;
    const t = box[i];
    box[i] = box[j];
    box[j] = t;
  }
  const keyBox = new Uint8Array(256);
  for (let i = 0; i < 256; i++) {
    const ii = (i + 1) & 0xff;
    const si = box[ii];
    const sj = box[(ii + si) & 0xff];
    keyBox[i] = box[(si + sj) & 0xff];
  }
  return keyBox;
}

function decryptNcm(data) {
  if (data.length < 10 || !data.subarray(0, 8).equals(NCM_MAGIC)) {
    const error = new Error("此 NCM 文件已损坏或不是有效的网易云音乐文件。");
    error.code = "MUSIC_DECRYPT_INVALID";
    error.messages = {
      zhCN: "此 NCM 文件已损坏或不是有效的网易云音乐文件。",
      enUS: "This NCM file is corrupted or not a valid NetEase Cloud Music file."
    };
    throw error;
  }

  let offset = 10;

  // --- 密钥段 ---
  const keyLen = data.readUInt32LE(offset);
  offset += 4;
  if (offset + keyLen > data.length) {
    const error = new Error("NCM 文件密钥段越界，文件可能损坏。");
    error.code = "MUSIC_DECRYPT_INVALID";
    error.messages = { zhCN: "NCM 文件密钥段越界，文件可能损坏。", enUS: "NCM key section out of range. File may be corrupted." };
    throw error;
  }
  const keyCipher = Buffer.from(data.subarray(offset, offset + keyLen));
  offset += keyLen;
  for (let i = 0; i < keyCipher.length; i++) keyCipher[i] ^= 0x64;
  const keyPlain = aesEcbDecrypt(keyCipher, NCM_CORE_KEY);
  const keyData = keyPlain.subarray(17);
  if (!keyData.length) {
    const error = new Error("NCM 文件密钥解析失败。");
    error.code = "MUSIC_DECRYPT_INVALID";
    error.messages = { zhCN: "NCM 文件密钥解析失败。", enUS: "Failed to parse NCM file key." };
    throw error;
  }
  const keyBox = ncmKeyBox(keyData);

  // --- 元数据段 ---
  const metaLen = data.readUInt32LE(offset);
  offset += 4;
  let meta = null;
  if (metaLen > 0 && offset + metaLen <= data.length) {
    const metaCipher = Buffer.from(data.subarray(offset, offset + metaLen));
    offset += metaLen;
    for (let i = 0; i < metaCipher.length; i++) metaCipher[i] ^= 0x63;
    const b64 = metaCipher.subarray(22).toString("latin1");
    try {
      const metaPlain = aesEcbDecrypt(Buffer.from(b64, "base64"), NCM_META_KEY).toString("utf8");
      const labelIndex = metaPlain.indexOf(":");
      if (labelIndex > 0) {
        const payload = metaPlain.slice(labelIndex + 1);
        let parsed = JSON.parse(payload);
        if (metaPlain.slice(0, labelIndex) === "dj") parsed = parsed.mainMusic;
        meta = parsed;
      }
    } catch {
      // 元数据解析失败不阻断解密（部分 ncm 元数据可能为空）
    }
  }

  // --- 音频段 ---
  if (offset + 9 > data.length) {
    const error = new Error("NCM 文件音频段越界，文件可能损坏。");
    error.code = "MUSIC_DECRYPT_INVALID";
    error.messages = { zhCN: "NCM 文件音频段越界，文件可能损坏。", enUS: "NCM audio section out of range. File may be corrupted." };
    throw error;
  }
  offset += data.readUInt32LE(offset + 5) + 13;
  const audio = Buffer.from(data.subarray(offset));
  for (let i = 0; i < audio.length; i++) audio[i] ^= keyBox[i & 0xff];

  return { audio, meta };
}

// ============================================================================
// 酷狗音乐 .kgm / .kgma / .vpr 解密（KGM V2 算法）
// ============================================================================
const KGM_HEADER = Buffer.from([0x7c, 0xd5, 0x32, 0xeb, 0x86, 0x02, 0x7f, 0x4b, 0xa8, 0xaf, 0xa6, 0x8e, 0x0f, 0xff, 0x99, 0x14]);
const VPR_HEADER = Buffer.from([0x05, 0x28, 0xbc, 0x96, 0xe9, 0xe4, 0x5a, 0x43, 0x91, 0xaa, 0xbd, 0xd0, 0x7a, 0xf5, 0x36, 0x31]);
const VPR_MASK_DIFF = [0x25, 0xdf, 0xe8, 0xa6, 0x75, 0x1e, 0x75, 0x0e, 0x2f, 0x80, 0xf3, 0x2d, 0xb8, 0xb6, 0xe3, 0x11, 0x00];

// KGM V2 掩码表（与 unlock-music KgmWasm / kugou-crypto 一致）
const KGM_TABLE1 = [
  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x21, 0x01, 0x61, 0x01, 0x21, 0x01,
  0xe1, 0x01, 0x21, 0x01, 0x61, 0x01, 0x21, 0x01, 0xd2, 0x23, 0x02, 0x02,
  0x42, 0x42, 0x02, 0x02, 0xc2, 0xc2, 0x02, 0x02, 0x42, 0x42, 0x02, 0x02,
  0xd3, 0xd3, 0x02, 0x03, 0x63, 0x43, 0x63, 0x03, 0xe3, 0xc3, 0xe3, 0x03,
  0x63, 0x43, 0x63, 0x03, 0x94, 0xb4, 0x94, 0x65, 0x04, 0x04, 0x04, 0x04,
  0x84, 0x84, 0x84, 0x84, 0x04, 0x04, 0x04, 0x04, 0x95, 0x95, 0x95, 0x95,
  0x04, 0x05, 0x25, 0x05, 0xe5, 0x85, 0xa5, 0x85, 0xe5, 0x05, 0x25, 0x05,
  0xd6, 0xb6, 0x96, 0xb6, 0xd6, 0x27, 0x06, 0x06, 0xc6, 0xc6, 0x86, 0x86,
  0xc6, 0xc6, 0x06, 0x06, 0xd7, 0xd7, 0x97, 0x97, 0xd7, 0xd7, 0x06, 0x07,
  0xe7, 0xc7, 0xe7, 0x87, 0xe7, 0xc7, 0xe7, 0x07, 0x18, 0x38, 0x18, 0x78,
  0x18, 0x38, 0x18, 0xe9, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08,
  0x19, 0x19, 0x19, 0x19, 0x19, 0x19, 0x19, 0x19, 0x08, 0x09, 0x29, 0x09,
  0x69, 0x09, 0x29, 0x09, 0xda, 0x3a, 0x1a, 0x3a, 0x5a, 0x3a, 0x1a, 0x3a,
  0xda, 0x2b, 0x0a, 0x0a, 0x4a, 0x4a, 0x0a, 0x0a, 0xdb, 0xdb, 0x1b, 0x1b,
  0x5b, 0x5b, 0x1b, 0x1b, 0xdb, 0xdb, 0x0a, 0x0b, 0x6b, 0x4b, 0x6b, 0x0b,
  0x9c, 0xbc, 0x9c, 0x7c, 0x1c, 0x3c, 0x1c, 0x7c, 0x9c, 0xbc, 0x9c, 0x6d,
  0x0c, 0x0c, 0x0c, 0x0c, 0x9d, 0x9d, 0x9d, 0x9d, 0x1d, 0x1d, 0x1d, 0x1d,
  0x9d, 0x9d, 0x9d, 0x9d, 0x0c, 0x0d, 0x2d, 0x0d, 0xde, 0xbe, 0x9e, 0xbe,
  0xde, 0x3e, 0x1e, 0x3e, 0xde, 0xbe, 0x9e, 0xbe, 0xde, 0x2f, 0x0e, 0x0e,
  0xdf, 0xdf, 0x9f, 0x9f, 0xdf, 0xdf, 0x1f, 0x1f, 0xdf, 0xdf, 0x9f, 0x9f,
  0xdf, 0xdf, 0x0e, 0x0f, 0x00, 0x20, 0x00, 0x60, 0x00, 0x20, 0x00, 0xe0,
  0x00, 0x20, 0x00, 0x60, 0x00, 0x20, 0x00, 0xf1
];

const KGM_TABLE2 = [
  0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
  0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x23, 0x01, 0x67, 0x01, 0x23, 0x01,
  0xef, 0x01, 0x23, 0x01, 0x67, 0x01, 0x23, 0x01, 0xdf, 0x21, 0x02, 0x02,
  0x46, 0x46, 0x02, 0x02, 0xce, 0xce, 0x02, 0x02, 0x46, 0x46, 0x02, 0x02,
  0xde, 0xde, 0x02, 0x03, 0x65, 0x47, 0x65, 0x03, 0xed, 0xcf, 0xed, 0x03,
  0x65, 0x47, 0x65, 0x03, 0x9d, 0xbf, 0x9d, 0x63, 0x04, 0x04, 0x04, 0x04,
  0x8c, 0x8c, 0x8c, 0x8c, 0x04, 0x04, 0x04, 0x04, 0x9c, 0x9c, 0x9c, 0x9c,
  0x04, 0x05, 0x27, 0x05, 0xeb, 0x8d, 0xaf, 0x8d, 0xeb, 0x05, 0x27, 0x05,
  0xdb, 0xbd, 0x9f, 0xbd, 0xdb, 0x25, 0x06, 0x06, 0xca, 0xca, 0x8e, 0x8e,
  0xca, 0xca, 0x06, 0x06, 0xda, 0xda, 0x9e, 0x9e, 0xda, 0xda, 0x06, 0x07,
  0xe9, 0xcb, 0xe9, 0x8f, 0xe9, 0xcb, 0xe9, 0x07, 0x19, 0x3b, 0x19, 0x7f,
  0x19, 0x3b, 0x19, 0xe7, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08,
  0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x08, 0x09, 0x2b, 0x09,
  0x6f, 0x09, 0x2b, 0x09, 0xd7, 0x39, 0x1b, 0x39, 0x5f, 0x39, 0x1b, 0x39,
  0xd7, 0x29, 0x0a, 0x0a, 0x4e, 0x4e, 0x0a, 0x0a, 0xd6, 0xd6, 0x1a, 0x1a,
  0x5e, 0x5e, 0x1a, 0x1a, 0xd6, 0xd6, 0x0a, 0x0b, 0x6d, 0x4f, 0x6d, 0x0b,
  0x95, 0xb7, 0x95, 0x7b, 0x1d, 0x3f, 0x1d, 0x7b, 0x95, 0xb7, 0x95, 0x6b,
  0x0c, 0x0c, 0x0c, 0x0c, 0x94, 0x94, 0x94, 0x94, 0x1c, 0x1c, 0x1c, 0x1c,
  0x94, 0x94, 0x94, 0x94, 0x0c, 0x0d, 0x2f, 0x0d, 0xd3, 0xb5, 0x97, 0xb5,
  0xd3, 0x3d, 0x1f, 0x3d, 0xd3, 0xb5, 0x97, 0xb5, 0xd3, 0x2d, 0x0e, 0x0e,
  0xd2, 0xd2, 0x96, 0x96, 0xd2, 0xd2, 0x1e, 0x1e, 0xd2, 0xd2, 0x96, 0x96,
  0xd2, 0xd2, 0x0e, 0x0f, 0x00, 0x22, 0x00, 0x66, 0x00, 0x22, 0x00, 0xee,
  0x00, 0x22, 0x00, 0x66, 0x00, 0x22, 0x00, 0xfe
];

const KGM_MASK_V2_PREDEF = [
  0xb8, 0xd5, 0x3d, 0xb2, 0xe9, 0xaf, 0x78, 0x8c, 0x83, 0x33, 0x71, 0x51,
  0x76, 0xa0, 0xcd, 0x37, 0x2f, 0x3e, 0x35, 0x8d, 0xa9, 0xbe, 0x98, 0xb7,
  0xe7, 0x8c, 0x22, 0xce, 0x5a, 0x61, 0xdf, 0x68, 0x69, 0x89, 0xfe, 0xa5,
  0xb6, 0xde, 0xa9, 0x77, 0xfc, 0xc8, 0xbd, 0xbd, 0xe5, 0x6d, 0x3e, 0x5a,
  0x36, 0xef, 0x69, 0x4e, 0xbe, 0xe1, 0xe9, 0x66, 0x1c, 0xf3, 0xd9, 0x02,
  0xb6, 0xf2, 0x12, 0x9b, 0x44, 0xd0, 0x6f, 0xb9, 0x35, 0x89, 0xb6, 0x46,
  0x6d, 0x73, 0x82, 0x06, 0x69, 0xc1, 0xed, 0xd7, 0x85, 0xc2, 0x30, 0xdf,
  0xa2, 0x62, 0xbe, 0x79, 0x2d, 0x62, 0x62, 0x3d, 0x0d, 0x7e, 0xbe, 0x48,
  0x89, 0x23, 0x02, 0xa0, 0xe4, 0xd5, 0x75, 0x51, 0x32, 0x02, 0x53, 0xfd,
  0x16, 0x3a, 0x21, 0x3b, 0x16, 0x0f, 0xc3, 0xb2, 0xbb, 0xb3, 0xe2, 0xba,
  0x3a, 0x3d, 0x13, 0xec, 0xf6, 0x01, 0x45, 0x84, 0xa5, 0x70, 0x0f, 0x93,
  0x49, 0x0c, 0x64, 0xcd, 0x31, 0xd5, 0xcc, 0x4c, 0x07, 0x01, 0x9e, 0x00,
  0x1a, 0x23, 0x90, 0xbf, 0x88, 0x1e, 0x3b, 0xab, 0xa6, 0x3e, 0xc4, 0x73,
  0x47, 0x10, 0x7e, 0x3b, 0x5e, 0xbc, 0xe3, 0x00, 0x84, 0xff, 0x09, 0xd4,
  0xe0, 0x89, 0x0f, 0x5b, 0x58, 0x70, 0x4f, 0xfb, 0x65, 0xd8, 0x5c, 0x53,
  0x1b, 0xd3, 0xc8, 0xc6, 0xbf, 0xef, 0x98, 0xb0, 0x50, 0x4f, 0x0f, 0xea,
  0xe5, 0x83, 0x58, 0x8c, 0x28, 0x2c, 0x84, 0x67, 0xcd, 0xd0, 0x9e, 0x47,
  0xdb, 0x27, 0x50, 0xca, 0xf4, 0x63, 0x63, 0xe8, 0x97, 0x7f, 0x1b, 0x4b,
  0x0c, 0xc2, 0xc1, 0x21, 0x4c, 0xcc, 0x58, 0xf5, 0x94, 0x52, 0xa3, 0xf3,
  0xd3, 0xe0, 0x68, 0xf4, 0x00, 0x23, 0xf3, 0x5e, 0x0a, 0x7b, 0x93, 0xdd,
  0xab, 0x12, 0xb2, 0x13, 0xe8, 0x84, 0xd7, 0xa7, 0x9f, 0x0f, 0x32, 0x4c,
  0x55, 0x1d, 0x04, 0x36, 0x52, 0xdc, 0x03, 0xf3, 0xf9, 0x4e, 0x42, 0xe9,
  0x3d, 0x61, 0xef, 0x7c, 0xb6, 0xb3, 0x93, 0x50
];

function kgmGetMask(pos) {
  let offset = pos >> 4;
  let value = 0;
  while (offset >= 0x11) {
    value ^= KGM_TABLE1[offset % 272];
    offset >>= 4;
    value ^= KGM_TABLE2[offset % 272];
    offset >>= 4;
  }
  return (KGM_MASK_V2_PREDEF[pos % 272] ^ value) & 0xff;
}

function decryptKgm(data, isVpr) {
  const header = data.subarray(0, 16);
  const okHeader = isVpr ? header.equals(VPR_HEADER) : header.equals(KGM_HEADER);
  if (!okHeader) {
    const error = new Error(isVpr ? "此 VPR 文件已损坏或不是有效的酷狗音乐文件。" : "此 KGM 文件已损坏或不是有效的酷狗音乐文件。");
    error.code = "MUSIC_DECRYPT_INVALID";
    error.messages = {
      zhCN: isVpr ? "此 VPR 文件已损坏或不是有效的酷狗音乐文件。" : "此 KGM 文件已损坏或不是有效的酷狗音乐文件。",
      enUS: isVpr ? "This VPR file is corrupted or not a valid Kugou music file." : "This KGM file is corrupted or not a valid Kugou music file."
    };
    throw error;
  }

  const headerLen = data.readUInt32LE(0x10);
  const key = Buffer.alloc(17);
  data.copy(key, 0, 0x1c, 0x2c); // 16 字节 + 末尾 0
  if (headerLen >= data.length) {
    const error = new Error("KGM 文件头长度异常，文件可能损坏。");
    error.code = "MUSIC_DECRYPT_INVALID";
    error.messages = { zhCN: "KGM 文件头长度异常，文件可能损坏。", enUS: "KGM header length is abnormal. File may be corrupted." };
    throw error;
  }
  const body = data.subarray(headerLen);
  const out = Buffer.alloc(body.length);
  for (let i = 0; i < body.length; i++) {
    let med8 = key[i % 17] ^ body[i];
    med8 ^= (med8 & 0xf) << 4;
    let msk8 = kgmGetMask(i);
    msk8 ^= (msk8 & 0xf) << 4;
    let result = med8 ^ msk8;
    if (isVpr) result ^= VPR_MASK_DIFF[i % 17];
    out[i] = result & 0xff;
  }
  return out;
}

// ============================================================================
// 酷我音乐 .kwm 解密
// 结构：16 字节魔数 "yeelion-kuwo-tme"（或 "yeelion-kuwo\0..."），
//       0x18 处 8 字节文件密钥 → BigUint64LE 十进制 → 32 字符补齐 → 与预置密钥异或生成掩码，
//       0x400 起音频逐字节异或掩码。
// ============================================================================
const KWM_MAGIC1 = Buffer.from([0x79, 0x65, 0x65, 0x6c, 0x69, 0x6f, 0x6e, 0x2d, 0x6b, 0x75, 0x77, 0x6f, 0x2d, 0x74, 0x6d, 0x65]);
const KWM_MAGIC2 = Buffer.from([0x79, 0x65, 0x65, 0x6c, 0x69, 0x6f, 0x6e, 0x2d, 0x6b, 0x75, 0x77, 0x6f, 0x00, 0x00, 0x00, 0x00]);
const KWM_PREDEFINED_KEY = "MoOtOiTvINGwd2E6n0E1i7L5t2IoOoNk";

function kwmCreateMask(fileKey) {
  let keyStr = fileKey.readBigUInt64LE(0).toString();
  if (keyStr.length > 32) keyStr = keyStr.slice(0, 32);
  else if (keyStr.length < 32) keyStr = keyStr.padEnd(32, keyStr);
  const mask = new Uint8Array(32);
  for (let i = 0; i < 32; i++) mask[i] = KWM_PREDEFINED_KEY.charCodeAt(i) ^ keyStr.charCodeAt(i);
  return mask;
}

function decryptKwm(data) {
  if (!data.subarray(0, 16).equals(KWM_MAGIC1) && !data.subarray(0, 16).equals(KWM_MAGIC2)) {
    const error = new Error("此 KWM 文件已损坏或不是有效的酷我音乐文件。");
    error.code = "MUSIC_DECRYPT_INVALID";
    error.messages = {
      zhCN: "此 KWM 文件已损坏或不是有效的酷我音乐文件。",
      enUS: "This KWM file is corrupted or not a valid Kuwo music file."
    };
    throw error;
  }
  if (data.length < 0x400 + 16) {
    const error = new Error("KWM 文件数据段过短，文件可能损坏。");
    error.code = "MUSIC_DECRYPT_INVALID";
    error.messages = { zhCN: "KWM 文件数据段过短，文件可能损坏。", enUS: "KWM data section is too short. File may be corrupted." };
    throw error;
  }
  const mask = kwmCreateMask(data.subarray(0x18, 0x20));
  const audio = Buffer.from(data.subarray(0x400));
  for (let i = 0; i < audio.length; i++) audio[i] ^= mask[i % 0x20];
  return audio;
}

// ============================================================================
// 音频格式嗅探（解密后）
// ============================================================================
function sniffAudioExt(buffer) {
  if (buffer.length >= 3 && buffer.subarray(0, 3).toString("latin1") === "ID3") return "mp3";
  if (buffer.length >= 2 && buffer[0] === 0xff && (buffer[1] & 0xe0) === 0xe0) return "mp3";
  if (buffer.length >= 4 && buffer.subarray(0, 4).toString("latin1") === "fLaC") return "flac";
  if (buffer.length >= 4 && buffer.subarray(0, 4).toString("latin1") === "OggS") return "ogg";
  if (buffer.length >= 12 && buffer.subarray(4, 8).toString("latin1") === "ftyp") return "m4a";
  return "mp3";
}

// ============================================================================
// 解密 + 转 MP3 全链路
// decryptor: (Buffer) => Buffer（解密后的音频数据）
// ============================================================================
function makePlatformToMp3(platformName, decryptor, errorPrefix) {
  return async function platformToMp3(inputPath, outputPath, options = {}) {
    let raw;
    try {
      raw = fs.readFileSync(inputPath);
    } catch (error) {
      const wrapped = new Error(`读取加密文件失败：${String(error.message || error)}`);
      wrapped.code = `${errorPrefix}_READ_FAILED`;
      wrapped.messages = { zhCN: `${platformName}文件读取失败。`, enUS: `Failed to read ${platformName} file.` };
      throw wrapped;
    }

    const decrypted = decryptor(raw);
    // 兼容 decryptor 返回 { audio, meta } 或直接返回 Buffer（NCM 返回对象以携带元数据）
    const audio = Buffer.isBuffer(decrypted) ? decrypted : decrypted.audio;
    if (!Buffer.isBuffer(audio)) {
      const error = new Error("解密输出不是有效音频数据。");
      error.code = `${errorPrefix}_DECRYPT_INVALID`;
      error.messages = { zhCN: `${platformName}格式解密输出无效。`, enUS: `Invalid decrypted output for ${platformName} format.` };
      throw error;
    }

    // 解密后写入临时文件，再交给 FFmpeg 转 MP3
    const tempDec = path.join(os.tmpdir(), `${path.basename(inputPath)}.${Date.now()}.${sniffAudioExt(audio)}`);
    try {
      fs.writeFileSync(tempDec, audio);
      await convertDecryptedToMp3(tempDec, outputPath, platformName, errorPrefix);
    } finally {
      fs.rmSync(tempDec, { force: true });
    }
    return outputPath;
  };
}

async function convertDecryptedToMp3(decryptedPath, outputPath, platformName, errorPrefix) {
  if (!fs.existsSync(decryptedPath)) {
    const error = new Error("解密后的音频文件不存在。");
    error.code = `${errorPrefix}_DECRYPTED_MISSING`;
    error.messages = {
      zhCN: "解密后的音频文件不存在。",
      enUS: "The decrypted audio file is missing."
    };
    throw error;
  }

  const args = [
    "-hide_banner", "-y",
    "-i", decryptedPath,
    "-vn",
    "-codec:a", "libmp3lame",
    "-q:a", "2",
    "-id3v2_version", "3",
    outputPath
  ];

  try {
    await run(FFMPEG_PATH, args, { timeout: 600000 });
  } catch (error) {
    const wrapped = new Error(`转码失败：${String(error.message || error)}`);
    wrapped.code = `${errorPrefix}_CONVERT_FAILED`;
    wrapped.messages = {
      zhCN: `${platformName}格式转 MP3 失败，请确认解密输出为有效音频。`,
      enUS: `Failed to convert ${platformName} format to MP3. Make sure the decrypted output is valid audio.`
    };
    throw wrapped;
  }

  return outputPath;
}

// ============================================================================
// 各平台主入口
// ============================================================================
const kgmToMp3 = makePlatformToMp3("酷狗音乐", (data) => decryptKgm(data, false), "KGM");
const kgmaToMp3 = makePlatformToMp3("酷狗音乐", (data) => decryptKgm(data, false), "KGM");
const vprToMp3 = makePlatformToMp3("酷狗音乐", (data) => decryptKgm(data, true), "KGM");
const kwmToMp3 = makePlatformToMp3("酷我音乐", decryptKwm, "KWM");
const ncmToMp3 = makePlatformToMp3("网易云音乐", decryptNcm, "NCM");

// ============================================================================
// 批量入口
// ============================================================================
async function batchToMp3(platformToMp3, inputs, outputDir, options = {}) {
  fs.mkdirSync(outputDir, { recursive: true });
  const results = [];
  for (const input of inputs) {
    const base = path.basename(input, path.extname(input));
    const output = path.join(outputDir, `${base}.mp3`);
    try {
      await platformToMp3(input, output, options);
      results.push({ input, output, ok: true });
    } catch (error) {
      results.push({ input, output, ok: false, error: String(error.message || error) });
    }
  }
  return results;
}

// ============================================================================
// CLI：node music-platform-convert.js <平台> <加密文件> [输出|--out-dir DIR]
// 平台：kgm / kgma / vpr / kwm / ncm
// ============================================================================
if (require.main === module) {
  (async () => {
    const argv = process.argv.slice(2);
    if (argv.length < 2) {
      console.error("用法: node music-platform-convert.js <kgm|kgma|vpr|kwm|ncm> <加密文件> [输出文件|--out-dir <目录>]");
      process.exit(2);
    }
    const platform = argv[0].toLowerCase();
    const input = argv[1];
        const fnMap = { kgm: kgmToMp3, kgma: kgmaToMp3, vpr: vprToMp3, kwm: kwmToMp3, ncm: ncmToMp3 };
    const fn = fnMap[platform];
    if (!fn) {
      console.error(`未知平台: ${platform}`);
      process.exit(2);
    }
    if (argv.includes("--out-dir")) {
      const dir = argv[argv.indexOf("--out-dir") + 1];
      const results = await batchToMp3(fn, [input], dir, {});
      console.log(JSON.stringify(results, null, 2));
      process.exit(results.every((r) => r.ok) ? 0 : 1);
    }
    const output = argv[2] || path.join(path.dirname(input), `${path.basename(input, path.extname(input))}.mp3`);
    await fn(input, output, {});
    console.log(`转换完成: ${output}`);
  })().catch((e) => {
    console.error(e.message);
    process.exit(1);
  });
}

module.exports = {
  decryptNcm,
  decryptKgm,
  decryptKwm,
  sniffAudioExt,
  kgmToMp3,
  kgmaToMp3,
  vprToMp3,
  kwmToMp3,
  ncmToMp3,
  batchToMp3,
  convertDecryptedToMp3
};
