package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.junit4.createComposeRule
import me.rerere.rikkahub.ui.components.richtext.MarkdownPreparationState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MessageMarkdownContinuityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectionBranchAndLazyStyleRemountUseTheSamePreparedSnapshotImmediately() {
        val loading = mutableStateOf(true)
        val mounted = mutableStateOf(true)
        var latest: MarkdownPreparationState? = null
        var firstCompleted: MarkdownPreparationState? = null
        val rendered = mutableListOf<MarkdownPreparationState>()
        val heights = mutableListOf<Int>()
        compose.setContent {
            val scope = rememberCoroutineScope()
            val store = remember { MessageMarkdownStateStore(scope) }
            DisposableEffect(store) { onDispose { store.close() } }
            CompositionLocalProvider(LocalMessageMarkdownStore provides store) {
                if (mounted.value) {
                    val display = rememberMessageMarkdown(
                        "正文第一行\n正文第二行", null, "conversation", "message:0", 0, emptyList(), true,
                    )
                    val snapshot = display.snapshot
                    SideEffect { latest = snapshot }
                    val content = @Composable {
                        SideEffect { rendered += snapshot }
                        Column(Modifier.onSizeChanged { heights += it.height }) { Text(snapshot.fallbackText) }
                    }
                    if (loading.value || !display.settled) content() else SelectionContainer { content() }
                }
            }
        }
        compose.waitUntil(5000) { latest?.prepared != null }
        compose.runOnIdle {
            firstCompleted = latest
            rendered.clear()
            heights.clear()
            loading.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(rendered.isNotEmpty())
            assertTrue(rendered.all { it === firstCompleted })
            assertTrue(heights.all { it > 0 })
            mounted.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { rendered.clear(); mounted.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(rendered.isNotEmpty())
            assertTrue(rendered.all { it === firstCompleted })
        }
    }
}
