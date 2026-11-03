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
                                                    val prompt = buildLocalPrompt(messages.dropLast(1), settings.localModelPath)
                                                    val result = withContext(Dispatchers.IO) {
                                                        engine?.nativeGenerate(prompt, settings.maxTokens, 0.7f)
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
                                                engine?.nativeGenerate("### Instruction:\n$fullPrompt\n\n### Response:\n", settings.maxTokens, 0.6f).orEmpty()
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
                            modifier = Modifier.padding(padding),
                            settings = settings,
                            loaded = localLoaded,
                            busy = isBusy,
                            status = workspaceStatus,
                            error = errorText,
                            onContext = { updateSettings(settings.copy(contextSize = it)) },
                            onImport = { uri ->
                                scope.launch {
                                    isBusy = true
                                    errorText = ""
                                    try {
                                        val imported = withContext(Dispatchers.IO) { importModel(context, uri) }
                                        updateSettings(settings.copy(localModelPath = imported.absolutePath))
                                        workspaceStatus = "已导入：${imported.name}"
                                    } catch (e: Exception) {
                                        errorText = e.message ?: "导入失败"
                                    } finally { isBusy = false }
                                }
                            },
                            onLoad = {
                                val path = settings.localModelPath
                                if (path.isNotBlank()) scope.launch {
                                    isBusy = true
                                    errorText = ""
                                    try {
                                        if (!LocalModelEngine.available) throw IllegalStateException("本地推理引擎未编译进此设备版本")
                                        val e = engine ?: LocalModelEngine().also { engine = it }
                                        val result = withContext(Dispatchers.IO) {
                                            e.nativeLoad(path, settings.contextSize, Runtime.getRuntime().availableProcessors())
                                        }
                                        if (result.startsWith("ERROR:")) throw IllegalStateException(result.removePrefix("ERROR:").trim())
                                        localLoaded = true
                                    } catch (e: Exception) { errorText = e.message ?: "加载模型失败" }
                                    finally { isBusy = false }
                                }
                            },
                            onUnload = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { engine?.nativeUnload() }
                                    localLoaded = false
                                    useLocal = false
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

private fun buildLocalPrompt(messages: List<ChatMessage>, modelPath: String): String {
    val recent = messages.filter { it.content.isNotBlank() }.takeLast(24)
    val system = "你是 AI Studio 的智能助手。请用用户使用的语言回答，准确、清晰，并直接完成用户要求。"
    return if (modelPath.contains("phi", ignoreCase = true)) {
        buildString {
            append("<|system|>\n").append(system).append("<|end|>\n")
            recent.forEach { message ->
                val role = if (message.role == "user") "user" else "assistant"
                append("<|").append(role).append("|>\n")
                append(message.content.take(12000))
                append("<|end|>\n")
            }
            append("<|assistant|>\n")
        }
    } else {
        buildString {
            append("系统：").append(system).append("\n")
            recent.forEach { message ->
                append(if (message.role == "user") "用户：" else "助手：")
                append(message.content.take(12000)).append("\n")
            }
            append("助手：")
        }
    }
}

private fun importModel(context: Context, uri: Uri): File {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: "imported-model.gguf"
    require(name.endsWith(".gguf", ignoreCase = true)) { "请选择 .gguf 格式的模型文件" }
    val folder = File(context.filesDir, "models").apply { mkdirs() }
    val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    val target = File(folder, safeName)
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(target).use { output -> input.copyTo(output, 1024 * 1024) }
    } ?: throw IllegalStateException("无法读取所选文件")
    return target
}

@Composable
private fun SetupScreen(
    initial: AppSettings,
    initialKey: String,
    onStart: (AppSettings, String) -> Unit,
    onLocalOnly: () -> Unit
) {
    var provider by remember { mutableStateOf(initial.provider) }
    var apiProtocol by remember { mutableStateOf(initial.apiProtocol) }
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var model by remember { mutableStateOf(initial.model) }
    var key by remember { mutableStateOf(initialKey) }
    var showKey by remember { mutableStateOf(false) }
    val providers = listOf(
        Triple("OpenAI", "https://api.openai.com/v1", ""),
        Triple("DeepSeek", "https://api.deepseek.com", ""),
        Triple("OpenRouter", "https://openrouter.ai/api/v1", ""),
        Triple("智谱标准 API", "https://open.bigmodel.cn/api/paas/v4", ""),
        Triple("智谱 Coding Plan", "https://open.bigmodel.cn/api/coding/paas/v4", ""),
        Triple("Anthropic Claude", "https://api.anthropic.com", ""),
        Triple("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", ""),
        Triple("Ollama", "http://localhost:11434/v1", ""),
        Triple("自定义", endpoint, model)
    )
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("开始使用 AI Studio", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("连接 API，或之后导入本地 GGUF 模型。聊天、写代码和文件开发都在一个独立应用中完成。", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("选择服务商", fontWeight = FontWeight.SemiBold)
                providers.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { item ->
                            FilterChip(
                                selected = provider == item.first,
                                onClick = {
                                    provider = item.first
                                    apiProtocol = when {
                                        item.first.contains("Claude") -> "anthropic"
                                        item.first.contains("Gemini") -> "gemini"
                                        else -> "openai"
                                    }
                                    if (item.first != "自定义") { endpoint = item.second; model = "" }
                                },
                                label = { Text(item.first) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                OutlinedTextField(endpoint, { endpoint = it }, label = { Text("API 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it }, label = { Text("模型 ID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    key, { key = it }, label = { Text("API Key") }, singleLine = true,
                    visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") } }
                )
                Text("密钥使用 Android Keystore 加密保存在本机，不会写入项目文件。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = { onStart(initial.copy(provider = provider, apiProtocol = apiProtocol, endpoint = endpoint.trim(), model = model.trim()), key.trim()) },
                    enabled = endpoint.isNotBlank() && model.isNotBlank() && key.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(50.dp)
                ) { Text("保存并开始聊天") }
                TextButton(onClick = onLocalOnly, modifier = Modifier.fillMaxWidth()) { Text("暂时跳过，稍后导入本地模型") }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("AI Studio · 原生 Android", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(28.dp))
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
    Column(modifier.fillMaxSize().imePadding()) {
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

        if (messages.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(16.dp))
                Text("有什么可以帮你？", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("输入消息开始对话。模型会根据你填写的模型 ID 工作。", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
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

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input, onValueChange = onInput,
                    placeholder = { Text("发送消息…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 5,
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) {
            Icon(Icons.Default.SmartToy, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, end = 8.dp).size(22.dp))
        }
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(if (isUser) 22.dp else 16.dp),
            border = if (isUser) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
            modifier = Modifier.widthIn(max = 330.dp)
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
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    modifier: Modifier,
    settings: AppSettings,
    loaded: Boolean,
    busy: Boolean,
    status: String,
    error: String,
    onContext: (Int) -> Unit,
    onImport: (Uri) -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    val modelFile = settings.localModelPath.substringAfterLast('/')
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("本地模型", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        Text("直接在本机运行 GGUF 模型。推理引擎已编译到 APK 内，不需要 Termux、Python 或单独的控制台。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Memory, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (settings.hasLocalModel) modelFile else "尚未导入模型",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (loaded) "运行中 · 本机推理" else if (settings.hasLocalModel) "已导入 · 未加载" else "选择一个 .gguf 文件",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (loaded) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                }
                OutlinedButton(onClick = { launcher.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("导入 GGUF 模型")
                }
                if (settings.hasLocalModel && !loaded) {
                    Button(onClick = onLoad, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "正在加载模型…" else "加载模型")
                    }
                }
                if (loaded) {
                    OutlinedButton(onClick = onUnload, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("卸载模型并释放内存") }
                }
            }
        }
        Text("上下文长度：${settings.contextSize} tokens", fontWeight = FontWeight.Medium)
        Slider(
            value = settings.contextSize.toFloat(),
            onValueChange = { onContext((it.toInt() / 512) * 512) },
            valueRange = 1024f..8192f,
            steps = 13
        )
        Text("上下文越大，记忆的对话越长，也会占用更多内存。建议从 2048 或 4096 开始。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("建议", fontWeight = FontWeight.SemiBold)
        Text("使用适合手机内存的量化 GGUF（例如 Q4_K_M）。大型模型可能加载失败或导致系统回收应用。模型文件只保存在本机应用私有目录。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
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
    val providers = listOf(
        Triple("OpenAI", "https://api.openai.com/v1", ""),
        Triple("DeepSeek", "https://api.deepseek.com", ""),
        Triple("OpenRouter", "https://openrouter.ai/api/v1", ""),
        Triple("智谱标准 API", "https://open.bigmodel.cn/api/paas/v4", ""),
        Triple("智谱 Coding Plan", "https://open.bigmodel.cn/api/coding/paas/v4", ""),
        Triple("Anthropic Claude", "https://api.anthropic.com", ""),
        Triple("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", ""),
        Triple("Ollama", "http://localhost:11434/v1", "")
    )
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
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
        Text("API 配置", fontWeight = FontWeight.SemiBold)
        Text(
            when {
                settings.provider.contains("Coding") ->
                    "GLM Coding Plan 专用接口，请使用对应套餐密钥。模型 ID 由你自己选择。"
                settings.provider.contains("智谱") ->
                    "智谱标准 API，请使用「API Keys」页面的标准密钥。模型 ID 由你自己选择。"
                settings.apiProtocol == "anthropic" ->
                    "Anthropic Messages 协议：使用 Claude Console API Key，发送 x-api-key 与 anthropic-version。"
                settings.apiProtocol == "gemini" ->
                    "Gemini 原生协议：使用 Google AI Studio API Key，发送 x-goog-api-key。"
                else ->
                    "OpenAI Chat Completions 兼容协议，支持多个厂商和自建网关。模型 ID 由你自己填写。"
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("接口协议", fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(
                selected = settings.apiProtocol == "openai",
                onClick = { onChange(settings.copy(provider = "自定义", apiProtocol = "openai", endpoint = if (settings.endpoint.isBlank()) "https://api.openai.com/v1" else settings.endpoint)) },
                label = { Text("OpenAI 兼容") }
            )
            FilterChip(
                selected = settings.apiProtocol == "anthropic",
                onClick = { onChange(settings.copy(provider = "Anthropic", apiProtocol = "anthropic", endpoint = if (settings.endpoint.isBlank()) "https://api.anthropic.com" else settings.endpoint)) },
                label = { Text("Anthropic") }
            )
            FilterChip(
                selected = settings.apiProtocol == "gemini",
                onClick = { onChange(settings.copy(provider = "Google Gemini", apiProtocol = "gemini", endpoint = if (settings.endpoint.isBlank()) "https://generativelanguage.googleapis.com/v1beta" else settings.endpoint)) },
                label = { Text("Gemini") }
            )
        }
        providers.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { p ->
                    FilterChip(
                        selected = settings.provider == p.first,
                        onClick = {
                            val proto = when {
                                p.first.contains("Claude") -> "anthropic"
                                p.first.contains("Gemini") -> "gemini"
                                else -> "openai"
                            }
                            onChange(settings.copy(provider = p.first, apiProtocol = proto, endpoint = p.second, model = ""))
                        },
                        label = { Text(p.first, fontSize = 13.sp, maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            FilterChip(
                selected = settings.provider == "自定义",
                onClick = { onChange(settings.copy(provider = "自定义")) },
                label = { Text("自定义 API") }
            )
        }
        OutlinedTextField(
            value = settings.endpoint,
            onValueChange = { onChange(settings.copy(endpoint = it)) },
            label = { Text("API 基础地址") },
            supportingText = { Text("标准 API：/api/paas/v4 · Coding Plan：/api/coding/paas/v4") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = settings.model,
            onValueChange = { onChange(settings.copy(model = it)) },
            label = { Text("模型 ID（自行填写）") },
            placeholder = { Text("输入你选择的模型 ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKey,
            label = { Text("API Key") },
            placeholder = { Text("粘贴服务商控制台生成的完整 API Key") },
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
            "API Key 只保存在本机 Android Keystore 加密存储中。不要把密钥发给任何人。",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onTest,
                enabled = !busy && apiKey.isNotBlank() && settings.endpoint.isNotBlank() && settings.model.isNotBlank(),
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text(if (busy) "测试中…" else "测试当前模型") }
            Button(
                onClick = onSave,
                enabled = apiKey.isNotBlank() && settings.endpoint.isNotBlank(),
                modifier = Modifier.weight(1f).height(48.dp)
            ) { Text("保存配置") }
        }
        OutlinedButton(
            onClick = onFetchModels,
            enabled = !busy && apiKey.isNotBlank() && settings.endpoint.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) { Text(if (busy) "正在读取模型…" else "获取服务商模型列表") }
        if (availableModels.isNotEmpty()) {
            OutlinedButton(
                onClick = { showModelPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
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
                confirmButton = { TextButton(onClick = { showModelPicker = false }) { Text("关闭") }
                }
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
                Text(if (settings.provider.contains("智谱")) "发送智谱 thinking 参数" else "向 API 发送 reasoning_effort")
                Text(
                    if (settings.provider.contains("智谱")) "智谱接口使用 thinking.type；不同模型支持情况不同。" else "仅部分模型支持；不支持时请关闭。",
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
