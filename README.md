# AI Studio Android

独立原生 Android AI 客户端。无需 Termux、Python、浏览器控制台或常驻本地服务器。

## v1.1.4 更新
- 新增智谱「标准 API」和「GLM Coding Plan」两种独立连接预设，分别使用自己的 API 基础地址
- API 配置增加在线模型列表读取：输入 Key 后可先测试接口、获取模型 ID 列表，再由使用者自行选择；不会自动选模型
- 测试连接不再强制要求先填模型 ID；如果服务商不支持 `GET /models`，仍可手动填写模型 ID 后直接聊天
- 聊天输入框继续适配 Android IME：adjustResize、imePadding、隐藏导航栏与输入法 Send 动作
- CI 增加 APK 签名证书验证，并在 GitHub Actions 摘要中显示 SHA-256 指纹
- 支持通过 GitHub Actions Secrets 配置固定 Android 签名密钥，避免每次 CI 生成不同证书而导致覆盖安装失败
- 模型默认值清理改为一次性迁移，不会再把用户自己选定的模型 ID 清空
- 输入框增加系统导航区域 Insets 适配，避免底部手势区域遮挡发送栏

## 云端 API
- OpenAI 兼容 API：OpenAI、DeepSeek、OpenRouter、智谱标准 API、GLM Coding Plan、自定义端点
- SSE 流式聊天、多轮上下文、本地历史对话
- 单次回复额度、会话额度、推理强度
- API 配置支持测试连接、获取服务端模型列表、手动输入模型 ID
- API Key 使用 Android Keystore AES-GCM 加密存储

### 智谱 API 类型
标准 API 基础地址：
`https://open.bigmodel.cn/api/paas/v4`

GLM Coding Plan 基础地址：
`https://open.bigmodel.cn/api/coding/paas/v4`

两种端点对应不同套餐，必须使用匹配的凭据。标准 API Key 不要填进 Coding Plan 端点，反之亦然。具体以智谱当前控制台和官方文档为准。

智谱官方 HTTP 文档列出两种鉴权方式：直接使用完整 API Key，或用 `id.secret` 生成 HS256 JWT。应用优先直接发送 API Key；收到 401 后，对于符合格式的 Key 自动尝试 JWT。官方文档：[HTTP API 调用](https://docs.bigmodel.cn/cn/guide/develop/http/introduction)。

如果两种方式都返回 401，应用会显示服务端返回的错误信息。模型 ID 错误通常表现为模型或资源不存在，而不是身份验证失败；不要在未验证的情况下把 401 当成模型名称错误。

## 本地模型
从应用的「模型」页面导入兼容的 GGUF 文件，加载后即可在聊天中切换到本地推理。模型不会被打包进 APK。内存需求由模型大小、量化方式、上下文长度决定；建议先从小型 Q4 量化模型和 2048/4096 上下文开始。

## Android APK 签名与安装
Android APK 必须带有签名证书。Gradle 构建的 APK 会嵌入签名证书，Android 安装器会自动验证，不需要把 PEM 证书单独安装到手机系统。

GitHub Actions 在上传 Release 前会运行 `apksigner verify --print-certs`，验证 APK 签名并记录证书 SHA-256 指纹。

默认 CI 回退到 Gradle 自动生成的 Debug 签名密钥。这种密钥在不同 GitHub Runner 上可能不同：全新安装可用，但覆盖升级旧版本时可能出现签名不一致。当前 v1.1.3 及更早版本若使用了临时 Debug 证书，第一次换成下面的固定证书时需要卸载旧安装后再安装新版本；Android 不允许跨证书直接覆盖。卸载会删除本机应用数据，请先考虑记录聊天内容并准备重新输入 API Key。

要启用稳定签名：
1. 下载本次提供的私有签名密钥文件 `AIStudio-release-keystore.jks` 和配置文件 `AIStudio-GitHub-Signing-Secrets.txt`。
2. 打开 GitHub 仓库 Settings → Secrets and variables → Actions。
3. 新建以下 4 个 Repository secrets：`ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。
4. `ANDROID_KEYSTORE_BASE64` 填入配置文件中对应 Secret 的整行 base64 内容；其余 3 项按文件里的说明填写。
5. 重新运行 GitHub Actions。后续版本会使用同一签名身份。

不要将 `.jks`、密码或 base64 密钥内容提交到公开 GitHub 仓库。丢失私钥后，不能继续用相同身份签署升级包。

## 获取 APK
每次版本更新到 main 分支后，GitHub Actions 自动构建 APK、验证签名，并发布到 GitHub Releases：
[GitHub Releases 下载中心](https://github.com/fzdff114/AI-deployment-tool-apk/releases)

## 构建
需要 JDK 17、Android SDK、NDK 27.2.12479018、CMake 3.22.1、Gradle 8.13。
```bash
gradle :app:assembleDebug
```
产物：`app/build/outputs/apk/debug/app-debug.apk`

## 安全说明
API Key 仅保存在本机 Keystore 加密后的应用私有偏好中。请求仅发送到你配置的 API 地址。请勿将 API Key 提交到 Git。应用尚未进行正式安全审计。
