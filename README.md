# AI Studio Android

独立原生 Android AI 客户端。无需 Termux、Python、浏览器控制台或常驻本地服务器。

## 功能
- OpenAI 兼容 API：OpenAI、DeepSeek、OpenRouter、自定义端点
- SSE 流式聊天、会话历史、单次回复额度、会话额度与推理强度
- 三套主题：白蓝、米白、黑白
- 工作区：生成代码/文本并导出文件
- GGUF 模型导入与本机 llama.cpp 推理（arm64-v8a，模型运行在应用进程内）
- API Key 使用 Android Keystore AES-GCM 加密存储

## 获取 APK
GitHub Actions 会在推送代码后自动构建 Debug APK。构建完成后，在仓库 **Actions → Android APK → Artifacts** 下载 `AIStudio-debug-apk`。

## 构建
需要 JDK 17/21、Android SDK、NDK 27.2.12479018、CMake 3.22.1、Gradle 8.13。
```bash
gradle :app:assembleDebug
```
产物：`app/build/outputs/apk/debug/app-debug.apk`

## 本地模型
从应用的「模型」页面导入兼容的 GGUF 文件，加载后即可在聊天中切换到本地推理。模型不会被打包进 APK。内存需求由模型大小、量化方式、上下文长度决定；建议先从小型 Q4 量化模型和 2048/4096 上下文开始。

## 安全说明
API Key 仅保存在本机 Keystore 加密后的应用私有偏好中。请求仅发送到你配置的 API 地址。请勿将密钥提交到 Git。应用尚未进行正式安全审计。
