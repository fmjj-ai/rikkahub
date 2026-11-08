package me.rerere.search

import kotlinx.coroutines.CancellationException

/** 供应商错误保留 Result 约定，取消则继续向生成任务传播。 */
internal inline fun <T> runSearchCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}
