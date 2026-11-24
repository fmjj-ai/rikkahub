package me.rerere.rikkahub.service

/** 前台服务在主线程串行处理租约；一个会话可以同时拥有多个后台任务。 */
internal class GenerationLeaseRegistry {
    private val generations = linkedMapOf<String, String>()

    val isEmpty: Boolean get() = generations.isEmpty()
    val latestConversationId: String? get() = generations.values.lastOrNull()
    val generationIds: Set<String> get() = generations.keys.toSet()

    fun acquire(generationId: String, conversationId: String) {
        generations[generationId] = conversationId
    }

    fun release(generationId: String) {
        generations.remove(generationId)
    }

    fun clear() {
        generations.clear()
    }
}
