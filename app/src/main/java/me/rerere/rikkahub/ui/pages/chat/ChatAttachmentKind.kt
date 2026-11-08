package me.rerere.rikkahub.ui.pages.chat

/** 显式选择器保留媒体类型，分享入口才根据每个源文件的 MIME 自动分类。 */
enum class ChatAttachmentKind {
    AUTO,
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
}
