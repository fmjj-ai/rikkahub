package me.rerere.rikkahub.data.model

internal enum class ImageAttachmentSource { CAMERA, GALLERY }

/** 本阶段保留原拍照确认流程；相册无论张数都直接加入草稿，点击附件才进入裁剪。 */
internal fun shouldEditImageAttachment(
    source: ImageAttachmentSource,
    imageCount: Int,
    @Suppress("UNUSED_PARAMETER") skipImageEditing: Boolean,
): Boolean = imageCount == 1 && source == ImageAttachmentSource.CAMERA
