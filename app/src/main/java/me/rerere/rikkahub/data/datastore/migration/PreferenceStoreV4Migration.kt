package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.JsonInstant

// 迁移必须使用固定值，不能引用会再次变化的默认值常量。
private const val LEGACY_CONTEXT_WINDOW_TOKENS = 472_000
private const val LEGACY_AUTO_COMPRESS_THRESHOLD_TOKENS = 272_000
private const val UPGRADED_CONTEXT_WINDOW_TOKENS = 1_000_000
private const val UPGRADED_AUTO_COMPRESS_THRESHOLD_TOKENS = 472_000

/**
 * 把仍然使用旧默认值（472k 上下文 / 272k 自动压缩阈值）的模型一次性升级到 1M / 472k。
 *
 * 只有两个字段都等于旧默认值时才更新，用户手动调整过任意一项的模型保持不变。
 */
class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        prefs[SettingsStore.PROVIDERS] = migrateProviderModelDefaults(
            prefs[SettingsStore.PROVIDERS] ?: "[]"
        )
        prefs[SettingsStore.VERSION] = 4
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}

internal fun migrateProviderModelDefaults(providersJson: String): String {
    return runCatching {
        val root = JsonInstant.parseToJsonElement(providersJson) as? JsonArray
            ?: return@runCatching providersJson
        var changed = false
        val migrated = JsonArray(
            root.map { provider ->
                val providerObj = provider as? JsonObject ?: return@map provider
                val models = providerObj["models"] as? JsonArray ?: return@map provider
                var modelsChanged = false
                val migratedModels = JsonArray(
                    models.map { model ->
                        val modelObj = model as? JsonObject ?: return@map model
                        val contextWindow = modelObj["contextWindowTokens"]?.jsonPrimitive?.intOrNull
                        val threshold = modelObj["autoCompressThresholdTokens"]?.jsonPrimitive?.intOrNull
                        if (contextWindow == LEGACY_CONTEXT_WINDOW_TOKENS &&
                            threshold == LEGACY_AUTO_COMPRESS_THRESHOLD_TOKENS
                        ) {
                            modelsChanged = true
                            JsonObject(
                                modelObj.toMutableMap().apply {
                                    put(
                                        "contextWindowTokens",
                                        JsonPrimitive(UPGRADED_CONTEXT_WINDOW_TOKENS)
                                    )
                                    put(
                                        "autoCompressThresholdTokens",
                                        JsonPrimitive(UPGRADED_AUTO_COMPRESS_THRESHOLD_TOKENS)
                                    )
                                }
                            )
                        } else {
                            model
                        }
                    }
                )
                if (modelsChanged) {
                    changed = true
                    JsonObject(providerObj.toMutableMap().apply { put("models", migratedModels) })
                } else {
                    provider
                }
            }
        )
        if (changed) JsonInstant.encodeToString(migrated) else providersJson
    }.getOrElse { providersJson }
}
