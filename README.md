# AI Studio Android

独立原生 Android AI 客户端。无需 Termux、Python、浏览器控制台或常驻本地服务器。

## v1.1.3 更新
- 智谱 API 鉴权：先按官方方式使用原始 API Key；若服务端返回 401，且密钥为 `id.secret` 格式，再自动尝试智谱文档提供的 HS256 JWT 方式
- API Key 输入会清理首尾空格、引号和可选的 Bearer 前缀
- 仍然收到 401 时，明确提示鉴权失败与模型 ID 无关，并保留服务端原始错误信息
- 服务商预设只填写 API 地址，不再自动替用户选择模型
- 清除旧版自动填入的示例模型 ID；模型 ID 由使用者自己输入
- 移除聊天首页预设问题卡片
- 聊天输入框适配 Android 输入法：键盘弹出时隐藏底部导航、输入区域随键盘上移、输入法发送键可以发送消息
- Activity 与 Manifest 均设置 adjustResize，避免输入框被输入法遮挡

## 功能
- OpenAI 兼容 API：OpenAI、DeepSeek、OpenRouter、智谱 AI、自定义端点
- SSE 流式聊天、本地历史对话、单次回复额度、会话额度与推理强度
- 三套主题：白蓝、米白、黑白
- 工作区：生成代码/文本并导出文件
- GGUF 模型导入与本机 llama.cpp CPU 推理（arm64-v8a，模型运行在应用进程内）
- API Key 使用 Android Keystore AES-GCM 加密存储

## 智谱 AI 配置
选择「智谱 AI」只会填入 API 基础地址：
`https://open.bigmodel.cn/api/paas/v4`

模型 ID 不会自动选择，请从智谱当前模型目录中自行选择并填写。请求路径为：
`https://open.bigmodel.cn/api/paas/v4/chat/completions`

智谱官方 HTTP 文档说明，通用 API 可直接使用 `Authorization: Bearer YOUR_API_KEY`；同时也提供由 `id.secret` API Key 生成的 HS256 JWT 鉴权方式。本应用先尝试直接 API Key，遇到 401 后再尝试 JWT。见 [智谱 HTTP API 调用文档](https://docs.bigmodel.cn/cn/guide/develop/http/introduction)。

特别注意：智谱标准 API Key 与 GLM Coding Plan Key 分开管理、互不通用。标准通用 API 使用 `/api/paas/v4`；Coding Plan 使用专用的 `/api/coding/paas/v4`，且官方说明仅限指定工具与产品环境。当前 AI Studio 的智谱预设针对标准 API，不会把 Coding Plan Key 当成标准 Key 使用。

若两种鉴权方式都返回 401，需检查你创建 Key 的具体页面是否为「标准 API Keys」，该 Key 是否被禁用，以及是否完整复制。不要将 API Key 发送给他人，也不要提交到 GitHub。

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
