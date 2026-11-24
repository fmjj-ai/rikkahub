package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Video01
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.ui.hooks.ChatInputState
import me.rerere.rikkahub.ui.context.LocalToaster
import org.koin.compose.koinInject

@Composable
internal fun MediaFileInputRow(
    state: ChatInputState,
    cameraTransition: CameraAttachmentTransitionState? = null,
) {
    val filesManager: FilesManager = koinInject()
    val context = LocalContext.current
    val managedFiles by filesManager.observe().collectAsState(initial = emptyList())
    val displayNameByRelativePath = remember(managedFiles) {
        managedFiles.associate { it.relativePath to it.displayName }
    }
    val displayNameByFileName = remember(managedFiles) {
        managedFiles.associate { it.relativePath.substringAfterLast('/') to it.displayName }
    }

    fun removePart(part: UIMessagePart, url: String) {
        state.removeAttachment(part)
        if (state.shouldDeleteFileOnRemove(part)) {
            filesManager.deleteChatFiles(listOf(url.toUri()))
        }
    }

    val toaster = LocalToaster.current
    var editing by remember { mutableStateOf<UIMessagePart.Image?>(null) }
    var editRevision by remember { mutableStateOf(0L) }
    val launchCrop = useCropLauncher(
        onCroppedImageReady = { cropped ->
            val original = editing
            if (original != null) {
                val revision = editRevision
                val deleteOriginal = state.shouldDeleteFileOnRemove(original)
                var copied: android.net.Uri? = null
                var accepted = false
                try {
                    // 临时文件在挂起回调完成后才释放；流式复制，不在主线程读取整张图片。
                    withContext(NonCancellable) {
                        val saved = filesManager.saveManagedFromUri(
                            me.rerere.rikkahub.data.files.FileFolders.UPLOAD, cropped)
                        copied = filesManager.getFile(saved).toUri()
                    }
                    currentCoroutineContext().ensureActive()
                    val url = copied ?: error("裁剪图片保存失败")
                    accepted = state.replaceImage(original, url.toString(), revision)
                    if (accepted && deleteOriginal) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            filesManager.deleteChatFiles(listOf(original.url.toUri()))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    toaster.show(error.message ?: "裁剪图片保存失败", type = ToastType.Error)
                } finally {
                    if (!accepted) copied?.let { uri ->
                        withContext(NonCancellable + Dispatchers.IO) { filesManager.deleteChatFiles(listOf(uri)) }
                    }
                }
            }
        },
        onCleanup = { editing = null },
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        state.messageContent.fastForEach { part ->
            when (part) {
                is UIMessagePart.Image -> {
                    key(part.url) {
                        val arriving = cameraTransition?.photo?.url == part.url
                        val bringIntoView = remember { BringIntoViewRequester() }
                        val imageRequest = remember(part.url, context) {
                            ImageRequest.Builder(context).data(part.url).crossfade(false).build()
                        }
                        LaunchedEffect(arriving) {
                            if (arriving) {
                                withFrameNanos { }
                                bringIntoView.bringIntoView()
                                if (cameraTransition?.photo?.url == part.url) cameraTransition.targetVisible = true
                            }
                        }
                        val visible = remember { MutableTransitionState(arriving).apply { targetState = true } }
                        AnimatedVisibility(
                            visibleState = visible,
                            enter = if (arriving) EnterTransition.None
                                else fadeIn() + scaleIn(initialScale = 0.85f) + slideInVertically { it / 4 },
                        ) {
                            Box(Modifier.size(88.dp).bringIntoViewRequester(bringIntoView)
                                .onGloballyPositioned { cameraTransition?.updateTarget(part.url, it.boundsInWindow()) }
                                .graphicsLayer { alpha = if (cameraTransition?.photo?.url == part.url) 0f else 1f }
                            ) {
                                AsyncImage(
                                    model = imageRequest,
                                    onSuccess = { cameraTransition?.thumbnailLoaded(part.url) },
                                    contentDescription = "图片附件，点击裁剪",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).clickable(
                                        enabled = !arriving,
                                    ) {
                                        editing = part
                                        editRevision = state.attachmentRevision
                                        launchCrop(part.url.toUri())
                                    },
                                )
                                IconButton(
                                    onClick = { removePart(part, part.url) },
                                    enabled = !arriving,
                                    modifier = Modifier.align(Alignment.TopEnd).size(40.dp),
                                ) {
                                    Icon(
                                        HugeIcons.Cancel01, contentDescription = "删除图片附件",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp).clip(CircleShape)
                                            .background(Color.Black.copy(alpha = 0.5f)).padding(4.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                is UIMessagePart.Video -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "video",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Video01) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Audio -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = "audio",
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.MusicNote03) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                is UIMessagePart.Document -> {
                    AttachmentChip(
                        title = attachmentNameFromUrl(
                            url = part.url,
                            fallback = part.fileName,
                            displayNameByRelativePath = displayNameByRelativePath,
                            displayNameByFileName = displayNameByFileName
                        ),
                        leading = { AttachmentLeadingIcon(icon = HugeIcons.Files02) },
                        onRemove = { removePart(part, part.url) }
                    )
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun AttachmentChip(
    title: String,
    leading: @Composable () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 1.dp,
        shadowElevation = 0.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .height(44.dp)
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            leading()
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.widthIn(min = 40.dp, max = 180.dp),
            )
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .size(26.dp)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachmentLeadingIcon(
    icon: ImageVector,
) {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun attachmentNameFromUrl(
    url: String,
    fallback: String,
    displayNameByRelativePath: Map<String, String>,
    displayNameByFileName: Map<String, String>,
): String {
    val parsed = runCatching { url.toUri() }.getOrNull()
    val relativePath = parsed?.path?.substringAfter("/files/", missingDelimiterValue = "")?.takeIf { it.isNotBlank() }
    if (relativePath != null) {
        displayNameByRelativePath[relativePath]?.let { return it }
    }

    val storedFileName = parsed?.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    if (storedFileName != null) {
        displayNameByFileName[storedFileName]?.let { return it }
        return storedFileName
    }

    return fallback
}
