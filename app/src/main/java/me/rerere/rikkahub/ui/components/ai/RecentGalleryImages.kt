package me.rerere.rikkahub.ui.components.ai

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.hooks.ChatInputState

private enum class GalleryAccess { NONE, PARTIAL, FULL }

private fun galleryAccess(context: Context): GalleryAccess {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> GalleryAccess.FULL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> GalleryAccess.PARTIAL
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> GalleryAccess.FULL
        else -> GalleryAccess.NONE
    }
}

/** 仅加载有限数量的 URI；缩略图由 Coil 按控件尺寸解码，不读取整本相册的原图。 */
private suspend fun recentImages(context: Context): List<Uri> = withContext(Dispatchers.IO) {
    val collection = if (Build.VERSION.SDK_INT >= 29) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val args = Bundle().apply {
        putInt(ContentResolver.QUERY_ARG_LIMIT, 60)
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
            "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC")
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.SIZE} > 0")
    }
    buildList {
        context.contentResolver.query(collection, arrayOf(MediaStore.Images.Media._ID), args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            // 部分系统忽略查询条数参数，仍限制读取，更多图片通过上方系统相册选择。
            while (size < 60 && cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                add(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)))
            }
        }
    }
}

@Composable
internal fun RecentGalleryImages(
    state: ChatInputState,
    onToggle: (Uri) -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    var access by remember { mutableStateOf(galleryAccess(context)) }
    var images by remember { mutableStateOf(emptyList<Uri>()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Uri?>(null) }
    val selected = state.selectedGallerySources
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = galleryAccess(context)
        refresh++
    }
    fun requestAccess() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                access = galleryAccess(context)
                refresh++
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(access, refresh) {
        images = emptyList()
        error = null
        if (access == GalleryAccess.NONE) return@LaunchedEffect
        loading = true
        try {
            images = recentImages(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            access = galleryAccess(context)
            error = "相册权限已变化，可重新授权或使用上方“照片”入口"
        } catch (_: Exception) {
            error = "相册预览读取失败，仍可使用上方“照片”入口"
        } finally {
            loading = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("最近照片", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (selected.isNotEmpty()) Text("已选 ${selected.size} 张", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = onDone) { Text("完成") }
        }
        when {
            access == GalleryAccess.NONE -> Surface(
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("允许访问照片后可在这里预览并多选，也可直接使用上方“照片”入口。",
                        style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { requestAccess() }) { Text("允许访问") }
                        TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                "package:${context.packageName}".toUri()))
                        }) { Text("系统设置") }
                    }
                }
            }
            loading -> Box(Modifier.fillMaxWidth().height(108.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
            error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(error.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { refresh++ }) { Text("重试") }
            }
            images.isEmpty() -> Text("暂无可访问的照片，可使用上方“照片”入口选择。",
                style = MaterialTheme.typography.bodySmall)
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(images, key = { it.toString() }) { uri ->
                    val index = selected.indexOf(uri.toString())
                    Surface(
                        modifier = Modifier.size(108.dp).clip(RoundedCornerShape(20.dp))
                            .combinedClickable(
                                onClickLabel = if (index >= 0) "取消选择照片" else "选择照片",
                                onLongClickLabel = "预览照片",
                                onClick = { onToggle(uri) }, onLongClick = { preview = uri },
                            ).semantics {
                                role = Role.Checkbox
                                stateDescription = if (index >= 0) "已选择，第 ${index + 1} 张" else "未选择"
                            },
                        shape = RoundedCornerShape(20.dp),
                        border = if (index >= 0) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Box {
                            AsyncImage(uri, "相册照片，长按预览", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(7.dp).size(28.dp)
                                    .clip(CircleShape).background(
                                        if (index >= 0) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f)),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (index >= 0) "${index + 1}" else "○", color = Color.White) }
                        }
                    }
                }
            }
        }
        if (access == GalleryAccess.PARTIAL) {
            TextButton(onClick = { requestAccess() }) { Text("当前仅显示授权照片 · 管理可访问照片") }
        }
    }
    preview?.let { uri ->
        ImagePreviewDialog(images = listOf(uri.toString()), onDismissRequest = { preview = null }, showSaveAction = false)
    }
}
