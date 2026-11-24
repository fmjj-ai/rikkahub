package me.rerere.rikkahub.data.db.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaCreationMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun upgrading24To26PreservesLegacyDataAndAddsMediaTables() {
        val name = "media-24-26"
        helper.createDatabase(name, 24).use { db ->
            db.execSQL("INSERT INTO ConversationEntity(id,title,nodes,create_at,update_at) " +
                "VALUES('chat','原聊天😀','[]',1,2)")
            db.execSQL("INSERT INTO MemoryEntity(assistant_id,content) VALUES('assistant','原记忆')")
            db.execSQL("INSERT INTO GenMediaEntity(path,model_id,prompt,create_at) " +
                "VALUES('image.png','old-image','旧生图',1)")
            db.execSQL("INSERT INTO workspaces(id,name,root,shell_status,created_at,updated_at) " +
                "VALUES('workspace','原工作区','/workspace','DISABLED',1,2)")
        }
        helper.runMigrationsAndValidate(name, 26, true).use { db ->
            db.query("SELECT title FROM ConversationEntity WHERE id='chat'").use {
                assertTrue(it.moveToFirst()); assertEquals("原聊天😀", it.getString(0))
            }
            db.query("SELECT content FROM MemoryEntity").use {
                assertTrue(it.moveToFirst()); assertEquals("原记忆", it.getString(0))
            }
            db.query("SELECT model_id FROM GenMediaEntity").use {
                assertTrue(it.moveToFirst()); assertEquals("old-image", it.getString(0))
            }
            db.query("SELECT shell_compatibility_mode FROM workspaces").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            listOf("media_creation_session", "media_creation_node", "media_creation_record").forEach { table ->
                db.query("SELECT COUNT(*) FROM $table").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
        }
    }
}
