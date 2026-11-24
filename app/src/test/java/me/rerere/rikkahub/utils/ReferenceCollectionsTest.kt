package me.rerere.rikkahub.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReferenceCollectionsTest {
    private class Input(val value: String) {
        override fun hashCode(): Int = error("投影缓存不应计算内容 hash")
        override fun equals(other: Any?): Boolean = error("投影缓存不应比较内容")
    }

    @Test
    fun cacheUsesIdentityAndOnlyRetainsCurrentItems() = runBlocking {
        var calls = 0
        val cache = ReferenceProjectionCache<Input, String> { calls++; it.value }
        val a = Input("a")
        val b = Input("b")
        assertEquals(listOf("a", "a", "b"), cache.project(listOf(a, a, b)))
        assertEquals(2, calls)
        cache.project(listOf(b))
        assertEquals(2, calls)
        cache.project(listOf(a, b))
        assertEquals(3, calls)
        cache.project(emptyList())
        cache.project(listOf(b))
        assertEquals(4, calls)
        cache.project(listOf(Input("b")))
        assertEquals(5, calls)
    }

    @Test
    fun identityMappingReusesListAndUntouchedElements() {
        val a = Any()
        val b = Any()
        val replacement = Any()
        val values = listOf(a, b)
        assertSame(values, values.mapPreservingIdentity { it })
        val changed = values.mapPreservingIdentity { if (it === b) replacement else it }
        assertSame(a, changed[0])
        assertSame(replacement, changed[1])
        assertSame(b, values[1])
    }
}
