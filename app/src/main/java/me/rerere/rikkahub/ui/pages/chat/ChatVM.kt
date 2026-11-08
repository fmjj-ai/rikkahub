package me.rerere.rikkahub.ui.pages.chat

import me.rerere.rikkahub.data.datastore.updateAssistant
import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.sample
import me.rerere.ai.ui.ContextUsage
import me.rerere.ai.ui.contextUsage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.NodeFavoriteTarget
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.service.ChatError
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.utils.isAllowedFileType
import me.rerere.rikkahub.ui.hooks.DraftAttachmentImporter
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.UpdateChecker
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "ChatVM"

class ChatVM(
    id: String,
    private val context: Application,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val chatService: ChatService,
    val updateChecker: UpdateChecker,
    private val analytics: FirebaseAnalytics,
    private val filesManager: FilesManager,
    private val favoriteRepository: FavoriteRepository,
) : ViewModel() {
    private val _conversationId: Uuid = Uuid.parse(id)
    val conversation: StateFlow<Conversation> = chatService.getConversationFlow(_conversationId)
    var chatListInitialized by mutableStateOf(false) // 聊天列表是否已经滚动到底部

    // 聊天输入状态 - 保存在 ViewModel 中避免 TransactionTooLargeException
    val inputState = ChatInputState()

    private val _attachmentImportErrors = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val attachmentImportErrors: SharedFlow<String> = _attachmentImportErrors
    private val attachmentImporter = DraftAttachmentImporter(
        state = inputState,
        scope = viewModelScope,
        copyImage = { source ->
            val saved = filesManager.saveManagedFromUri(
                me.rerere.rikkahub.data.files.FileFolders.UPLOAD, source.toUri())
            filesManager.getFile(saved).toUri().toString()
        },
        discardFile = { url -> withContext(Dispatchers.IO) { filesManager.deleteChatFiles(listOf(url.toUri())) } },
        onError = { _attachmentImportErrors.tryEmit(it); Unit },
    )

    fun addGalleryImages(uris: List<android.net.Uri>) = attachmentImporter.add(uris.map { it.toString() })

    fun toggleGalleryImage(uri: android.net.Uri) = attachmentImporter.toggle(uri.toString())

    fun addAttachments(
        uris: List<android.net.Uri>,
        kind: ChatAttachmentKind,
        onImported: (UIMessagePart?) -> Unit = {},
    ) {
        uris.forEach { uri ->
            attachmentImporter.addAttachment(copy = { copyAttachment(uri, kind) }, onImported = onImported)
        }
    }

    fun addPastedText(text: String) {
        attachmentImporter.addAttachment(copy = {
            val saved = filesManager.saveManagedText(FileFolders.UPLOAD, text)
            UIMessagePart.Document(
                url = filesManager.getFile(saved).toUri().toString(),
                fileName = saved.displayName,
                mime = saved.mimeType,
            )
        })
    }

    private suspend fun copyAttachment(uri: android.net.Uri, requestedKind: ChatAttachmentKind): UIMessagePart =
        withContext(Dispatchers.IO) {
            val name = filesManager.getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
            val mime = filesManager.getFileMimeType(uri)
                ?: android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: if (requestedKind == ChatAttachmentKind.DOCUMENT) "text/plain" else "application/octet-stream"
            val kind = if (requestedKind == ChatAttachmentKind.AUTO) {
                when {
                    mime.startsWith("image/") -> ChatAttachmentKind.IMAGE
                    mime.startsWith("video/") -> ChatAttachmentKind.VIDEO
                    mime.startsWith("audio/") -> ChatAttachmentKind.AUDIO
                    else -> ChatAttachmentKind.DOCUMENT
                }
            } else requestedKind
            if (kind == ChatAttachmentKind.DOCUMENT && !isAllowedFileType(name, mime)) {
                error("不支持的文件类型：$name")
            }
            val saved = filesManager.saveManagedFromUri(FileFolders.UPLOAD, uri, name, mime)
            val url = filesManager.getFile(saved).toUri().toString()
            when (kind) {
                ChatAttachmentKind.IMAGE -> UIMessagePart.Image(url)
                ChatAttachmentKind.VIDEO -> UIMessagePart.Video(url)
                ChatAttachmentKind.AUDIO -> UIMessagePart.Audio(url)
                ChatAttachmentKind.DOCUMENT -> UIMessagePart.Document(url, saved.displayName, saved.mimeType)
                ChatAttachmentKind.AUTO -> error("未解析的附件类型")
            }
        }

    /** 拍照文件移交草稿导入器；切换草稿后拒收，复制完成或取消后删除本次临时原片。 */
    fun addCameraImage(file: java.io.File, expectedRevision: Long, onImported: (String?) -> Unit = {}) {
        if (inputState.attachmentRevision != expectedRevision) {
            file.delete()
            onImported(null)
            return
        }
        attachmentImporter.add(listOf(file.toUri().toString()), onImported = { _, url -> onImported(url) }) {
            withContext(Dispatchers.IO) { file.delete() }
        }
    }

    // 异步任务 (从ChatService获取，响应式)
    val conversationJob: StateFlow<Job?> =
        chatService
            .getGenerationJobStateFlow(_conversationId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val processingStatus: StateFlow<String?> =
        chatService
            .getProcessingStatusFlow(_conversationId)

    val conversationJobs = chatService
        .getConversationJobs()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    init {
        // 添加对话引用
        chatService.addConversationReference(_conversationId)

        // 初始化对话
        viewModelScope.launch {
            chatService.initializeConversation(_conversationId)
        }

        // 记住对话ID, 方便下次启动恢复
        context.writeStringPreference("lastConversationId", _conversationId.toString())
    }

    override fun onCleared() {
        super.onCleared()
        // 移除对话引用
        chatService.removeConversationReference(_conversationId)
    }

    // 用户设置
    val settings: StateFlow<Settings> =
        settingsStore.settingsFlow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.dummy())

    // 网络搜索(每个助手独立)
    val enableWebSearch = settings.map {
        it.getCurrentAssistant().enableWebSearch
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // 当前模型
    val currentChatModel = settings.map { settings ->
        settings.getCurrentChatModel()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    // 大上下文的估算放在后台，并限制流式生成时的刷新频率。
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val contextUsage = combine(conversation, settings) { conversation, settings -> conversation to settings }
        .sample(500)
        .mapLatest { (conversation, settings) ->
            withContext(Dispatchers.Default) {
                val model = settings.getCurrentChatModel() ?: return@withContext ContextUsage()
                val assistant = settings.getCurrentAssistant()
                val system = if (assistant.allowConversationSystemPrompt && !conversation.customSystemPrompt.isNullOrBlank()) {
                    conversation.customSystemPrompt.orEmpty()
                } else assistant.systemPrompt
                conversation.currentMessages.contextUsage(model, system, assistant.contextMessageLimit)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContextUsage())

    // 错误状态
    val errors: StateFlow<List<ChatError>> = chatService.errors

    fun dismissError(id: Uuid) = chatService.dismissError(id)

    fun clearAllErrors() = chatService.clearAllErrors()

    // 生成完成
    val generationDoneFlow: SharedFlow<Uuid> = chatService.generationDoneFlow

    // MCP管理器
    val mcpManager = chatService.mcpManager

    // 更新设置
    fun updateSettings(update: (Settings) -> Settings) {
        viewModelScope.launch {
            settingsStore.updateWithAppearanceCleanup(
                fileIdentity = filesManager::canonicalOwnedFileUrl,
                deleteFiles = { urls -> filesManager.deleteChatFiles(urls.map { it.toUri() }) },
                fn = update,
            )
        }
    }

    fun updateAssistant(assistantId: Uuid, update: (Assistant) -> Assistant) {
        updateSettings { it.updateAssistant(assistantId, update) }
    }

    // 检查用户头像删除


    // 设置聊天模型
    fun setChatModel(assistant: Assistant, model: Model) {
        viewModelScope.launch {
            settingsStore.update { settings ->
                settings.copy(
                    assistants = settings.assistants.map {
                        if (it.id == assistant.id) {
                            it.copy(
                                chatModelId = model.id
                            )
                        } else {
                            it
                        }
                    })
            }
        }
    }

    // Update checker
    val updateState =
        updateChecker.checkUpdate().stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    /**
     * 处理消息发送
     *
     * @param content 消息内容
     * @param answer 是否触发消息生成，如果为false，则仅添加消息到消息列表中
     */
    fun handleMessageSend(content: List<UIMessagePart>,answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        analytics.logEvent("ai_send_message", null)

        chatService.sendMessage(_conversationId, content, answer)
    }

    fun handleMessageEdit(parts: List<UIMessagePart>, messageId: Uuid) {
        if (parts.isEmptyInputMessage()) return
        analytics.logEvent("ai_edit_message", null)

        viewModelScope.launch {
            chatService.editMessage(_conversationId, messageId, parts)
        }
    }

    fun handleCompressContext(additionalPrompt: String, targetTokens: Int, keepRecentMessages: Int): Job {
        return chatService.compressConversation(_conversationId, additionalPrompt, targetTokens, keepRecentMessages)
    }

    /** 聊天页直接压缩早期历史，并至少保留最新一轮。 */
    fun handleImmediateCompressContext(): Job {
        return handleCompressContext(
            additionalPrompt = "",
            targetTokens = 2_000,
            keepRecentMessages = 4,
        )
    }

    suspend fun forkMessage(message: UIMessage): Conversation {
        return chatService.forkConversationAtMessage(_conversationId, message.id)
    }

    fun deleteMessage(message: UIMessage) {
        viewModelScope.launch {
            chatService.deleteMessage(_conversationId, message)
        }
    }

    fun showDeleteBlockedWhileGeneratingError() {
        chatService.addError(
            error = IllegalStateException("请先停止生成再删除消息"),
            conversationId = _conversationId,
            title = context.getString(R.string.error_title_operation)
        )
    }

    fun regenerateAtMessage(
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        analytics.logEvent("ai_regenerate_at_message", null)
        chatService.regenerateAtMessage(_conversationId, message, regenerateAssistantMsg)
    }

    fun handleToolApproval(
        toolCallId: String,
        approved: Boolean,
        reason: String = ""
    ) {
        analytics.logEvent("ai_tool_approval", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved, reason)
    }

    fun handleToolAnswer(
        toolCallId: String,
        answer: String,
    ) {
        analytics.logEvent("ai_tool_answer", null)
        chatService.handleToolApproval(_conversationId, toolCallId, approved = true, answer = answer)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatService.stopGeneration(_conversationId)
        }
    }

    fun saveConversationAsync() {
        viewModelScope.launch {
            chatService.saveCurrentConversation(_conversationId)
        }
    }

    fun updateConversationMetadata(update: (Conversation) -> Conversation) {
        viewModelScope.launch {
            chatService.updateConversationMetadataAndSave(_conversationId, update = update)
        }
    }

    fun updateTitle(title: String) {
        updateConversationMetadata { it.copy(title = title) }
    }

    fun deleteConversation(conversation: Conversation) {
        viewModelScope.launch {
            conversationRepo.deleteConversation(conversation)
        }
    }

    fun updatePinnedStatus(conversation: Conversation) {
        viewModelScope.launch {
            chatService.updateConversationMetadataAndSave(conversation.id, createIfMissing = false) {
                it.copy(isPinned = !it.isPinned)
            }
        }
    }

    fun moveConversationToAssistant(conversation: Conversation, targetAssistantId: Uuid) {
        viewModelScope.launch {
            // 其它客户端也可能持有这个会话，统一通过 Service 同步元数据。
            chatService.updateConversationMetadataAndSave(conversation.id, createIfMissing = false) {
                it.copy(assistantId = targetAssistantId, folderId = null)
            }
            if (conversation.id == _conversationId) settingsStore.updateAssistant(targetAssistantId)
        }
    }

    fun translateMessage(message: UIMessage, targetLanguage: Locale) {
        chatService.translateMessage(_conversationId, message, targetLanguage)
    }

    fun generateTitle(conversation: Conversation, force: Boolean = false) {
        viewModelScope.launch {
            val conversationFull = conversationRepo.getConversationById(conversation.id) ?: return@launch
            chatService.generateTitle(_conversationId, conversationFull, force)
        }
    }

    fun generateSuggestion(conversation: Conversation) {
        viewModelScope.launch {
            chatService.generateSuggestion(_conversationId, conversation)
        }
    }

    fun clearTranslationField(messageId: Uuid) {
        chatService.clearTranslationField(_conversationId, messageId)
    }

    fun updateConversation(update: (Conversation) -> Conversation) {
        chatService.updateConversationState(_conversationId, update)
    }

    fun toggleMessageFavorite(node: MessageNode) {
        viewModelScope.launch {
            val currentlyFavorited = favoriteRepository.isNodeFavorited(_conversationId, node.id)
            if (currentlyFavorited) {
                favoriteRepository.removeNodeFavorite(_conversationId, node.id)
            } else {
                favoriteRepository.addNodeFavorite(
                    NodeFavoriteTarget(
                        conversationId = _conversationId,
                        conversationTitle = conversation.value.title,
                        nodeId = node.id,
                        node = node
                    )
                )
            }

            chatService.updateConversationState(_conversationId) { currentConversation ->
                currentConversation.copy(
                    messageNodes = currentConversation.messageNodes.map { existingNode ->
                        if (existingNode.id == node.id) {
                            existingNode.copy(isFavorite = !currentlyFavorited)
                        } else {
                            existingNode
                        }
                    }
                )
            }
        }
    }

}
