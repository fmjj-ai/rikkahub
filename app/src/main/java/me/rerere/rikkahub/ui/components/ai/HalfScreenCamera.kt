package me.rerere.rikkahub.ui.components.ai

import android.view.Surface
import android.view.OrientationEventListener
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowDpSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.CameraRotated01
import java.io.File
import kotlin.uuid.Uuid

internal fun cameraPreviewHeight(panelWidth: Dp, windowHeight: Dp, landscape: Boolean = false): Dp = minOf(
    (panelWidth - 24.dp).coerceAtLeast(1.dp) * (if (landscape) 3f / 4f else 4f / 3f),
    (windowHeight * 0.6f).coerceAtLeast(1.dp),
)

internal fun cameraCaptureRotation(orientation: Int): Int? = when (orientation) {
    in 0..44, in 315..359 -> Surface.ROTATION_0
    in 45..134 -> Surface.ROTATION_270
    in 135..224 -> Surface.ROTATION_180
    in 225..314 -> Surface.ROTATION_90
    else -> null
}

/** 手机和平板共用，离开组合时仅解绑自己拥有的用例。 */
@Composable
internal fun HalfScreenCamera(
    onCancel: () -> Unit,
    launchBounds: Rect = Rect.Zero,
    visible: Boolean = true,
    onClosed: () -> Unit = {},
    onTransitionProgress: (Float) -> Unit = {},
    onCaptured: (File, Rect) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCaptured by rememberUpdatedState(onCaptured)
    val acceptsCapture by rememberUpdatedState(visible)
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var lens by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var canSwitch by remember { mutableStateOf(false) }
    var cameraOpen by remember { mutableStateOf(false) }
    var previewStreaming by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var previewCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    val ready = cameraOpen && previewStreaming && error == null
    val capture = remember(lens, retry) {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()
    }
    var transitionFrame by remember(capture) { mutableStateOf<ImageBitmap?>(null) }
    var closingFrameReady by remember { mutableStateOf(false) }
    fun copyPreviewFrame(): ImageBitmap? {
        if (!previewStreaming || previewView.width <= 0 || previewView.height <= 0) return null
        return try {
            // CameraX 已处理旋转、裁切与前摄镜像，不能改用原始 TextureView 截图。
            val bitmap = previewView.bitmap ?: return null
            val size = cameraPreviewFrameSize(bitmap.width, bitmap.height)
            val scaled = Bitmap.createScaledBitmap(bitmap, size.width, size.height, true)
            // bitmap 是本次复制的新对象，尚未交给 Compose；只回收缩小前、不再使用的临时原件。
            if (scaled !== bitmap) bitmap.recycle()
            scaled.asImageBitmap()
        } catch (_: RuntimeException) {
            null
        }
    }
    LaunchedEffect(visible, previewStreaming, capture) {
        if (visible) {
            closingFrameReady = false
            if (previewStreaming) copyPreviewFrame()?.let { transitionFrame = it }
        } else {
            copyPreviewFrame()?.let { transitionFrame = it }
            closingFrameReady = true
        }
    }
    // 仅在首帧和返回时复制，不逐帧读 GPU；有界缓存保留到退出，复制失败时也不闪成黑屏。
    // 与具体绑定实例一起失效，关闭弹窗后迟到的拍摄结果只清理，不再进入草稿。
    val session = remember(previewView, lifecycleOwner, capture) { CameraCaptureSession() }

    DisposableEffect(previewView, lifecycleOwner, capture) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var cameraState: androidx.lifecycle.LiveData<CameraState>? = null
        cameraOpen = false
        previewStreaming = false
        error = null
        var sensorRotation: Int? = null
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (!disposed) cameraCaptureRotation(orientation)?.let { rotation ->
                    sensorRotation = rotation
                    capture.targetRotation = rotation
                }
            }
        }
        fun updateOrientationListener() {
            if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                orientationListener.canDetectOrientation()
            ) orientationListener.enable() else orientationListener.disable()
        }
        val lifecycleObserver = LifecycleEventObserver { _, _ -> updateOrientationListener() }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        updateOrientationListener()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val streamObserver = Observer<PreviewView.StreamState> { state ->
            if (!disposed) previewStreaming = state == PreviewView.StreamState.STREAMING
        }
        previewView.previewStreamState.observe(lifecycleOwner, streamObserver)
        val observer = Observer<CameraState> { state ->
            if (!disposed) {
                cameraOpen = state.type == CameraState.Type.OPEN && state.error == null
                if (state.error != null) error = "相机暂不可用，请关闭其他相机应用后重试"
                else if (cameraOpen) error = null
            }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed) {
                try {
                    val current = future.get()
                    provider = current
                    val back = current.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                    val front = current.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                    check(back || front) { "此设备没有可用摄像头，可返回后从照片中添加" }
                    canSwitch = back && front
                    val actualLens = if (lens == CameraSelector.LENS_FACING_BACK && !back) {
                        CameraSelector.LENS_FACING_FRONT
                    } else lens
                    val selector = CameraSelector.Builder().requireLensFacing(actualLens).build()
                    val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    preview.targetRotation = rotation
                    capture.targetRotation = sensorRotation ?: rotation
                    val camera = current.bindToLifecycle(lifecycleOwner, selector, preview, capture)
                    cameraState = camera.cameraInfo.cameraState
                    cameraState?.observe(lifecycleOwner, observer)
                } catch (exception: Exception) {
                    error = exception.message ?: "相机启动失败，请重试"
                }
            }
        }, executor)
        onDispose {
            disposed = true
            session.active = false
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            orientationListener.disable()
            cameraState?.removeObserver(observer)
            previewView.previewStreamState.removeObserver(streamObserver)
            provider?.unbind(preview, capture)
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cornerRadius = with(LocalDensity.current) { 32.dp.toPx() }
        val window = currentWindowDpSize()
        val height = minOf(cameraPreviewHeight(maxWidth, window.height, window.width > window.height), maxHeight)
        val shutterSize = (maxWidth - 120.dp).coerceIn(48.dp, 80.dp)
        val controlsInset = ((maxWidth - 120.dp - shutterSize) / 2f).coerceIn(0.dp, 28.dp)
        CameraOpeningTransition(
            source = launchBounds,
            visible = visible,
            readyToClose = closingFrameReady,
            onClosed = onClosed,
            onProgress = onTransitionProgress,
            stationaryPreview = { atRest ->
                val showPreview = useStationaryCameraPreview(previewStreaming, atRest, visible, closingFrameReady)
                AndroidView(
                    factory = { previewView },
                    update = {
                        // AndroidView 不保证按 Compose 边界裁切，原生视图也必须有自己的轮廓。
                        val outline = (it.background as? GradientDrawable) ?: GradientDrawable().also { drawable ->
                            drawable.setColor(android.graphics.Color.BLACK)
                            it.background = drawable
                        }
                        if (outline.cornerRadius != cornerRadius) {
                            outline.cornerRadius = cornerRadius
                            it.invalidateOutline()
                        }
                        if (!it.clipToOutline) it.clipToOutline = true
                    },
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)
                        .stationaryCameraPreview(showPreview, initializing = !previewStreaming),
                )
            },
        ) { atRest ->
            val expanded = visible && atRest
            val showPreview = useStationaryCameraPreview(previewStreaming, atRest, visible, closingFrameReady)
            Box(
                modifier = Modifier.fillMaxWidth().height(height).padding(horizontal = 12.dp)
                    .onGloballyPositioned { previewCoordinates = it }
                    .clip(RoundedCornerShape(32.dp))
                    .then(if (showPreview) Modifier else Modifier.background(Color.Black)),
                contentAlignment = Alignment.Center,
            ) {
                if (!showPreview) transitionFrame?.let { frame ->
                    Image(frame, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
                // 绑定与展开并行；首帧到达即显示，不额外等待淡入。慢启动才在展开后提示。
                if (error == null && !showPreview && transitionFrame == null) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(HugeIcons.Camera01, null, tint = Color.White, modifier = Modifier.size(48.dp))
                        if (showCameraLoadingHint(expanded, previewStreaming, error != null)) {
                            Text("正在打开相机…", color = Color.White)
                        }
                    }
                }
                error?.let { message ->
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter)
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(message)
                            TextButton(onClick = { retry++ }, enabled = expanded && !capturing) { Text("重试") }
                        }
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(horizontal = controlsInset, vertical = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CameraOverlayButton(
                        icon = HugeIcons.ArrowLeft01,
                        description = "返回附件",
                        onClick = onCancel,
                    )
                    Surface(
                        modifier = Modifier.size(shutterSize).semantics {
                            contentDescription = if (capturing) "拍摄中" else "拍照"
                        },
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.45f),
                        enabled = expanded && ready && !capturing,
                        onClick = capturePhoto@{
                            // 展开结束后按快门时再读取实际边界，不复用动画初始帧的测量快照。
                            val capturedBounds = previewCoordinates?.takeIf { it.isAttached }?.boundsInWindow()
                                ?: return@capturePhoto
                            capturing = true
                            val file = File(context.appTempFolder, "camera_${Uuid.random()}.jpg")
                            val options = ImageCapture.OutputFileOptions.Builder(file).build()
                            // 保留 CameraX 的 EXIF 方向，前摄原片不做自拍镜像，文字不会反写。
                            try {
                                capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                        if (session.active && acceptsCapture) {
                                            latestOnCaptured(file, capturedBounds)
                                        } else file.delete()
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        file.delete()
                                        if (session.active) {
                                            capturing = false
                                            error = "拍照失败：${exception.message ?: "请重试"}"
                                        }
                                    }
                                })
                            } catch (exception: Exception) {
                                file.delete()
                                capturing = false
                                error = "拍照失败：${exception.message ?: "请重试"}"
                            }
                        },
                    ) {
                        Box(
                            modifier = Modifier.padding(6.dp).fillMaxSize().clip(CircleShape)
                                .background(Color.White.copy(alpha = if (ready || capturing) 1f else 0.45f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (capturing) CircularProgressIndicator(
                                modifier = Modifier.size(28.dp), color = Color.DarkGray, strokeWidth = 2.dp,
                            )
                        }
                    }
                    CameraOverlayButton(
                        icon = HugeIcons.CameraRotated01,
                        description = "切换前后摄像头",
                        enabled = expanded && canSwitch && !capturing,
                        onClick = {
                            lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
                                else CameraSelector.LENS_FACING_BACK
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraOverlayButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.45f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = Color.White.copy(alpha = if (enabled) 1f else 0.38f),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

private class CameraCaptureSession {
    var active = true
}
