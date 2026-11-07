package me.rerere.rikkahub.ui.hooks

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLayoutTest {
    @Test
    fun `only phone sized configurations use the new layout and navigation`() {
        listOf(320, 393, 599).forEach { assertTrue(isPhoneLayout(it)) }
        listOf(600, 800, 1100, 0, -1).forEach { assertFalse(isPhoneLayout(it)) }
    }
}
