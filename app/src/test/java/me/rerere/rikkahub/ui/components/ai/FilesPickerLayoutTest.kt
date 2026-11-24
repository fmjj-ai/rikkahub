package me.rerere.rikkahub.ui.components.ai

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class FilesPickerLayoutTest {
    @Test
    fun `workspace footer moves the button bottom up to the former gallery bottom`() {
        // 同时覆盖普通字号、大字体及按钮换行后的高度，不写死某一台手机的像素位置。
        listOf(60.dp, 100.dp, 160.dp).forEach { rowHeight ->
            assertEquals(rowHeight + 16.dp, phoneWorkspaceFooterHeight(rowHeight) + 8.dp)
        }
    }
}
