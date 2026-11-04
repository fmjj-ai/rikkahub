package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.hooks.ChatInputState
import kotlin.math.roundToInt

internal const val CAMERA_ATTACHMENT_FLIGHT_MILLIS = 420

internal data class CameraAttachmentPhoto(val url: String, val source: Rect, val revision: Long)

internal class CameraAttachmentTransitionState {
    var photo by mutableStateOf<CameraAttachmentPhoto?>(null)
        private set
    var target by mutableStateOf<Rect?>(null)
        private set
    var targetVisible by mutableStateOf(false)
    var thumbnailReady by mutableStateOf(false)
        private set

    fun prepare(url: String, source: Rect, revision: Long) {
        target = null
        targetVisible = false
        thumbnailReady = false
        photo = CameraAttachmentPhoto(url, source, revision)
    }

    fun updateTarget(url: String, bounds: Rect) {
        if (photo?.url == url && bounds.width > 0f && bounds.height > 0f) target = bounds
    }

    fun thumbnailLoaded(url: String) {
        if (photo?.url == url) thumbnailReady = true
    }

    fun finish(expected: CameraAttachmentPhoto) {
        if (photo != expected) return
        photo = null
        target = null
        targetVisible = false
        thumbnailReady = false
    }
}

internal fun cameraAttachmentBounds(source: Rect, target: Rect, progress: Float): Rect {
    val t = progress.coerceIn(0f, 1f)
    fun between(start: Float, end: Float) = start + (end - start) * t
    return Rect(
        between(source.left, target.left), between(source.top, target.top),
        between(source.right, target.right), between(source.bottom, target.bottom),
    )
}

@Composable
internal fun CameraAttachmentTransition(
    transition: CameraAttachmentTransitionState,
    inputState: ChatInputState,
    onPhotoReady: () -> Unit,
) {
    val photo = transition.photo ?: return
    val context = LocalContext.current
    val latestOnPhotoReady by rememberUpdatedState(onPhotoReady)
    val progress = remember(photo) { Animatable(0f) }
    var loaded by remember(photo) { mutableStateOf(false) }
    var failed by remember(photo) { mutableStateOf(false) }
    var cameraReleased by remember(photo) { mutableStateOf(false) }
    var host by remember { mutableStateOf(Rect.Zero) }
    val request = remember(photo, context) {
        ImageRequest.Builder(context).data(photo.url).size(1280, 1280).crossfade(false).build()
    }
    fun releaseCamera() {
        if (!cameraReleased) {
            cameraReleased = true
            latestOnPhotoReady()
        }
    }

    LaunchedEffect(photo, inputState.attachmentRevision, inputState.messageContent) {
        if (photo.revision != inputState.attachmentRevision ||
            inputState.messageContent.none { it is UIMessagePart.Image && it.url == photo.url }
        ) {
            releaseCamera()
            transition.finish(photo)
        }
    }
    LaunchedEffect(photo) {
        try {
            val imageReady = withTimeoutOrNull(2000) { snapshotFlow { loaded || failed }.first { it } }
            releaseCamera()
            if (imageReady == null || failed || photo.source.isEmpty) return@LaunchedEffect
            val settled = withTimeoutOrNull(700) {
                var previous: Rect? = null
                var stableFrames = 0
                while (stableFrames < 3) {
                    withFrameNanos { }
                    val bounds = transition.target.takeIf { transition.targetVisible && transition.thumbnailReady }
                    stableFrames = if (bounds != null && bounds == previous) stableFrames + 1 else 0
                    previous = bounds
                }
                true
            }
            if (settled == null && (!transition.targetVisible || !transition.thumbnailReady || transition.target == null)) {
                return@LaunchedEffect
            }
            progress.animateTo(1f, tween(CAMERA_ATTACHMENT_FLIGHT_MILLIS, easing = FastOutSlowInEasing))
        } finally {
            transition.finish(photo)
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { host = it.boundsInWindow() }) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { loaded = true },
            onError = { failed = true },
            modifier = Modifier.layout { measurable, constraints ->
                val bounds = cameraAttachmentBounds(photo.source, transition.target ?: photo.source, progress.value)
                val placeable = measurable.measure(
                    Constraints.fixed(
                        bounds.width.roundToInt().coerceAtLeast(1), bounds.height.roundToInt().coerceAtLeast(1),
                    )
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place((bounds.left - host.left).roundToInt(), (bounds.top - host.top).roundToInt())
                }
            }.graphicsLayer {
                alpha = if (loaded) 1f else 0f
                shape = RoundedCornerShape((32f - 16f * progress.value).dp)
                clip = true
            },
        )
    }
}
