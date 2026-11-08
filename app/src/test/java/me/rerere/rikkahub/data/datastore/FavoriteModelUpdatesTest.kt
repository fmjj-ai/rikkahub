package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class FavoriteModelUpdatesTest {
    @Test
    fun filteredDragUsesDisplayedIdsAndKeepsConcurrentAdditionsAndDeletions() {
        val image = Uuid.random()
        val firstChat = Uuid.random()
        val secondChat = Uuid.random()
        val added = Uuid.random()
        val displayed = listOf(firstChat, secondChat)
        val latest = Settings.dummy().copy(favoriteModels = listOf(added, image, firstChat, secondChat))
        assertEquals(
            listOf(added, image, secondChat, firstChat),
            latest.reorderFavoriteModels(displayed, 0, 1).favoriteModels,
        )
        val deleted = latest.copy(favoriteModels = latest.favoriteModels - firstChat)
        assertEquals(deleted.favoriteModels, deleted.reorderFavoriteModels(displayed, 0, 1).favoriteModels)
    }

    @Test
    fun delayedFavoriteClickTogglesLatestMembershipRatherThanDuplicatingAnId() {
        val modelId = Uuid.random()
        val delayedClick: (Settings) -> Settings = { it.toggleFavoriteModel(modelId) }
        val latest = Settings.dummy().toggleFavoriteModel(modelId)
        assertEquals(emptyList<Uuid>(), delayedClick(latest).favoriteModels)
        assertEquals(listOf(modelId), delayedClick(delayedClick(latest)).favoriteModels)
        val duplicated = latest.copy(favoriteModels = listOf(modelId, modelId))
        assertEquals(emptyList<Uuid>(), duplicated.toggleFavoriteModel(modelId).favoriteModels)
    }
}
