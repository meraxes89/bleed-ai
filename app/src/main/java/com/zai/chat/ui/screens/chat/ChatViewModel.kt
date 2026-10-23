package com.zai.chat.ui.screens.chat

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zai.chat.config.ZaiConfig
import com.zai.chat.data.local.preferences.SettingsDataStore
import com.zai.chat.data.model.FileAttachment
import com.zai.chat.data.model.Message
import com.zai.chat.data.model.MessageRole
import com.zai.chat.data.repository.ChatRepository
import com.zai.chat.network.ZaiApiService
import com.zai.chat.network.sse.StreamEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.zai.chat.network.transport.CaptchaTokenPool
import com.zai.chat.network.transport.WebViewSigner
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val repository: ChatRepository,
    private val apiService: ZaiApiService,
    val signer: WebViewSigner,
    private val tokenPool: CaptchaTokenPool,
    settingsDataStore: SettingsDataStore,
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var messagesJob: Job? = null
    private var metaJob: Job? = null
    private val uploadJobs = mutableMapOf<String, Job>()
    private var isScrolledUp = false

    init {
        // P8: navigation passes chatId via the route; SavedStateHandle delivers it.
        savedStateHandle.get<String>("chatId")?.let { id ->
            _uiState.update { it.copy(chatId = id) }
            observeChat(id)
            viewModelScope.launch { repository.syncChatMessages(id) }
        }

        viewModelScope.launch {
            apiService.getAvailableModels().let { models ->
                _uiState.update { it.copy(availableModels = models) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.enterIsSend.collectLatest { enabled ->
                _uiState.update { it.copy(enterIsSend = enabled) }
            }
        }
        viewModelScope.launch {
            settingsDataStore.defaultModel.collectLatest { model ->
                if (model.isNotBlank()) {
                    _uiState.update { it.copy(selectedModel = model) }
                }
            }
        }
    }

    /** Called by P8 drawer navigation to open an existing chat. */
    fun loadChat(chatId: String) {
        _uiState.update { it.copy(chatId = chatId, chatTitle = "…", messages = emptyList()) }
        observeChat(chatId)
        viewModelScope.launch { repository.syncChatMessages(chatId) }
    }

    private fun observeChat(chatId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            repository.getMessagesStream(chatId).collectLatest { msgs ->
                _uiState.update { st ->
                    if (st.awaitingHandoff && msgs.lastOrNull()?.role == MessageRole.ASSISTANT) {
                        // Persisted reply arrived in Room → swap out the transient
                        // bubble atomically with buffers clearing. No flicker.
                        st.copy(
                            messages = msgs,
                            isStreaming = false,
                            liveContent = "",
                            liveReasoning = "",
                            awaitingHandoff = false
                        )
                    } else {
                        st.copy(messages = msgs)
                    }
                }
            }
        }
        metaJob?.cancel()
        metaJob = viewModelScope.launch {
            repository.getChatsStream().collectLatest { chats ->
                chats.firstOrNull { it.id == chatId }?.let { chat ->
                    _uiState.update { it.copy(chatTitle = chat.title) }
                }
            }
        }
    }

    fun onEvent(event: ChatUiEvent) {
        when (event) {
            is ChatUiEvent.SendMessage -> handleSend(event.text)
            is ChatUiEvent.StopStreaming -> stopStreaming()
            is ChatUiEvent.ToggleWebSearch ->
                _uiState.update { it.copy(webSearch = event.enabled) }
            is ChatUiEvent.ToggleDeepThinking ->
                _uiState.update { it.copy(deepThinking = event.enabled) }
            is ChatUiEvent.SelectModel ->
                _uiState.update { it.copy(selectedModel = event.model) }
            is ChatUiEvent.StartEdit ->
                _uiState.update { it.copy(editingMessage = event.message) }
            is ChatUiEvent.ConfirmEdit -> handleConfirmEdit(event.newText)
            is ChatUiEvent.DismissEdit ->
                _uiState.update { it.copy(editingMessage = null) }
            is ChatUiEvent.RegenerateFrom -> handleRegenerate(event.message)
            is ChatUiEvent.DismissError ->
                _uiState.update { it.copy(bannerError = null) }
            is ChatUiEvent.ScrolledStateChange -> {
                isScrolledUp = event.isScrolledUp
                if (!event.isScrolledUp) {
                    _uiState.update { it.copy(unreadWhileScrolledUp = 0) }
                }
            }
            is ChatUiEvent.AddAttachments -> handleAddAttachments(event.uris)
            is ChatUiEvent.RemoveAttachment -> handleRemoveAttachment(event.localId)
            is ChatUiEvent.UserComposing -> tokenPool.onUserComposing()
        }
    }

    private fun handleAddAttachments(uris: List<Uri>) {
        val contentResolver = context.contentResolver
        uris.forEach { uri ->
            val localId = UUID.randomUUID().toString()
            val displayName = queryFileName(contentResolver, uri) ?: "file_${System.currentTimeMillis()}"
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val isImage = mimeType.startsWith("image/")

            val pending = PendingAttachment(
                localId = localId,
                uri = uri,
                name = displayName,
                isImage = isImage,
                status = PendingAttachment.Status.Copying
            )
            _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + pending) }

            val job = viewModelScope.launch(Dispatchers.IO) {
                var cacheFile: File? = null
                try {
                    val uploadsDir = File(context.cacheDir, "uploads").apply { mkdirs() }
                    cacheFile = File(uploadsDir, "${System.currentTimeMillis()}_$displayName")

                    contentResolver.openInputStream(uri)?.use { input ->
                        cacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IOException("Cannot open input stream for $uri")

                    _uiState.update { state ->
                        state.copy(
                            pendingAttachments = state.pendingAttachments.map {
                                if (it.localId == localId) it.copy(status = PendingAttachment.Status.Uploading(0)) else it
                            }
                        )
                    }

                    var lastProgressTime = 0L
                    var lastPercent = 0

                    val response = apiService.uploadFile(
                        file = cacheFile,
                        mimeType = mimeType,
                        onProgress = { bytesWritten, totalLength ->
                            if (totalLength > 0) {
                                val percent = ((bytesWritten * 100) / totalLength).toInt().coerceIn(0, 100)
                                val now = System.currentTimeMillis()
                                if (percent != lastPercent && (now - lastProgressTime >= 100 || (percent - lastPercent) >= 5 || percent == 100)) {
                                    lastPercent = percent
                                    lastProgressTime = now
                                    _uiState.update { state ->
                                        state.copy(
                                            pendingAttachments = state.pendingAttachments.map {
                                                if (it.localId == localId) it.copy(status = PendingAttachment.Status.Uploading(percent)) else it
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )

                    val attachment = FileAttachment(
                        id = response.id,
                        name = displayName,
                        mimeType = mimeType,
                        sizeBytes = cacheFile.length().takeIf { it > 0 }
                    )

                    _uiState.update { state ->
                        state.copy(
                            pendingAttachments = state.pendingAttachments.map {
                                if (it.localId == localId) it.copy(status = PendingAttachment.Status.Done(attachment)) else it
                            }
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _uiState.update { state ->
                        state.copy(
                            pendingAttachments = state.pendingAttachments.map {
                                if (it.localId == localId) it.copy(status = PendingAttachment.Status.Failed(e.message ?: "Upload failed")) else it
                            }
                        )
                    }
                } finally {
                    cacheFile?.delete()
                }
            }
            uploadJobs[localId] = job
        }
    }

    private fun handleRemoveAttachment(localId: String) {
        uploadJobs[localId]?.cancel()
        uploadJobs.remove(localId)
        _uiState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.localId == localId })
        }
    }

    private fun queryFileName(resolver: ContentResolver, uri: Uri): String? {
        return if (uri.scheme == "content") {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) cursor.getString(index) else null
                } else null
            }
        } else {
            uri.path?.let { File(it).name }
        }
    }

    private fun handleSend(text: String) {
        val current = _uiState.value
        if (text.isBlank() || current.isStreaming) return
        if (current.pendingAttachments.any { it.status is PendingAttachment.Status.Copying || it.status is PendingAttachment.Status.Uploading }) return

        val completedAttachments = current.pendingAttachments.mapNotNull {
            (it.status as? PendingAttachment.Status.Done)?.attachment
        }
        uploadJobs.values.forEach { it.cancel() }
        uploadJobs.clear()
        _uiState.update { it.copy(pendingAttachments = emptyList()) }

        launchSend(
            chatId = current.chatId,
            prompt = text,
            attachments = completedAttachments,
            createIfMissing = true
        )
    }

    private fun handleConfirmEdit(newText: String) {
        val state = _uiState.value
        val target = state.editingMessage ?: return
        val chatId = state.chatId ?: return
        if (newText.isBlank() || state.isStreaming) return
        _uiState.update { it.copy(editingMessage = null) }
        viewModelScope.launch {
            repository.deleteFromMessage(chatId, target.id)   // target + everything after
            launchSend(chatId = chatId, prompt = newText, attachments = emptyList(), createIfMissing = false)
        }
    }

    private fun handleRegenerate(message: Message) {
        val state = _uiState.value
        val chatId = state.chatId ?: return
        if (state.isStreaming) return
        viewModelScope.launch {
            repository.deleteFromMessage(chatId, message.id)  // partial/reply + everything after
            launchSend(chatId = chatId, prompt = null, attachments = emptyList(), createIfMissing = false)
        }
    }

    /**
     * Single pipeline for send / edit-resend / regenerate / retry.
     * [prompt] == null → history already ends at a user message (regenerate/retry path).
     */
    private fun launchSend(
        chatId: String?,
        prompt: String?,
        attachments: List<FileAttachment> = emptyList(),
        createIfMissing: Boolean
    ) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            var effectiveChatId = chatId
            if (effectiveChatId == null && createIfMissing) {
                val promptText = prompt ?: return@launch
                effectiveChatId = repository.createChat(promptText)
                _uiState.update {
                    it.copy(
                        chatId = effectiveChatId,
                        chatTitle = promptText.lineSequence().firstOrNull()
                            ?.take(28).orEmpty().ifBlank { "New Chat" }
                    )
                }
                observeChat(effectiveChatId)
            }
            if (effectiveChatId == null) return@launch

            prompt?.let {
                repository.persistUserMessage(
                    chatId = effectiveChatId,
                    content = it,
                    attachments = attachments
                )
            }
            _uiState.update {
                it.copy(
                    isStreaming = true,
                    liveContent = "",
                    liveReasoning = "",
                    awaitingHandoff = false,
                    unreadWhileScrolledUp = 0,
                    bannerError = null
                )
            }
            startStream(effectiveChatId, attachments)
        }
    }

    private suspend fun startStream(chatId: String, attachments: List<FileAttachment> = emptyList()) {
        val state = _uiState.value
        Log.i("BleedAI-ChatVM", "startStream() -> Starting completion stream for chatId=$chatId, model=${state.selectedModel}")
        repository.streamCompletion(
            chatId = chatId,
            model = state.selectedModel,
            webSearch = state.webSearch,
            deepThinking = state.deepThinking,
            attachments = attachments
        ).collect { event ->
            when (event) {
                is StreamEvent.ReasoningDelta -> {
                    Log.d("BleedAI-ChatVM", "Live Reasoning chunk: ${event.text.take(40)}...")
                    _uiState.update { it.copy(liveReasoning = it.liveReasoning + event.text) }
                }
                is StreamEvent.ContentDelta -> {
                    Log.d("BleedAI-ChatVM", "Live Content chunk: ${event.text.take(40)}...")
                    _uiState.update {
                        it.copy(
                            liveContent = it.liveContent + event.text,
                            unreadWhileScrolledUp =
                                if (isScrolledUp) it.unreadWhileScrolledUp + 1 else 0
                        )
                    }
                }
                is StreamEvent.Citations -> {
                    Log.d("BleedAI-ChatVM", "Live Citations: ${event.citations.size} items")
                }
                is StreamEvent.Usage -> {
                    Log.d("BleedAI-ChatVM", "Live Usage: ${event.usage.totalTokens} tokens")
                }
                is StreamEvent.Done -> {
                    Log.i("BleedAI-ChatVM", "Stream completed (Done event received)")
                    val hasText = _uiState.value.let {
                        it.liveContent.isNotBlank() || it.liveReasoning.isNotBlank()
                    }
                    _uiState.update {
                        if (hasText) {
                            it.copy(awaitingHandoff = true)   // keep visible → Room swap
                        } else {
                            it.copy(
                                isStreaming = false,
                                bannerError = "Empty response from model"
                            )
                        }
                    }
                }
                is StreamEvent.Error -> {
                    Log.e("BleedAI-ChatVM", "Stream error: ${event.throwable.message}")
                    val hasPartial = event.partialContent.isNotEmpty() ||
                        event.partialReasoning.isNotEmpty()
                    // Prefer the backend's own rejection text (in-stream error
                    // payloads are now surfaced by the SSE reader) over the old
                    // generic guess, so the user sees WHY it failed.
                    val backendDetail = event.throwable.message
                        ?.trim()
                        ?.take(200)
                        ?.takeIf { it.isNotBlank() }
                    _uiState.update {
                        if (hasPartial) {
                            it.copy(awaitingHandoff = true, bannerError = "Stream interrupted")
                        } else {
                            it.copy(
                                isStreaming = false,
                                liveContent = "",
                                liveReasoning = "",
                                bannerError = backendDetail
                                    ?: "Connection failed — is a session token set?"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun stopStreaming() {
        streamJob?.cancel()
        streamJob = null
        // Manual stop = discard. (Network-failure partials are persisted by the
        // repository; deliberate choice — a stop mid-word shouldn't save a stub.)
        _uiState.update {
            it.copy(
                isStreaming = false,
                liveContent = "",
                liveReasoning = "",
                awaitingHandoff = false
            )
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        uploadJobs.values.forEach { it.cancel() }
        uploadJobs.clear()
        super.onCleared()
    }
}
