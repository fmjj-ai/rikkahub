package me.rerere.search

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class SearchRequestResultTest {
    @Test
    fun successKeepsValue() {
        assertEquals("response", runSearchCatching { "response" }.getOrThrow())
    }

    @Test
    fun requestFailureKeepsOriginalError() {
        val error = IOException("request failed")
        assertSame(error, runSearchCatching { throw error }.exceptionOrNull())
    }

    @Test
    fun cancellationIsNotConvertedToFailure() {
        val cancelled = CancellationException("stopped")
        val thrown = assertThrows(CancellationException::class.java) {
            runSearchCatching { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }
}
