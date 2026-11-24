package me.rerere.rikkahub.ui.components.ai

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.Logging
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.crop.ImageCropDialog
import java.io.File
import kotlin.uuid.Uuid

/** 原生裁剪结果只作为临时文件交付；完成、失败、取消或离开页面都清理本次资源。 */
@Composable
internal fun useCropLauncher(
    onCroppedImageReady: suspend (Uri) -> Unit,
    onCleanup: (() -> Unit)? = null,
    aspectRatio: Pair<Float, Float>? = null,
    freeStyleCropEnabled: Boolean = true,
): (Uri) -> Unit {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val currentOnReady by rememberUpdatedState(onCroppedImageReady)
    val currentOnCleanup by rememberUpdatedState(onCleanup)
    var source by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }
    var cleanupPending by remember { mutableStateOf(false) }
    val ratio = aspectRatio?.let { (width, height) ->
        require(width.isFinite() && height.isFinite() && width > 0 && height > 0)
        width / height
    }
    require(freeStyleCropEnabled || ratio != null) { "固定裁剪模式需要有效宽高比" }

    fun finish() {
        source = null
        if (cleanupPending) {
            cleanupPending = false
            currentOnCleanup?.invoke()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (cleanupPending) {
                cleanupPending = false
                currentOnCleanup?.invoke()
            }
        }
    }

    source?.let { uri ->
        ImageCropDialog(
            image = uri,
            aspectRatio = ratio,
            busy = saving,
            onDismiss = { if (!saving) finish() },
            onConfirm = { bitmap ->
                if (!saving) {
                    saving = true
                    scope.launch {
                        val output = File(context.appTempFolder, "crop_output_${Uuid.random()}.png")
                        try {
                            withContext(Dispatchers.IO) {
                                output.outputStream().use { stream ->
                                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "图片编码失败" }
                                }
                            }
                            currentOnReady(output.toUri())
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Logging.log("CropLauncher", "图片裁剪失败：${error.stackTraceToString()}")
                            toaster.show("图片裁剪失败：${error.message.orEmpty()}", type = ToastType.Error)
                        } finally {
                            output.delete()
                            saving = false
                            finish()
                        }
                    }
                }
            },
        )
    }
    return { uri ->
        if (!saving) {
            cleanupPending = true
            source = uri
        }
    }
}
