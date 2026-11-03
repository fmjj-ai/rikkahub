package me.rerere.rikkahub.service

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById

/** 标题和建议统一走快速模型；旧独立选择只留作备份兼容，不再作为回退。 */
internal fun Settings.requireFastModel(): Model {
    val model = findModelById(fastModelId)
        ?: error("请先在设置 → 默认模型中选择可用的快速模型，标题与聊天建议均使用该模型。")
    check(model.type == ModelType.CHAT) { "快速模型必须是聊天模型，请检查默认模型设置。" }
    return model
}
