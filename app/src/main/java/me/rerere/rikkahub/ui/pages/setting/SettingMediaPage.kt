package me.rerere.rikkahub.ui.pages.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.mediagen.model.MediaGenerationModel
import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.model.saveMediaProvider
import me.rerere.rikkahub.data.model.validated
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

/** 使用当前主题和卡片组件，不导入官方新版工具栏/图标依赖。 */
@Composable
fun SettingMediaPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    var showTypes by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val factories = remember {
        listOf<() -> MediaGenerationProviderSetting>(
            { MediaGenerationProviderSetting.OpenAI() },
            { MediaGenerationProviderSetting.Aliyun() },
            { MediaGenerationProviderSetting.Volcengine() },
            { MediaGenerationProviderSetting.MiniMax() },
            { MediaGenerationProviderSetting.OpenRouter() },
        )
    }
    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text("媒体提供商") },
                navigationIcon = { BackButton() },
                colors = CustomColors.topBarColors,
                actions = {
                    IconButton(onClick = { showTypes = true }, enabled = !settings.init && !adding) {
                        Icon(HugeIcons.Add01, "添加媒体提供商")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "图像与视频接口独立配置，不替换聊天提供商、旧生图模型或聊天生图工具。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { nav.navigate(Screen.SettingMediaUpload) }) { Text("参考素材上传存储") }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (settings.mediaGenerationProviders.isEmpty()) {
                item { Text("尚未添加媒体提供商，点击右上角添加。") }
            }
            items(settings.mediaGenerationProviders, key = { it.id }) { provider ->
                CardGroup {
                    item(
                        onClick = { nav.navigate(Screen.SettingMediaProviderDetail(provider.id.toString())) },
                        headlineContent = { Text(provider.name.ifBlank { provider.typeLabel }) },
                        supportingContent = {
                            Text("${provider.typeLabel} · 图片 ${provider.models.count { it.kind == MediaKind.IMAGE }}" +
                                " · 视频 ${provider.models.count { it.kind == MediaKind.VIDEO }}")
                        },
                    )
                }
            }
        }
    }
    if (showTypes) {
        AlertDialog(
            onDismissRequest = { if (!adding) showTypes = false },
            title = { Text("添加媒体提供商") },
            text = {
                Column {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    factories.forEach { create ->
                        val provider = remember(create) { create() }
                        TextButton(
                            enabled = !adding,
                            onClick = {
                                adding = true
                                scope.launch {
                                    try {
                                        vm.saveSettings { latest ->
                                            if (latest.mediaGenerationProviders.any { it.id == provider.id }) latest
                                            else latest.copy(
                                                mediaGenerationProviders = latest.mediaGenerationProviders + provider,
                                            )
                                        }
                                        showTypes = false
                                        nav.navigate(Screen.SettingMediaProviderDetail(provider.id.toString()))
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        // 保持本次类型实例和 UUID；再次点击同类型只重试落盘，不另建一份。
                                        error = "${e.message ?: "保存失败"}。配置可能已暂存在内存，可重试此类型，或返回列表打开后保存。"
                                    } finally {
                                        adding = false
                                    }
                                }
                            },
                        ) { Text(provider.typeLabel) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showTypes = false }, enabled = !adding) { Text("取消") } },
        )
    }
}

@Composable
fun SettingMediaProviderDetailPage(id: Uuid, vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val provider = settings.mediaGenerationProviders.find { it.id == id }
    val nav = LocalNavController.current
    // 删除记录会先更新内存，此作用域不能随着 provider == null 的分支被销毁。
    val scope = rememberCoroutineScope()
    if (provider == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("媒体提供商") }, navigationIcon = { BackButton() }) }) { padding ->
            Text(if (settings.init) "正在加载…" else "提供商已被删除", Modifier.padding(padding).padding(16.dp))
        }
        return
    }
    // 在整个编辑周期内保留基线，以便发现外部修改，避免旧草稿覆盖新配置。
    var original by rememberSaveable(id.toString(), stateSaver = mediaProviderSaver) { mutableStateOf(provider) }
    var draft by rememberSaveable(id.toString(), stateSaver = mediaProviderSaver) { mutableStateOf(provider) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val dirty = draft != original
    val leave = { if (dirty) confirmLeave = true else nav.popBackStack(); Unit }
    BackHandler(enabled = dirty || busy) { if (!busy) confirmLeave = true }
    Scaffold(
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            TopAppBar(
                title = { Text(draft.name.ifBlank { draft.typeLabel }) },
                colors = CustomColors.topBarColors,
                navigationIcon = { TextButton(onClick = leave, enabled = !busy) { Text("返回") } },
                actions = {
                    TextButton(enabled = !busy, onClick = { confirmDelete = true }) { Text("删除") }
                    TextButton(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val saved = draft.validated()
                                vm.saveSettings { it.saveMediaProvider(original, saved) }
                                original = saved
                                draft = saved
                                error = null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                val current = vm.latestSettings.mediaGenerationProviders.find { it.id == id }
                                if (current != null && current == runCatching { draft.validated() }.getOrNull()) {
                                    // 内存修改已成功而磁盘失败，后续继续编辑时以这份内存值为基线。
                                    original = current
                                }
                                error = e.message ?: "保存失败"
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text(if (busy) "保存中…" else "保存") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text("${draft.typeLabel} · 点击保存后生效", style = MaterialTheme.typography.bodySmall)
            MediaConfigurationCard {
                OutlinedTextField(
                    value = draft.name, onValueChange = { draft = draft.copyProvider(name = it) },
                    label = { Text("名称") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                )
                OutlinedTextField(
                    value = draft.apiKey, onValueChange = { draft = draft.copyProvider(apiKey = it) },
                    label = { Text("API Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
                    },
                )
                OutlinedTextField(
                    value = draft.baseUrl, onValueChange = { draft = draft.copyProvider(baseUrl = it) },
                    label = { Text("Base URL") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, enabled = !busy,
                )
                (draft as? MediaGenerationProviderSetting.Aliyun)?.let { aliyun ->
                    OutlinedTextField(
                        value = aliyun.workspaceId, onValueChange = { draft = aliyun.copy(workspaceId = it) },
                        label = { Text("Workspace ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        enabled = !busy,
                        supportingText = { Text("地址含 {WorkspaceId} 时使用；自定义完整地址可留空。") },
                    )
                }
            }
            Text("模型", style = MaterialTheme.typography.titleMedium)
            draft.models.forEach { model ->
                androidx.compose.runtime.key(model.id) {
                    MediaConfigurationCard {
                        Row {
                            OutlinedTextField(
                                value = model.modelId,
                                onValueChange = { value -> draft = draft.copyProvider(models = draft.models.map {
                                    if (it.id == model.id) it.copy(modelId = value) else it
                                }) },
                                label = { Text("模型 ID") }, modifier = Modifier.weight(1f), singleLine = true,
                                enabled = !busy,
                            )
                            IconButton(enabled = !busy, onClick = {
                                draft = draft.copyProvider(models = draft.models.filter { it.id != model.id })
                            }) { Icon(HugeIcons.Delete01, "删除模型") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MediaKind.entries.filter { it in draft.supportedKinds }.forEach { kind ->
                                FilterChip(
                                    selected = model.kind == kind, enabled = !busy,
                                    onClick = { draft = draft.copyProvider(models = draft.models.map {
                                        if (it.id == model.id) it.copy(kind = kind) else it
                                    }) },
                                    label = { Text(if (kind == MediaKind.IMAGE) "图片" else "视频") },
                                )
                            }
                        }
                    }
                }
            }
            Button(enabled = !busy, onClick = {
                draft = draft.copyProvider(
                    models = draft.models + MediaGenerationModel("", draft.supportedKinds.first()),
                )
            }) { Text("添加模型") }
            Text("空模型 ID 不保存；模型及参数是否可用以实际服务端为准。", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmDelete || confirmLeave) {
        val deleting = confirmDelete
        AlertDialog(
            onDismissRequest = { if (!busy) { confirmDelete = false; confirmLeave = false } },
            title = { Text(if (deleting) "删除提供商？" else "放弃未保存的修改？") },
            text = { Text(if (deleting) "仅删除这份媒体配置，不删除聊天提供商和旧生图设置。" else "此页面的草稿尚未保存。") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    if (!deleting) {
                        confirmLeave = false
                        nav.popBackStack()
                    } else {
                        busy = true
                        scope.launch {
                            try {
                                vm.saveSettings { latest -> latest.copy(
                                    mediaGenerationProviders = latest.mediaGenerationProviders.filter { it.id != id },
                                ) }
                                confirmDelete = false
                                nav.popBackStack()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "删除失败"
                                confirmDelete = false
                            } finally { busy = false }
                        }
                    }
                }) { Text(if (deleting) "删除" else "放弃") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { confirmDelete = false; confirmLeave = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MediaConfigurationCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = CustomColors.listItemColors.containerColor),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

private val mediaProviderSaver = Saver<MediaGenerationProviderSetting, String>(
    save = { JsonInstant.encodeToString<MediaGenerationProviderSetting>(it) },
    restore = { runCatching { JsonInstant.decodeFromString<MediaGenerationProviderSetting>(it) }.getOrNull() },
)

private val MediaGenerationProviderSetting.typeLabel: String
    get() = when (this) {
        is MediaGenerationProviderSetting.OpenAI -> "OpenAI"
        is MediaGenerationProviderSetting.Aliyun -> "阿里百炼"
        is MediaGenerationProviderSetting.Volcengine -> "火山方舟"
        is MediaGenerationProviderSetting.MiniMax -> "MiniMax"
        is MediaGenerationProviderSetting.OpenRouter -> "OpenRouter"
    }
