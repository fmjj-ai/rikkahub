package me.rerere.rikkahub.ui.pages.mediacreation

import me.rerere.mediagen.model.MediaKind
import me.rerere.mediagen.provider.MediaGenerationProviderSetting
import me.rerere.rikkahub.data.model.MediaCreationParams

internal const val AUTO_DURATION = -1
internal data class MediaCreationPresets(
    val resolutions: List<String> = emptyList(),
    val aspectRatios: List<String> = emptyList(),
    val durations: List<Int> = emptyList(),
    val counts: List<Int> = emptyList(),
) {
    /** 接口必填的参数没有设置时使用的值：各自的第一个快捷选项。 */
    val defaults: MediaCreationParams
        get() = MediaCreationParams(
            count = counts.firstOrNull(),
            resolution = resolutions.firstOrNull(),
            aspectRatio = aspectRatios.firstOrNull(),
            durationSeconds = durations.firstOrNull(),
        )
}

internal fun MediaGenerationProviderSetting.presets(kind: MediaKind): MediaCreationPresets = when (this) {
    is MediaGenerationProviderSetting.OpenAI -> MediaCreationPresets(
        resolutions = listOf("1024x1024", "1536x1024", "1024x1536"),
        counts = IMAGE_COUNTS,
    )

    is MediaGenerationProviderSetting.Volcengine -> when (kind) {
        // 各代 Seedream 的档位不同：5.0 pro / flash 是 1K、1.5K、2K，5.0 lite 是 2K、3K、4K，4.5 是 2K、4K
        MediaKind.IMAGE -> MediaCreationPresets(resolutions = listOf("1K", "1.5K", "2K", "3K", "4K"))
        // 智能时长是 Seedance 2.0 起才有的
        MediaKind.VIDEO -> MediaCreationPresets(
            resolutions = listOf("480p", "720p", "1080p"),
            aspectRatios = VIDEO_ASPECT_RATIOS + "adaptive",
            durations = VIDEO_DURATIONS + AUTO_DURATION,
        )
    }

    is MediaGenerationProviderSetting.Aliyun -> when (kind) {
        // 千问 3.0 的 size 只接受像素尺寸，万相 2.7 另外还接受 1K、2K 档位
        MediaKind.IMAGE -> MediaCreationPresets(
            resolutions = listOf("1024x1024", "1536x1024", "1024x1536", "2048x2048"),
            counts = IMAGE_COUNTS,
        )
        MediaKind.VIDEO -> MediaCreationPresets(
            resolutions = listOf("480P", "720P", "1080P"),
            aspectRatios = VIDEO_ASPECT_RATIOS,
            durations = VIDEO_DURATIONS + AUTO_DURATION,
        )
    }

    // 分辨率、比例、时长是必填的，排在最前面的作为默认值；768P 是 H3 和 H3-Max 都支持的档位
    is MediaGenerationProviderSetting.MiniMax -> MediaCreationPresets(
        resolutions = listOf("768P", "2K", "480P"),
        aspectRatios = VIDEO_ASPECT_RATIOS + "adaptive",
        durations = VIDEO_DURATIONS,
    )

    // 背后是各家的模型，取值取的是多数模型都接受的那些
    is MediaGenerationProviderSetting.OpenRouter -> when (kind) {
        MediaKind.IMAGE -> MediaCreationPresets(
            resolutions = listOf("1K", "2K", "4K"),
            aspectRatios = listOf("1:1", "3:2", "2:3", "4:3", "3:4", "16:9", "9:16"),
            counts = IMAGE_COUNTS,
        )

        MediaKind.VIDEO -> MediaCreationPresets(
            resolutions = listOf("480p", "720p", "1080p", "4K"),
            aspectRatios = VIDEO_ASPECT_RATIOS,
            durations = listOf(4, 5, 6, 8, 10),
        )
    }
}

private val IMAGE_COUNTS = listOf(1, 2, 3, 4)
private val VIDEO_ASPECT_RATIOS = listOf("16:9", "9:16", "1:1", "4:3", "3:4", "21:9")
private val VIDEO_DURATIONS = listOf(5, 10)
