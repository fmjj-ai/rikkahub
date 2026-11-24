package me.rerere.rikkahub.ui.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantAvatarNavigationTest {
    @Test
    fun expandedAvatarIsAboveTheExpandedPageOverlay() {
        // 展开页现有共享边界的 overlay 层级为 1，头像仍须在其上独立移动。
        assertTrue(assistantAvatarOverlayZIndex(true) > 1f)
    }

    @Test
    fun regularNavigationKeepsOriginalOverlayLevel() {
        assertEquals(0f, assistantAvatarOverlayZIndex(false), 0f)
    }
}
