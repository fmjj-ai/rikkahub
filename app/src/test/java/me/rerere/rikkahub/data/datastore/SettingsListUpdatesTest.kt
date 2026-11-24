package me.rerere.rikkahub.data.datastore

import me.rerere.search.SearchServiceOptions
import org.junit.Assert.*
import org.junit.Test

class SettingsListUpdatesTest {
    @Test
    fun `reorder keeps latest entries and ignores deleted drag endpoints`() {
        val latest = listOf("a", "new", "b", "c")
        assertEquals(listOf("new", "b", "c", "a"), latest.moveById("a", "c") { it })
        assertSame(latest, latest.moveById("deleted", "c") { it })
        assertSame(latest, latest.moveById(null, "c") { it })
    }

    @Test
    fun `adding reordering and deleting search providers preserves the selected identity`() {
        val a = SearchServiceOptions.TavilyOptions()
        val b = SearchServiceOptions.TavilyOptions()
        val added = SearchServiceOptions.TavilyOptions()
        val before = Settings(searchServices = listOf(a, b), searchServiceSelected = 1)
        val inserted = before.updateSearchServices { listOf(added) + it }
        assertEquals(b.id, inserted.searchServices[inserted.searchServiceSelected].id)
        val moved = inserted.updateSearchServices { it.moveById(b.id, added.id) { service -> service.id } }
        assertEquals(b.id, moved.searchServices[moved.searchServiceSelected].id)
        val removed = moved.removeSearchService(a.id)
        assertEquals(b.id, removed.searchServices[removed.searchServiceSelected].id)
    }

    @Test
    fun `deleting selected provider clamps safely and does not remove the last provider`() {
        val a = SearchServiceOptions.TavilyOptions()
        val b = SearchServiceOptions.TavilyOptions()
        val result = Settings(searchServices = listOf(a, b), searchServiceSelected = 1).removeSearchService(b.id)
        assertEquals(0, result.searchServiceSelected)
        assertEquals(listOf(a), result.searchServices)
        assertEquals(result.searchServices, result.removeSearchService(a.id).searchServices)
    }
}
