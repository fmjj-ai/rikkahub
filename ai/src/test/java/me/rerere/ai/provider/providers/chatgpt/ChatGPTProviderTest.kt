package me.rerere.ai.provider.providers.chatgpt

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.withoutCredential
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ChatGPTProviderTest {
    private val setting = ProviderSetting.ChatGPT(credential = OAuthCredential("access", "refresh", accountId = "account"))
    private val params = TextGenerationParams(model = Model(modelId = "test-model"), temperature = 0.7f,
        topP = 0.9f, maxTokens = 500)

    private fun provider(stream: String, capture: (Request) -> Unit = {}) = ChatGPTProvider(
        OkHttpClient.Builder().addInterceptor { chain ->
            capture(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(stream.toResponseBody(null)).build()
        }.build(),
        AccountCredentialProvider { _, _ -> setting.credential!! },
    )

    private fun event(json: String) = "data: $json\n\n"
    private val completed = event("""{"type":"response.completed","response":{"id":"resp_1","model":"test-model","usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12}}}""")

    @Test
    fun `codex request has developer messages and excludes unsupported fields`() {
        val api = ResponseAPI(OkHttpClient())
        val body = api.buildRequestBody(ProviderSetting.OpenAI(baseUrl = CODEX_BASE_URL),
            listOf(UIMessage.system("规则一"), UIMessage.system("规则二"), UIMessage.user("你好")),
            params, true, CODEX_CAPABILITIES)
        assertEquals("false", body["store"].toString())
        assertEquals("true", body["stream"].toString())
        listOf("temperature", "top_p", "max_output_tokens", "instructions").forEach { assertFalse(body.containsKey(it)) }
        assertEquals("developer", body["input"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("规则二", body["input"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content)
        assertTrue(body["include"].toString().contains("reasoning.encrypted_content"))
    }

    @Test
    fun `missing content type supports complete text usage and fixed authentication`() = runBlocking {
        var request: Request? = null
        val stream = event("""{"type":"response.output_text.delta","item_id":"msg_1","delta":"你好🙂"}""") + completed
        val result = withTimeout(5000) {
            provider(stream) { request = it }.generateText(setting, listOf(UIMessage.user("hello")),
                params.copy(customHeaders = listOf(CustomHeader("Authorization", "wrong"), CustomHeader("ChatGPT-Account-ID", "wrong")),
                    customBody = listOf(CustomBody("store", Json.parseToJsonElement("true")))))
        }
        assertEquals("你好🙂", (result.choices.single().message!!.parts.single() as UIMessagePart.Text).text)
        assertEquals(12, result.usage!!.totalTokens)
        assertEquals("resp_1", result.id)
        assertEquals("Bearer access", request!!.header("Authorization"))
        assertEquals("account", request!!.header("ChatGPT-Account-ID"))
        val body = Json.parseToJsonElement(Buffer().also { request!!.body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals("false", body["store"].toString())
        assertEquals("$CODEX_BASE_URL/responses", request!!.url.toString())
    }

    @Test
    fun `tool argument events preserve call id distinct from item id`() = runBlocking {
        val stream = event("""{"type":"response.output_item.added","item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"search","arguments":""}}""") +
            event("""{"type":"response.function_call_arguments.done","item_id":"fc_1","arguments":"{\"q\":\"hello\"}"}""") + completed
        val result = withTimeout(5000) { provider(stream).generateText(setting, listOf(UIMessage.user("hi")), params) }
        val tool = result.choices.single().message!!.parts.single() as UIMessagePart.Tool
        assertEquals("call_1", tool.toolCallId)
        assertEquals("search", tool.toolName)
        assertEquals("""{"q":"hello"}""", tool.input)
    }

    @Test
    fun `failed incomplete malformed and truncated streams do not succeed`() = runBlocking {
        val streams = listOf(
            event("""{"type":"response.failed","response":{"error":{"message":"quota exceeded"}}}"""),
            event("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"limit"}}}"""),
            event("{broken"),
            event("""{"type":"response.output_text.delta","delta":"partial"}"""),
        )
        streams.forEach { stream ->
            val error = runCatching { withTimeout(5000) {
                provider(stream).generateText(setting, listOf(UIMessage.user("hi")), params)
            } }.exceptionOrNull()
            assertNotNull(error)
            assertFalse(error is kotlinx.coroutines.TimeoutCancellationException)
        }
    }

    @Test
    fun `catalog and quota parse server capability and reset windows`() {
        val models = parseCodexModels("""{"models":[{"slug":"visible","visibility":"list","supported_reasoning_levels":["high"],"context_window":10000},{"slug":"hidden","visibility":"hide"}]}""")
        assertEquals(listOf("visible"), models.map { it.modelId })
        assertEquals(8000, models.single().autoCompressThresholdTokens)
        val usage = parseCodexUsage("""{"rate_limit":{"limit_reached":false,"primary_window":{"used_percent":12.5,"limit_window_seconds":18000,"reset_at":1800000000}}}""")
        assertEquals(12.5, usage.windows.single().usedPercent, 0.01)
        assertEquals(18000L, usage.windows.single().windowSeconds)
        assertEquals(1800000000L, usage.windows.single().resetAtSeconds)
        assertTrue(parseCodexUsage("{}").windows.isEmpty())
    }

    @Test
    fun `credentials serialize for persistence but are removed for sharing and logs`() {
        val encoded = Json.encodeToString<ProviderSetting>(setting)
        assertEquals(setting.credential, (Json.decodeFromString<ProviderSetting>(encoded) as ProviderSetting.ChatGPT).credential)
        assertNull((setting.withoutCredential() as ProviderSetting.ChatGPT).credential)
        assertFalse(setting.toString().contains("accessToken=access"))
        assertFalse(setting.toString().contains("refreshToken=refresh"))
    }
}
