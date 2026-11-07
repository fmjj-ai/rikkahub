package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEGACY_COMPRESS_PROMPT
import me.rerere.rikkahub.data.model.Assistant

internal fun Assistant.withOfficialDefaultPrompt(): Assistant {
    val default = DEFAULT_ASSISTANTS[1]
    val oldPrompt = default.systemPrompt.replace("- Date: {{cur_date}}", "- Time: {{cur_datetime}}")
    // 仅迁移未被用户编辑过的内置文本，不改用户助手、会话或工作区提示。
    return if (id == default.id && systemPrompt == oldPrompt) copy(systemPrompt = default.systemPrompt) else this
}

internal fun officialDefaultCompressPrompt(prompt: String): String =
    if (prompt == LEGACY_COMPRESS_PROMPT) DEFAULT_COMPRESS_PROMPT else prompt
