package me.rerere.rikkahub.ui.pages.setting.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.chatgpt.ChatGPTProvider
import me.rerere.ai.provider.providers.chatgpt.ChatGPTUsage
import me.rerere.rikkahub.data.ai.account.AccountLoginStatus
import me.rerere.rikkahub.data.ai.account.ChatGPTAuth
import me.rerere.rikkahub.data.ai.account.ProviderAccountManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ProviderConfigureChatGPT(
    provider: ProviderSetting.ChatGPT,
    modifier: Modifier = Modifier,
    onEdit: (ProviderSetting) -> Unit,
) {
    val accounts: ProviderAccountManager = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val providers: ProviderManager = koinInject()
    val settings by settingsStore.settingsFlow.collectAsState()
    val statuses by accounts.loginStatus.collectAsState()
    val saved = settings.providers.find { it.id == provider.id } as? ProviderSetting.ChatGPT
    val credential = saved?.credential
    val status = statuses[provider.id]
    val signingIn = status == AccountLoginStatus.Authorizing
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var usage by remember(provider.id, credential?.accountId, credential != null) { mutableStateOf<ChatGPTUsage?>(null) }
    var usageError by remember(provider.id, credential?.accountId, credential != null) { mutableStateOf<String?>(null) }
    var loading by remember(provider.id) { mutableStateOf(false) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = provider.name,
            onValueChange = { onEdit(provider.copy(name = it)) },
            label = { Text("名称") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("启用")
            Switch(checked = provider.enabled, onCheckedChange = { onEdit(provider.copy(enabled = it)) })
        }
        Text("通过 ChatGPT 账号授权使用可用的 Codex 订阅额度。模型与额度以账号实际权限为准。",
            style = MaterialTheme.typography.bodySmall)
        if (saved == null) {
            Text("请先添加提供商，再进入配置页登录。")
        } else {
            Text(credential?.email ?: if (credential != null) "已登录 ChatGPT" else "尚未登录")
            credential?.plan?.let { Text("订阅：${ChatGPTAuth.planDisplayName(it)}") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { accounts.startLogin(provider.id, context) }, enabled = !signingIn) {
                    Text(if (signingIn) "等待浏览器授权…" else if (credential == null) "登录 ChatGPT" else "重新登录")
                }
                if (signingIn) {
                    OutlinedButton(onClick = { accounts.cancelLogin(provider.id) }) { Text("取消") }
                } else if (credential != null) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            try { accounts.logout(provider.id) } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                usageError = e.message ?: "退出登录失败"
                            }
                        }
                    }) { Text("退出登录") }
                }
            }
            if (status is AccountLoginStatus.Failed) {
                Text(status.message, color = MaterialTheme.colorScheme.error)
            }
            if (credential != null) {
                OutlinedButton(enabled = !loading && !signingIn, onClick = {
                    scope.launch {
                        loading = true
                        usageError = null
                        try {
                            usage = (providers.getProvider("chatgpt") as ChatGPTProvider).getUsage(saved)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            usageError = e.message ?: "额度查询失败"
                        } finally { loading = false }
                    }
                }) { Text(if (loading) "查询中…" else "查询订阅用量") }
                usage?.let { value ->
                    if (value.limitReached) Text("当前已达到使用限额", color = MaterialTheme.colorScheme.error)
                    if (value.windows.isEmpty()) Text("账号未返回用量窗口")
                    value.windows.forEachIndexed { index, window ->
                        val label = window.windowSeconds?.let { seconds ->
                            when {
                                seconds % 86400L == 0L -> "${seconds / 86400} 天"
                                seconds % 3600L == 0L -> "${seconds / 3600} 小时"
                                else -> "${seconds / 60} 分钟"
                            }
                        } ?: "窗口 ${index + 1}"
                        Text("$label：已用 ${String.format(Locale.getDefault(), "%.1f", window.usedPercent)}%")
                        window.resetAtSeconds?.let { seconds ->
                            val time = runCatching {
                                DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochSecond(seconds))
                            }.getOrNull()
                            if (time != null) Text("重置时间：$time", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("登录后到“模型”页获取模型列表。备份会包含登录会话；跨设备恢复后可能需要重新登录。",
                    style = MaterialTheme.typography.bodySmall)
            }
            usageError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
