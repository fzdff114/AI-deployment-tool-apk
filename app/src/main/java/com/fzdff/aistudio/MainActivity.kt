package com.fzdff.aistudio

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContent { AIStudioApp() }
    }
}

private enum class AppTab(val label: String) {
    CHAT("聊天"), FILES("工作区"), MODELS("模型"), SETTINGS("设置")
}

private data class ApiProviderPreset(
    val name: String,
    val endpoint: String,
    val protocol: String = "openai_chat",
    val authHeaderName: String = "auto",
    val authHeaderPrefix: String = "Bearer"
)

private val API_PROVIDERS = listOf(
    ApiProviderPreset("OpenAI", "https://api.openai.com/v1", "openai_responses"),
    ApiProviderPreset("Anthropic / Claude", "https://api.anthropic.com", "anthropic_messages"),
    ApiProviderPreset("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini_generate"),
    ApiProviderPreset("DeepSeek", "https://api.deepseek.com"),
    ApiProviderPreset("OpenRouter", "https://openrouter.ai/api/v1"),
    ApiProviderPreset("智谱标准 API", "https://open.bigmodel.cn/api/paas/v4"),
    ApiProviderPreset("智谱 Coding Plan", "https://open.bigmodel.cn/api/coding/paas/v4"),
    ApiProviderPreset("Groq", "https://api.groq.com/openai/v1"),
    ApiProviderPreset("Mistral", "https://api.mistral.ai/v1"),
    ApiProviderPreset("xAI", "https://api.x.ai/v1"),
    ApiProviderPreset("Together AI", "https://api.together.xyz/v1"),
    ApiProviderPreset("Fireworks AI", "https://api.fireworks.ai/inference/v1"),
    ApiProviderPreset("Cerebras", "https://api.cerebras.ai/v1"),
    ApiProviderPreset("NVIDIA NIM", "https://integrate.api.nvidia.com/v1"),
    ApiProviderPreset("Cohere 兼容 API", "https://api.cohere.ai/compatibility/v1"),
    ApiProviderPreset("Hugging Face Inference", "https://router.huggingface.co/v1"),
    ApiProviderPreset("Kimi / 月之暗面（中国）", "https://api.moonshot.cn/v1"),
    ApiProviderPreset("Kimi / Moonshot（国际）", "https://api.moonshot.ai/v1"),
    ApiProviderPreset("豆包 / 火山方舟", "https://ark.cn-beijing.volces.com/api/v3"),
    ApiProviderPreset("MiniMax（中国）", "https://api.minimaxi.com/v1"),
    ApiProviderPreset("MiniMax（国际）", "https://api.minimax.io/v1"),
    ApiProviderPreset("百度千帆", "https://qianfan.baidubce.com/v2"),
    ApiProviderPreset("腾讯混元兼容 API", "https://api.hunyuan.cloud.tencent.com/v1"),
    ApiProviderPreset("阶跃星辰 StepFun", "https://api.stepfun.com/v1"),
    ApiProviderPreset("小米 MiMo", "https://api.xiaomimimo.com/v1"),
    ApiProviderPreset("百川智能", "https://api.baichuan-ai.com/v1"),
    ApiProviderPreset("零一万物 Yi", "https://api.lingyiwanwu.com/v1"),
    ApiProviderPreset("魔搭 ModelScope", "https://api-inference.modelscope.cn/v1"),
    ApiProviderPreset("DeepInfra", "https://api.deepinfra.com/v1/openai"),
    ApiProviderPreset("Novita AI", "https://api.novita.ai/openai/v1"),
    ApiProviderPreset("SambaNova", "https://api.sambanova.ai/v1"),
    ApiProviderPreset("Hyperbolic", "https://api.hyperbolic.xyz/v1"),
    ApiProviderPreset("Z.ai（GLM 国际）", "https://api.z.ai/api/paas/v4"),
    ApiProviderPreset("Google Gemini（OpenAI 兼容）", "https://generativelanguage.googleapis.com/v1beta/openai"),
    ApiProviderPreset("Azure OpenAI（自定义部署）", "", "openai_chat", "api-key", ""),
    ApiProviderPreset("SiliconFlow", "https://api.siliconflow.cn/v1"),
    ApiProviderPreset("阿里云百炼兼容模式", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    ApiProviderPreset("Ollama（本机 / 局域网）", "http://127.0.0.1:11434/v1", "openai_chat", "none", ""),
    ApiProviderPreset("自定义", "", "openai_chat")
)

private val API_PROTOCOLS = listOf(
    "openai_chat" to "OpenAI 兼容 Chat Completions",
    "openai_responses" to "OpenAI Responses API",
    "anthropic_messages" to "Anthropic Messages API",
    "gemini_generate" to "Google Gemini GenerateContent"
)

private fun apiProtocolLabel(value: String): String =
    API_PROTOCOLS.firstOrNull { it.first == value }?.second ?: "OpenAI 兼容 Chat Completions"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AIStudioApp() {
    val context = LocalContext.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AppStore.loadSettings(context)) }
    val sessions = remember { mutableStateListOf<ChatSession>().apply { addAll(ChatHistoryStore.load(context)) } }
    var currentSessionId by remember { mutableStateOf(sessions.firstOrNull()?.id ?: System.currentTimeMillis()) }
    val messages = remember {
        mutableStateListOf<ChatMessage>().apply {
            addAll(sessions.firstOrNull()?.messages ?: AppStore.loadMessages(context))
        }
    }
    var showHistory by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(if (AppStore.isConfigured(context)) AppTab.CHAT else null) }
    var apiKey by remember { mutableStateOf(SecureStore.read(context)) }
    var isBusy by remember { mutableStateOf(false) }
    var localLoaded by remember { mutableStateOf(false) }
    var useLocal by remember { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var workspacePrompt by rememberSaveable { mutableStateOf("") }
    var workspaceName by rememberSaveable { mutableStateOf("generated-file.md") }
    var workspaceOutput by remember { mutableStateOf("") }
    var workspaceStatus by remember { mutableStateOf("") }
    var apiModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var engine by remember { mutableStateOf<LocalModelEngine?>(null) }
    var localModels by remember { mutableStateOf(scanLocalModels(context)) }

    fun updateSettings(next: AppSettings) {
        settings = next
        AppStore.saveSettings(context, next)
    }

    fun persistCurrentSession() {
        val title = messages.firstOrNull { it.role == "user" }?.content?.replace("\n", " ")?.take(32) ?: "新对话"
        val saved = ChatSession(currentSessionId, title, System.currentTimeMillis(), settings.sessionUsed, messages.toList())
        val oldIndex = sessions.indexOfFirst { it.id == currentSessionId }
        if (oldIndex >= 0) sessions[oldIndex] = saved else sessions.add(saved)
        val sorted = sessions.sortedByDescending { it.updatedAt }.take(50)
        sessions.clear()
        sessions.addAll(sorted)
        ChatHistoryStore.save(context, sessions)
        AppStore.saveMessages(context, messages)
    }

    fun startNewChat() {
        persistCurrentSession()
        messages.clear()
        currentSessionId = System.currentTimeMillis()
        updateSettings(settings.copy(sessionUsed = 0))
        persistCurrentSession()
        errorText = ""
    }

    fun openSession(session: ChatSession) {
        persistCurrentSession()
        messages.clear()
        messages.addAll(session.messages)
        currentSessionId = session.id
        updateSettings(settings.copy(sessionUsed = session.usedTokens))
        persistCurrentSession()
        showHistory = false
        tab = AppTab.CHAT
        errorText = ""
    }

    val theme = when (settings.theme) {
        "cream" -> lightColorScheme(
            primary = Color(0xFF735C43), onPrimary = Color.White,
            secondary = Color(0xFF8C765C), background = Color(0xFFF5F0E6),
            surface = Color(0xFFFFFCF6), surfaceVariant = Color(0xFFECE4D7),
            onBackground = Color(0xFF28231D), onSurface = Color(0xFF28231D),
            outline = Color(0xFFB8AA97)
        )
        "mono" -> darkColorScheme(
            primary = Color(0xFFF2F2F2), onPrimary = Color(0xFF111111),
            secondary = Color(0xFFBFC3C8), background = Color(0xFF090A0C),
            surface = Color(0xFF15171A), surfaceVariant = Color(0xFF25282D),
            onBackground = Color(0xFFF4F4F4), onSurface = Color(0xFFF4F4F4),
            outline = Color(0xFF555B63)
        )
        else -> lightColorScheme(
            primary = Color(0xFF1769D2), onPrimary = Color.White,
            secondary = Color(0xFF477DBD), background = Color(0xFFF5F8FD),
            surface = Color.White, surfaceVariant = Color(0xFFEAF1FA),
            onBackground = Color(0xFF121923), onSurface = Color(0xFF121923),
            outline = Color(0xFFD3DCE8)
        )
    }

    MaterialTheme(colorScheme = theme) {
        Surface(Modifier.fillMaxSize(), color = theme.background) {
            if (tab == null) {
                SetupScreen(
                    initial = settings,
                    initialKey = apiKey,
                    onStart = { next, key ->
                        updateSettings(next)
                        apiKey = key
                        SecureStore.save(context, key)
                        AppStore.markSetupDone(context)
                        tab = AppTab.CHAT
                    },
                    onLocalOnly = { AppStore.markSetupDone(context); tab = AppTab.CHAT }
                )
            } else {
                Scaffold(
                    containerColor = theme.background,
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text("AI Studio", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                    Text(
                                        when (tab) {
                                            AppTab.CHAT -> if (useLocal) "本机模型 · 离线推理" else "${settings.provider} · ${settings.model.ifBlank { "请选择模型" }}"
                                            AppTab.FILES -> "代码与文件开发"
                                            AppTab.MODELS -> "本地模型管理"
                                            AppTab.SETTINGS -> "偏好与 API 配置"
                                            null -> ""
                                        },
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            actions = {
                                if (tab == AppTab.CHAT) {
                                    IconButton(onClick = { showHistory = true }) {
                                        Icon(Icons.Default.History, contentDescription = "历史对话")
                                    }
                                    IconButton(onClick = { startNewChat() }) {
                                        Icon(Icons.Default.Add, contentDescription = "新对话")
                                    }
                                }
                                if (tab != AppTab.SETTINGS) {
                                    IconButton(onClick = { tab = AppTab.SETTINGS }) {
                                        Icon(Icons.Default.Settings, contentDescription = "设置")
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = theme.surface,
                                titleContentColor = theme.onSurface
                            )
                        )
                    },
                    bottomBar = {
                        if (!imeVisible) {
                        NavigationBar(containerColor = theme.surface) {
                            NavigationBarItem(
                                selected = tab == AppTab.CHAT, onClick = { tab = AppTab.CHAT },
                                icon = { Icon(Icons.Default.ChatBubbleOutline, null) }, label = { Text("聊天") }
                            )
                            NavigationBarItem(
                                selected = tab == AppTab.FILES, onClick = { tab = AppTab.FILES },
                                icon = { Icon(Icons.Default.Code, null) }, label = { Text("工作区") }
                            )
                            NavigationBarItem(
                                selected = tab == AppTab.MODELS, onClick = { tab = AppTab.MODELS },
                                icon = { Icon(Icons.Default.Memory, null) }, label = { Text("模型") }
                            )
                            NavigationBarItem(
                                selected = tab == AppTab.SETTINGS, onClick = { tab = AppTab.SETTINGS },
                                icon = { Icon(Icons.Default.Palette, null) }, label = { Text("设置") }
                            )
                        }
                        }
                    }
                ) { padding ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.fillMaxSize().widthIn(max = 980.dp)) {
                    when (tab) {
                        AppTab.CHAT -> ChatScreen(
                            modifier = Modifier.padding(padding),
                            messages = messages,
                            input = input,
                            onInput = { input = it },
                            busy = isBusy,
                            error = errorText,
                            settings = settings,
                            useLocal = useLocal,
                            localReady = localLoaded,
                            onToggleLocal = { enabled ->
                                if (enabled && !localLoaded) errorText = "请先到「模型」页面加载 GGUF 模型"
                                else { useLocal = enabled; errorText = "" }
                            },
                            onSend = {
                                val text = input.trim()
                                if (text.isNotEmpty() && !isBusy) {
                                    if (settings.sessionLimit > 0 && settings.sessionUsed >= settings.sessionLimit) {
                                        errorText = "本会话额度已达到上限。请新建对话或在设置中调整额度。"
                                    } else {
                                        input = ""
                                        errorText = ""
                                        messages.add(ChatMessage("user", text))
                                        messages.add(ChatMessage("assistant", ""))
                                        persistCurrentSession()
                                        scope.launch {
                                            isBusy = true
                                            try {
                                                val assistantIndex = messages.lastIndex
                                                if (useLocal) {
                                                    val prompt = buildLocalPrompt(messages.dropLast(1), settings.localModelPath, settings.localSystemPrompt, settings.localPromptTemplate)
                                                    val result = withContext(Dispatchers.IO) {
                                                        engine?.nativeGenerate(prompt, settings.maxTokens, settings.localTemperature, settings.localTopP, settings.localTopK, settings.localRepeatPenalty, -1)
                                                            ?: "ERROR: 本地模型尚未加载"
                                                    }
                                                    if (result.startsWith("ERROR:")) throw IllegalStateException(result.removePrefix("ERROR:").trim())
                                                    messages[assistantIndex] = messages[assistantIndex].copy(content = result)
                                                } else {
                                                    ApiClient.streamChat(settings, apiKey, messages.filter { it.content.isNotBlank() }) { chunk ->
                                                        withContext(Dispatchers.Main) {
                                                            if (assistantIndex in messages.indices) {
                                                                messages[assistantIndex] = messages[assistantIndex].copy(
                                                                    content = messages[assistantIndex].content + chunk
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                                val added = (messages[assistantIndex].content.length / 3).coerceAtLeast(1)
                                                updateSettings(settings.copy(sessionUsed = settings.sessionUsed + added))
                                                persistCurrentSession()
                                            } catch (e: Exception) {
                                                errorText = e.message ?: "请求失败"
                                                if (messages.isNotEmpty() && messages.last().role == "assistant" && messages.last().content.isBlank()) {
                                                    messages.removeAt(messages.lastIndex)
                                                }
                                            } finally {
                                                isBusy = false
                                                persistCurrentSession()
                                            }
                                        }
                                    }
                                }
                            }
                        )
                        AppTab.FILES -> WorkspaceScreen(
                            modifier = Modifier.padding(padding),
                            fileName = workspaceName,
                            onFileName = { workspaceName = it },
                            prompt = workspacePrompt,
                            onPrompt = { workspacePrompt = it },
                            output = workspaceOutput,
                            status = workspaceStatus,
                            busy = isBusy,
                            onGenerate = {
                                if (workspacePrompt.isNotBlank() && !isBusy) scope.launch {
                                    isBusy = true
                                    workspaceStatus = "正在生成…"
                                    try {
                                        val fullPrompt = "你是资深软件工程师。根据需求生成一个完整、可直接保存使用的文件内容。只输出文件正文，不要解释。文件名：${workspaceName}\n需求：${workspacePrompt}"
                                        workspaceOutput = if (useLocal && localLoaded) {
                                            withContext(Dispatchers.IO) {
                                                engine?.nativeGenerate("### Instruction:\n$fullPrompt\n\n### Response:\n", settings.maxTokens, settings.localTemperature, settings.localTopP, settings.localTopK, settings.localRepeatPenalty, -1).orEmpty()
                                            }
                                        } else {
                                            ApiClient.complete(settings, apiKey, fullPrompt)
                                        }
                                        workspaceStatus = "生成完成 · 可复制或导出"
                                    } catch (e: Exception) {
                                        workspaceStatus = e.message ?: "生成失败"
                                    } finally { isBusy = false }
                                }
                            },
                            onClear = { workspaceOutput = ""; workspaceStatus = "" }
                        )
                        AppTab.MODELS -> ModelScreen(
                            modifier = Modifier.padding(padding), settings = settings, models = localModels,
                            loaded = localLoaded, busy = isBusy, status = workspaceStatus, error = errorText,
                            onSettingsChange = { updateSettings(it) },
                            onSelect = { path ->
                                if (!localLoaded || path == settings.localModelPath) {
                                    updateSettings(settings.copy(localModelPath = path))
                                    workspaceStatus = "已选择模型：${File(path).name}"
                                } else errorText = "请先卸载当前模型，再切换模型。"
                            },
                            onRefresh = { localModels = scanLocalModels(context) },
                            onDelete = { path ->
                                if (localLoaded && path == settings.localModelPath) {
                                    errorText = "请先卸载正在运行的模型，再删除文件。"
                                } else {
                                    val deleted = withContext(Dispatchers.IO) { File(path).delete() }
                                    localModels = scanLocalModels(context)
                                    if (deleted && settings.localModelPath == path) {
                                        updateSettings(settings.copy(localModelPath = localModels.firstOrNull()?.file?.absolutePath ?: ""))
                                    }
                                    workspaceStatus = if (deleted) "模型文件已删除" else "删除失败，请检查文件状态。"
                                }
                            },
                            onImport = { uris ->
                                scope.launch {
                                    isBusy = true; errorText = ""; workspaceStatus = "正在导入模型文件…"
                                    try {
                                        val imported = withContext(Dispatchers.IO) { importModels(context, uris) }
                                        localModels = scanLocalModels(context)
                                        imported.firstOrNull { !it.name.contains("mmproj", true) }?.let {
                                            updateSettings(settings.copy(localModelPath = it.absolutePath))
                                        }
                                        val projectorCount = imported.count { it.name.contains("mmproj", true) }
                                        workspaceStatus = "导入完成：${imported.size} 个 GGUF 文件" +
                                            if (projectorCount > 0) "（含 ${projectorCount} 个 mmproj 配套文件）" else ""
                                    } catch (e: Exception) {
                                        errorText = e.message ?: "导入失败"; workspaceStatus = ""
                                    } finally { isBusy = false }
                                }
                            },
                            onLoad = {
                                val modelPath = settings.localModelPath
                                if (modelPath.isNotBlank()) scope.launch {
                                    isBusy = true; errorText = ""; workspaceStatus = "正在加载 GGUF · 准备模型与上下文…"
                                    try {
                                        if (!LocalModelEngine.available) throw IllegalStateException("本地推理引擎未编译进此设备版本")
                                        val e = engine ?: LocalModelEngine().also { engine = it }
                                        val result = withContext(Dispatchers.IO) {
                                            e.nativeLoad(modelPath, settings.contextSize, settings.localThreads)
                                        }
                                        if (result.startsWith("ERROR:")) throw IllegalStateException(result.removePrefix("ERROR:").trim())
                                        localLoaded = true; workspaceStatus = "模型加载成功 · ${File(modelPath).name}"
                                    } catch (e: Exception) {
                                        errorText = e.message ?: "加载模型失败"; workspaceStatus = ""
                                    } finally { isBusy = false }
                                }
                            },
                            onUnload = {
                                scope.launch {
                                    isBusy = true
                                    try {
                                        withContext(Dispatchers.IO) { engine?.nativeUnload() }
                                        localLoaded = false; useLocal = false; workspaceStatus = "模型已卸载，内存已释放"
                                    } finally { isBusy = false }
                                }
                            }
                        )
                        AppTab.SETTINGS -> SettingsScreen(
                            modifier = Modifier.padding(padding),
                            settings = settings,
                            apiKey = apiKey,
                            onApiKey = { apiKey = it },
                            onSave = {
                                updateSettings(settings)
                                SecureStore.save(context, apiKey)
                                workspaceStatus = "设置已保存"
                            },
                            onChange = { updateSettings(it) },
                            onTest = {
                                scope.launch {
                                    isBusy = true
                                    errorText = ""
                                    workspaceStatus = ""
                                    try {
                                        if (settings.model.isBlank()) {
                                            throw IllegalArgumentException("先填写模型 ID，再测试聊天接口。")
                                        }
                                        val reply = ApiClient.complete(
                                            settings.copy(maxTokens = 16, reasoning = "auto", sendReasoning = false),
                                            apiKey,
                                            "请只回复 OK"
                                        )
                                        workspaceStatus = "聊天接口连接成功 · 模型已返回：${reply.take(40)}"
                                    } catch (e: Exception) {
                                        errorText = e.message ?: "聊天接口测试失败"
                                    } finally {
                                        isBusy = false
                                    }
                                }
                            },
                            onFetchModels = {
                                scope.launch {
                                    isBusy = true
                                    errorText = ""
                                    workspaceStatus = ""
                                    try {
                                        val found = ApiClient.fetchModels(settings, apiKey)
                                        apiModels = found
                                        workspaceStatus = "已获取 ${found.size} 个模型。请自行选择或继续手动填写模型 ID。"
                                    } catch (e: Exception) {
                                        errorText = (e.message ?: "模型列表获取失败") +
                                            "\n部分兼容接口不开放 GET /models；你仍可以手动填写模型 ID。"
                                    } finally {
                                        isBusy = false
                                    }
                                }
                            },
                            availableModels = apiModels,
                            onSelectModel = { selected -> updateSettings(settings.copy(model = selected)); workspaceStatus = "已选择模型：$selected" },
                            busy = isBusy,
                            status = workspaceStatus,
                            error = errorText
                        )
                        null -> Unit
                    }
                    }
                    }
                }
                if (showHistory) {
                    AlertDialog(
                        onDismissRequest = { showHistory = false },
                        title = { Text("历史对话") },
                        text = {
                            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                                val savedSessions = sessions.filter { it.messages.isNotEmpty() }
                                if (savedSessions.isEmpty()) {
                                    Text("暂时没有历史对话。聊天记录会保存在本机。")
                                } else {
                                    savedSessions.forEach { session ->
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                            TextButton(onClick = { openSession(session) }, modifier = Modifier.weight(1f)) {
                                                Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            IconButton(onClick = {
                                                sessions.removeAll { it.id == session.id }
                                                ChatHistoryStore.save(context, sessions)
                                                if (currentSessionId == session.id) {
                                                    messages.clear()
                                                    currentSessionId = System.currentTimeMillis()
                                                    updateSettings(settings.copy(sessionUsed = 0))
                                                    persistCurrentSession()
                                                }
                                            }) { Icon(Icons.Default.DeleteOutline, contentDescription = "删除对话") }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { showHistory = false }) { Text("关闭") } },
                        dismissButton = { TextButton(onClick = { startNewChat(); showHistory = false }) { Text("新对话") } }
                    )
                }
            }
        }
    }
}

private fun buildLocalPrompt(messages: List<ChatMessage>, modelPath: String, systemPrompt: String, selectedTemplate: String): String {
    val recent = messages.filter { it.content.isNotBlank() }.takeLast(24)
    val system = systemPrompt.ifBlank { "你是 AI Studio 的本地智能助手。请用用户使用的语言回答，准确、清晰，并直接完成用户要求。" }
    val template = when (selectedTemplate) {
        "auto" -> if (modelPath.contains("phi", ignoreCase = true)) "phi" else "plain"
        else -> selectedTemplate
    }
    return when (template) {
        "chatml" -> buildString {
            append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
            recent.forEach { message ->
                append("<|im_start|>").append(if (message.role == "user") "user" else "assistant").append("\n")
                append(message.content.take(12000)).append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
        "llama3" -> buildString {
            append("<|start_header_id|>system<|end_header_id|>\n").append(system).append("<|eot_id|>")
            recent.forEach { message ->
                append("<|start_header_id|>").append(if (message.role == "user") "user" else "assistant")
                    .append("<|end_header_id|>\n").append(message.content.take(12000)).append("<|eot_id|>")
            }
            append("<|start_header_id|>assistant<|end_header_id|>\n")
        }
        "phi" -> buildString {
            append("<|system|>\n").append(system).append("<|end|>\n")
            recent.forEach { message ->
                append("<|").append(if (message.role == "user") "user" else "assistant").append("|>\n")
                append(message.content.take(12000)).append("<|end|>\n")
            }
            append("<|assistant|>\n")
        }
        "alpaca" -> buildString {
            append("### System:\n").append(system).append("\n\n")
            recent.forEach { message ->
                append(if (message.role == "user") "### Instruction:\n" else "### Response:\n")
                append(message.content.take(12000)).append("\n\n")
            }
            append("### Response:\n")
        }
        else -> buildString {
            append("系统：").append(system).append("\n")
            recent.forEach { message ->
                append(if (message.role == "user") "用户：" else "助手：")
                append(message.content.take(12000)).append("\n")
            }
            append("助手：")
        }
    }
}

private data class LocalGgufModel(val file: File) { val sizeBytes: Long get() = file.length() }

private fun scanLocalModels(context: Context): List<LocalGgufModel> {
    val folder = File(context.filesDir, "models")
    if (!folder.exists() && !folder.mkdirs()) return emptyList()
    return folder.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".gguf", true) && !it.name.contains("mmproj", true) }
        ?.sortedBy { it.name.lowercase() }
        ?.map { LocalGgufModel(it) }
        ?: emptyList()
}

private fun importModels(context: Context, uris: List<Uri>): List<File> {
    val folder = File(context.filesDir, "models").apply { mkdirs() }
    val imported = mutableListOf<File>()
    uris.forEach { uri ->
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "imported-model.gguf"
        if (!name.endsWith(".gguf", true)) return@forEach
        val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "imported-model.gguf" }
        val extension = safeName.substringAfterLast('.', "gguf")
        val base = safeName.removeSuffix(".$extension")
        var target = File(folder, safeName)
        var duplicate = 2
        while (target.exists()) {
            target = File(folder, base + "_" + duplicate + "." + extension)
            duplicate++
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, 1024 * 1024) }
        } ?: throw IllegalStateException("无法读取文件：$name")
        if (target.length() <= 4L || !hasGgufMagic(target)) {
            target.delete()
            throw IllegalArgumentException("文件不是有效的 GGUF：$name")
        }
        imported.add(target)
    }
    if (imported.isEmpty()) throw IllegalArgumentException("没有找到 .gguf 文件。请选择 GGUF 主模型或 mmproj 配套文件。")
    return imported
}

private fun hasGgufMagic(file: File): Boolean = try {
    file.inputStream().use { input ->
        val header = ByteArray(4)
        input.read(header) == 4 && header.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))
    }
} catch (_: Exception) { false }

private fun formatModelSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) { value /= 1024.0; index++ }
    return String.format(java.util.Locale.US, "%.2f %s", value, units[index])
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupScreen(
    initial: AppSettings,
    initialKey: String,
    onStart: (AppSettings, String) -> Unit,
    onLocalOnly: () -> Unit
) {
    var provider by remember { mutableStateOf(initial.provider) }
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var model by remember { mutableStateOf(initial.model) }
    var protocol by remember { mutableStateOf(initial.apiProtocol) }
    var authHeader by remember { mutableStateOf(initial.authHeaderName) }
    var authPrefix by remember { mutableStateOf(initial.authHeaderPrefix) }
    var key by remember { mutableStateOf(initialKey) }
    var showKey by remember { mutableStateOf(false) }
    var showProviders by remember { mutableStateOf(false) }
    var showProtocols by remember { mutableStateOf(false) }
    var showAuthDetails by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(42.dp))
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("开始使用 AI Studio", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "接入云端 AI API，或稍后导入本地 GGUF 模型。支持多种 API 协议和自定义服务地址。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("选择服务商", fontWeight = FontWeight.SemiBold)
                Box {
                    OutlinedButton(onClick = { showProviders = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(provider.ifBlank { "选择服务商" }, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                        Icon(Icons.Default.ExpandMore, contentDescription = null)
                    }
                    DropdownMenu(expanded = showProviders, onDismissRequest = { showProviders = false }) {
                        API_PROVIDERS.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name) },
                                onClick = {
                                    provider = item.name
                                    if (item.name != "自定义") {
                                        endpoint = item.endpoint
                                        protocol = item.protocol
                                        authHeader = item.authHeaderName
                                        authPrefix = item.authHeaderPrefix
                                        model = ""
                                    }
                                    showProviders = false
                                }
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { showProtocols = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(apiProtocolLabel(protocol), modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                        Icon(Icons.Default.ExpandMore, contentDescription = null)
                    }
                    DropdownMenu(expanded = showProtocols, onDismissRequest = { showProtocols = false }) {
                        API_PROTOCOLS.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.second) },
                                onClick = {
                                    protocol = item.first
                                    authHeader = "auto"
                                    authPrefix = "Bearer"
                                    showProtocols = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    endpoint, { endpoint = it },
                    label = { Text("API 基础地址") },
                    placeholder = { Text("https://api.example.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    model, { model = it },
                    label = { Text("模型 ID（自行选择）") },
                    placeholder = { Text("从服务商控制台复制模型 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    key, { key = it },
                    label = { Text(if (authHeader == "none") "API Key（可留空）" else "API Key") },
                    singleLine = true,
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") } }
                )
                TextButton(onClick = { showAuthDetails = !showAuthDetails }, modifier = Modifier.align(Alignment.Start)) {
                    Text(if (showAuthDetails) "收起认证选项" else "高级认证选项")
                }
                if (showAuthDetails) {
                    OutlinedTextField(
                        authHeader, { authHeader = it },
                        label = { Text("认证请求头名称") },
                        supportingText = { Text("auto、Authorization、x-api-key、x-goog-api-key 或 none") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        authPrefix, { authPrefix = it },
                        label = { Text("认证前缀") },
                        supportingText = { Text("常见 Bearer；x-api-key 和 x-goog-api-key 通常留空") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text("密钥使用 Android Keystore 加密保存在本机。协议选择错误时，请按服务商文档切换接口协议。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        onStart(
                            initial.copy(
                                provider = provider,
                                endpoint = endpoint.trim(),
                                model = model.trim(),
                                apiProtocol = protocol,
                                authHeaderName = authHeader.trim().ifBlank { "auto" },
                                authHeaderPrefix = authPrefix.trim()
                            ),
                            key.trim()
                        )
                    },
                    enabled = endpoint.isNotBlank() && model.isNotBlank() &&
                        (authHeader.equals("none", true) || key.isNotBlank()),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("保存并开始聊天") }
                TextButton(onClick = onLocalOnly, modifier = Modifier.fillMaxWidth()) { Text("暂时跳过，稍后导入本地模型") }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("AI Studio · 原生 Android", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChatScreen(
    modifier: Modifier,
    messages: SnapshotStateList<ChatMessage>,
    input: String,
    onInput: (String) -> Unit,
    busy: Boolean,
    error: String,
    settings: AppSettings,
    useLocal: Boolean,
    localReady: Boolean,
    onToggleLocal: (Boolean) -> Unit,
    onSend: () -> Unit
) {
    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Column(modifier.fillMaxSize().widthIn(max = 900.dp).imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !useLocal, onClick = { onToggleLocal(false) }, label = { Text("云端 API") }, leadingIcon = { Icon(Icons.Default.Cloud, null, Modifier.size(16.dp)) })
                FilterChip(selected = useLocal, onClick = { onToggleLocal(true) }, enabled = localReady, label = { Text("本地模型") }, leadingIcon = { Icon(Icons.Default.Memory, null, Modifier.size(16.dp)) })
            }
            Text("约 ${settings.sessionUsed} / ${if (settings.sessionLimit == 0) "∞" else settings.sessionLimit} tokens", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        AnimatedVisibility(modifier = Modifier.weight(1f).fillMaxWidth(), visible = messages.isEmpty(), enter = fadeIn() + slideInVertically { it / 8 }, exit = fadeOut() + slideOutVertically { it / 8 }) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(16.dp))
                Text("有什么可以帮你？", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("输入消息开始对话。模型会根据你填写的模型 ID 工作。", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        AnimatedVisibility(modifier = Modifier.weight(1f).fillMaxWidth(), visible = messages.isNotEmpty(), enter = fadeIn() + slideInVertically { it / 12 }, exit = fadeOut()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                itemsIndexed(messages, key = { index, item -> "${item.id}-$index" }) { _, message ->
                    MessageBubble(message)
                }
                if (busy && messages.lastOrNull()?.content.isNullOrEmpty()) {
                    item { Text("AI 正在思考…", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp)) }
                }
            }
        }

        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp, modifier = Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input, onValueChange = onInput,
                    placeholder = { Text("发送消息…") },
                    modifier = Modifier.weight(1f).animateContentSize(),
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (!busy && input.isNotBlank()) {
                                onSend()
                                keyboardController?.hide()
                            }
                        }
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { onSend(); keyboardController?.hide() },
                    enabled = !busy && input.isNotBlank(),
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = "发送")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) {
            Icon(Icons.Default.SmartToy, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, end = 8.dp).size(22.dp))
        }
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(if (isUser) 22.dp else 16.dp),
            border = if (isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            modifier = Modifier.widthIn(max = maxWidth * 0.84f).animateContentSize()
        ) {
            SelectionContainer {
                Text(
                    message.content.ifBlank { "…" },
                    Modifier.padding(horizontal = 15.dp, vertical = 12.dp),
                    fontSize = 15.sp,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

    }
}

@Composable
private fun WorkspaceScreen(
    modifier: Modifier,
    fileName: String,
    onFileName: (String) -> Unit,
    prompt: String,
    onPrompt: (String) -> Unit,
    output: String,
    status: String,
    busy: Boolean,
    onGenerate: () -> Unit,
    onClear: () -> Unit
) {
    val context = LocalContext.current
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(output.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(context, "文件已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { Toast.makeText(context, e.message ?: "保存失败", Toast.LENGTH_LONG).show() }
    }
    Column(modifier.fillMaxSize().widthIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("开发工作区", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("描述你要创建的文件，AI 会生成正文，你可以检查后导出到手机。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(fileName, onFileName, label = { Text("文件名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(prompt, onPrompt, label = { Text("开发需求") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        Button(onClick = onGenerate, enabled = !busy && prompt.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "正在生成…" else "生成文件")
        }
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
        if (output.isNotBlank()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("生成结果", fontWeight = FontWeight.SemiBold)
                Row {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AI Studio output", output))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }) { Text("复制") }
                    TextButton(onClick = { saveLauncher.launch(fileName.ifBlank { "generated.txt" }) }) { Text("导出") }
                }
            }
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))) {
                SelectionContainer {
                    Text(output, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 520.dp).verticalScroll(rememberScrollState()).padding(14.dp), fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
            TextButton(onClick = onClear) { Icon(Icons.Default.DeleteOutline, null); Text("清空结果") }
        }
    }
}

@Composable
private fun ModelScreen(
    modifier: Modifier, settings: AppSettings, models: List<LocalGgufModel>,
    loaded: Boolean, busy: Boolean, status: String, error: String,
    onSettingsChange: (AppSettings) -> Unit, onSelect: (String) -> Unit,
    onRefresh: () -> Unit, onDelete: (String) -> Unit, onImport: (List<Uri>) -> Unit,
    onLoad: () -> Unit, onUnload: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) onImport(uris) }
    var templateMenu by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<LocalGgufModel?>(null) }
    val folder = File(LocalContext.current.filesDir, "models")
    val projectorCount = folder.listFiles()?.count { it.isFile && it.name.endsWith(".gguf", true) && it.name.contains("mmproj", true) } ?: 0
    val maxThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
    val selectedModel = models.firstOrNull { it.file.absolutePath == settings.localModelPath }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("GGUF 模型库", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("${models.size} 个本地模型 · llama.cpp CPU 推理", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Default.FolderOpen, contentDescription = "刷新模型列表") }
        }
        Text("管理本机 GGUF 文件、切换模型、调整上下文与采样参数。模型文件保存在应用私有目录，不会上传。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status.isNotBlank()) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Text(status, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        }
        if (error.isNotBlank()) Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(error, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (loaded) "模型运行中" else "当前选择", fontWeight = FontWeight.SemiBold)
                        Text(selectedModel?.file?.name ?: "尚未选择模型", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (loaded) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { launcher.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(6.dp)); Text("批量导入 GGUF")
                    }
                    OutlinedButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.height(48.dp)) { Text("刷新") }
                }
                if (selectedModel != null) {
                    Text("文件大小：${formatModelSize(selectedModel.sizeBytes)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("模型路径：${selectedModel.file.parent}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (loaded) OutlinedButton(onClick = onUnload, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("卸载模型并释放内存") }
                else Button(onClick = onLoad, enabled = !busy && selectedModel != null, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "正在加载…" else "加载当前模型") }
            }
        }
        Text("已导入模型", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        if (models.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(8.dp)); Text("模型库为空")
                    Text("导入一个或多个 .gguf 文件即可开始。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        } else models.forEach { model ->
            val selected = settings.localModelPath == model.file.absolutePath
            val quant = Regex("(?i)Q[0-9]+[_A-Z0-9]*").find(model.file.name)?.value ?: "量化信息未知"
            Card(
                modifier = Modifier.fillMaxWidth().clickable(enabled = !loaded || selected) { onSelect(model.file.absolutePath) },
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Memory, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(model.file.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${formatModelSize(model.sizeBytes)} · $quant", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        if (selected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onSelect(model.file.absolutePath) }, enabled = !loaded || selected) { Text(if (selected) "当前模型" else "设为当前") }
                        TextButton(onClick = { deleteTarget = model }, enabled = !busy && !(loaded && selected)) {
                            Icon(Icons.Default.DeleteOutline, null); Text("删除")
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Text("推理配置", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上下文长度：${settings.contextSize} tokens", fontWeight = FontWeight.Medium)
                Slider(value = settings.contextSize.coerceIn(1024, 8192).toFloat(), onValueChange = { onSettingsChange(settings.copy(contextSize = (it.toInt() / 512 * 512).coerceIn(1024, 8192))) }, valueRange = 1024f..8192f, steps = 13, enabled = !loaded)
                Text("CPU 线程数：${settings.localThreads}", fontWeight = FontWeight.Medium)
                Slider(value = settings.localThreads.coerceIn(1, maxThreads).toFloat(), onValueChange = { onSettingsChange(settings.copy(localThreads = it.toInt().coerceIn(1, maxThreads))) }, valueRange = 1f..maxThreads.toFloat(), steps = (maxThreads - 2).coerceAtLeast(0), enabled = !loaded && maxThreads > 1)
                Text("线程与上下文只在加载模型时生效。当前版本使用 CPU 后端，尚未启用 Vulkan/GPU 层卸载。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("采样参数", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Temperature：${String.format(java.util.Locale.US, "%.2f", settings.localTemperature)}")
                Slider(value = settings.localTemperature, onValueChange = { onSettingsChange(settings.copy(localTemperature = it)) }, valueRange = 0.05f..2.0f, steps = 18)
                Text("Top-P：${String.format(java.util.Locale.US, "%.2f", settings.localTopP)}")
                Slider(value = settings.localTopP, onValueChange = { onSettingsChange(settings.copy(localTopP = it)) }, valueRange = 0.05f..1.0f, steps = 18)
                Text("Top-K：${settings.localTopK}")
                Slider(value = settings.localTopK.toFloat(), onValueChange = { onSettingsChange(settings.copy(localTopK = it.toInt().coerceIn(1, 200))) }, valueRange = 1f..200f, steps = 18)
                Text("重复惩罚：${String.format(java.util.Locale.US, "%.2f", settings.localRepeatPenalty)}")
                Slider(value = settings.localRepeatPenalty, onValueChange = { onSettingsChange(settings.copy(localRepeatPenalty = it)) }, valueRange = 1.0f..2.0f, steps = 19)
                Text("这些数值会传入 llama.cpp 原生采样器，并在本机保存。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("提示词与聊天模板", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = settings.localSystemPrompt, onValueChange = { onSettingsChange(settings.copy(localSystemPrompt = it)) }, label = { Text("系统提示词") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6)
                Box {
                    OutlinedButton(onClick = { templateMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(when (settings.localPromptTemplate) { "chatml" -> "ChatML"; "llama3" -> "Llama 3"; "phi" -> "Phi"; "alpaca" -> "Alpaca"; else -> "自动识别 / 通用" }, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                        Icon(Icons.Default.ExpandMore, null)
                    }
                    DropdownMenu(expanded = templateMenu, onDismissRequest = { templateMenu = false }) {
                        listOf("auto" to "自动识别 / 通用", "chatml" to "ChatML", "llama3" to "Llama 3", "phi" to "Phi", "alpaca" to "Alpaca").forEach { item ->
                            DropdownMenuItem(text = { Text(item.second) }, onClick = { onSettingsChange(settings.copy(localPromptTemplate = item.first)); templateMenu = false })
                        }
                    }
                }
                Text("模板必须与模型训练时使用的聊天模板匹配；不确定时优先选择自动识别。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("配套文件与兼容性", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("检测到 $projectorCount 个 mmproj GGUF 文件")
                Text("mmproj 文件可以批量导入并留存在本机；当前 JNI 后端尚未接入 mmproj / mtmd 视觉解码，因此图片理解暂不可用。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("当前构建仅启用 arm64-v8a CPU。支持范围取决于 llama.cpp 架构、量化类型和设备内存。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("模型文件只存放在应用私有目录。卸载应用会删除本地模型；删除列表中的模型前请确认不再需要。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
    }
    if (deleteTarget != null) {
        val target = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除本地模型？") },
            text = { Text("将从应用私有目录删除 ${target.file.name}。此操作无法撤销。") },
            confirmButton = { TextButton(onClick = { onDelete(target.file.absolutePath); deleteTarget = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    modifier: Modifier,
    settings: AppSettings,
    apiKey: String,
    onApiKey: (String) -> Unit,
    onSave: () -> Unit,
    onChange: (AppSettings) -> Unit,
    onTest: () -> Unit,
    onFetchModels: () -> Unit,
    availableModels: List<String>,
    onSelectModel: (String) -> Unit,
    busy: Boolean,
    status: String,
    error: String
) {
    var showApiKey by remember { mutableStateOf(false) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showProviders by remember { mutableStateOf(false) }
    var showProtocols by remember { mutableStateOf(false) }
    var showAuthDetails by remember { mutableStateOf(false) }
    val needsKey = !settings.authHeaderName.equals("none", ignoreCase = true)

    Column(
        modifier.fillMaxSize().widthIn(max = 760.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("外观", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ThemeChoice("白蓝", "blue", settings.theme == "blue", Modifier.weight(1f)) { onChange(settings.copy(theme = "blue")) }
            ThemeChoice("米白", "cream", settings.theme == "cream", Modifier.weight(1f)) { onChange(settings.copy(theme = "cream")) }
            ThemeChoice("黑白", "mono", settings.theme == "mono", Modifier.weight(1f)) { onChange(settings.copy(theme = "mono")) }
        }

        HorizontalDivider()
        Text("AI API 配置", fontWeight = FontWeight.SemiBold)
        Text(
            when (settings.apiProtocol) {
                "anthropic_messages" -> "Anthropic Messages：使用 x-api-key 与 anthropic-version 请求头。"
                "gemini_generate" -> "Google Gemini：使用 x-goog-api-key 与 GenerateContent / StreamGenerateContent。"
                "openai_responses" -> "OpenAI Responses API：适用于支持 /responses 的模型和网关。"
                else -> "OpenAI Chat Completions 兼容协议：适用于大量模型厂商、聚合平台、自建网关和 Ollama。"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("服务商预设", fontWeight = FontWeight.Medium)
        Box {
            OutlinedButton(onClick = { showProviders = true }, modifier = Modifier.fillMaxWidth()) {
                Text(settings.provider.ifBlank { "选择服务商" }, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(Icons.Default.ExpandMore, contentDescription = null)
            }
            DropdownMenu(expanded = showProviders, onDismissRequest = { showProviders = false }) {
                API_PROVIDERS.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.name) },
                        onClick = {
                            if (item.name == "自定义") {
                                onChange(settings.copy(provider = item.name))
                            } else {
                                onChange(
                                    settings.copy(
                                        provider = item.name,
                                        endpoint = item.endpoint,
                                        model = "",
                                        apiProtocol = item.protocol,
                                        authHeaderName = item.authHeaderName,
                                        authHeaderPrefix = item.authHeaderPrefix
                                    )
                                )
                            }
                            showProviders = false
                        }
                    )
                }
            }
        }

        Text("API 协议", fontWeight = FontWeight.Medium)
        Box {
            OutlinedButton(onClick = { showProtocols = true }, modifier = Modifier.fillMaxWidth()) {
                Text(apiProtocolLabel(settings.apiProtocol), modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                Icon(Icons.Default.ExpandMore, contentDescription = null)
            }
            DropdownMenu(expanded = showProtocols, onDismissRequest = { showProtocols = false }) {
                API_PROTOCOLS.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.second) },
                        onClick = {
                            onChange(
                                settings.copy(
                                    provider = "自定义",
                                    apiProtocol = item.first,
                                    authHeaderName = "auto",
                                    authHeaderPrefix = "Bearer"
                                )
                            )
                            showProtocols = false
                        }
                    )
                }
            }
        }

        OutlinedTextField(
            value = settings.endpoint,
            onValueChange = { onChange(settings.copy(endpoint = it)) },
            label = { Text("API 基础地址") },
            placeholder = { Text("https://api.example.com/v1") },
            supportingText = {
                Text(
                    when (settings.apiProtocol) {
                        "anthropic_messages" -> "Anthropic 默认：https://api.anthropic.com"
                        "gemini_generate" -> "Gemini 默认：https://generativelanguage.googleapis.com/v1beta"
                        else -> "填写服务商提供的 API 基础地址，不需要自己拼接聊天路径"
                    }
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.model,
            onValueChange = { onChange(settings.copy(model = it)) },
            label = { Text("模型 ID（自行选择）") },
            placeholder = { Text("从模型目录复制，或通过下方模型列表获取") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKey,
            label = { Text(if (needsKey) "API Key / Token" else "API Key（当前认证模式可留空）") },
            placeholder = { Text("粘贴服务商控制台生成的完整密钥") },
            singleLine = true,
            visualTransformation = if (showApiKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showApiKey = !showApiKey }) {
                    Text(if (showApiKey) "隐藏" else "显示")
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "密钥使用 Android Keystore 加密保存在本机。不会上传到 GitHub 项目。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TextButton(onClick = { showAuthDetails = !showAuthDetails }, modifier = Modifier.align(Alignment.Start)) {
            Text(if (showAuthDetails) "收起高级认证选项" else "高级认证选项")
        }
        if (showAuthDetails) {
            OutlinedTextField(
                value = settings.authHeaderName,
                onValueChange = { onChange(settings.copy(authHeaderName = it)) },
                label = { Text("认证请求头名称") },
                supportingText = { Text("auto、Authorization、x-api-key、x-goog-api-key 或 none") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = settings.authHeaderPrefix,
                onValueChange = { onChange(settings.copy(authHeaderPrefix = it)) },
                label = { Text("认证前缀") },
                supportingText = { Text("常见为 Bearer；x-api-key 和 x-goog-api-key 通常留空") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onTest,
                enabled = !busy && settings.model.isNotBlank() && settings.endpoint.isNotBlank() && (!needsKey || apiKey.isNotBlank()),
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text(if (busy) "测试中…" else "测试当前模型") }
            Button(
                onClick = onSave,
                enabled = settings.endpoint.isNotBlank() && (!needsKey || apiKey.isNotBlank()),
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text("保存配置") }
        }
        OutlinedButton(
            onClick = onFetchModels,
            enabled = !busy && settings.endpoint.isNotBlank() && (!needsKey || apiKey.isNotBlank()),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) { Text(if (busy) "正在读取模型…" else "获取服务商模型列表") }
        if (availableModels.isNotEmpty()) {
            OutlinedButton(onClick = { showModelPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Text("从接口返回的模型列表选择（${availableModels.size}）")
            }
        }
        if (showModelPicker) {
            AlertDialog(
                onDismissRequest = { showModelPicker = false },
                title = { Text("选择模型 ID") },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        availableModels.forEach { modelId ->
                            TextButton(
                                onClick = { onSelectModel(modelId); showModelPicker = false },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(modelId, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Start) }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showModelPicker = false }) { Text("关闭") } }
            )
        }

        if (status.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        }
        if (error.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(error, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        }

        HorizontalDivider()
        Text("对话额度", fontWeight = FontWeight.SemiBold)
        Text("单次回复上限：${settings.maxTokens} tokens")
        Slider(
            value = settings.maxTokens.toFloat(),
            onValueChange = { onChange(settings.copy(maxTokens = (it.toInt() / 128) * 128)) },
            valueRange = 128f..8192f,
            steps = 62
        )
        Text("会话累计额度：${if (settings.sessionLimit == 0) "不限额" else settings.sessionLimit.toString() + " tokens"}")
        Slider(
            value = settings.sessionLimit.toFloat(),
            onValueChange = { onChange(settings.copy(sessionLimit = if (it < 500) 0 else (it.toInt() / 512) * 512)) },
            valueRange = 0f..65536f,
            steps = 127
        )
        TextButton(onClick = { onChange(settings.copy(sessionUsed = 0)) }) { Text("重置当前额度计数") }

        HorizontalDivider()
        Text("推理强度", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf("auto", "low", "medium", "high").forEach { value ->
                FilterChip(
                    selected = settings.reasoning == value,
                    onClick = { onChange(settings.copy(reasoning = value)) },
                    label = { Text(when (value) {
                        "auto" -> "自动"; "low" -> "低"; "medium" -> "中"; else -> "高"
                    }) }
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("发送模型推理参数")
                Text(
                    "仅部分 API 协议和模型支持；遇到参数不兼容时，请关闭。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = settings.sendReasoning, onCheckedChange = { onChange(settings.copy(sendReasoning = it)) })
        }

        HorizontalDivider()
        Text("隐私与存储", fontWeight = FontWeight.SemiBold)
        Text(
            "API Key 通过 Android Keystore 加密；聊天历史与 GGUF 模型存放在应用私有目录。卸载应用会删除本地数据。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ThemeChoice(label: String, value: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(
        modifier.clickable(onClick = onClick),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().height(28.dp)
                    .background(when (value) { "cream" -> Color(0xFFF5F0E6); "mono" -> Color(0xFF101010); else -> Color.White }, RoundedCornerShape(6.dp))
                    .padding(6.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(Modifier.width(20.dp).height(12.dp).background(when (value) { "cream" -> Color(0xFF735C43); "mono" -> Color.White; else -> Color(0xFF1769D2) }, RoundedCornerShape(4.dp)))
            }
            Spacer(Modifier.height(6.dp))
            Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}
