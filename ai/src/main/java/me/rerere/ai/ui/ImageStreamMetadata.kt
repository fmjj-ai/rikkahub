package me.rerere.ai.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 图片流标记放进既有 metadata，旧消息和普通分片图片无需改变序列化结构。 */
fun imageStreamMetadata(id: String, complete: Boolean, index: Int = -1): JsonObject = buildJsonObject {
    put("rikkahub_image_id", id)
    put("rikkahub_image_complete", complete)
    put("rikkahub_image_index", index)
}

val UIMessagePart.Image.streamImageId: String?
    get() = metadata?.get("rikkahub_image_id")?.jsonPrimitive?.contentOrNull

val UIMessagePart.Image.isIncompleteStreamImage: Boolean
    get() = streamImageId != null && metadata?.get("rikkahub_image_complete")?.jsonPrimitive?.contentOrNull != "true"

internal fun UIMessagePart.Image.acceptsSnapshot(next: UIMessagePart.Image): Boolean {
    if (!isIncompleteStreamImage) return false
    val oldIndex = metadata?.get("rikkahub_image_index")?.jsonPrimitive?.intOrNull ?: -1
    val newIndex = next.metadata?.get("rikkahub_image_index")?.jsonPrimitive?.intOrNull ?: -1
    return !next.isIncompleteStreamImage || newIndex >= oldIndex
}

/** 中止时移除未完成快照，避免将半成品持久化成成功结果或再次发送给模型。 */
fun UIMessage.withoutIncompleteStreamImages(): UIMessage {
    val remaining = parts.filterNot { it is UIMessagePart.Image && it.isIncompleteStreamImage }
    return if (remaining.size == parts.size) this else copy(parts = remaining)
}
