# FlyingMouse Format · Android 版（鼠鼠格式转换）

桌面版 [try-typetransfer](https://github.com/startime-ltk/try-typetransfer) 的安卓移植分支（`android` 分支），基于同一套"飞鼠格式"产品理念：**纯本地、无网络、即选即转**的离线文件格式转换器。

## 功能范围（移动端子集）

桌面版支持格式较多，安卓端首版移植了**纯本地可闭环**的核心转换子集：

| 类别 | 源格式 | 可转目标 |
| ---- | ------ | -------- |
| 图片 | JPG / JPEG / PNG / WebP / BMP / GIF | PNG / JPG / WebP / BMP / PDF |
| 图片（多选） | 任意多张图片 | 合并为一个 PDF |
| PDF | PDF | PNG / JPG（逐页渲染）+ 打包 ZIP / TXT 提取 |
| 文本 | TXT | HTML / Markdown / EPUB |
| 文本 | Markdown | HTML / TXT / EPUB |
| 文本 | HTML | TXT / Markdown |
| 数据 | JSON | XML / 美化 |
| 数据 | XML | JSON / 美化 |
| 数据 | CSV | JSON / Markdown 表格 / HTML 表格 |
| 压缩包 | ZIP | 解压提取文本内容 |

## 使用方式

1. 打开 App，点击 **选择文件**，可多选；
2. 点击目标格式（多图时可选"合并 PDF"）；
3. 点击 **转换**，产物自动保存到 `下载/FlyingMouseFormat/`。

也支持从系统文件管理器"用…打开"或"分享"直接送入本 App 转换。

## 技术要点

- 纯 **Java + 原生 View** 实现，不依赖 AndroidX / 第三方转换库，转换逻辑全部自研；
- 文件选择走 **SAF**（Storage Access Framework），无需存储权限即可读取；产物经 MediaStore 写入公共下载目录；
- 图片解码/编码使用 `BitmapFactory`（BMP 24 位含手写编码器），PDF 渲染基于系统 `PdfRenderer`；
- JSON↔XML 由自研 `JsonXml` 实现（Android 内置 `org.json` 不含桌面版使用的 `org.json.XML`）；
- EPUB3 生成器为极简实现（mimetype + container + OPF + XHTML）；
- 沿用桌面版鼠鼠状态图作为 UI 形象。

## 构建

```bash
# 本目录为独立 Gradle 工程
./gradlew :app:assembleDebug
# APK 输出：app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17+、Android SDK（compileSdk 36 / minSdk 26 / targetSdk 36）、AGP 9.1.0 + Gradle 9.3.1（wrapper 已内置）。

## 工程结构

```
android/
├── app/src/main/java/com/flyingmouse/format/
│   ├── MainActivity.java          # 主界面：选文件 → 选目标 → 转换
│   ├── convert/
│   │   ├── ConversionEngine.java  # 转换调度引擎
│   │   ├── ImageTool.java         # 图片互转 / 多图合并 PDF
│   │   ├── PdfTool.java           # PDF 逐页渲染 PNG/JPG
│   │   ├── TextTool.java          # TXT/Markdown/HTML 互转
│   │   ├── JsonXml.java           # JSON↔XML 自研实现
│   │   ├── EpubTool.java          # EPUB3 生成器
│   │   ├── EpubReader.java        # EPUB → TXT 提取
│   │   └── ZipTool.java           # ZIP 解压/文本提取
│   ├── util/FormatKit.java        # 格式分类与转换目标矩阵
│   └── util/OutputSaver.java      # MediaStore 输出保存
└── app/src/main/res/              # 布局 / 鼠鼠状态图 / 图标
```

## 后续规划

- 音频（mp3/flac/wav/ogg/aac 等）互转需本地解码器与 FFmpeg 集成，属下一阶段重点；
- Office 系列依赖桌面端引擎，暂不在移动端子集内；
- UI 将持续对齐桌面版鼠鼠主题与交互。
