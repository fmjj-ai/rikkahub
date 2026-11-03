package me.rerere.rikkahub.data.files

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MediaCreationPathsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `media path stays inside dedicated directory`() {
        val path = "media_creation/session/record/out_0.png"
        assertEquals(File(folder.root, path).canonicalFile, resolveMediaCreationFile(folder.root, path))
    }

    @Test fun `traversal absolute paths and directory root are rejected`() {
        listOf("media_creation/../settings.pb", "media_creation/../../outside", "upload/private.png",
            "media_creation", File(folder.root, "outside.png").absolutePath).forEach {
            assertTrue("应拒绝越界路径：$it", runCatching { resolveMediaCreationFile(folder.root, it) }.isFailure)
        }
    }
}
