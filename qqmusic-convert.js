// qqmusic-convert.js — QQ音乐加密格式 → MP3 转码模块。
// 职责边界：解密由 QQ 音乐官方授权接口完成（resolveDecryptedFile），
// 本模块只负责把接口返回的【解密后音频】用内置 FFmpeg 转成 MP3。
// 与 media.js 同构：复用 config.FFMPEG_PATH 与 utils.run，错误带 zhCN/enUS 消息。
const fs = require("fs");
const path = require("path");
const { FFMPEG_PATH } = require("./config");
const { run } = require("./utils");

// ============================================================================
// 授权接口接入点（用户已实现）
// 输入：原始加密文件路径（如 xxx.mflac）
// 返回：解密后音频的本地路径（FLAC / Ogg / M4A 等任意 FFmpeg 可解码格式）；
//       或 { decryptedPath, cleanup }（cleanup 可选，转换后回调用于清理临时文件）
// ============================================================================
async function resolveDecryptedFile(inputPath) {
  // TODO(工程师): 调用您自己的授权接口，例如：
  //   const resp = await fetch("https://api.qqmusic.example.com/decrypt", {
  //     method: "POST",
  //     body: JSON.stringify({ filePath: inputPath, token: "<授权token>" }),
  //   });
  //   将接口返回的音频流写入本地临时文件并 return 其路径。
  throw Object.assign(new Error("授权接口未接入，请实现 resolveDecryptedFile()"), {
    code: "QQMUSIC_DECRYPT_UNAVAILABLE",
    messages: {
      zhCN: "QQ音乐解密授权接口未接入，暂时无法转换该文件。",
      enUS: "The QQ Music decryption API is not connected yet, so this file cannot be converted."
    }
  });
}

// ============================================================================
// 将【解密后音频】用 FFmpeg 转成 MP3。
// ============================================================================
async function convertDecryptedToMp3(decryptedPath, outputPath) {
  await run(FFMPEG_PATH, [
    "-hide_banner",
    "-y",
    "-i",
    decryptedPath,
    "-vn",
    "-codec:a",
    "libmp3lame",
    "-q:a",
    "2",
    outputPath
  ], { timeout: 1000 * 60 * 30 });
}

// ============================================================================
// 单文件入口：加密文件 → MP3
// ============================================================================
async function qqmusicToMp3(inputPath, outputPath, options = {}) {
  const decrypted = await resolveDecryptedFile(inputPath);
  const decryptedPath = typeof decrypted === "string" ? decrypted : decrypted.decryptedPath;
  const cleanup = typeof decrypted === "string" ? null : (decrypted.cleanup || null);

  try {
    await convertDecryptedToMp3(decryptedPath, outputPath);
  } finally {
    if (typeof cleanup === "function") {
      try { cleanup(); } catch (_) { /* 忽略清理异常 */ }
    }
  }
  return outputPath;
}

// ============================================================================
// 批量入口：加密文件列表 → 各自输出目录
// 返回逐文件 { input, output, ok, error? }
// ============================================================================
async function qqmusicBatchToMp3(inputs, outputDir, options = {}) {
  fs.mkdirSync(outputDir, { recursive: true });
  const results = [];
  for (const input of inputs) {
    const base = path.basename(input, path.extname(input));
    const output = path.join(outputDir, `${base}.mp3`);
    try {
      await qqmusicToMp3(input, output, options);
      results.push({ input, output, ok: true });
    } catch (error) {
      results.push({ input, output, ok: false, error: String(error.message || error) });
    }
  }
  return results;
}

// ============================================================================
// CLI：node qqmusic-convert.js in.mflac [out.mp3|--out-dir DIR]
// ============================================================================
if (require.main === module) {
  (async () => {
    const argv = process.argv.slice(2);
    if (!argv.length) {
      console.error("用法: node qqmusic-convert.js <加密文件> [输出文件|--out-dir <目录>]");
      process.exit(2);
    }
    const input = argv[0];
    if (argv.includes("--out-dir")) {
      const dir = argv[argv.indexOf("--out-dir") + 1];
      const results = await qqmusicBatchToMp3([input], dir);
      console.log(JSON.stringify(results, null, 2));
      process.exit(results.every(r => r.ok) ? 0 : 1);
    }
    const output = argv[1] || path.join(path.dirname(input), `${path.basename(input, path.extname(input))}.mp3`);
    await qqmusicToMp3(input, output);
    console.log(`转换完成: ${output}`);
  })().catch((e) => {
    console.error(e.message);
    process.exit(1);
  });
}

module.exports = { qqmusicToMp3, qqmusicBatchToMp3, convertDecryptedToMp3, resolveDecryptedFile };
