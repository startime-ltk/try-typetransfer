# FlyingMouse Format · Android 版（鼠鼠格式转换）

桌面版 [try-typetransfer](https://github.com/startime-ltk/try-typetransfer) 的安卓移植分支（`android` 分支），基于同一套"飞鼠格式"产品理念：**纯本地、无网络、即选即转**的离线文件格式转换器。

## 功能范围（移动端子集）

桌面版支持格式较多，安卓端首版移植了**纯本地可闭环**的核心转换子集：

| 类别 | 源格式 | 可转目标 |
| ---- | ------ | -------- |
| 图片 | JPG / JPEG / PNG / WebP / BMP / AVIF / HEIC / HEIF / ICO / TGA | PNG / JPG / WebP / BMP / PDF |
| 图片 OCR | 同上任意图片 | TXT / Markdown（离线识别中英文） |
| 图片（多选） | 任意多张图片 | 合并为一个 PDF |
| PDF | PDF | PNG / JPG（逐页渲染）+ 打包 ZIP / TXT / MD（无文本层的扫描件自动逐页 OCR，最多 30 页） |
| 文本 | TXT | HTML / Markdown / EPUB |
| 文本 | Markdown | HTML / TXT / EPUB |
| 文本 | HTML | TXT / Markdown |
| 数据 | JSON | XML / 美化 |
| 数据 | XML | JSON / 美化 |
| 数据 | CSV | JSON / Markdown 表格 / HTML 表格 |
| 压缩包 | ZIP | 解压提取文本内容 |
| 电子书 | EPUB | TXT / Markdown |
| 电子书 | MOBI | TXT / Markdown / EPUB |
| 文档 | DOCX | TXT / Markdown / HTML（段落+表格文本提取） |
| 表格 | XLSX | CSV / Markdown 表格 / HTML 表格（含 sharedStrings、多 sheet） |
| 演示 | PPTX | TXT / Markdown / HTML（按页分割文本） |

## 使用方式

1. 打开 App，点击 **选择文件**，可多选；
2. 点击目标格式（多图时可选"合并 PDF"）；
3. 点击 **转换**，产物自动保存到 `下载/FlyingMouseFormat/`。

也支持从系统文件管理器"用…打开"或"分享"直接送入本 App 转换。

## 技术要点

- 纯 **Java + 原生 View** 实现，转换逻辑全部自研；仅 P6 图片 OCR 引入 ML Kit（`com.google.mlkit:text-recognition-chinese`，其 AndroidX 传递依赖要求 `android.useAndroidX=true`）；
- 图片 OCR（P6）：ML Kit 内置 bundled 模型，**完全离线**识别中英文，输出 TXT / Markdown，无需联网与 Play 服务；已用 `abiFilters` 仅保留 `arm64-v8a` / `x86_64`，APK 由 146.2MB 降至约 81.5MB；
- 文件选择走 **SAF**（Storage Access Framework），无需存储权限即可读取；产物经 MediaStore 写入公共下载目录；
- 图片解码/编码使用 `BitmapFactory`（BMP 24 位含手写编码器），PDF 渲染基于系统 `PdfRenderer`；
- PDF→TXT/MD 为纯 Java 自研文本层提取（自解对象索引与 `FlateDecode`，支持 `ToUnicode` CMap 与 Identity-H 双字节中文），不依赖 Poppler；扫描件（图片型 PDF）无文本层时（P6c）自动逐页 `PdfRenderer` 渲染 + ML Kit 离线 OCR 兜底（最多 30 页，页面长边超 1800px 先等比缩放以控耗时）；
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
│   │   ├── PdfTool.java           # PDF 逐页渲染 PNG/JPG + 扫描件 OCR 文本
│   │   ├── PdfTextExtractor.java  # PDF 文本层提取（纯 Java：CMap/FlateDecode）
│   │   ├── OcrEngine.java         # 图片 OCR（ML Kit 离线中英文识别）
│   │   ├── TextTool.java          # TXT/Markdown/HTML 互转
│   │   ├── JsonXml.java           # JSON↔XML 自研实现
│   │   ├── EpubTool.java          # EPUB3 生成器
│   │   ├── EpubReader.java        # EPUB → TXT 提取
│   │   ├── OfficeReader.java      # DOCX/XLSX/PPTX 可用子集（zip+XML 提取）
│   │   └── ZipTool.java           # ZIP 解压/文本提取
│   ├── util/FormatKit.java        # 格式分类与转换目标矩阵
│   └── util/OutputSaver.java      # MediaStore 输出保存
└── app/src/main/res/              # 布局 / 鼠鼠状态图 / 图标
```

## 后续规划

- 图片 OCR（P6a）已落地：任意图片 → TXT / Markdown，ML Kit 离线中英文识别；
- 扫描件 PDF OCR（P6c）已落地：无文本层的图片型 PDF 自动逐页渲染 + OCR，输出 TXT / Markdown（上限 30 页）；
- PDF 文本提取（P8）与逐页渲染（P7）已落地，扫描件空隙已由 P6c 补齐；
- PDF→xlsx/docx 智能结构还原、相机 RAW 等对齐桌面端能力，属后续阶段重点；
- UI 将持续对齐桌面版鼠鼠主题与交互。
