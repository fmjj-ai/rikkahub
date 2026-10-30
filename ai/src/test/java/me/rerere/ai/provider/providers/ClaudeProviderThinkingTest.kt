package me.rerere.ai.provider.providers

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeProviderThinkingTest {
    private val provider = ClaudeProvider(OkHttpClient())

    private fun buildRequest(modelId: String, level: ReasoningLevel): JsonObject {
        val method = ClaudeProvider::class.java.getDeclaredMethod(
            "buildMessageRequest",
            ProviderSetting.Claude::class.java,
            List::class.java,
            TextGenerationParams::class.java,
            Boolean::class.javaPrimitiveType!!
        )
        method.isAccessible = true
        return method.invoke(
            provider,
            ProviderSetting.Claude(),
            listOf(UIMessage.user("hello")),
            TextGenerationParams(
                model = Model(
                    modelId = modelId,
                    abilities = listOf(ModelAbility.REASONING),
                ),
                reasoningLevel = level,
            ),
            false,
        ) as JsonObject
    }

    @Test
    fun `off omits thinking for models that reject disabled type`() {
        val request = buildRequest("claude-opus-5-5", ReasoningLevel.OFF)

        assertNull(request["thinking"])
        assertNull(request["output_config"])
    }

    @Test
    fun `auto uses adaptive thinking with an explicit effort`() {
        val request = buildRequest("claude-opus-5-5", ReasoningLevel.AUTO)

        val thinking = request["thinking"]!!.jsonObject
        assertEquals("adaptive", thinking["type"]!!.jsonPrimitive.content)
        assertEquals("summarized", thinking["display"]!!.jsonPrimitive.content)

        val effort = request["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content
        assertEquals("high", effort)
    }

    @Test
    fun `explicit level keeps its effort value`() {
        val request = buildRequest("claude-opus-5-5", ReasoningLevel.MEDIUM)

        assertEquals("adaptive", request["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "medium",
            request["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content,
        )
    }
}
