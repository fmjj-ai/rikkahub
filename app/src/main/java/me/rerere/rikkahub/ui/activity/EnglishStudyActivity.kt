package me.rerere.rikkahub.ui.activity

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.uuid.Uuid
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatService
import org.json.JSONArray
import org.json.JSONObject
import org.koin.android.ext.android.inject

class EnglishStudyActivity : ComponentActivity() {
    private val chatService by inject<ChatService>()
    private val settingsStore by inject<SettingsStore>()
    private var webView: WebView? = null
    private var bridge: EnglishBridge? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val view = WebView(this)
        webView = view
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            databaseEnabled = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }
        view.webViewClient = WebViewClient()
        val englishBridge = EnglishBridge(
            activity = this,
            webView = view,
            chatService = chatService,
            settingsStore = settingsStore,
        )
        bridge = englishBridge
        view.addJavascriptInterface(englishBridge, "RikkaEnglish")
        view.setDownloadListener(EnglishDownloadListener(this))
        setContentView(view)
        view.loadUrl("file:///android_asset/english/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        bridge?.close()
        bridge = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}

class EnglishBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val chatService: ChatService,
    private val settingsStore: SettingsStore,
) {
    @Volatile
    private var conversationId: Uuid = Uuid.random()

    @Volatile
    private var inlineInitialized: Boolean = false

    @Volatile
    private var selectedAssistantIndex: Int = 0

    @Volatile
    private var selectedModelId: Uuid? = null

    @Volatile
    private var reasoningEnabled: Boolean = true

    init {
        chatService.addConversationReference(conversationId)
    }

    fun close() {
        chatService.removeConversationReference(conversationId)
    }

    private fun newInlineConversationId(): Uuid {
        chatService.removeConversationReference(conversationId)
        conversationId = Uuid.random()
        chatService.addConversationReference(conversationId)
        return conversationId
    }

    private fun normalizeFilename(filename: String?): String {
        val name = filename?.trim()?.takeIf { it.isNotEmpty() } ?: "rikkahub_english_vocab.json"
        return if (name.endsWith(".json", ignoreCase = true)) name else "$name.json"
    }

    private fun toast(text: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveToDownloads(json: String?, filename: String?) {
        val content = json ?: "{}"
        val safeName = normalizeFilename(filename)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/RikkaHub English")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = activity.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert returned null")
            resolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
            } ?: throw IOException("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            toast("已保存到系统下载/RikkaHub English")
        } else {
            val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.cacheDir
            val file = File(dir, safeName)
            FileOutputStream(file).use { output ->
                output.write(bytes)
            }
            toast("已保存到应用下载目录：${file.absolutePath}")
        }
    }

    fun emitAI(requestId: Int, text: String?, done: Boolean, error: String?) {
        val script = "window.rikkaEnglishAIUpdate&&window.rikkaEnglishAIUpdate(" +
            requestId + "," +
            JSONObject.quote(text ?: "") + "," +
            done + "," +
            JSONObject.quote(error ?: "") +
            ");"
        activity.runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }

    @JavascriptInterface
    fun getAIConfig(): String {
        return runCatching {
            val settings = settingsStore.settingsFlow.value
            val assistants = JSONArray()
            settings.assistants.forEachIndexed { index, assistant ->
                assistants.put(
                    JSONObject()
                        .put("index", index)
                        .put("id", assistant.id.toString())
                        .put("name", assistant.name.ifBlank { "助手 ${index + 1}" })
                        .put("selected", index == selectedAssistantIndex)
                )
            }

            val models = JSONArray()
            settings.providers.forEach { provider ->
                provider.models.forEach { model ->
                    models.put(
                        JSONObject()
                            .put("id", model.id.toString())
                            .put("name", model.displayName.ifBlank { model.modelId })
                            .put("modelId", model.modelId)
                    )
                }
            }

            JSONObject()
                .put("assistants", assistants)
                .put("models", models)
                .put("selectedAssistantIndex", selectedAssistantIndex)
                .put("selectedModelId", selectedModelId?.toString().orEmpty())
                .put("reasoningEnabled", reasoningEnabled)
                .toString()
        }.getOrElse {
            "{}"
        }
    }

    @JavascriptInterface
    fun setAIConfig(assistantIndex: String?, modelId: String?, reasoningEnabled: Boolean) {
        assistantIndex?.trim()?.toIntOrNull()?.takeIf { it >= 0 }?.let {
            selectedAssistantIndex = it
        }
        selectedModelId = modelId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
        this.reasoningEnabled = reasoningEnabled

        val settings = settingsStore.settingsFlow.value
        val assistant = settings.assistants.getOrNull(selectedAssistantIndex) ?: settings.getCurrentAssistant()
        chatService.configureEnglishSession(
            conversationId = conversationId,
            assistantId = assistant.id,
            modelId = selectedModelId,
            reasoningEnabled = this.reasoningEnabled,
        )
    }

    @JavascriptInterface
    fun resetInlineAI() {
        newInlineConversationId()
        inlineInitialized = false
        toast("已重置页面内 AI 会话")
    }

    @JavascriptInterface
    fun sendInlineAI(prompt: String?, requestId: Int) {
        val text = prompt?.trim().orEmpty()
        if (text.isBlank()) {
            toast("没有可发送内容")
            return
        }

        runCatching {
            val settings = settingsStore.settingsFlow.value
            val assistant = settings.assistants.getOrNull(selectedAssistantIndex) ?: settings.getCurrentAssistant()
            val id = conversationId

            if (!inlineInitialized) {
                chatService.replaceConversationState(
                    id,
                    Conversation.ofId(id = id, assistantId = assistant.id),
                )
                inlineInitialized = true
            }
            chatService.configureEnglishSession(
                conversationId = id,
                assistantId = assistant.id,
                modelId = selectedModelId,
                reasoningEnabled = reasoningEnabled,
            )
            chatService.sendMessage(
                conversationId = id,
                content = listOf(UIMessagePart.Text(text)),
                answer = true,
            )
            thread(name = "RikkaEnglishAI-$requestId", isDaemon = true) {
                EnglishAiPollRunnable(
                    bridge = this,
                    chatService = chatService,
                    conversationId = id,
                    requestId = requestId,
                ).run()
            }
            toast("已发送到页面内 AI")
        }.onFailure { error ->
            emitAI(requestId, "", true, "页面内 AI 启动失败：$error")
            toast("页面内 AI 启动失败")
        }
    }

    @JavascriptInterface
    fun sendToAI(prompt: String?) {
        val text = prompt?.trim().orEmpty()
        if (text.isBlank()) {
            toast("没有可发送内容")
            return
        }
        runCatching {
            val intent = Intent(activity, RouteActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            activity.runOnUiThread {
                activity.startActivity(intent)
            }
            toast("已发送到 RikkaHub")
        }.onFailure {
            toast("发送失败，请复制提示词后手动粘贴")
        }
    }

    @JavascriptInterface
    fun saveVocab(json: String?, filename: String?) {
        runCatching {
            saveToDownloads(json, filename)
        }.onFailure {
            toast("保存失败，正在打开分享")
            shareVocab(json, filename)
        }
    }

    @JavascriptInterface
    fun shareVocab(json: String?, filename: String?) {
        runCatching {
            val file = File(activity.cacheDir, normalizeFilename(filename))
            FileOutputStream(file).use { output ->
                output.write((json ?: "{}").toByteArray(StandardCharsets.UTF_8))
            }
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.runOnUiThread {
                activity.startActivity(Intent.createChooser(intent, "分享生词本 JSON"))
            }
            toast("已生成 JSON 文件，可保存或分享")
        }.onFailure {
            toast("分享失败")
        }
    }
}

private class EnglishAiPollRunnable(
    private val bridge: EnglishBridge,
    private val chatService: ChatService,
    private val conversationId: Uuid,
    private val requestId: Int,
) : Runnable {
    override fun run() {
        var lastText = ""
        repeat(600) {
            try {
                val conversation = chatService.getConversationFlow(conversationId).value
                val text = conversation.currentMessages
                    .asReversed()
                    .firstOrNull { it.role == MessageRole.ASSISTANT }
                    ?.toText()
                    .orEmpty()
                if (text != lastText) {
                    lastText = text
                    bridge.emitAI(requestId, lastText, false, null)
                }
                if (!chatService.isGenerating(conversationId)) {
                    bridge.emitAI(requestId, lastText, true, null)
                    return
                }
                Thread.sleep(350L)
            } catch (error: Exception) {
                bridge.emitAI(requestId, "", true, error.toString())
                return
            }
        }
        bridge.emitAI(requestId, lastText, true, "页面内 AI 等待超时，可稍后重试或发送到主程序")
    }
}

private class EnglishDownloadListener(
    private val activity: Activity,
) : DownloadListener {
    override fun onDownloadStart(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long,
    ) {
        if (url.isNullOrBlank()) return
        if (url.startsWith("data:application/json")) {
            Toast.makeText(activity, "请使用页面内“保存 JSON / 分享 JSON”按钮导出生词本", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
}
