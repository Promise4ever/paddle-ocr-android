# 飞桨 OCR 识图（Android）

一个调用 PaddleOCR 服务进行文字识别的 Android 应用：支持拍照/相册选图、手动框选识别区域、批量识别、历史记录与云端配额统计；兼容本地 PaddleX / PaddleHub 服务与 AI Studio 云端 API，并内置 PP-OCRv4 端侧离线识别。

项目位于 [`PaddleOcrApp/`](PaddleOcrApp/)，详细文档见 [PaddleOcrApp/README.md](PaddleOcrApp/README.md)。

## 功能特性

- 📷 拍照 / 相册选图（Photo Picker，无需存储权限），手动框选识别区域（拖拽、八向手柄、放大镜、实时预览）
- 🗂 批量多图识别：自动保存历史、逐张进度、取消/单张重试、一键导出全部 TXT
- 🔌 三种服务端协议：
  - PaddleX 基础服务（PP-OCRv5/v6）：`POST /ocr/predict`
  - PaddleHub Serving（PP-OCRv3）：`POST /predict/ocr_system`
  - AI Studio 云端 API：异步任务，429 自动切换候选模型，首页按日估算剩余配额
- 📴 端侧离线识别：内置 PP-OCRv4 mobile ONNX 模型（det + rec + OpenCV 后处理），无网可用
- 🕘 历史记录：Room 持久化（含 v1→v2 迁移与索引）、全文搜索、收藏、分页加载
- 📄 结果展示：Markdown 渲染（VL 模型结构化结果）、识别框叠加原图、复制 / 分享 / 导出 TXT/MD
- 🔐 Access Token 使用 Android Keystore（AES-GCM）加密存储

## 构建

环境要求：JDK 17、Android SDK Platform 35。

```bash
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

产物位于 `PaddleOcrApp/app/build/outputs/apk/debug/`。仓库内还提供 `build_apk.bat`（一键构建）与 `setup_android_env.ps1`（环境准备）。

## 测试

```bash
./gradlew testDebugUnitTest                # JVM 单元测试：ONNX 输出解析、Markdown 渲染
./gradlew connectedDebugAndroidTest        # 设备测试：Room v1→v2 迁移验证（需连接手机）
```

Room schema 历史快照保存在 `PaddleOcrApp/app/schemas/`，随仓库提交供迁移测试使用。

## 部署 PaddleOCR 服务端

- PaddleX（推荐）：`pip install paddlex && paddlex --serve --pipeline OCR`，默认监听 `0.0.0.0:8080`
- PaddleHub：`pip install paddlehub==2.1.0 paddlepaddle && hub serving start -m ocr_system`，默认 `8866`
- AI Studio：在 [AI Studio PaddleOCR 服务页](https://aistudio.baidu.com/paddleocr/task) 创建任务，获取任务 URL / Token / 模型名填入 App 设置

手机与电脑连同一局域网即可直接使用 `http://` 地址。

## License

示例工程，按个人学习/演示用途使用。PaddleOCR 本身遵循 Apache 2.0，部署端请参考其官方文档。
