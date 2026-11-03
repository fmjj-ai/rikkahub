package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.Screen
import kotlin.uuid.Uuid

/** 冷启动通知也保留聊天根页面，目标媒体会话已删除时仍可正常返回。 */
internal fun mediaCreationInitialScreens(chat: Screen.Chat, sessionId: String?): List<Screen> {
    val valid = sessionId?.takeIf { runCatching { Uuid.parse(it) }.isSuccess }
    return if (valid == null) listOf(chat) else listOf(chat, Screen.MediaCreation(valid))
}
