package me.rerere.rikkahub.service

import me.rerere.rikkahub.data.repository.ConversationMetadataChange
import me.rerere.rikkahub.utils.mapPreservingIdentity
import me.rerere.rikkahub.data.model.removedFileUrlsSince
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.ai.ui.compressionTarget
import me.rerere.ai.ui.compressContextText
import me.rerere.ai.ui.compressionText
import me.rerere.ai.ui.contextMessages
import me.rerere.ai.ui.contextFingerprint
import me.rerere.ai.ui.estimateTokens
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.resolveEndpoint
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.insertContextCheckpoint
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckFastModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // ---- 后台生成保活 ----

    private val keepAliveLock = Any()
    private data class KeepAliveRequest(
        val generationId: Uuid,
        val conversationId: Uuid,
        var acquired: Boolean = false,
    )
    private val keepAliveJobs = ConcurrentHashMap<Job, KeepAliveRequest>()

    // 关闭时释放全部租约；运行中重新开启时为尚未结束的任务重新申请。
    init {
        appScope.launch {
            settingsStore.settingsFlow
                .distinctUntilChangedBy { it.displaySetting.enableBackgroundKeepAlive }
                .collect { settings ->
                    synchronized(keepAliveLock) {
                        keepAliveJobs.forEach { (job, request) ->
                            if (settings.displaySetting.enableBackgroundKeepAlive && !job.isCompleted) {
                                acquireKeepAliveLocked(request)
                            } else {
                                releaseKeepAliveLocked(request)
                            }
                        }
                    }
                }
        }
    }

    private fun Job.trackKeepAlive(conversationId: Uuid): Job {
        val request = KeepAliveRequest(Uuid.random(), conversationId)
        synchronized(keepAliveLock) {
            if (isCompleted) return this
            keepAliveJobs[this] = request
            if (settingsStore.settingsFlow.value.displaySetting.enableBackgroundKeepAlive) {
                acquireKeepAliveLocked(request)
            }
        }
        invokeOnCompletion {
            synchronized(keepAliveLock) {
                keepAliveJobs.remove(this)?.let(::releaseKeepAliveLocked)
            }
        }
        return this
    }

    private fun acquireKeepAliveLocked(request: KeepAliveRequest) {
        if (!request.acquired) {
            request.acquired = ChatGenerationService.acquire(context, request.generationId, request.conversationId)
        }
    }

    private fun releaseKeepAliveLocked(request: KeepAliveRequest) {
        if (request.acquired) {
            request.acquired = false
            ChatGenerationService.release(context, request.generationId)
        }
    }

    internal fun cancelBackgroundGenerations(generationIds: Set<String>) {
        keepAliveJobs.entries.filter { it.value.generationId.toString() in generationIds }
            .forEach { it.key.cancel() }
    }

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update {
            it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution)
        }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    fun cleanup() = runCatching {
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }.trackKeepAlive(conversationId)

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun configureEnglishSession(
        conversationId: Uuid,
        assistantId: Uuid,
        modelId: Uuid?,
        reasoningEnabled: Boolean,
    ) {
        val session = getOrCreateSession(conversationId)
        session.englishAIConfig = EnglishSessionAIConfig(
            modelId = modelId,
            reasoningEnabled = reasoningEnabled,
        )
        updateConversationState(conversationId) { it.copy(assistantId = assistantId) }
    }

    fun isGenerating(conversationId: Uuid): Boolean {
        return sessions[conversationId]?.isGenerating == true
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            val conversation = loadSession(session)
            if (session.englishAIConfig == null) {
                settingsStore.updateAssistant(conversation.assistantId)
            }
        }
    }

    /** 读写入口等待完整历史，但不触发导航所需的全局助手切换。 */
    suspend fun getInitializedConversation(conversationId: Uuid): Conversation {
        val session = getOrCreateSession(conversationId)
        return session.withRefSuspend { loadSession(session) }
    }

    private suspend fun loadSession(session: ConversationSession): Conversation = session.initialize {
        conversationRepo.getConversationById(session.id) ?: run {
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            Conversation.ofId(
                id = session.id,
                assistantId = assistant.id,
                newConversation = true,
            ).updateCurrentMessages(assistant.presetMessages)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        previousJob?.cancel()
        val job = appScope.launch {
            try {
                runCatching { previousJob?.join() }
                getInitializedConversation(conversationId)
                finishInterruptedPendingTools(conversationId)
                val current = session.state.value
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(current.assistantId) ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)
                val node = UIMessage(role = MessageRole.USER, parts = processedContent).toMessageNode()
                updateConversationAndSave(conversationId) { latest ->
                    latest.copy(messageNodes = latest.messageNodes + node)
                }
                if (answer) handleMessageComplete(conversationId)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            }
        }
        session.setJob(job.trackKeepAlive(conversationId))
    }

    private fun preprocessUserInputParts(parts: List<UIMessagePart>, assistant: Assistant): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()
        val job = appScope.launch {
            try {
                val conversation = getInitializedConversation(conversationId)
                if (message.role == MessageRole.USER) {
                    updateConversationAndSave(conversationId) { current ->
                        val index = current.messageNodes.indexOfFirst { node -> node.messages.any { it.id == message.id } }
                        if (index < 0) throw NotFoundException("Message node not found")
                        current.copy(messageNodes = current.messageNodes.take(index + 1))
                    }
                    handleMessageComplete(conversationId)
                } else if (regenerateAssistantMsg) {
                    val node = conversation.getMessageNodeByMessage(message)
                    val nodeIndex = conversation.messageNodes.indexOf(node)
                    handleMessageComplete(conversationId, messageRange = 0..<nodeIndex)
                } else {
                    saveCurrentConversation(conversationId)
                }
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            }
        }
        session.setJob(job.trackKeepAlive(conversationId))
    }

    // ---- 处理工具调用审批 ----

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()
        val job = appScope.launch {
            try {
                val approval = when {
                    answer != null -> ToolApprovalState.Answered(answer)
                    approved -> ToolApprovalState.Approved
                    else -> ToolApprovalState.Denied(reason)
                }
                updateConversationAndSave(conversationId) { current ->
                    current.copy(messageNodes = current.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { message ->
                            message.copy(parts = message.parts.map { part ->
                                if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                                    part.copy(approvalState = approval)
                                } else part
                            })
                        })
                    })
                }
                val hasPendingTools = session.state.value.messageNodes.any { node ->
                    node.currentMessage.parts.any { it is UIMessagePart.Tool && it.isPending }
                }
                if (!hasPendingTools) handleMessageComplete(conversationId)
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            }
        }
        session.setJob(job.trackKeepAlive(conversationId))
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null
    ) {
        getInitializedConversation(conversationId)
        val settings = settingsStore.settingsFlow.first()
        val session = getOrCreateSession(conversationId)
        val initialConversation = session.state.value
        val englishAIConfig = session.englishAIConfig
        val baseAssistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val assistant = englishAIConfig?.let { config ->
            baseAssistant.copy(
                chatModelId = config.modelId ?: baseAssistant.chatModelId,
                reasoningLevel = if (config.reasoningEnabled) {
                    baseAssistant.reasoningLevel
                } else {
                    ReasoningLevel.OFF
                },
            )
        } ?: baseAssistant
        val model = settings.findModelById(englishAIConfig?.modelId ?: assistant.chatModelId ?: settings.chatModelId)
            ?: return

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        runCatching {

            // reset suggestions
            updateConversationState(conversationId) { it.copy(chatSuggestions = emptyList()) }

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty() ||
                    assistant.localTools.contains(LocalToolOption.FunctionGraph)) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // start generating
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                compressContext = { messages ->
                    if (messages.compressionTarget() == null) {
                        messages
                    } else {
                        session.processingStatus.value = context.getString(R.string.chat_page_compressing)
                        try {
                            val compacted = compactMessages(messages, assistant, model, conversation = conversation)
                            saveCompactedMessages(conversationId, messages, compacted)
                            compacted
                        } finally {
                            session.processingStatus.value = null
                        }
                    }
                },
                tools = createChatTools(settings, assistant, conversation.workspaceCwd),
            ).onCompletion {
                // 可能被取消了，或者意外结束，兜底更新
                updateConversationState(conversationId) { current ->
                    val nodes = current.messageNodes.mapPreservingIdentity { node ->
                        val messages = node.messages.mapPreservingIdentity { it.finishReasoning() }
                        if (messages === node.messages) node else node.copy(messages = messages)
                    }
                    current.copy(messageNodes = nodes, updateAt = Instant.now())
                }
                val updatedConversation = getConversationFlow(conversationId).value

                // 生成结束：取消 Live Update 通知，后台时发送完成通知
                appEventBus.emit(
                    AppEvent.ChatGenerationEnded(
                        conversationId = conversationId,
                        senderName = senderName,
                        contentPreview = updatedConversation.currentMessages.lastOrNull()
                            ?.toText()?.take(50)?.trim() ?: "",
                    )
                )
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        updateConversationState(conversationId) { current ->
                            current.updateCurrentMessages(chunk.messages)
                        }

                        // 通知等边缘副作用由 ChatNotificationManager 消费；
                        // tryEmit 不挂起，事件丢失只影响单次通知更新，不能反压生成链
                        chunk.messages.lastOrNull()?.let { lastMessage ->
                            appEventBus.tryEmit(
                                AppEvent.ChatGenerationUpdate(conversationId, lastMessage, senderName)
                            )
                        }
                    }
                }
            }
        }.onFailure {
            // 兜底取消 Live Update 通知（生成开始前失败时 onCompletion 不会执行）
            appEventBus.tryEmit(AppEvent.ChatGenerationEnded(conversationId, senderName, null))

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            saveCurrentConversation(conversationId)
            val finalConversation = getConversationFlow(conversationId).value

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    // ---- 检查无效消息 ----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        updateConversationAndSave(conversationId) { current ->
            val lastNode = current.messageNodes.lastOrNull() ?: return@updateConversationAndSave current
            val lastMessage = lastNode.currentMessage
            val updated = lastMessage.finishPendingTools(::cancelToolByUser)
            if (updated == lastMessage) return@updateConversationAndSave current
            current.copy(messageNodes = current.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { if (it.id == lastMessage.id) updated else it },
            ))
        }
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.requireFastModel()
            val provider = model.resolveEndpoint(settings.providers) ?: error("快速模型的提供商或协议未配置")

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
            )

            if (conversationId != conversation.id || !conversationRepo.existsConversationById(conversationId)) return
            val title = result.choices.firstOrNull()?.message?.toText()?.trim().orEmpty()
            updateConversationMetadataAndSave(conversationId, createIfMissing = false) { it.copy(title = title) }
        }.onFailure {
            if (it is CancellationException) throw it
            it.printStackTrace()
            addError(
                error = it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_generate_title),
                solution = ChatErrorSolution.CheckFastModelSettings,
            )
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.requireFastModel()
            val provider = model.resolveEndpoint(settings.providers) ?: error("快速模型的提供商或协议未配置")

            sessions[conversationId]?.let {
                updateConversationState(conversationId) { it.copy(chatSuggestions = emptyList()) }
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model, settings.fastModelReasoningLevel),
            )
            val suggestions =
                result.choices.firstOrNull()?.message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            if (conversationId != conversation.id || !conversationRepo.existsConversationById(conversationId)) return
            updateConversationMetadataAndSave(conversationId, createIfMissing = false) {
                it.copy(chatSuggestions = suggestions.take(10))
            }
        }.onFailure {
            if (it is CancellationException) throw it
            addError(it, conversationId, "生成聊天建议失败", ChatErrorSolution.CheckFastModelSettings)
        }
    }

    /** 正常聊天和压缩共用工具集合，保持协议请求前缀一致。 */
    private suspend fun createChatTools(settings: Settings, assistant: Assistant, cwd: String?): List<Tool> {
        val mcpTools = mcpManager.getAllAvailableTools()
        val invalidNames = mcpTools.map { it.second }.distinct().filter { name ->
            name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        }
        check(invalidNames.isEmpty()) {
            context.getString(R.string.error_mcp_invalid_server_name, invalidNames.joinToString(", "))
        }
        return buildList {
            if (assistant.enableWebSearch) addAll(createSearchTools(settings))
            addAll(localTools.getTools(assistant.localTools))
            if (assistant.enableRecentChatsReference) addAll(createConversationTools(conversationRepo, assistant.id))
            addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), cwd))
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(createSkillTools(assistant.enabledSkills, skillManager.listSkills()))
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(Tool(
                    name = "mcp__${serverName}__${tool.name}",
                    description = tool.description ?: "",
                    parameters = { tool.inputSchema },
                    needsApproval = { tool.needsApproval },
                    execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                ))
            }
        }
    }

    // ---- 压缩对话历史 ----

    fun compressConversation(
        conversationId: Uuid,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 4,
    ): Job {
        val session = getOrCreateSession(conversationId)
        if (session.isGenerating) {
            addError(IllegalStateException("请等待当前生成或压缩完成"), conversationId)
            return Job().apply { complete() }
        }
        val job = appScope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                getInitializedConversation(conversationId)
                val settings = settingsStore.settingsFlow.first()
                val conversation = session.state.value
                val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
                val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
                    ?: error("请先选择模型")
                session.processingStatus.value = context.getString(R.string.chat_page_compressing)
                val compacted = compactMessages(
                    conversation.currentMessages, assistant, model, additionalPrompt, targetTokens, keepRecentMessages,
                    conversation = conversation,
                )
                saveCompactedMessages(conversationId, conversation.currentMessages, compacted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addError(e, conversationId, context.getString(R.string.error_title_compress_conversation))
            } finally {
                session.processingStatus.value = null
            }
        }
        session.setJob(job.trackKeepAlive(conversationId))
        job.start()
        return job
    }

    private suspend fun saveCompactedMessages(
        conversationId: Uuid,
        originalMessages: List<UIMessage>,
        compactedMessages: List<UIMessage>,
    ) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val originalIds = originalMessages.mapTo(HashSet()) { it.id }
        val pointIndex = compactedMessages.indexOfFirst { it.isContextCheckpoint && it.id !in originalIds }
        check(pointIndex > 0) { "压缩没有产生有效检查点，原记录已保留" }
        updateConversationAndSave(conversationId) { current ->
            check(current.currentMessages.take(originalMessages.size).contextFingerprint() ==
                originalMessages.contextFingerprint()) { "压缩期间对话已改变，请重新压缩" }
            current.insertContextCheckpoint(
                afterMessageId = compactedMessages[pointIndex - 1].id,
                checkpoint = compactedMessages[pointIndex],
            ) ?: error("压缩期间历史或分支已改变，原记录已保留，请重新压缩")
        }
    }

    private suspend fun compactMessages(
        messages: List<UIMessage>,
        assistant: Assistant,
        chatModel: Model,
        additionalPrompt: String = "",
        targetTokens: Int = 2_000,
        keepRecentMessages: Int = 4,
        conversation: Conversation,
    ): List<UIMessage> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val originalBoundary = messages.compressionTarget(keepRecentMessages)
            ?: error("暂无可压缩的早期历史；可调整保留条数，或选择全部活动上下文压缩")
        val settings = settingsStore.settingsFlow.first()
        val prefix = messages.take(originalBoundary + 1)
        check(prefix.contextMessages().none { message -> message.getTools().any { !it.isExecuted } }) {
            "请先完成或取消待处理工具调用，再压缩这部分历史。"
        }
        // 文档在发送前会展开，必须按展开后的活动历史比较，不能拿零 token 的附件占位作基准。
        val sourceMessages = prefix.contextMessages().transforms(
            transformers = listOf(DocumentAsPromptTransformer, OcrTransformer),
            context = context,
            model = chatModel,
            assistant = assistant,
            settings = settings,
        )
        val sourceTokens = sourceMessages.estimateTokens()
        val summaryTarget = me.rerere.rikkahub.data.ai.compressionSummaryTarget(targetTokens, sourceTokens)
        val memories = if (assistant.useGlobalMemory) memoryRepository.getGlobalMemories()
            else memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
        val primarySummary = generationHandler.generateCompressionSummary(
            settings, assistant, chatModel, prefix,
            createChatTools(settings, assistant, conversation.workspaceCwd), memories, conversation,
            inputTransformers + templateTransformer + workspaceReminderTransformer,
            additionalPrompt,
        )
        fun withCheckpoint(summary: String): List<UIMessage> {
            me.rerere.rikkahub.data.ai.validateCompressionSummary(summary, sourceTokens)
            val point = UIMessage.user(summary).copy(
                isContextCheckpoint = true,
                contextCheckpointHash = prefix.contextFingerprint(),
            )
            return prefix + point + messages.drop(originalBoundary + 1)
        }
        if (primarySummary != null) return@withContext withCheckpoint(primarySummary)
        val model = settings.findModelById(settings.compressModelId) ?: chatModel
        val provider = model.resolveEndpoint(settings.providers) ?: error("找不到压缩模型的服务商或协议未配置")
        val providerHandler = providerManager.getProviderByType(provider)
        val outputBudget = summaryTarget.coerceIn(128, (model.contextWindow / 4).coerceAtLeast(128))
        fun prompt(content: String) = settings.compressPrompt.applyPlaceholders(
            "content" to content,
            "target_tokens" to summaryTarget.coerceAtMost(outputBudget).toString(),
            "additional_context" to additionalPrompt,
            "locale" to Locale.getDefault().displayName,
        )
        // 单次压缩请求的输入上限动态跟随当前聊天模型的自动压缩阈值（模型设置里的“自动压缩上下文”），
        // 每次压缩都会重新读取，用户改完设置后下次压缩立即生效；触发自动压缩时待压缩历史不会超过该阈值，
        // 因此正常情况下第一次压缩只需要一个请求，不再按固定的 32k 切块。
        val promptTokens = prompt("").estimateTokens()
        val autoCompressTokenLimit = chatModel.autoCompressThreshold
        val inputBudget = minOf(
            autoCompressTokenLimit,
            model.contextWindow - outputBudget - promptTokens - 512,
        )
        check(inputBudget >= 128) { "压缩模型的上下文容量不足，请调整模型设置" }
        // 展开完整附件和 OCR；不再把每条消息截成 2,000 字符。
        val source = sourceMessages.joinToString("\n\n") { it.compressionText() }

        suspend fun summarize(content: String): String {
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt(content))),
                params = backgroundTextGenerationParams(model, ReasoningLevel.OFF).copy(maxTokens = outputBudget),
            )
            return result.choices.firstOrNull()?.message?.toText()?.trim()?.takeIf { it.isNotBlank() }
                ?: error("压缩模型返回了空摘要，原始记录已保留")
        }

        // 顺序分块避免同时发起大量请求；多块摘要再合并，确保压缩确实降低占用。
        val summary = compressContextText(source, inputBudget, ::summarize)
        withCheckpoint(summary)
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val (previous, updated) = getOrCreateSession(conversationId).replace(conversation)
        checkFilesDelete(updated, previous)
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val (previous, updated) = getOrCreateSession(conversationId).update { current ->
            update(current).takeIf { it.id == conversationId } ?: current
        }
        checkFilesDelete(updated, previous)
    }

    /** 显式完整替换；普通 UI 修改使用 updateConversationState 传递字段变换。 */
    fun replaceConversationState(conversationId: Uuid, conversation: Conversation) {
        updateConversation(conversationId, conversation)
    }

    suspend fun saveCurrentConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            session.withPersistenceLock {
                loadSession(session)
                persistConversation(session.state.value)
            }
        }
    }

    suspend fun updateConversationAndSave(conversationId: Uuid, update: (Conversation) -> Conversation) {
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            session.withPersistenceLock {
                val before = loadSession(session)
                updateConversationState(conversationId, update)
                val updated = session.state.value
                if (updated !== before) persistConversation(updated)
            }
        }
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        updateConversationMetadataAndSave(conversationId, createIfMissing = false) { it.copy(folderId = folderId) }
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        folderRepository.deleteFolder(folderId)
        val clearMatchingFolder: (Conversation) -> Conversation = { current ->
            if (current.folderId == folderId) current.copy(folderId = null) else current
        }
        sessions.values.toList().forEach { session ->
            session.withRefSuspend {
                session.withPersistenceLock {
                    // 不按占位态提前筛选，首次加载中的会话也需要排队清空旧归属。
                    updateConversationState(session.id, clearMatchingFolder)
                    // 删除前已开始的保存可能刚刚提交；在同一保存锁内修正其父表字段。
                    conversationRepo.updateConversationMetadata(session.id, clearMatchingFolder)
                }
            }
        }
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val deletedFiles = newConversation.removedFileUrlsSince(oldConversation)
            .map { android.net.Uri.parse(it) }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            session.withPersistenceLock {
                updateConversation(conversationId, conversation)
                persistConversation(session.state.value)
            }
        }
    }

    suspend fun updateConversationMetadataAndSave(
        conversationId: Uuid,
        createIfMissing: Boolean = true,
        update: (Conversation) -> Conversation,
    ) {
        val session = getOrCreateSession(conversationId)
        session.withRefSuspend {
            session.withPersistenceLock {
                session.awaitInitializationInProgress()
                val memoryChange = if (session.isInitialized) {
                    val before = session.state.value
                    ConversationMetadataChange(before, update(before))
                } else null
                val change = conversationRepo.updateConversationMetadata(conversationId) { current ->
                    val updated = update(current)
                    memoryChange?.applyTo(updated) ?: updated
                }
                if (change != null) {
                    // 同时保留尚未落盘的字段变更和显式赋值；冷态排队的仍是幂等 patch。
                    updateConversationState(conversationId) { current ->
                        val updated = change.applyTo(current)
                        memoryChange?.applyTo(updated) ?: updated
                    }
                } else if (createIfMissing) {
                    loadSession(session)
                    updateConversationState(conversationId) { current ->
                        update(current).also {
                            require(it.id == current.id && it.messageNodes === current.messageNodes)
                        }
                    }
                    persistConversation(session.state.value)
                }
            }
        }
    }

    private suspend fun persistConversation(conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) return
        if (!exists) {
            conversationRepo.insertConversation(conversation)
        } else {
            conversationRepo.updateConversation(conversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveCurrentConversation(conversationId)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }.trackKeepAlive(conversationId)
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(conversationId: Uuid, messageId: Uuid, parts: List<UIMessagePart>) {
        if (parts.isEmptyInputMessage()) return
        val conversation = getInitializedConversation(conversationId)
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(conversation.assistantId) ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        updateConversationAndSave(conversationId) { current ->
            current.copy(messageNodes = current.messageNodes.map { node ->
                if (node.messages.none { it.id == messageId }) node else node.copy(
                    messages = node.messages + UIMessage(role = node.role, parts = processedParts),
                    selectIndex = node.messages.size,
                )
            })
        }
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getInitializedConversation(conversationId)
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
        )

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(conversationId: Uuid, nodeId: Uuid, selectIndex: Int) {
        updateConversationAndSave(conversationId) { current ->
            val target = current.messageNodes.firstOrNull { it.id == nodeId }
                ?: throw NotFoundException("Message node not found")
            if (selectIndex !in target.messages.indices) throw BadRequestException("Invalid selectIndex")
            if (target.selectIndex == selectIndex) current else current.copy(
                messageNodes = current.messageNodes.map { if (it.id == nodeId) it.copy(selectIndex = selectIndex) else it },
            )
        }
    }

    suspend fun deleteMessage(conversationId: Uuid, messageId: Uuid, failIfMissing: Boolean = true) {
        updateConversationAndSave(conversationId) { current ->
            buildConversationAfterMessageDelete(current, messageId) ?: if (failIfMissing) {
                throw NotFoundException("Message not found")
            } else current
        }
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val job = sessions[conversationId]?.getJob() ?: return
        job.cancel()
        runCatching { job.join() }
        finishInterruptedPendingTools(conversationId)
    }
}
