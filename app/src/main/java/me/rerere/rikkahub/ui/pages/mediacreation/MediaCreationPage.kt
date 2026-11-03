package me.rerere.rikkahub.ui.pages.mediacreation

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.mediagen.model.ImageRole
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationParameter
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.repository.MediaCreationRepository
import me.rerere.rikkahub.service.MediaCreationService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import java.io.File
import kotlin.uuid.Uuid

@Composable
fun MediaCreationSessionsPage(
    repository: MediaCreationRepository = koinInject(),
    service: MediaCreationService = koinInject(),
) {
    val sessions by repository.observeSessions().collectAsStateWithLifecycle(initialValue = emptyList())
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<MediaCreationSession?>(null) }
    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text("媒体创作") }, navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                actions = {
                    TextButton(onClick = { nav.navigate(Screen.SettingMedia) }) { Text("接口设置") }
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val session = repository.newSession()
                                nav.navigate(Screen.MediaCreation(session.id.toString()))
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "创建失败" }
                            finally { busy = false }
                        }
                    }) { Text("新建") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("独立图片与视频工作台；聊天生图保持原样。生成会调用你配置的接口，费用以提供商为准。",
                    style = MaterialTheme.typography.bodySmall)
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (sessions.isEmpty()) item { Text("还没有创作记录，点击右上角新建。") }
            items(sessions, key = { it.id }) { session ->
                CardGroup {
                    item(
                        onClick = { nav.navigate(Screen.MediaCreation(session.id.toString())) },
                        headlineContent = {
                            Text(session.title.ifBlank { "新创作" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = { Text("${session.nodeCount} 项 · ${session.activeCount} 项进行中") },
                        trailingContent = { TextButton(onClick = { deleting = session }) { Text("删除") } },
                    )
                }
            }
        }
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null }, title = { Text("删除创作会话？") },
            text = { Text("会停止等待其生成任务，并删除本地记录、素材及产出。已经提交到服务端的任务和费用不会撤回。") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try { service.deleteSession(target.id); deleting = null }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "删除失败"; deleting = null }
                        finally { busy = false }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
fun MediaCreationPage(id: String, vm: MediaCreationVM = koinViewModel(parameters = { parametersOf(id) })) {
    val nav = LocalNavController.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val uploadConfigured by vm.uploadConfigured.collectAsStateWithLifecycle()
    val nodes = vm.nodes.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    var error by remember { mutableStateOf<String?>(null) }
    var showParams by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MediaCreationRecord?>(null) }
    var resubmitting by remember { mutableStateOf<MediaCreationRecord?>(null) }
    var rename by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var exportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val path = exportPath
        exportPath = null
        if (uri != null && path != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val source = vm.resolve(path)
                    check(source.isFile) { "产出文件不存在" }
                    val destination = context.contentResolver.openOutputStream(uri) ?: error("目标文件不可写")
                    destination.use { out -> source.inputStream().use { it.copyTo(out) } }
                }
                notice = "已保存到所选位置"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "导出失败" }
        }
    }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        vm.importAssets(it, MediaCreationAssetType.IMAGE, ImageRole.REFERENCE)
    }
    val videos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        vm.importAssets(it, MediaCreationAssetType.VIDEO, ImageRole.REFERENCE)
    }
    LaunchedEffect(vm) {
        vm.events.collect { event -> when (event) {
            is MediaCreationEvent.Error -> error = event.message
            MediaCreationEvent.UploadNotConfigured -> error = "当前接口的参考素材需要公网地址，请先配置上传存储。"
            MediaCreationEvent.SessionGone -> nav.popBackStack()
        } }
    }
    LaunchedEffect(vm) { vm.submitted.collect { listState.animateScrollToItem(0) } }
    val options = remember(settings.mediaGenerationProviders) {
        settings.mediaGenerationProviders.flatMap { provider -> provider.models.map { provider to it } }
    }
    val selected = options.find { it.second.id == draft.modelId }
    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text(session?.title?.ifBlank { "媒体创作" } ?: "媒体创作", maxLines = 1) },
                navigationIcon = { BackButton() }, colors = CustomColors.topBarColors,
                actions = {
                    TextButton(onClick = { name = session?.title.orEmpty(); rename = true }) { Text("重命名") }
                    TextButton(onClick = { nav.navigate(Screen.SettingMedia) }) { Text("接口") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            state = listState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("composer") {
                MediaCard {
                    notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    error?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { error = null }) { Text("关闭提示") }
                    }
                    if (selected != null) {
                        Select(options, selected, { vm.selectModel(it.first, it.second) }, optionToString = {
                            "${it.first.name} · ${it.second.displayName.ifBlank { it.second.modelId }}" +
                                if (it.second.kind == MediaKind.VIDEO) "（视频）" else "（图片）"
                        })
                    } else {
                        Text("请先在接口设置中添加媒体提供商和模型。")
                        TextButton(onClick = { nav.navigate(Screen.SettingMedia) }) { Text("配置媒体接口") }
                    }
                    OutlinedTextField(
                        state = vm.promptState, modifier = Modifier.fillMaxWidth(), label = { Text("创作提示词") },
                        lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 10),
                    )
                    selection?.let { target ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (target.capabilities.imageRoles.isNotEmpty()) {
                                TextButton(onClick = { images.launch("image/*") }) { Text("添加图片") }
                            }
                            if (target.capabilities.videoInput) {
                                TextButton(onClick = { videos.launch("video/*") }) { Text("添加视频") }
                            }
                            TextButton(onClick = { showParams = true }) { Text("参数") }
                        }
                        draft.assets.forEach { asset ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(File(asset.path).name, Modifier.weight(1f), maxLines = 1)
                                TextButton(onClick = { vm.removeAsset(asset) }) { Text("移除") }
                            }
                            if (asset.type == MediaCreationAssetType.IMAGE && target.capabilities.imageRoles.size > 1) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    target.capabilities.imageRoles.forEach { role ->
                                        FilterChip(selected = asset.role == role,
                                            onClick = { vm.setAssetRole(asset, role) },
                                            label = { Text(role.labelText) })
                                    }
                                }
                            }
                        }
                        if (draft.assets.mixesFramesWithReferences(target.capabilities)) {
                            Text("当前接口不能混用首尾帧与参考素材，请移除其中一组。",
                                color = MaterialTheme.colorScheme.error)
                        }
                        if (target.capabilities.requiresRemoteInputs && draft.assets.isNotEmpty() &&
                            !uploadConfigured) {
                            TextButton(onClick = { nav.navigate(Screen.SettingMediaUpload) }) { Text("配置参考素材上传存储") }
                        }
                        draft.editingNodeId?.let {
                            Text("正在编辑历史项，新结果将作为它的新版本。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = vm::stopEditing) { Text("改为新建一项") }
                        }
                        Button(onClick = { error = null; vm.generate() }, enabled =
                            draft.copy(prompt = vm.promptState.text.toString()).canSubmit(target.capabilities)) {
                            Text("生成（调用接口）")
                        }
                    }
                }
            }
            items(nodes.itemCount, key = { index -> nodes.peek(index)?.id?.toString() ?: "loading-$index" }) { index ->
                nodes[index]?.let { node ->
                    val record = node.record
                    MediaCard {
                        Text("${record.providerName} · ${record.modelId}", style = MaterialTheme.typography.labelMedium)
                        Text(record.status.labelText, color = MaterialTheme.colorScheme.primary)
                        if (record.status.isActive) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(record.prompt)
                        record.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        record.outputs.forEach { output ->
                            val file = runCatching { vm.resolve(output.path) }.getOrNull()
                            val open = {
                                try {
                                    check(file?.isFile == true) { "产出文件不存在" }
                                    val uri = FileProvider.getUriForFile(
                                        context, "${context.packageName}.fileprovider", file,
                                    )
                                    context.startActivity(Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(uri, output.mimeType)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                } catch (e: Exception) { error = e.message ?: "没有可打开此文件的应用" }
                            }
                            if (!output.isVideo && file != null) {
                                AsyncImage(file, "生成的图片", modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                                    .clickable(onClick = open), contentScale = ContentScale.Fit)
                            } else TextButton(onClick = open) { Text("打开视频") }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    exportPath = output.path
                                    export.launch(File(output.path).name)
                                }) { Text("保存到文件") }
                                TextButton(onClick = {
                                    try {
                                        val target = requireNotNull(file) { "产出文件不存在" }
                                        val uri = FileProvider.getUriForFile(context,
                                            "${context.packageName}.fileprovider", target)
                                        val share = Intent(Intent.ACTION_SEND).setType(output.mimeType)
                                            .putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        context.startActivity(Intent.createChooser(share, "分享产出"))
                                    } catch (e: Exception) { error = e.message ?: "分享失败" }
                                }) { Text("分享") }
                                TextButton(onClick = {
                                    if (output.isVideo) vm.useVideo(output)
                                    else vm.useImage(output, ImageRole.REFERENCE)
                                }) { Text("作为参考素材") }
                                if (output.isVideo) TextButton(onClick = { vm.continueVideo(output) }) { Text("续写") }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (record.status.isActive) TextButton(onClick = { vm.cancel(record) }) { Text("停止等待") }
                            else {
                                TextButton(onClick = { vm.editFrom(record); error = null }) { Text("编辑") }
                                TextButton(onClick = { resubmitting = record }) {
                                    Text(if (record.taskId != null && record.status != MediaCreationStatus.SUCCEEDED)
                                        "继续查询" else "再生成")
                                }
                                TextButton(onClick = { deleting = record }) { Text("删除版本") }
                            }
                        }
                        if (node.versionCount > 1) {
                            Row {
                                TextButton(enabled = node.versionIndex > 0,
                                    onClick = { vm.switchVersion(node, -1) }) { Text("上一版") }
                                Text("${node.versionIndex + 1}/${node.versionCount}")
                                TextButton(enabled = node.versionIndex + 1 < node.versionCount,
                                    onClick = { vm.switchVersion(node, 1) }) { Text("下一版") }
                            }
                        }
                    }
                }
            }
            nodes.loadState.refresh.let { state -> if (state is androidx.paging.LoadState.Error) {
                item { Text(state.error.message ?: "历史加载失败"); TextButton(onClick = nodes::retry) { Text("重试") } }
            } }
        }
    }
    selection?.let { if (showParams) MediaParamsDialog(draft.params, it.capabilities.parameters,
        onDismiss = { showParams = false }, onSave = { params -> vm.updateParams(params); showParams = false }) }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("重命名") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { vm.rename(name); rename = false }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("取消") } })
    deleting?.let { record -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除这个版本？") },
        text = { Text("将删除此版本的本地素材和产出；其他版本与聊天生图不受影响。") },
        confirmButton = { TextButton(onClick = { vm.delete(record); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
    resubmitting?.let { record ->
        val querying = record.taskId != null && record.status != MediaCreationStatus.SUCCEEDED
        AlertDialog(onDismissRequest = { resubmitting = null }, title = { Text(if (querying) "继续查询？" else "重新生成？") },
            text = { Text(if (querying) "使用原任务 ID 继续查询，不另行提交生成请求。"
                else "会提交新的生成请求，可能再次计费。同步请求失败不代表服务端没有执行，请确认后继续。") },
            confirmButton = { TextButton(onClick = {
                if (querying || record.status != MediaCreationStatus.SUCCEEDED) vm.retry(record) else vm.rerun(record)
                resubmitting = null
            }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { resubmitting = null }) { Text("取消") } })
    }
}

@Composable
private fun MediaCard(content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor)) {
        Column(Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

private val ImageRole.labelText: String get() = when (this) {
    ImageRole.REFERENCE -> "参考图"
    ImageRole.FIRST_FRAME -> "首帧"
    ImageRole.LAST_FRAME -> "尾帧"
}

private val MediaCreationStatus.labelText: String get() = when (this) {
    MediaCreationStatus.PREPARING -> "准备 / 上传素材"
    MediaCreationStatus.QUEUED -> "排队中"
    MediaCreationStatus.RUNNING -> "生成中"
    MediaCreationStatus.DOWNLOADING -> "保存产出中"
    MediaCreationStatus.SUCCEEDED -> "已完成"
    MediaCreationStatus.FAILED -> "失败"
    MediaCreationStatus.CANCELLED -> "已停止等待"
}

@Composable
private fun MediaParamsDialog(
    initial: MediaCreationParams, supported: Set<MediaGenerationParameter>,
    onDismiss: () -> Unit, onSave: (MediaCreationParams) -> Unit,
) {
    var resolution by remember { mutableStateOf(initial.resolution.orEmpty()) }
    var ratio by remember { mutableStateOf(initial.aspectRatio.orEmpty()) }
    var duration by remember { mutableStateOf(initial.durationSeconds?.toString().orEmpty()) }
    var count by remember { mutableStateOf(initial.count?.toString().orEmpty()) }
    var seed by remember { mutableStateOf(initial.seed?.toString().orEmpty()) }
    var audio by remember { mutableStateOf(initial.generateAudio) }
    var watermark by remember { mutableStateOf(initial.watermark) }
    var enhancement by remember { mutableStateOf(initial.promptEnhancement) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("生成参数") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("留空遵循接口默认值；必填项会采用厂商预设。模型是否接受这些值以服务端为准。")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (MediaGenerationParameter.RESOLUTION in supported)
                    OutlinedTextField(resolution, { resolution = it }, label = { Text("分辨率 / 尺寸") }, singleLine = true)
                if (MediaGenerationParameter.ASPECT_RATIO in supported)
                    OutlinedTextField(ratio, { ratio = it }, label = { Text("比例，例如 16:9") }, singleLine = true)
                if (MediaGenerationParameter.DURATION in supported)
                    OutlinedTextField(duration, { duration = it }, label = { Text("时长（秒；-1 为智能）") }, singleLine = true)
                if (MediaGenerationParameter.COUNT in supported)
                    OutlinedTextField(count, { count = it }, label = { Text("数量") }, singleLine = true)
                if (MediaGenerationParameter.SEED in supported)
                    OutlinedTextField(seed, { seed = it }, label = { Text("随机种子") }, singleLine = true)
                @Composable fun choice(label: String, value: Boolean?, set: (Boolean?) -> Unit) {
                    Text(label)
                    Select(listOf(null, true, false), value, set,
                        optionToString = { when (it) { null -> "默认"; true -> "开启"; false -> "关闭" } })
                }
                if (MediaGenerationParameter.GENERATE_AUDIO in supported) choice("声音", audio) { audio = it }
                if (MediaGenerationParameter.WATERMARK in supported) choice("水印", watermark) { watermark = it }
                if (MediaGenerationParameter.PROMPT_ENHANCEMENT in supported)
                    choice("提示词增强", enhancement) { enhancement = it }
            }
        },
        confirmButton = { TextButton(onClick = {
            try {
                fun intValue(text: String): Int? = text.trim().takeIf { it.isNotEmpty() }?.let {
                    requireNotNull(it.toIntOrNull()) { "数量和时长必须是整数" }
                }
                val n = intValue(count)
                val seconds = intValue(duration)
                require(n == null || n > 0) { "数量必须大于 0" }
                require(seconds == null || seconds == -1 || seconds > 0) { "时长必须大于 0 或为 -1" }
                val randomSeed = seed.trim().takeIf { it.isNotEmpty() }?.let {
                    requireNotNull(it.toLongOrNull()) { "随机种子必须是整数" }
                }
                onSave(MediaCreationParams(n, resolution.trim().ifEmpty { null }, ratio.trim().ifEmpty { null },
                    seconds, audio, watermark, randomSeed, enhancement))
            } catch (e: IllegalArgumentException) { error = e.message }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
