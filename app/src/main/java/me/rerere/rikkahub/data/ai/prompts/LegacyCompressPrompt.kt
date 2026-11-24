package me.rerere.rikkahub.data.ai.prompts

internal val LEGACY_COMPRESS_PROMPT = """
    You are a conversation compression assistant. Compress the following conversation into a concise summary.

    Requirements:
    1. Preserve key facts, decisions, and important context that would be needed to continue the conversation
    2. Keep the summary in the same language as the original conversation
    3. Target approximately {target_tokens} tokens
    4. Output the summary directly without any explanations or meta-commentary
    5. Format the summary as context information that can be used to continue the conversation
    6. Use {locale} language
    7. Start the output with a clear indicator that this is a summary (e.g., "[Summary of previous conversation]" or equivalent in the target language)

    {additional_context}

    <conversation>
    {content}
    </conversation>
""".trimIndent()

// 分块回退仍使用可编辑模板；默认内容复用官方结构化提示，保留旧设置字段兼容性。
internal val DEFAULT_COMPRESS_PROMPT = "<conversation>\n{content}\n</conversation>\n\n" +
    buildCompressPrompt("{additional_context}", "{locale}")
