# AI Studio Android

独立原生 Android AI 客户端。无需 Termux、Python、浏览器控制台或常驻本地服务器。

## v1.1.2 更新
- 修复智谱 BigModel v4 的 API 路径：`/api/paas/v4/chat/completions`，不再错误追加 `/v1`
- 增加智谱 AI 服务商预设（默认地址与模型 ID 可自行修改）
- API Key 支持输入时自动去除可选的 `Bearer` 前缀、空格与引号
- 优化 400 / 401 / 403 / 404 / 429 错误说明，优先显示服务端 `message` 和 `code`
- 智谱推理开关使用 `thinking.type` 参数
- 设置页面服务商改为双列布局；模型文件名避免折行；聊天消息自动滚动到底部

## 功能
- OpenAI 兼容 API：OpenAI、DeepSeek、OpenRouter、智谱 AI、自定义端点
- SSE 流式聊天、本地历史对话、单次回复额度、会话额度与推理强度
- 三套主题：白蓝、米白、黑白
- 工作区：生成代码/文本并导出文件
- GGUF 模型导入与本机 llama.cpp CPU 推理（arm64-v8a，模型运行在应用进程内）
- API Key 使用 Android Keystore AES-GCM 加密存储

## 智谱 AI 配置
在设置中选择「智谱 AI」，或手动填写：
- API 基础地址：`https://open.bigmodel.cn/api/paas/v4`
- 模型 ID：请以智谱控制台实际可用的模型 ID 为准，例如界面中提供的模型名称
- API Key：从智谱开放平台的 API Keys 页面复制完整 API Key

本应用会自动请求 `/chat/completions`，完整请求地址为 `https://open.bigmodel.cn/api/paas/v4/chat/completions`。采用 OpenAI 兼容的 `Authorization: Bearer <API_KEY>` 认证格式。

**如果返回 HTTP 401**，这是服务端拒绝了密钥，不是模型名称错误。请确认复制的是智谱开放平台生成的完整、有效 API Key，而不是网页登录令牌、其他平台密钥、过期密钥，或仅复制了密钥的一部分。不要把 API Key 发到聊天、截图或 GitHub。

## 获取 APK
每次版本更新到 main 分支后，GitHub Actions 自动构建 APK 并发布到 GitHub Releases：
[GitHub Releases 下载中心](https://github.com/fzdff114/AI-deployment-tool-apk/releases)

## 构建
需要 JDK 17、Android SDK、NDK 27.2.12479018、CMake 3.22.1、Gradle 8.13。
```bash
gradle :app:assembleDebug
```
产物：`app/build/outputs/apk/debug/app-debug.apk`

## 本地模型
从应用的「模型」页面导入兼容的 GGUF 文件，加载后即可在聊天中切换到本地推理。模型不会被打包进 APK。内存需求由模型大小、量化方式、上下文长度决定；建议先从小型 Q4 量化模型和 2048/4096 上下文开始。

## 安全说明
API Key 仅保存在本机 Keystore 加密后的应用私有偏好中。请求仅发送到你配置的 API 地址。请勿将密钥提交到 Git。应用尚未进行正式安全审计。
