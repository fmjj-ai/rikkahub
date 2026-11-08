package me.rerere.common.http

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 返回原始响应；调用方负责关闭响应及管理后续正文读取的生命周期。 */
suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, unconsumedResponse, _ ->
                    unconsumedResponse.closeQuietly()
                }
            }
        })
    }
}

/** 已读取并释放底层响应的文本结果，不再持有连接或响应流。 */
data class HttpTextResponse(
    val code: Int,
    val message: String,
    val body: String,
) {
    val isSuccessful: Boolean
        get() = code in 200..299
}

/** 在正文读取结束前始终将协程取消传递给 Call，成功和失败响应均统一释放。 */
suspend fun Call.awaitText(): HttpTextResponse {
    return suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!continuation.isActive) {
                    response.closeQuietly()
                    return
                }
                try {
                    val result = response.use {
                        HttpTextResponse(code = it.code, message = it.message, body = it.body.string())
                    }
                    continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
            }
        })
    }
}
