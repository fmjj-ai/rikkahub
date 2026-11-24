package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.contextMessages
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageReferencesTest {
    @get:Rule val folder = TemporaryFolder()

    private fun image(url: String) = UIMessage(
        role = MessageRole.USER, parts = listOf(UIMessagePart.Text("参考这张"), UIMessagePart.Image(url)),
    )

    @Test
    fun `user images and tool generated images have stable distinct labels without changing stored messages`() {
        val user = image("file:///first.png")
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Tool(
            toolCallId = "call", toolName = IMAGE_GENERATION_TOOL_NAME, input = "{}",
            output = listOf(UIMessagePart.Image("file:///generated.png")),
        )))
        val messages = listOf(user, assistant)
        val refs = imageReferences(messages)
        assertEquals(2, refs.size)
        assertNotEquals(refs[0].id, refs[1].id)
        val labeled = labelImageReferences(messages)
        assertTrue(labeled[0].toText().contains(refs[0].id))
        assertTrue((labeled[1].parts[0] as UIMessagePart.Tool).output.filterIsInstance<UIMessagePart.Text>()
            .single().text.contains(refs[1].id))
        assertEquals(2, user.parts.size)
        assertEquals(1, assistant.getTools().single().output.size)
        assertEquals(refs, imageReferences(messages + UIMessage.assistant("继续")))
    }

    @Test
    fun `model selection respects order deduplicates and never uses latest implicitly`() {
        val messages = listOf(image("file:///one.png"), image("file:///two.png"))
        val refs = imageReferences(messages)
        val value = Json.parseToJsonElement("[\"${refs[1].id}\",\"${refs[0].id}\",\"${refs[1].id}\"]")
        assertEquals(listOf(refs[1], refs[0]), selectImageReferences(value, messages))
        assertTrue(selectImageReferences(null, messages).isEmpty())
        assertTrue(selectImageReferences(Json.parseToJsonElement("[]"), messages).isEmpty())
    }

    @Test
    fun `paths forged ids branch changes and malformed arguments are rejected`() {
        val oldBranch = listOf(image("file:///old.png"))
        val currentBranch = listOf(image("file:///new.png"))
        val oldId = imageReferences(oldBranch).single().id
        for (json in listOf("[\"$oldId\"]", "[\"file:///private/key\"]", "[42]", "{}", "null",
            "[\"1\",\"2\",\"3\",\"4\",\"5\"]")) {
            assertThrows(Exception::class.java) { selectImageReferences(Json.parseToJsonElement(json), currentBranch) }
        }
    }

    @Test
    fun `only latest 32 active images are labeled and selectable`() {
        val messages = (0..35).map { image("file:///$it.png") }
        val refs = imageReferences(messages)
        assertEquals(32, refs.size)
        assertEquals("file:///4.png", refs.first().url)
        assertFalse(labelImageReferences(messages).first().toText().contains("Reference image ID"))
        val checkpoint = UIMessage.user("summary").copy(isContextCheckpoint = true)
        assertTrue(imageReferences(listOf(checkpoint).contextMessages()).isEmpty())
    }

    @Test
    fun `local references stay inside files directory and require existing nonempty files`() {
        val root = folder.newFolder("files")
        val valid = root.resolve("图😀.png").apply { writeText("image") }
        assertEquals(listOf(valid.canonicalFile), imageReferenceFiles(listOf(ImageReference("id", valid.toURI().toString())), root))
        val outside = folder.newFile("outside.png").apply { writeText("private") }
        for (url in listOf(outside.toURI().toString(), root.resolve("missing.png").toURI().toString(),
            "https://example.com/image.png", "file://host/image.png")) {
            assertThrows(Exception::class.java) { imageReferenceFiles(listOf(ImageReference("id", url)), root) }
        }
    }
}
