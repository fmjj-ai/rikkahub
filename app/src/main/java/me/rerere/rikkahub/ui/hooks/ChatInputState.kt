package me.rerere.rikkahub.ui.hooks

import android.net.Uri
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

class ChatInputState {
    val textContent = TextFieldState()
    var messageContent by mutableStateOf(listOf<UIMessagePart>())
    var editingMessage by mutableStateOf<Uuid?>(null)
    private var editingParts: List<UIMessagePart>? = null
    private var editingAttachmentUrls: Set<String> = emptySet()
    private var galleryImages by mutableStateOf<Map<String, GalleryImageSelection>>(emptyMap())
    private var pendingAttachmentImports by mutableStateOf<Set<Uuid>>(emptySet())
    internal var attachmentRevision: Long = 0
        private set

    internal val selectedGallerySources: Set<String>
        get() = galleryImages.filterValues { selection ->
            selection.localUrl == null || messageContent.any { it is UIMessagePart.Image && it.url == selection.localUrl }
        }.keys

    internal val pendingGalleryImageCount: Int get() = galleryImages.values.count { it.localUrl == null }
    internal val pendingAttachmentCount: Int get() = pendingAttachmentImports.size

    fun clearInput() {
        attachmentRevision++
        textContent.setTextAndPlaceCursorAtEnd("")
        messageContent = emptyList()
        editingMessage = null
        editingParts = null
        editingAttachmentUrls = emptySet()
        galleryImages = emptyMap()
        pendingAttachmentImports = emptySet()
    }

    fun isEditing() = editingMessage != null

    fun setMessageText(text: String) {
        textContent.setTextAndPlaceCursorAtEnd(text)
    }

    fun appendText(content: String) {
        textContent.setTextAndPlaceCursorAtEnd(textContent.text.toString() + content)
    }

    fun setContents(contents: List<UIMessagePart>) {
        attachmentRevision++
        galleryImages = emptyMap()
        pendingAttachmentImports = emptySet()
        val lastTextIndex = contents.indexOfLast { it is UIMessagePart.Text }
        val text = if (lastTextIndex >= 0) {
            (contents[lastTextIndex] as UIMessagePart.Text).text
        } else {
            ""
        }
        textContent.setTextAndPlaceCursorAtEnd(text)
        messageContent = contents.filter { it !is UIMessagePart.Text }
        editingParts = contents
        editingAttachmentUrls = contents.mapNotNull { it.attachmentUrlOrNull() }.toSet()
    }

    fun getContents(): List<UIMessagePart> {
        val text = textContent.text.toString()
        if (isEditing()) {
            val originalParts = editingParts
            if (originalParts != null) {
                val editedTextIndex = originalParts.indexOfLast { it is UIMessagePart.Text }
                val remainingAttachments = messageContent.toMutableList()
                val merged = mutableListOf<UIMessagePart>()

                originalParts.forEachIndexed { index, part ->
                    when {
                        index == editedTextIndex -> {
                            merged.add(UIMessagePart.Text(text))
                        }

                        part is UIMessagePart.Text -> {
                            merged.add(part)
                        }

                        else -> {
                            val currentIndex = remainingAttachments.indexOf(part)
                            if (currentIndex >= 0) {
                                merged.add(remainingAttachments.removeAt(currentIndex))
                            }
                        }
                    }
                }
                // 新增附件按加入顺序追加。
                merged.addAll(remainingAttachments)
                return merged
            }
            return if (text.isBlank()) messageContent else listOf(UIMessagePart.Text(text)) + messageContent
        }
        return listOf(UIMessagePart.Text(text)) + messageContent
    }

    fun isEmpty(): Boolean {
        return textContent.text.toString().isBlank() && messageContent.isEmpty()
    }

    fun addImages(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Image(uri.toString()))
        }
        messageContent = newMessage
    }

    /** 分享附件替换当前附件集合，保留文字和编辑消息的原附件所有权信息。 */
    fun clearAttachments() {
        attachmentRevision++
        messageContent = emptyList()
        galleryImages = emptyMap()
        pendingAttachmentImports = emptySet()
    }

    internal fun beginAttachmentImport(): Uuid = Uuid.random().also {
        pendingAttachmentImports = pendingAttachmentImports + it
    }

    internal fun isAttachmentImportPending(token: Uuid): Boolean = token in pendingAttachmentImports

    internal fun finishAttachmentImport(token: Uuid, part: UIMessagePart): Boolean {
        if (!isAttachmentImportPending(token)) return false
        require(part.attachmentUrlOrNull() != null) { "导入结果必须是附件" }
        messageContent = messageContent + part
        pendingAttachmentImports = pendingAttachmentImports - token
        return true
    }

    internal fun cancelAttachmentImport(token: Uuid) {
        pendingAttachmentImports = pendingAttachmentImports - token
    }

    internal fun beginGalleryImageImport(source: String): Uuid? {
        if (source in selectedGallerySources) return null
        val token = beginAttachmentImport()
        galleryImages = galleryImages + (source to GalleryImageSelection(token))
        return token
    }

    internal fun isGalleryImageImportPending(source: String, token: Uuid): Boolean =
        galleryImages[source]?.let { it.token == token && it.localUrl == null } == true

    internal fun finishGalleryImageImport(source: String, token: Uuid, localUrl: String): Boolean {
        if (!isGalleryImageImportPending(source, token)) return false
        if (!finishAttachmentImport(token, UIMessagePart.Image(localUrl))) return false
        galleryImages = galleryImages + (source to GalleryImageSelection(token, localUrl))
        return true
    }

    internal fun cancelGalleryImageImport(source: String, token: Uuid) {
        if (isGalleryImageImportPending(source, token)) galleryImages = galleryImages - source
        cancelAttachmentImport(token)
    }

    internal fun deselectGalleryImage(source: String): UIMessagePart.Image? {
        val selection = galleryImages[source]
        val localUrl = selection?.localUrl
        selection?.let { cancelAttachmentImport(it.token) }
        galleryImages = galleryImages - source
        val part = messageContent.filterIsInstance<UIMessagePart.Image>().firstOrNull { it.url == localUrl }
        if (part != null) removeAttachment(part)
        return part
    }

    internal fun removeAttachment(part: UIMessagePart) {
        messageContent = messageContent.filterNot { it == part }
        val url = part.attachmentUrlOrNull()
        galleryImages = galleryImages.filterValues { it.localUrl != url }
    }

    /** 替换仍在当前草稿里的图片，不追加已被移除的旧结果；编辑历史消息时保持原内容顺序。 */
    internal fun replaceImage(
        original: UIMessagePart.Image,
        localUrl: String,
        expectedRevision: Long = attachmentRevision,
    ): Boolean {
        if (expectedRevision != attachmentRevision || original !in messageContent) return false
        val replacement = original.copy(url = localUrl)
        messageContent = messageContent.map { if (it == original) replacement else it }
        editingParts = editingParts?.map { if (it == original) replacement else it }
        galleryImages = galleryImages.mapValues { (_, selection) ->
            if (selection.localUrl == original.url) selection.copy(localUrl = localUrl) else selection
        }
        return true
    }

    fun addVideos(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Video(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addAudios(uris: List<Uri>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach { uri ->
            newMessage.add(UIMessagePart.Audio(uri.toString()))
        }
        messageContent = newMessage
    }

    fun addFiles(uris: List<UIMessagePart.Document>) {
        val newMessage = messageContent.toMutableList()
        uris.forEach {
            newMessage.add(it)
        }
        messageContent = newMessage
    }

    /**
     * 仅删除当前输入组件临时新增的本地文件。
     * 编辑历史消息时，原有附件不在这里删除，由会话层统一做差异清理。
     */
    fun shouldDeleteFileOnRemove(part: UIMessagePart): Boolean {
        val url = part.attachmentUrlOrNull() ?: return false
        if (!url.startsWith("file:")) return false
        return !isEditing() || url !in editingAttachmentUrls
    }

}

internal fun UIMessagePart.attachmentUrlOrNull(): String? = when (this) {
    is UIMessagePart.Image -> url
    is UIMessagePart.Video -> url
    is UIMessagePart.Audio -> url
    is UIMessagePart.Document -> url
    else -> null
}

private data class GalleryImageSelection(val token: Uuid, val localUrl: String? = null)
