package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import kotlin.uuid.Uuid

/** 已提交的字段差异可重复应用，冷 Session 初始化时不会把 toggle 再执行一次。 */
internal class ConversationMetadataChange(
    private val before: Conversation,
    private val after: Conversation,
) {
    init {
        require(after.id == before.id && after.messageNodes === before.messageNodes) {
            "元数据更新不得替换消息节点或会话 ID"
        }
    }

    fun applyTo(current: Conversation): Conversation {
        require(current.id == before.id)
        return current.copy(
            title = changed(before.title, after.title, current.title),
            createAt = changed(before.createAt, after.createAt, current.createAt),
            updateAt = changed(before.updateAt, after.updateAt, current.updateAt),
            assistantId = changed(before.assistantId, after.assistantId, current.assistantId),
            chatSuggestions = changed(before.chatSuggestions, after.chatSuggestions, current.chatSuggestions),
            isPinned = changed(before.isPinned, after.isPinned, current.isPinned),
            customSystemPrompt = changed(before.customSystemPrompt, after.customSystemPrompt, current.customSystemPrompt),
            modeInjectionIds = changed(before.modeInjectionIds, after.modeInjectionIds, current.modeInjectionIds),
            lorebookIds = changed(before.lorebookIds, after.lorebookIds, current.lorebookIds),
            workspaceCwd = changed(before.workspaceCwd, after.workspaceCwd, current.workspaceCwd),
            folderId = changed(before.folderId, after.folderId, current.folderId),
        )
    }

    private fun <T> changed(old: T, updated: T, current: T): T = if (old != updated) updated else current
}

/** 目标检查必须留在写入事务内，不能沿用界面检查后、排队保存前的文件夹快照。 */
internal suspend fun validateConversationFolderChange(
    before: Conversation,
    after: Conversation,
    findFolderOwner: suspend (Uuid) -> String?,
) {
    val folderId = after.folderId ?: return
    if (folderId == before.folderId && after.assistantId == before.assistantId) return
    val owner = findFolderOwner(folderId) ?: throw NotFoundException("Folder not found")
    if (owner != after.assistantId.toString()) {
        throw BadRequestException("Folder belongs to another assistant")
    }
}
