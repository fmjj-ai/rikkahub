package me.rerere.rikkahub.data.model

import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.datastore.Settings
import java.net.URI

/** 只规整用户输入，不修改模型 UUID、厂商类型或大小写敏感的 URL 路径。 */
fun MediaGenerationProviderSetting.validated(): MediaGenerationProviderSetting {
    val url = baseUrl.trim().trimEnd('/')
    val parsed = runCatching {
        URI(url.replace(MediaGenerationProviderSetting.Aliyun.WORKSPACE_PLACEHOLDER, "workspace"))
    }.getOrNull()
    require(parsed != null && parsed.scheme?.lowercase() in setOf("http", "https") &&
        !parsed.host.isNullOrBlank() && parsed.userInfo == null && parsed.query == null && parsed.fragment == null
    ) { "Base URL 必须是有效的 HTTP(S) 地址，且不含用户名、查询参数或片段" }
    val selectedModels = models.filter { it.modelId.isNotBlank() }.map { it.copy(modelId = it.modelId.trim()) }
    require(selectedModels.all { it.kind in supportedKinds }) { "该提供商不支持所选媒体类型" }
    require(selectedModels.distinctBy { it.id }.size == selectedModels.size) { "模型 UUID 重复，请重新添加重复项" }
    val saved = copyProvider(name = name.trim(), apiKey = apiKey.trim(), baseUrl = url, models = selectedModels)
    return if (saved is MediaGenerationProviderSetting.Aliyun) {
        saved.copy(workspaceId = saved.workspaceId.trim())
    } else saved
}

/** 草稿只覆盖其所属提供商；已删除或被另一个页面编辑的配置不静默复活/覆盖。 */
fun Settings.saveMediaProvider(
    original: MediaGenerationProviderSetting,
    draft: MediaGenerationProviderSetting,
): Settings {
    require(original.id == draft.id && original::class == draft::class) { "提供商类型和 UUID 不可变更" }
    val current = mediaGenerationProviders.find { it.id == original.id }
        ?: error("提供商已被删除，请返回列表")
    val saved = draft.validated()
    // 上次可能已改内存但落盘失败；允许同一草稿重试落盘，不把它误报为外部冲突。
    require(current == original || current == saved) { "配置已在其他位置修改，请返回后重新打开" }
    return copy(mediaGenerationProviders = mediaGenerationProviders.map { if (it.id == saved.id) saved else it })
}
