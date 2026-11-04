package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.net.URI

internal data class ImageReference(val id: String, val url: String)

internal fun imageReferences(messages: List<UIMessage>): List<ImageReference> = buildList {
    messages.filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }.forEach { message ->
        message.parts.forEachIndexed { index, part ->
            when (part) {
                is UIMessagePart.Image -> add(ImageReference("img_${message.id}_$index", part.url))
                is UIMessagePart.Tool -> part.output.forEachIndexed { outputIndex, output ->
                    if (output is UIMessagePart.Image) {
                        add(ImageReference("img_${message.id}_${index}_$outputIndex", output.url))
                    }
                }
                else -> Unit
            }
        }
    }
}.takeLast(32)

internal fun labelImageReferences(messages: List<UIMessage>): List<UIMessage> {
    val available = imageReferences(messages).associateBy { it.id }
    fun label(id: String): List<UIMessagePart> = if (id in available) {
        listOf(UIMessagePart.Text("[Reference image ID: $id]"))
    } else emptyList()
    return messages.map { message ->
        message.copy(parts = message.parts.flatMapIndexed { index, part ->
            when (part) {
                is UIMessagePart.Image -> label("img_${message.id}_$index") + part
                is UIMessagePart.Tool -> listOf(part.copy(output = part.output.flatMapIndexed { outputIndex, output ->
                    if (output is UIMessagePart.Image) label("img_${message.id}_${index}_$outputIndex") + output
                    else listOf(output)
                }))
                else -> listOf(part)
            }
        })
    }
}

internal fun selectImageReferences(value: JsonElement?, messages: List<UIMessage>): List<ImageReference> {
    if (value == null) return emptyList()
    require(value is JsonArray && value.size <= 4) { "reference_image_ids 必须是最多 4 项的图片 ID 数组" }
    val ids = value.map {
        require(it is JsonPrimitive && it.isString) { "参考图 ID 必须是字符串" }
        it.content
    }.distinct()
    val available = imageReferences(messages).associateBy { it.id }
    return ids.map { available[it] ?: error("参考图 ID 已失效或不在当前上下文中：$it，请重新选择") }
}

internal fun imageReferenceFiles(references: List<ImageReference>, filesDir: File): List<File> {
    val root = filesDir.canonicalFile.toPath()
    return references.map { reference ->
        val uri = runCatching { URI(reference.url) }.getOrNull()
        require(uri?.scheme == "file" && uri.authority.isNullOrEmpty()) { "参考图必须是已保存到当前会话的本地图片" }
        val file = File(uri).canonicalFile
        require(file.toPath().startsWith(root) && file.isFile && file.length() in 1..50L * 1024 * 1024) {
            "参考图不存在、超出应用目录或大于 50MB，请重新添加图片"
        }
        file
    }
}
