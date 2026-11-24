package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isIncompleteStreamImage
import me.rerere.ai.ui.withoutIncompleteStreamImages
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class ResponseImageStreamTest {
    private val api = ResponseAPI(OkHttpClient())
    private fun event(text: String) = api.parseResponseDelta(Json.parseToJsonElement(text).jsonObject)!!

    @Test
    fun interleavedSnapshotsReplaceTheirOwnImageAndIgnoreOlderFrames() {
        var message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        for (id in listOf("a", "b")) {
            message += event("""{"type":"response.output_item.added","item":{"id":"$id","type":"image_generation_call"}}""")
        }
        message += event("""{"type":"response.image_generation_call.partial_image","item_id":"a","partial_image_index":1,"partial_image_b64":"YQ=="}""")
        message += event("""{"type":"response.image_generation_call.partial_image","item_id":"b","partial_image_index":0,"partial_image_b64":"Yg=="}""")
        message += event("""{"type":"response.image_generation_call.partial_image","item_id":"a","partial_image_index":0,"partial_image_b64":"b2xk"}""")
        assertEquals(listOf("data:image/png;base64,YQ==", "data:image/png;base64,Yg=="),
            message.parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
        message += event("""{"type":"response.output_item.done","item":{"id":"a","type":"image_generation_call","result":"ZmluYWw=","output_format":"webp"}}""")
        val images = message.parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals("data:image/webp;base64,ZmluYWw=", images[0].url)
        assertFalse(images[0].isIncompleteStreamImage)
        assertTrue(images[1].isIncompleteStreamImage)
        assertEquals(listOf(images[0]), message.withoutIncompleteStreamImages().parts)
        val restored = Json.decodeFromString<UIMessage>(Json.encodeToString(message))
        assertEquals(message, restored)
    }

    @Test
    fun finalOnlyAndNonStreamingResultsRetainSeparateImages() {
        val chunk = api.parseResponseOutput(Json.parseToJsonElement("""{
          "id":"response", "output":[
            {"id":"a","type":"image_generation_call","result":"YQ=="},
            {"id":"b","type":"image_generation_call","result":"Yg==","output_format":"jpeg"}
          ]
        }""").jsonObject)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()) + chunk
        assertEquals(2, message.parts.size)
        assertEquals("data:image/jpeg;base64,Yg==", (message.parts[1] as UIMessagePart.Image).url)
        assertSame(message, message.withoutIncompleteStreamImages())
    }

    @Test
    fun incompleteAndEmptyEventsDoNotBecomeFinishedImages() {
        assertNull(api.parseResponseDelta(Json.parseToJsonElement("""{
            "type":"response.image_generation_call.partial_image","item_id":"a","partial_image_b64":""
        }""").jsonObject))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()) + event("""{
            "type":"response.output_item.added","item":{"id":"a","type":"image_generation_call"}
        }""")
        assertTrue(message.withoutIncompleteStreamImages().parts.isEmpty())
    }
}
