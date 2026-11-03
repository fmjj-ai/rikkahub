package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.remote.RemoteFileStore
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private val uploadSaver = Saver<S3Config, String>(
    save = { JsonInstant.encodeToString(it) }, restore = { JsonInstant.decodeFromString<S3Config>(it) },
)

@Composable
fun SettingMediaUploadPage(vm: SettingVM = koinViewModel(), remote: RemoteFileStore = koinInject()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var draft by rememberSaveable(stateSaver = uploadSaver) { mutableStateOf(settings.uploadS3Config) }
    var original by rememberSaveable(stateSaver = uploadSaver) { mutableStateOf(settings.uploadS3Config) }
    var initialized by rememberSaveable { mutableStateOf(!settings.init) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(settings.init) {
        if (!settings.init && !initialized) {
            // 冷启动等待真实配置；页面恢复时不覆盖已经保存的输入草稿。
            if (original == S3Config() && draft == S3Config()) draft = settings.uploadS3Config
            original = settings.uploadS3Config
            initialized = true
        }
    }
    suspend fun save() {
        val current = draft.copy(
            endpoint = draft.endpoint.trim(), bucket = draft.bucket.trim(), region = draft.region.trim(),
        )
        vm.saveSettings { latest ->
            require(latest.uploadS3Config == original || latest.uploadS3Config == current) {
                "上传配置已在其他位置修改，请重新打开页面"
            }
            latest.copy(uploadS3Config = current)
        }
        original = current
        draft = current
    }
    Scaffold(containerColor = CustomColors.topBarColors.containerColor, topBar = {
        TopAppBar(title = { Text("媒体素材上传") }, navigationIcon = { BackButton() }, colors = CustomColors.topBarColors)
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("仅供需要公网参考素材的接口使用，与备份 S3 设置独立。文件按内容哈希上传至 rikkahub_uploads/，" +
                "通过临时签名地址访问，桶可保持私有；缓存保留 7 天。文生图及支持本地素材的接口不需要配置。",
                style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            OutlinedTextField(draft.endpoint, { draft = draft.copy(endpoint = it) }, label = { Text("S3 Endpoint") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            OutlinedTextField(draft.accessKeyId, { draft = draft.copy(accessKeyId = it.trim()) },
                label = { Text("Access Key ID") }, modifier = Modifier.fillMaxWidth(),
                singleLine = true, enabled = !busy)
            OutlinedTextField(draft.secretAccessKey, { draft = draft.copy(secretAccessKey = it.trim()) },
                label = { Text("Secret Access Key") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                enabled = !busy, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(draft.bucket, { draft = draft.copy(bucket = it) }, label = { Text("Bucket") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            OutlinedTextField(draft.region, { draft = draft.copy(region = it) }, label = { Text("Region") },
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            Row { Text("Path-style 地址", Modifier.weight(1f)); Switch(draft.pathStyle,
                { draft = draft.copy(pathStyle = it) }, enabled = !busy) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && initialized, onClick = {
                    busy = true
                    scope.launch {
                        try { save(); message = "已保存" }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = e.message ?: "保存失败" }
                        finally { busy = false }
                    }
                }) { Text("保存") }
                OutlinedButton(enabled = !busy && initialized, onClick = {
                    busy = true
                    scope.launch {
                        try { save(); remote.testConnection().getOrThrow(); message = "连接成功" }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = e.message ?: "连接失败" }
                        finally { busy = false }
                    }
                }) { Text("保存并测试连接") }
            }
        }
    }
}
