package me.rerere.rikkahub.data.db.fts

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("message_fts.rank, c.update_at DESC"),
    NEWEST_FIRST("c.update_at DESC, message_fts.rank"),
    OLDEST_FIRST("c.update_at ASC, message_fts.rank"),
}

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    private fun transactionDatabase(): SupportSQLiteDatabase = db.also {
        check(it.inTransaction()) { "FTS 写入必须在调用方的 Room 事务内执行" }
    }

    fun syncNodesInTransaction(
        conversationId: String,
        previous: List<MessageNode>,
        current: List<MessageNode>,
    ) {
        val target = transactionDatabase()
        val mutations = planFtsNodeMutations(previous, current)
        if (mutations.isEmpty()) return
        // 合并删除，避免对未建立普通索引的 FTS 元数据列逐消息扫描。
        mutations.map { it.nodeId }.chunked(900).forEach { nodeIds ->
            val placeholders = nodeIds.joinToString(",") { "?" }
            target.execSQL(
                "DELETE FROM message_fts WHERE conversation_id = ? AND node_id IN ($placeholders)",
                arrayOf(conversationId, *nodeIds.toTypedArray()),
            )
        }
        insertNodes(target, conversationId, mutations)
    }

    fun replaceConversationInTransaction(conversation: Conversation) {
        val target = transactionDatabase()
        val id = conversation.id.toString()
        target.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(id))
        insertNodes(target, id, conversation.messageNodes.map { FtsNodeMutation(it.id.toString(), it.ftsMessages()) })
    }

    fun deleteConversationInTransaction(conversationId: String) {
        transactionDatabase().execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    fun deleteAllInTransaction() {
        transactionDatabase().execSQL("DELETE FROM message_fts")
    }

    private fun insertNodes(
        target: SupportSQLiteDatabase,
        conversationId: String,
        mutations: List<FtsNodeMutation>,
    ) {
        if (mutations.none { it.messages.isNotEmpty() }) return
        target.compileStatement(
            "INSERT INTO message_fts(text, node_id, message_id, conversation_id) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            mutations.forEach { node ->
                node.messages.forEach { message ->
                    statement.bindString(1, message.text)
                    statement.bindString(2, node.nodeId)
                    statement.bindString(3, message.messageId)
                    statement.bindString(4, conversationId)
                    statement.executeInsert()
                }
            }
        }
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        val cursor = db.query(
            """
            SELECT message_fts.node_id, message_fts.message_id, message_fts.conversation_id,
                   c.title, c.update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            JOIN conversationentity AS c ON c.id = message_fts.conversation_id
            WHERE message_fts.text MATCH jieba_query(?)
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            arrayOf(keyword)
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }
}

internal fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
