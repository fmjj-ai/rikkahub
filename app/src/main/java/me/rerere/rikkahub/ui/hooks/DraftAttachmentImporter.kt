package me.rerere.rikkahub.ui.hooks

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/** 所有附件共用草稿生命周期与复制队列；关闭弹窗不取消导入，状态操作留在主线程。 */
internal class DraftAttachmentImporter(
    private val state: ChatInputState,
    private val scope: CoroutineScope,
    private val copyImage: suspend (String) -> String?,
    private val discardFile: suspend (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val copyMutex = Mutex()

    fun add(
        sources: List<String>,
        onImported: (String, String?) -> Unit = { _, _ -> },
        onSourceConsumed: suspend (String) -> Unit = {},
    ) {
        sources.distinct().forEach { source ->
            val token = state.beginGalleryImageImport(source) ?: return@forEach
            enqueue(
                token = token,
                gallerySource = source,
                copy = { copyImage(source)?.let { UIMessagePart.Image(it) } },
                onImported = { part -> onImported(source, part?.attachmentUrlOrNull()) },
                onSourceConsumed = { onSourceConsumed(source) },
            )
        }
    }

    /** 非图库来源不按 URI 去重，允许重复粘贴；元数据随单个复制结果一起交付。 */
    fun addAttachment(
        copy: suspend () -> UIMessagePart?,
        onImported: (UIMessagePart?) -> Unit = {},
    ) {
        enqueue(token = state.beginAttachmentImport(), copy = copy, onImported = onImported)
    }

    private fun enqueue(
        token: Uuid,
        gallerySource: String? = null,
        copy: suspend () -> UIMessagePart?,
        onImported: (UIMessagePart?) -> Unit,
        onSourceConsumed: suspend () -> Unit = {},
    ) {
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var copied: UIMessagePart? = null
            var accepted = false
            var reported = false
            try {
                copyMutex.withLock {
                    if (!state.isAttachmentImportPending(token)) return@launch
                    // 拿到完整输出后再响应取消，确保离开页面时仍能回收本次复制的文件。
                    withContext(NonCancellable) { copied = copy() }
                }
                currentCoroutineContext().ensureActive()
                val part = copied ?: error("附件读取失败，请重新选择或检查访问权限")
                accepted = if (gallerySource != null) {
                    state.finishGalleryImageImport(gallerySource, token, part.attachmentUrlOrNull()!!)
                } else {
                    state.finishAttachmentImport(token, part)
                }
                reported = true
                onImported(part.takeIf { accepted })
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (state.isAttachmentImportPending(token)) onError(error.message ?: "附件添加失败")
            } finally {
                if (gallerySource != null) state.cancelGalleryImageImport(gallerySource, token)
                state.cancelAttachmentImport(token)
                try {
                    if (!accepted) copied?.attachmentUrlOrNull()?.let { url ->
                        withContext(NonCancellable) { discardFile(url) }
                    }
                } finally {
                    try {
                        if (!reported) onImported(null)
                    } finally {
                        withContext(NonCancellable) { onSourceConsumed() }
                    }
                }
            }
        }
    }

    fun toggle(source: String) {
        if (source !in state.selectedGallerySources) {
            add(listOf(source))
            return
        }
        val removed = state.deselectGalleryImage(source) ?: return
        if (state.shouldDeleteFileOnRemove(removed)) {
            scope.launch { withContext(NonCancellable) { discardFile(removed.url) } }
        }
    }
}
