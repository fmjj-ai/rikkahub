package me.rerere.ai.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class CustomParameterIdentityTest {
    @Test
    fun headerCopyKeepsEditorIdentityWithoutChangingSerializationOrValueEquality() {
        val header = CustomHeader("X-Test", "value")
        val equal = CustomHeader("X-Test", "value")
        assertEquals(header, equal)
        assertEquals(header.hashCode(), equal.hashCode())
        assertNotSame(header.editKey, equal.editKey)
        assertSame(header.editKey, header.copy(value = "updated").editKey)
        assertEquals("CustomHeader(name=X-Test, value=value)", header.toString())

        val encoded = Json.encodeToString(CustomHeader.serializer(), header)
        assertEquals("{\"name\":\"X-Test\",\"value\":\"value\"}", encoded)
        val decoded = Json.decodeFromString(CustomHeader.serializer(), encoded)
        assertEquals(header, decoded)
        assertNotSame(header.editKey, decoded.editKey)
    }

    @Test
    fun bodyCopyKeepsEditorIdentityWithoutAddingAPersistedField() {
        val body = CustomBody("enabled", JsonPrimitive(true))
        val equal = CustomBody("enabled", JsonPrimitive(true))
        assertEquals(body, equal)
        assertEquals(body.hashCode(), equal.hashCode())
        assertSame(body.editKey, body.copy(key = "other").editKey)
        assertEquals("CustomBody(key=enabled, value=true)", body.toString())
        val encoded = Json.encodeToString(CustomBody.serializer(), body)
        assertEquals("{\"key\":\"enabled\",\"value\":true}", encoded)
        val decoded = Json.decodeFromString(CustomBody.serializer(), encoded)
        assertEquals(body, decoded)
        assertNotSame(body.editKey, decoded.editKey)
    }

    @Test
    fun rapidFieldEditsUseTheSameRowEvenBeforeTheUiReceivesItsUpdatedObject() {
        val initial = CustomHeader("old name", "old value")
        var current = listOf(initial)
        current = current.map { if (it.editKey === initial.editKey) it.copy(name = "new name") else it }
        current = current.map { if (it.editKey === initial.editKey) it.copy(value = "new value") else it }
        assertEquals(CustomHeader("new name", "new value"), current.single())
    }

    @Test
    fun deletedOrDuplicateRowsDoNotRetargetAnOldCallback() {
        val first = CustomHeader("same", "value")
        val second = CustomHeader("same", "value")
        val added = CustomHeader("same", "value")
        var current = listOf(first, second)
        current = current.filterNot { it.editKey === first.editKey } + added
        current = current.map { if (it.editKey === first.editKey) it.copy(value = "late") else it }
        assertEquals(listOf(second, added), current)
        assertSame(second, current[0])
        assertSame(added, current[1])
    }
}
