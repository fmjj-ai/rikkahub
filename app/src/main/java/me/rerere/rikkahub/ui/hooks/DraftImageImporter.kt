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

/** 归属于聊天草稿而非弹窗，关闭选择面板不会丢失正在导入的图片。状态操作均在调用方主线程执行。 */
internal class DraftImageImporter(
    private val state: ChatInputState,
    private val scope: CoroutineScope,
    private val copyImage: suspend (String) -> String?,
    private val discardImage: suspend (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val copyMutex = Mutex()

    fun add(sources: List<String>) {
        sources.distinct().forEach { source ->
            val token = state.beginGalleryImageImport(source) ?: return@forEach
            scope.launch {
                var copied: String? = null
                var accepted = false
                try {
                    copyMutex.withLock {
                        if (!state.isGalleryImageImportPending(source, token)) return@launch
                        // 单个流式复制完成后再响应取消，确保即使页面离开，也拿得到文件并清理。
                        withContext(NonCancellable) { copied = copyImage(source) }
                    }
                    currentCoroutineContext().ensureActive()
                    val url = copied ?: error("图片读取失败，请重新选择或检查相册访问权限")
                    accepted = state.finishGalleryImageImport(source, token, url)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (state.isGalleryImageImportPending(source, token)) {
                        onError(error.message ?: "图片添加失败")
                    }
                } finally {
                    state.cancelGalleryImageImport(source, token)
                    if (!accepted) copied?.let { url ->
                        withContext(NonCancellable) { discardImage(url) }
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
            scope.launch { withContext(NonCancellable) { discardImage(removed.url) } }
        }
    }
}
