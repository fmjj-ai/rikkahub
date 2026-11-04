package me.rerere.rikkahub.ui.components.ai

import android.view.Surface
import android.view.OrientationEventListener
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
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
    onCaptured: (File, Rect) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnCaptured by rememberUpdatedState(onCaptured)
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var lens by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var canSwitch by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var previewBounds by remember { mutableStateOf(Rect.Zero) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    val capture = remember(lens, retry) {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()
    }
    // 与具体绑定实例一起失效，关闭弹窗后迟到的拍摄结果只清理，不再进入草稿。
    val session = remember(previewView, lifecycleOwner, capture) { CameraCaptureSession() }

    DisposableEffect(previewView, lifecycleOwner, capture) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var cameraState: androidx.lifecycle.LiveData<CameraState>? = null
        ready = false
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
        val observer = Observer<CameraState> { state ->
            if (!disposed) {
                ready = state.type == CameraState.Type.OPEN && state.error == null
                if (state.error != null) error = "相机暂不可用，请关闭其他相机应用后重试"
                else if (ready) error = null
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
            provider?.unbind(preview, capture)
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val window = currentWindowDpSize()
        val height = minOf(cameraPreviewHeight(maxWidth, window.height, window.width > window.height), maxHeight)
        val shutterSize = (maxWidth - 120.dp).coerceIn(48.dp, 80.dp)
        val controlsInset = ((maxWidth - 120.dp - shutterSize) / 2f).coerceIn(0.dp, 28.dp)
        Box(
            modifier = Modifier.fillMaxWidth().height(height).padding(horizontal = 12.dp)
                .onGloballyPositioned { previewBounds = it.boundsInWindow() }
                .clip(RoundedCornerShape(32.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (!ready && error == null) CircularProgressIndicator(color = Color.White)
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
                        TextButton(onClick = { retry++ }, enabled = !capturing) { Text("重试") }
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
                    enabled = ready && !capturing,
                    onClick = {
                        capturing = true
                        val file = File(context.appTempFolder, "camera_${Uuid.random()}.jpg")
                        val options = ImageCapture.OutputFileOptions.Builder(file).build()
                        // 保留 CameraX 的 EXIF 方向，前摄原片不做自拍镜像，文字不会反写。
                        try {
                            capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                                    if (session.active) {
                                        latestOnCaptured(file, previewBounds)
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
                    enabled = canSwitch && !capturing,
                    onClick = {
                        lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
                            else CameraSelector.LENS_FACING_BACK
                    },
                )
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
