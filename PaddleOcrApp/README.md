# 飞桨 OCR 识图（Android）

一个调用 PaddleOCR HTTP 服务进行文字识别的安卓 App：

- 📷 调用系统相机拍照
- 🖼️ 从相册选取图片（Android 照片选择器，无需存储权限）
- ✂️ 手动框选识别区域（支持拖拽框选、整体移动、八方向手柄微调、放大镜辅助、选区实时预览）
- 🔤 把选区发送到 PaddleOCR 服务，返回文字结果（可复制、可分享，并保留最近一次结果）
- 📊 首页实时显示云端配额剩余（AI Studio 模式，按本机识别统计估算，额度用尽自动提示）
- 🧭 底部导航三个一级页面：OCR（拍照/相册/配额）、历史记录（最多保留 50 条）、服务器设置

兼容三类常见的 PaddleOCR 服务端 API：

| 服务类型 | 默认请求格式 | 说明 |
| --- | --- | --- |
| PaddleX 基础服务（PP-OCRv5 / v6） | `POST /ocr/predict`，`{"file": base64, "fileType": 1, "modelName": "OCR"}` | 官方推荐的新部署方式，默认端口 8080 |
| PaddleHub Serving（PP-OCRv3） | `POST /predict/ocr_system`，`{"images": [base64]}` | 经典部署方式，默认端口 8866 |
| AI Studio 云端任务 API | `POST /api/v2/ocr/jobs`，multipart 上传图片 → 轮询 jobId → 下载 JSONL | 异步任务接口，需 `Authorization: bearer <TOKEN>` 与模型名称 |

## 工程结构

```
PaddleOcrApp/
├── app/
│   └── src/main/
│       ├── java/com/example/paddleocr/
│       │   ├── MainActivity.kt          # OCR 页：拍照 / 相册 / 最近结果 / 云端配额
│       │   ├── HistoryActivity.kt       # 历史记录页：查看并打开往次识别结果
│       │   ├── HelpActivity.kt          # 使用说明隐藏页（设置页右上角图标进入）
│       │   ├── BottomNav.kt             # 底部导航切换（三个一级页面互不堆叠）
│       │   ├── CropActivity.kt          # 选区裁剪 + 发起识别
│       │   ├── RegionSelectorView.kt    # 自定义区域选择控件（拖拽/手柄/放大镜）
│       │   ├── ResultActivity.kt        # 识别结果展示、复制、分享
│       │   ├── SettingsActivity.kt      # 服务器设置
│       │   ├── OcrClient.kt             # HTTP 客户端 + 多格式响应解析
│       │   ├── Prefs.kt                 # 设置与历史结果持久化
│       │   └── ImageUtils.kt            # 图片加载/降采样/EXIF 旋转/压缩
│       └── res/                         # 布局、主题、图标
├── gradle/wrapper/                      # Gradle 8.9 Wrapper（已包含）
├── build.gradle.kts
└── settings.gradle.kts
```

## 构建

环境要求：

- Android Studio（Ladybug 或更新版本）
- JDK 17
- Android SDK Platform 35（Android Studio 会自动提示安装）

步骤：

1. 用 Android Studio 打开本目录（`PaddleOcrApp/`），等待 Gradle 同步完成。
2. 连接手机（开启 USB 调试）或启动模拟器。
3. 点击 Run 安装运行。
4. 如需命令行构建：`./gradlew assembleDebug`（Windows 用 `gradlew.bat assembleDebug`）。

生成的 APK 位于 `app/build/outputs/apk/debug/`。

## 部署 PaddleOCR 服务端

### 方案一：PaddleX 基础服务（推荐，PP-OCRv5 / v6）

在装有 Python 3.10+ 的电脑上：

```bash
pip install paddlex
paddlex --install serving
paddlex --serve --pipeline OCR
```

服务默认监听 `0.0.0.0:8080`，接口为 `POST /ocr/predict`。

### 方案二：PaddleHub Serving（PP-OCRv3）

```bash
pip install paddlehub==2.1.0 paddlepaddle
# 按官方文档下载 PP-OCRv3 推理模型并安装服务模块
hub serving start -m ocr_system
```

服务默认监听 `8866` 端口，接口为 `POST /predict/ocr_system`。

### 方案三：AI Studio 云端 API

在 [AI Studio PaddleOCR 服务页](https://aistudio.baidu.com/paddleocr/task) 创建任务，获取任务接口 URL（形如 `https://paddleocr.aistudio-app.com/api/v2/ocr/jobs`）、Token 与模型名称（如 `PaddleOCR-VL-1.6` 或 `PP-OCRv6`），填入 App 设置即可。

该接口为异步任务式协议，App 已完整实现：

1. multipart 上传裁剪后的图片（字段 `model`、`optionalPayload`、`file`），请求头携带 `Authorization: bearer <TOKEN>`；
2. 从响应 `data.jobId` 取得任务 ID，每隔几秒轮询 `GET {jobs}/{jobId}`；
3. 任务 `state == done` 后下载 `data.resultUrl.jsonUrl` 指向的 JSONL 结果，逐行解析 `result.ocrResults[].prunedResult` 中的文字与置信度。

> 实测注意：`optionalPayload` 请传 `{}`（空对象）。当前接口对内容校验严格，传 `{"useDocOrientationClassify": false, ...}` 这类完整对象会返回 HTTP 400，空对象即使用服务端默认参数。

AI Studio 模式下，首页会显示“云端配额（估算）”卡片：官方规则为**每模型每日 3000 页**，卡片按本机 App 成功识别的页数统计今日已用量并估算剩余（识别完成返回首页即自动刷新，也可点右上角刷新按钮）。云端目前没有公开的“剩余配额”查询接口，该数值为估算值；当请求返回 429（额度用尽）时，卡片会自动提示“今日额度已用完”。其他设备或其他客户端产生的用量不会计入本机统计。

## 手机端设置

1. 手机和电脑连接同一个局域网。
2. 打开 App → “服务器设置”，选择服务类型。
3. “API 地址”填电脑的局域网 IP，例如：
   - PaddleX：`http://192.168.1.100:8080/ocr/predict`
   - PaddleHub：`http://192.168.1.100:8866/predict/ocr_system`
   - AI Studio：选择“AI Studio 云端 API”后填入任务接口 URL（默认 `https://paddleocr.aistudio-app.com/api/v2/ocr/jobs`）、Token 与模型名称（如 `PaddleOCR-VL-1.6`）
4. 保存后即可拍照或选图识别。

AI Studio 的“模型名称”为下拉选择，内置官方支持的模型：`PaddleOCR-VL-1.6`、`PaddleOCR-VL-1.5`、`PaddleOCR-VL`、`PP-OCRv6`、`PP-OCRv5`、`PP-StructureV3`。

说明：

- App 已允许明文 HTTP，局域网内可直接使用 `http://` 地址；公网部署建议加 HTTPS。
- PaddleX 的 `fileType` 在不同版本取值含义不同：PP-OCRv5 新版服务图片填 `1`；PaddleX 3.0/3.1 旧版填 `0`，可在设置中切换。
- 识别结果不理想时，可先在选区界面把文字区域框得更精准，再点“开始识别”。

## 技术要点

- 拍照使用系统相机 + `FileProvider`，相册使用 Android 照片选择器（`PickVisualMedia`，不支持时自动回退到 `ACTION_GET_CONTENT`），因此**无需申请存储权限**。
- 图片加载时按最大边长降采样（显示图 1800px、识别图 4096px），并根据 EXIF 方向自动旋转，兼顾内存与识别精度。
- 识别时只把裁剪出的选区区域压缩为 JPEG Base64 发送，节省流量、提升精度。
- 响应解析兼容 `result.ocrResults[].prunedResult`、`result.res.rec_texts`、`results[].data[]` 等多种返回结构。

## License

示例工程，按个人学习/演示用途使用。PaddleOCR 本身遵循 Apache 2.0 许可，部署端请参照其官方文档。
