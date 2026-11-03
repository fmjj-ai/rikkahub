package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.ui.ImageGenSize
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.data.ai.tools.ToolCallContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findImageGenerationModel
import me.rerere.rikkahub.data.datastore.resolveEndpoint
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.GenMediaRepository
import java.io.File
import kotlin.uuid.Uuid

/** 生图工具名，与 UI 渲染器、工具级系统提示共用 */
const val IMAGE_GENERATION_TOOL_NAME = "draw_image"

private const val TAG = "ImageGenerationTool"

private val ALLOWED_SIZES: List<String> = ImageGenSize.entries.map { it.value }

/**
 * 生图子模型调用的「内置提示词」（工具 description + 工具级 systemPrompt）。
 *
 * 需求要求：尽可能简单，只说明"可以怎么调用子 agent 画图"；不写风格指南、构图建议、参数大全。
 * 唯一额外的引导是"调用前先给用户一句话"，否则用户只会看到一个工具卡片在转圈。
 */
private val IMAGE_GENERATION_SYSTEM_PROMPT = """
    你有画图能力：当用户想要图片时，先回一句话说明你要画什么，然后调用 $IMAGE_GENERATION_TOOL_NAME 工具，用 prompt 描述画面。
    每次调用只生成 1 张图，需要多张时请连续多次调用；图片会直接显示在对话里，画好后你可以继续说明。
""".trimIndent()

/**
 * 生图过程中的半成品预览通道。
 *
 * 工具执行只有「完成后一次性返回 List<UIMessagePart>」这一条路径，没有现成的中间结果推送机制，
 * 所以这里用一个按 toolCallId 索引的可观察表：工具边收 partial 图边写入，
 * 专属 [me.rerere.rikkahub.ui.components.message.tools.ImageGenerationToolUI] 订阅后渲染。
 *
 * 清理时机：[_generateImage] 在 finally 里无条件清掉自己那条记录（含取消与失败）。
 */
object ImageGenerationProgress {
    private val _previews = MutableStateFlow<Map<String, String>>(emptyMap())

    /** toolCallId -> 半成品预览图（本地 file:// 地址） */
    val previews: StateFlow<Map<String, String>> = _previews.asStateFlow()

    fun update(toolCallId: String, previewUrl: String) {
        _previews.update { it + (toolCallId to previewUrl) }
    }

    fun clear(toolCallId: String) {
        _previews.update { it - toolCallId }
    }
}

/**
 * 把「应用级生图模型」下沉为主模型可调用的子 agent。
 *
 * - 模型与 provider 复用既有配置（`Settings.imageGenerationModelId`，模型类型 IMAGE），不新增第二套配置。
 * - 每次只生成 1 张（入参不暴露张数），需要多张由主模型多次调用。
 * - 免审批：`needsApproval` 保持默认 false。
 * - 图片写入应用图片目录并登记图库，与独立生图页面共用同一套数据。
 */
internal fun buildImageGenerationTool(
    context: Context,
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
    filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
): Tool = Tool(
    name = IMAGE_GENERATION_TOOL_NAME,
    description = """
        Draw one image with the image generation sub-agent.
        Use it when the user wants a picture. Tell the user in one short sentence what you are about to draw before calling it.
        Pass the picture description in `prompt`; `size` is optional.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "画面描述：画什么、什么风格、画面里有什么。描述越具体，出图越贴近预期。")
                })
                put("size", buildJsonObject {
                    put("type", "string")
                    put("description", "可选，输出尺寸；不确定时不要传。")
                    put("enum", buildJsonArray { ALLOWED_SIZES.forEach { add(JsonPrimitive(it)) } })
                })
            },
            required = listOf("prompt"),
        )
    },
    // 模型不支持工具调用时，工具定义本身不会随请求发出，
    // 这里同步不注入提示词，避免主模型声称自己会画图却调不到工具。
    systemPrompt = { model, _ ->
        if (model.abilities.contains(ModelAbility.TOOL)) IMAGE_GENERATION_SYSTEM_PROMPT else ""
    },
    execute = { args ->
        generateImage(
            args = args,
            context = context,
            settingsStore = settingsStore,
            providerManager = providerManager,
            filesManager = filesManager,
            genMediaRepository = genMediaRepository,
        )
    },
)

private suspend fun generateImage(
    args: JsonElement,
    context: Context,
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
    filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
): List<UIMessagePart> {
    val toolCallId = currentCoroutineContext()[ToolCallContext]?.toolCallId
    val arguments = runCatching { args.jsonObject }.getOrNull()
    val prompt = arguments?.get("prompt")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val size = arguments?.get("size")?.jsonPrimitive?.contentOrNull
        ?.takeIf { it in ALLOWED_SIZES }
        ?: ImageGenSize.AUTO.value

    if (prompt.isBlank()) {
        return errorOutput("prompt 不能为空，请传入画面描述后重试。")
    }

    return try {
        val settings = settingsStore.settingsFlow.first()
        val model = settings.findImageGenerationModel()
            ?: return errorOutput("尚未配置生图模型（设置 → 模型，把生图模型设为 IMAGE 类型），无法画图。请提示用户先去配置。")
        val provider = model.resolveEndpoint(settings.providers)
            ?: return errorOutput("找不到生图模型对应的服务商或协议未配置，请提示用户检查模型设置。")

        val params = ImageGenerationParams(
            model = model,
            prompt = prompt,
            // 单次上限固定 1 张，需要多张由主模型多次调用
            numOfImages = 1,
            size = size,
            customHeaders = model.customHeaders,
            customBody = model.customBodies,
        )

        var finalData: String? = null
        var previewFile: File? = null
        try {
            providerManager.getProviderByType(provider)
                .generateImage(provider, params)
                .collect { item ->
                    if (item.partial) {
                        if (toolCallId == null) return@collect
                        previewFile?.delete()
                        val file = File(
                            context.appTempFolder,
                            "imggen_preview_${toolCallId}_${System.currentTimeMillis()}.png",
                        )
                        previewFile = filesManager.createImageFileFromBase64(item.data, file.absolutePath)
                        ImageGenerationProgress.update(toolCallId, previewFile.toUri().toString())
                    } else {
                        finalData = item.data
                    }
                }
        } finally {
            // 预览的清理时机：无论成功、失败还是被取消，都撤销这条工具调用的半成品
            if (toolCallId != null) ImageGenerationProgress.clear(toolCallId)
            previewFile?.delete()
        }

        val data = finalData
            ?: return errorOutput("生图服务没有返回图片，请提示用户稍后重试或检查生图模型配置。")

        val savedFile = saveGeneratedImage(
            filesManager = filesManager,
            genMediaRepository = genMediaRepository,
            base64Data = data,
            prompt = prompt,
            modelName = model.displayName,
        )

        listOf(
            UIMessagePart.Image(url = savedFile.toUri().toString()),
            UIMessagePart.Text(
                buildJsonObject {
                    put("status", "ok")
                    put("prompt", prompt)
                    put("size", size)
                    put("count", 1)
                    put("note", "图片已显示在对话中，并归档到图库。需要更多张请再次调用本工具。")
                }.toString()
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "generateImage failed", e)
        errorOutput(e.message ?: e.javaClass.simpleName)
    }
}

private fun errorOutput(message: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(
        buildJsonObject {
            put("status", "error")
            put("error", message)
            put("hint", "请向用户解释失败原因，并给出替代方案；不要重试同一个必然失败的调用。")
        }.toString()
    )
)

/**
 * 落盘并登记现有图库。文件名不再包含可能带路径分隔符的模型显示名，避免越界和同毫秒碰撞。
 */
private suspend fun saveGeneratedImage(
    filesManager: FilesManager,
    genMediaRepository: GenMediaRepository,
    base64Data: String,
    prompt: String,
    modelName: String,
): File {
    val imagesDir = filesManager.getImagesDir()
    val timestamp = System.currentTimeMillis()
    val imageFile = File(imagesDir, newGeneratedImageFileName(timestamp))
    val createdFile = filesManager.createImageFileFromBase64(base64Data, imageFile.absolutePath)
    genMediaRepository.insertMedia(
        GenMediaEntity(
            path = "images/${imageFile.name}",
            modelId = modelName,
            prompt = prompt,
            createAt = timestamp,
            type = GenMediaEntity.TYPE_IMAGE_GENERATION,
        )
    )
    return createdFile
}

internal fun newGeneratedImageFileName(timestamp: Long): String = "${timestamp}_${Uuid.random()}.png"
