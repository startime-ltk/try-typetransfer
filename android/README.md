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

## 分享 / 另存 / 批量能力

产物拿到手之后的三条出口链路（均在结果弹窗中操作），以及多文件批量转换的容错策略：

| 能力 | 说明 |
| ---- | ---- |
| **分享全部产物** | 结果弹窗「分享全部」把本次全部产物（含多页 PDF 渲染出的多张图）一次性打包进 `ClipData`，逐条 `content://` Uri 附带读权限，可直接分享到微信 / QQ / 网盘等；单文件场景同样复用该入口 |
| **系统分享接收** | 在相册 / 文件管理器里「分享」或「用其他应用打开」可直接把文件送入本 App 并自动入队；`ACTION_SEND` 与 `ACTION_SEND_MULTIPLE` 双分支互通读取 `EXTRA_STREAM` 与 `ClipData`，兼容不同厂商 ROM 的注入方式 |
| **另存到指定目录** | 结果弹窗支持「另存为」，走 SAF（`ACTION_OPEN_DOCUMENT_TREE`）由用户任选目标目录，逐文件写入并保留原文件名 |
| **批量转换 + 失败隔离** | 多选文件后逐文件独立转换，单个文件异常（损坏 / 不支持 / 解码失败）只标记该条失败并给出原因，不中断其余文件；结果弹窗逐条列出成功 / 失败状态 |
| **进度与队列管理** | 转换过程显示进度与队列状态（等待 / 转换中 / 成功 / 失败），可对排队项单独移除；分享注入的文件在应用已打开时直接累计入队 |
| **产物命名去重** | 同名产物自动追加序号，并先剥离已有序号再做去重，避免出现 `xxx (1) (1)` 层叠后缀；弹窗展示的一律是落盘真实文件名 |

## 技术要点

- 纯 **Java + 原生 View** 实现，转换逻辑全部自研；仅 P6 图片 OCR 引入 ML Kit（`com.google.mlkit:text-recognition-chinese`，其 AndroidX 传递依赖要求 `android.useAndroidX=true`）；
- 图片 OCR（P6）：ML Kit 内置 bundled 模型，**完全离线**识别中英文，输出 TXT / Markdown，无需联网与 Play 服务；已用 `abiFilters` 仅保留 `arm64-v8a` / `x86_64`，APK 由 146.2MB 降至约 81.5MB；
- 文件选择走 **SAF**（Storage Access Framework），无需存储权限即可读取；产物经 MediaStore 写入公共下载目录；
- 图片解码/编码使用 `BitmapFactory`（BMP 24 位含手写编码器），PDF 渲染基于系统 `PdfRenderer`；
- PDF→TXT/MD 为纯 Java 自研文本层提取（自解对象索引与 `FlateDecode`，支持 `ToUnicode` CMap 与 Identity-H 双字节中文），不依赖 Poppler；扫描件（图片型 PDF）无文本层时（P6c）自动逐页 `PdfRenderer` 渲染 + ML Kit 离线 OCR 兜底（最多 30 页，页面长边超 1800px 先等比缩放以控耗时）；
- JSON↔XML 由自研 `JsonXml` 实现（Android 内置 `org.json` 不含桌面版使用的 `org.json.XML`）；
- EPUB3 生成器为极简实现（mimetype + container + OPF + XHTML）；
- 沿用桌面版鼠鼠状态图作为 UI 形象；
- **产物出口统一走 `content://`**：API 29+ 由 MediaStore 直接返回 `content://`；API 26–28 产物为 `file://`，统一经 `FileProvider`（`authorities=${applicationId}.fileprovider`，白名单 `Download/FlyingMouseFormat/`）转换后再分享 / 打开，规避 `FileUriExposedException`（同时新增 `androidx.core:core` 依赖提供 `FileProvider` 与 `ClipData` 多 Uri 授权）；
- **主界面 `launchMode="singleTop"`**：从相册 / 文件管理器「分享 / 用其他应用打开」进入时复用已有实例，接收到的文件累计入队而非重建界面；
- **产物命名去重**由 `OutputSaver.uniqueName()` / `stripDedupSuffix()` 实现，落盘真名回传结果弹窗展示；
- 批量转换逐文件 `try/catch` 失败隔离，单条失败不阻断整批。

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
