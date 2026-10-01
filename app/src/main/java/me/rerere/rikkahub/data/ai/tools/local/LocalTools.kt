package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.util.Log
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ProviderManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findImageGenerationModel
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.tts.provider.TTSManager

private const val TAG = "LocalTools"

class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val providerManager: ProviderManager,
    private val filesManager: FilesManager,
    private val genMediaRepository: GenMediaRepository,
) {
    val javascriptTool by lazy { buildJavascriptTool() }

    val timeTool by lazy { buildTimeInfoTool() }

    val clipboardTool by lazy { buildClipboardTool(context) }

    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }

    val askUserTool by lazy { buildAskUserTool() }

    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }

    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }

    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }

    val imageGenerationTool by lazy {
        buildImageGenerationTool(
            context = context,
            settingsStore = settingsStore,
            providerManager = providerManager,
            filesManager = filesManager,
            genMediaRepository = genMediaRepository,
        )
    }

    /**
     * 生图能力是否真的可用。
     *
     * 未配置可解析的生图模型时返回 false，[getTools] 便不会注入该工具，
     * 避免主模型调用一个必然失败的能力（需求 R2.2.5 / Q2.10 的优雅降级要求）。
     *
     * 解析顺序：生图页显式选中的模型 -> 第一个 IMAGE 类型模型（见 findImageGenerationModel）。
     */
    private fun isImageGenerationAvailable(): Boolean {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findImageGenerationModel()
        if (model == null) {
            Log.i(TAG, "getTools: 未配置生图模型(IMAGE 类型或生图页选中)，跳过 draw_image")
            return false
        }
        val provider = model.findProvider(settings.providers)
        if (provider == null) {
            Log.w(TAG, "getTools: 生图模型 ${model.modelId} 找不到服务商，跳过 draw_image")
            return false
        }
        Log.i(TAG, "getTools: 注入 draw_image，使用 ${model.modelId} @ ${provider.name}")
        return true
    }

    fun getTools(options: List<LocalToolOption>): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) {
            tools.add(javascriptTool)
        }
        if (options.contains(LocalToolOption.TimeInfo)) {
            tools.add(timeTool)
        }
        if (options.contains(LocalToolOption.Clipboard)) {
            tools.add(clipboardTool)
        }
        if (options.contains(LocalToolOption.Tts)) {
            tools.add(ttsTool)
        }
        if (options.contains(LocalToolOption.AskUser)) {
            tools.add(askUserTool)
        }
        if (options.contains(LocalToolOption.ScreenTime)) {
            tools.add(screenTimeTool)
        }
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.ImageGeneration) && isImageGenerationAvailable()) {
            tools.add(imageGenerationTool)
        }
        return tools
    }
}
