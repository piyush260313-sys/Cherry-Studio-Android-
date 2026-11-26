package com.cherry.studio

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class MessageRole(val apiValue: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    companion object {
        fun fromApi(value: String): MessageRole =
            entries.firstOrNull { it.apiValue == value } ?: USER
    }
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val model: String? = null,
    val latencyMs: Long? = null,
    val approxTokens: Int? = null,
    val isError: Boolean = false
)

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Chat",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val presetId: String = "default"
)

data class ProviderConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String = "",
    val models: List<String>,
    val selectedModel: String
)

data class PromptPreset(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val isBuiltIn: Boolean = false
)

data class EngineState(
    val sessions: List<ChatSession> = emptyList(),
    val activeSessionId: String? = null,
    val providers: List<ProviderConfig> = defaultProviders(),
    val activeProviderId: String = "openai",
    val presets: List<PromptPreset> = defaultPresets(),
    val isStreaming: Boolean = false,
    val searchQuery: String = "",
    val temperature: Float = 0.7f,
    val maxTokens: Int = 2048,
    val showStats: Boolean = true,
    val lastError: String? = null
) {
    val activeSession: ChatSession?
        get() = sessions.firstOrNull { it.id == activeSessionId }

    val activeProvider: ProviderConfig
        get() = providers.firstOrNull { it.id == activeProviderId } ?: providers.first()

    val filteredSessions: List<ChatSession>
        get() {
            val q = searchQuery.trim().lowercase()
            val list = if (q.isEmpty()) {
                sessions
            } else {
                sessions.filter { session ->
                    session.title.lowercase().contains(q) ||
                        session.messages.any { it.content.lowercase().contains(q) }
                }
            }
            return list.sortedWith(
                compareByDescending<ChatSession> { it.isPinned }
                    .thenByDescending { it.updatedAt }
            )
        }
}

class CherryEngine(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var streamJob: Job? = null
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    init {
        if (_state.value.sessions.isEmpty()) {
            createNewSession()
        }
    }

    // -------------------------------------------------------------------------
    // Session Management
    // -------------------------------------------------------------------------

    fun createNewSession(presetId: String = "default"): String {
        stopStreaming()
        val session = ChatSession(presetId = presetId)
        _state.update { current ->
            current.copy(
                sessions = listOf(session) + current.sessions,
                activeSessionId = session.id,
                lastError = null
            )
        }
        persistAsync()
        return session.id
    }

    fun selectSession(sessionId: String) {
        if (_state.value.activeSessionId == sessionId) return
        stopStreaming()
        _state.update { it.copy(activeSessionId = sessionId, lastError = null) }
        persistAsync()
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val cleaned = newTitle.trim().ifEmpty { "Untitled Chat" }
        _state.update { current ->
            current.copy(
                sessions = current.sessions.map {
                    if (it.id == sessionId) it.copy(title = cleaned, updatedAt = System.currentTimeMillis())
                    else it
                }
            )
        }
        persistAsync()
    }

    fun togglePinSession(sessionId: String) {
        _state.update { current ->
            current.copy(
                sessions = current.sessions.map {
                    if (it.id == sessionId) it.copy(isPinned = !it.isPinned) else it
                }
            )
        }
        persistAsync()
    }

    fun deleteSession(sessionId: String) {
        if (_state.value.activeSessionId == sessionId) stopStreaming()
        _state.update { current ->
            val remaining = current.sessions.filterNot { it.id == sessionId }
            val nextActive = when {
                current.activeSessionId != sessionId -> current.activeSessionId
                remaining.isNotEmpty() -> remaining.first().id
                else -> null
            }
            current.copy(sessions = remaining, activeSessionId = nextActive)
        }
        if (_state.value.sessions.isEmpty()) {
            createNewSession()
        } else {
            persistAsync()
        }
    }

    fun clearActiveSessionMessages() {
        val activeId = _state.value.activeSessionId ?: return
        stopStreaming()
        _state.update { current ->
            current.copy(
                sessions = current.sessions.map {
                    if (it.id == activeId) it.copy(
                        title = "New Chat",
                        messages = emptyList(),
                        updatedAt = System.currentTimeMillis()
                    ) else it
                },
                lastError = null
            )
        }
        persistAsync()
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun setSessionPreset(sessionId: String, presetId: String) {
        _state.update { current ->
            current.copy(
                sessions = current.sessions.map {
                    if (it.id == sessionId) it.copy(presetId = presetId) else it
                }
            )
        }
        persistAsync()
    }

    // -------------------------------------------------------------------------
    // Providers, Models & Presets
    // -------------------------------------------------------------------------

    fun selectProvider(providerId: String) {
        _state.update { it.copy(activeProviderId = providerId, lastError = null) }
        persistAsync()
    }

    fun selectModel(model: String) {
        val providerId = _state.value.activeProviderId
        _state.update { current ->
            current.copy(
                providers = current.providers.map { p ->
                    if (p.id == providerId) {
                        val updatedModels = if (model in p.models) p.models else p.models + model
                        p.copy(selectedModel = model, models = updatedModels)
                    } else p
                }
            )
        }
        persistAsync()
    }

    fun updateProviderConfig(
        providerId: String,
        apiKey: String,
        baseUrl: String? = null,
        customModel: String? = null
    ) {
        _state.update { current ->
            current.copy(
                providers = current.providers.map { p ->
                    if (p.id != providerId) return@map p
                    val cleanedUrl = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: p.baseUrl
                    val cleanedModel = customModel?.trim()?.takeIf { it.isNotEmpty() }
                    val models = if (cleanedModel != null && cleanedModel !in p.models) {
                        listOf(cleanedModel) + p.models
                    } else {
                        p.models
                    }
                    p.copy(
                        apiKey = apiKey.trim(),
                        baseUrl = cleanedUrl,
                        models = models,
                        selectedModel = cleanedModel ?: p.selectedModel
                    )
                },
                lastError = null
            )
        }
        persistAsync()
    }

    fun updateGenerationSettings(temperature: Float? = null, maxTokens: Int? = null, showStats: Boolean? = null) {
        _state.update { current ->
            current.copy(
                temperature = temperature?.coerceIn(0f, 2f) ?: current.temperature,
                maxTokens = maxTokens?.coerceIn(128, 32768) ?: current.maxTokens,
                showStats = showStats ?: current.showStats
            )
        }
        persistAsync()
    }

    fun addCustomPreset(name: String, systemPrompt: String) {
        val preset = PromptPreset(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Custom Preset" },
            systemPrompt = systemPrompt.trim(),
            isBuiltIn = false
        )
        _state.update { it.copy(presets = it.presets + preset) }
        persistAsync()
    }

    fun deletePreset(presetId: String) {
        _state.update { current ->
            val target = current.presets.firstOrNull { it.id == presetId }
            if (target == null || target.isBuiltIn) return@update current
            current.copy(
                presets = current.presets.filterNot { it.id == presetId },
                sessions = current.sessions.map {
                    if (it.presetId == presetId) it.copy(presetId = "default") else it
                }
            )
        }
        persistAsync()
    }

    fun clearError() {
        _state.update { it.copy(lastError = null) }
    }

    // -------------------------------------------------------------------------
    // Messaging, Streaming, Regenerate & Edit
    // -------------------------------------------------------------------------

    fun sendMessage(rawPrompt: String) {
        val prompt = rawPrompt.trim()
        if (prompt.isEmpty() || _state.value.isStreaming) return

        val sessionId = _state.value.activeSessionId ?: createNewSession()
        val userMsg = ChatMessage(
            role = MessageRole.USER,
            content = prompt,
            approxTokens = estimateTokens(prompt)
        )

        _state.update { current ->
            current.copy(
                lastError = null,
                sessions = current.sessions.map { session ->
                    if (session.id != sessionId) return@map session
                    val newTitle = if (session.messages.isEmpty()) generateTitle(prompt) else session.title
                    session.copy(
                        title = newTitle,
                        messages = session.messages + userMsg,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            )
        }
        persistAsync()
        triggerCompletion(sessionId)
    }

    fun editMessageAndResend(messageId: String, updatedContent: String) {
        val cleaned = updatedContent.trim()
        val session = _state.value.activeSession ?: return
        if (cleaned.isEmpty() || _state.value.isStreaming) return

        val index = session.messages.indexOfFirst { it.id == messageId && it.role == MessageRole.USER }
        if (index == -1) return

        val trimmedHistory = session.messages.take(index) + session.messages[index].copy(
            content = cleaned,
            timestamp = System.currentTimeMillis(),
            approxTokens = estimateTokens(cleaned)
        )

        _state.update { current ->
            current.copy(
                lastError = null,
                sessions = current.sessions.map {
                    if (it.id == session.id) it.copy(
                        messages = trimmedHistory,
                        updatedAt = System.currentTimeMillis()
                    ) else it
                }
            )
        }
        persistAsync()
        triggerCompletion(session.id)
    }

    fun regenerateLastResponse() {
        val session = _state.value.activeSession ?: return
        if (_state.value.isStreaming || session.messages.isEmpty()) return

        val updatedMessages = if (session.messages.last().role == MessageRole.ASSISTANT) {
            session.messages.dropLast(1)
        } else {
            session.messages
        }
        if (updatedMessages.none { it.role == MessageRole.USER }) return

        _state.update { current ->
            current.copy(
                lastError = null,
                sessions = current.sessions.map {
                    if (it.id == session.id) it.copy(messages = updatedMessages) else it
                }
            )
        }
        persistAsync()
        triggerCompletion(session.id)
    }

    fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        try {
            activeConnection?.disconnect()
        } catch (_: Exception) {
        } finally {
            activeConnection = null
        }
        _state.update { it.copy(isStreaming = false) }
    }

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    fun exportSessionAsMarkdown(sessionId: String = _state.value.activeSessionId ?: ""): String {
        val session = _state.value.sessions.firstOrNull { it.id == sessionId } ?: return ""
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return buildString {
            appendLine("# ${session.title}")
            appendLine("_Exported ${dateFmt.format(Date())}_")
            appendLine()
            session.messages.forEach { msg ->
                val header = when (msg.role) {
                    MessageRole.USER -> "### You"
                    MessageRole.ASSISTANT -> "### Assistant" + (msg.model?.let { " ($it)" } ?: "")
                    MessageRole.SYSTEM -> "### System"
                }
                appendLine(header)
                appendLine(msg.content)
                appendLine()
                appendLine("---")
                appendLine()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal Streaming Engine (OpenAI-Compatible SSE)
    // -------------------------------------------------------------------------

    private fun triggerCompletion(sessionId: String) {
        val snapshot = _state.value
        val provider = snapshot.activeProvider
        val session = snapshot.sessions.firstOrNull { it.id == sessionId } ?: return

        val requiresKey = !provider.baseUrl.contains("localhost") && !provider.baseUrl.contains("127.0.0.1")
        if (requiresKey && provider.apiKey.isBlank()) {
            _state.update { it.copy(lastError = "Add an API key for ${provider.name} in Settings.") }
            return
        }

        val assistantId = UUID.randomUUID().toString()
        val placeholder = ChatMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            content = "",
            model = provider.selectedModel
        )

        _state.update { current ->
            current.copy(
                isStreaming = true,
                lastError = null,
                sessions = current.sessions.map {
                    if (it.id == sessionId) it.copy(messages = it.messages + placeholder) else it
                }
            )
        }

        val systemPrompt = snapshot.presets
            .firstOrNull { it.id == session.presetId }
            ?.systemPrompt
            ?.takeIf { it.isNotBlank() }

        val historyToSend = session.messages.filterNot { it.isError }
        val startTime = System.currentTimeMillis()

        streamJob = scope.launch {
            val buffer = StringBuilder()
            try {
                val payload = buildRequestPayload(
                    model = provider.selectedModel,
                    systemPrompt = systemPrompt,
                    messages = historyToSend,
                    temperature = snapshot.temperature,
                    maxTokens = snapshot.maxTokens
                )

                val endpoint = "${provider.baseUrl.trimEnd('/')}/chat/completions"
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20_000
                    readTimeout = 90_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "text/event-stream")
                    if (provider.apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
                    }
                }
                activeConnection = conn

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload.toString())
                    writer.flush()
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    val errText = conn.errorStream?.bufferedReader()?.use { it.readText() }
                        ?: "HTTP $code"
                    throw IllegalStateException(parseApiError(code, errText))
                }

                val contentType = conn.contentType.orEmpty()
                if (contentType.contains("text/event-stream", ignoreCase = true)) {
                    BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                        var lastUiEmit = 0L
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            if (data.isEmpty()) continue

                            val delta = extractStreamDelta(data)
                            if (delta.isNotEmpty()) {
                                buffer.append(delta)
                                val now = System.currentTimeMillis()
                                if (now - lastUiEmit >= 32L) {
                                    updateAssistantMessage(sessionId, assistantId, buffer.toString())
                                    lastUiEmit = now
                                }
                            }
                        }
                    }
                } else {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    buffer.append(extractNonStreamContent(body))
                }

                val elapsed = System.currentTimeMillis() - startTime
                val finalText = buffer.toString().ifEmpty { "(Empty response)" }
                finalizeAssistantMessage(
                    sessionId = sessionId,
                    messageId = assistantId,
                    content = finalText,
                    latencyMs = elapsed,
                    isError = false
                )
            } catch (_: CancellationException) {
                val elapsed = System.currentTimeMillis() - startTime
                val partial = buffer.toString()
                if (partial.isNotEmpty()) {
                    finalizeAssistantMessage(sessionId, assistantId, partial, elapsed, isError = false)
                } else {
                    removeMessage(sessionId, assistantId)
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Request failed"
                finalizeAssistantMessage(
                    sessionId = sessionId,
                    messageId = assistantId,
                    content = "Error: $msg",
                    latencyMs = System.currentTimeMillis() - startTime,
                    isError = true
                )
                _state.update { it.copy(lastError = msg) }
            } finally {
                try {
                    activeConnection?.disconnect()
                } catch (_: Exception) {
                }
                activeConnection = null
                _state.update { it.copy(isStreaming = false) }
                persistAsync()
            }
        }
    }

    private fun updateAssistantMessage(sessionId: String, messageId: String, content: String) {
        _state.update { current ->
            current.copy(
                sessions = current.sessions.map { session ->
                    if (session.id != sessionId) return@map session
                    session.copy(
                        messages = session.messages.map { m ->
                            if (m.id == messageId) m.copy(content = content) else m
                        }
                    )
                }
            )
        }
    }

    private fun finalizeAssistantMessage(
        sessionId: String,
        messageId: String,
        content: String,
        latencyMs: Long,
        isError: Boolean
    ) {
        _state.update { current ->
            current.copy(
                sessions = current.sessions.map { session ->
                    if (session.id != sessionId) return@map session
                    session.copy(
                        updatedAt = System.currentTimeMillis(),
                        messages = session.messages.map { m ->
                            if (m.id == messageId) {
                                m.copy(
                                    content = content,
                                    latencyMs = latencyMs,
                                    approxTokens = estimateTokens(content),
                                    isError = isError
                                )
                            } else m
                        }
                    )
                }
            )
        }
    }

    private fun removeMessage(sessionId: String, messageId: String) {
        _state.update { current ->
            current.copy(
                sessions = current.sessions.map { session ->
                    if (session.id != sessionId) return@map session
                    session.copy(messages = session.messages.filterNot { it.id == messageId })
                }
            )
        }
    }

    // -------------------------------------------------------------------------
    // JSON Helpers & Persistence
    // -------------------------------------------------------------------------

    private fun buildRequestPayload(
        model: String,
        systemPrompt: String?,
        messages: List<ChatMessage>,
        temperature: Float,
        maxTokens: Int
    ): JSONObject {
        val msgArray = JSONArray()
        if (!systemPrompt.isNullOrBlank()) {
            msgArray.put(JSONObject().put("role", "system").put("content", systemPrompt))
        }
        messages.forEach { msg ->
            msgArray.put(
                JSONObject()
                    .put("role", msg.role.apiValue)
                    .put("content", msg.content)
            )
        }
        return JSONObject()
            .put("model", model)
            .put("messages", msgArray)
            .put("temperature", temperature.toDouble())
            .put("max_tokens", maxTokens)
            .put("stream", true)
    }

    private fun extractStreamDelta(jsonStr: String): String {
        return try {
            val obj = JSONObject(jsonStr)
            val choices = obj.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val delta = choices.getJSONObject(0).optJSONObject("delta") ?: return ""
            val content = delta.optString("content", "")
            if (content == "null") "" else content
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractNonStreamContent(jsonStr: String): String {
        return try {
            val obj = JSONObject(jsonStr)
            val choices = obj.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            choices.getJSONObject(0).optJSONObject("message")?.optString("content", "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseApiError(code: Int, rawBody: String): String {
        return try {
            val errObj = JSONObject(rawBody).optJSONObject("error")
            val msg = errObj?.optString("message")?.takeIf { it.isNotBlank() }
            msg ?: "HTTP $code"
        } catch (_: Exception) {
            "HTTP $code: ${rawBody.take(160)}"
        }
    }

    private fun generateTitle(prompt: String): String {
        val oneLine = prompt.replace('\n', ' ').trim()
        return if (oneLine.length <= 36) oneLine else oneLine.take(36).trimEnd() + "…"
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun persistAsync() {
        val snapshot = _state.value
        scope.launch {
            withContext(Dispatchers.IO) {
                prefs.edit().putString(KEY_STATE_JSON, serializeState(snapshot)).apply()
            }
        }
    }

    private fun serializeState(s: EngineState): String {
        val root = JSONObject()
        root.put("activeSessionId", s.activeSessionId ?: JSONObject.NULL)
        root.put("activeProviderId", s.activeProviderId)
        root.put("temperature", s.temperature.toDouble())
        root.put("maxTokens", s.maxTokens)
        root.put("showStats", s.showStats)

        val sessionsArr = JSONArray()
        s.sessions.forEach { session ->
            val msgsArr = JSONArray()
            session.messages.forEach { m ->
                msgsArr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("role", m.role.apiValue)
                        .put("content", m.content)
                        .put("timestamp", m.timestamp)
                        .put("model", m.model ?: JSONObject.NULL)
                        .put("latencyMs", m.latencyMs ?: JSONObject.NULL)
                        .put("approxTokens", m.approxTokens ?: JSONObject.NULL)
                        .put("isError", m.isError)
                )
            }
            sessionsArr.put(
                JSONObject()
                    .put("id", session.id)
                    .put("title", session.title)
                    .put("createdAt", session.createdAt)
                    .put("updatedAt", session.updatedAt)
                    .put("isPinned", session.isPinned)
                    .put("presetId", session.presetId)
                    .put("messages", msgsArr)
            )
        }
        root.put("sessions", sessionsArr)

        val providersArr = JSONArray()
        s.providers.forEach { p ->
            providersArr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("baseUrl", p.baseUrl)
                    .put("apiKey", p.apiKey)
                    .put("selectedModel", p.selectedModel)
                    .put("models", JSONArray(p.models))
            )
        }
        root.put("providers", providersArr)

        val presetsArr = JSONArray()
        s.presets.filterNot { it.isBuiltIn }.forEach { preset ->
            presetsArr.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("name", preset.name)
                    .put("systemPrompt", preset.systemPrompt)
            )
        }
        root.put("customPresets", presetsArr)
        return root.toString()
    }

    private fun loadInitialState(): EngineState {
        val raw = prefs.getString(KEY_STATE_JSON, null) ?: return EngineState()
        return try {
            val root = JSONObject(raw)

            val sessions = mutableListOf<ChatSession>()
            val sessionsArr = root.optJSONArray("sessions") ?: JSONArray()
            for (i in 0 until sessionsArr.length()) {
                val obj = sessionsArr.getJSONObject(i)
                val msgs = mutableListOf<ChatMessage>()
                val msgsArr = obj.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until msgsArr.length()) {
                    val m = msgsArr.getJSONObject(j)
                    msgs.add(
                        ChatMessage(
                            id = m.optString("id", UUID.randomUUID().toString()),
                            role = MessageRole.fromApi(m.optString("role", "user")),
                            content = m.optString("content", ""),
                            timestamp = m.optLong("timestamp", System.currentTimeMillis()),
                            model = m.optString("model").takeIf { it.isNotEmpty() && it != "null" },
                            latencyMs = if (m.isNull("latencyMs")) null else m.optLong("latencyMs"),
                            approxTokens = if (m.isNull("approxTokens")) null else m.optInt("approxTokens"),
                            isError = m.optBoolean("isError", false)
                        )
                    )
                }
                sessions.add(
                    ChatSession(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", "New Chat"),
                        messages = msgs,
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                        isPinned = obj.optBoolean("isPinned", false),
                        presetId = obj.optString("presetId", "default")
                    )
                )
            }

            val providers = mutableListOf<ProviderConfig>()
            val providersArr = root.optJSONArray("providers")
            if (providersArr != null && providersArr.length() > 0) {
                for (i in 0 until providersArr.length()) {
                    val p = providersArr.getJSONObject(i)
                    val modelsArr = p.optJSONArray("models") ?: JSONArray()
                    val models = List(modelsArr.length()) { idx -> modelsArr.getString(idx) }
                    providers.add(
                        ProviderConfig(
                            id = p.getString("id"),
                            name = p.getString("name"),
                            baseUrl = p.getString("baseUrl"),
                            apiKey = p.optString("apiKey", ""),
                            models = models,
                            selectedModel = p.optString("selectedModel", models.firstOrNull().orEmpty())
                        )
                    )
                }
            }

            val customPresets = mutableListOf<PromptPreset>()
            val presetsArr = root.optJSONArray("customPresets") ?: JSONArray()
            for (i in 0 until presetsArr.length()) {
                val pr = presetsArr.getJSONObject(i)
                customPresets.add(
                    PromptPreset(
                        id = pr.getString("id"),
                        name = pr.getString("name"),
                        systemPrompt = pr.getString("systemPrompt"),
                        isBuiltIn = false
                    )
                )
            }

            val activeSessionId = root.optString("activeSessionId")
                .takeIf { it.isNotEmpty() && it != "null" }
                ?: sessions.firstOrNull()?.id

            EngineState(
                sessions = sessions,
                activeSessionId = activeSessionId,
                providers = providers.ifEmpty { defaultProviders() },
                activeProviderId = root.optString("activeProviderId", "openai"),
                presets = defaultPresets() + customPresets,
                temperature = root.optDouble("temperature", 0.7).toFloat(),
                maxTokens = root.optInt("maxTokens", 2048),
                showStats = root.optBoolean("showStats", true)
            )
        } catch (_: Exception) {
            EngineState()
        }
    }

    companion object {
        private const val PREFS_NAME = "cherry_studio_engine_v2"
        private const val KEY_STATE_JSON = "engine_state_json"
    }
}

fun defaultProviders(): List<ProviderConfig> = listOf(
    ProviderConfig(
        id = "openai",
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        models = listOf("gpt-4o-mini", "gpt-4o", "o3-mini"),
        selectedModel = "gpt-4o-mini"
    ),
    ProviderConfig(
        id = "deepseek",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        models = listOf("deepseek-chat", "deepseek-reasoner"),
        selectedModel = "deepseek-chat"
    ),
    ProviderConfig(
        id = "openrouter",
        name = "OpenRouter",
        baseUrl = "https://openrouter.ai/api/v1",
        models = listOf("anthropic/claude-3.5-sonnet", "google/gemini-2.5-flash", "openai/gpt-4o-mini"),
        selectedModel = "google/gemini-2.5-flash"
    ),
    ProviderConfig(
        id = "ollama",
        name = "Ollama (Local)",
        baseUrl = "http://127.0.0.1:11434/v1",
        models = listOf("llama3.2", "qwen2.5-coder", "deepseek-r1"),
        selectedModel = "llama3.2"
    )
)

fun defaultPresets(): List<PromptPreset> = listOf(
    PromptPreset(
        id = "default",
        name = "Balanced",
        systemPrompt = "You are a helpful, clear, and thoughtful assistant.",
        isBuiltIn = true
    ),
    PromptPreset(
        id = "concise",
        name = "Concise",
        systemPrompt = "Be direct and concise. Lead with the answer and skip filler.",
        isBuiltIn = true
    ),
    PromptPreset(
        id = "coder",
        name = "Code Partner",
        systemPrompt = "You are a senior software engineer. Give clean, idiomatic code with brief explanations of edge cases.",
        isBuiltIn = true
    ),
    PromptPreset(
        id = "writer",
        name = "Editor",
        systemPrompt = "Refine writing for clarity, natural flow, and precision while keeping the author's voice.",
        isBuiltIn = true
    )
)
