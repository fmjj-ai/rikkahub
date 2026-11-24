package me.rerere.common.http

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RequestTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(15)

    @Test
    fun rawResponseRemainsOwnedByCaller() = runBlocking {
        val call = FakeCall()
        val body = TrackingBody("response")
        val result = async(start = CoroutineStart.UNDISPATCHED) { call.await() }

        call.respond(body)
        result.await().use {
            assertFalse(body.closed)
            assertEquals(200, it.code)
        }
        assertTrue(body.closed)
    }

    @Test
    fun cancellationStopsCallAndClosesLateResponse() = runBlocking {
        val call = FakeCall()
        val body = TrackingBody("late")
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.await().close() }

        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        call.respond(body)
        assertTrue(body.closed)
    }

    @Test
    fun promptCancellationClosesUndeliveredResponse() = runBlocking {
        val call = FakeCall()
        val body = TrackingBody("not delivered")
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.await().close() }

        call.respond(body)
        // 尚未把恢复后的协程交回事件循环，此时取消也必须释放响应。
        job.cancelAndJoin()
        assertTrue(body.closed)
    }

    @Test
    fun textResponsePreservesStatusAndClosesAllBodies() = runBlocking {
        for ((code, text) in listOf(200 to "response", 503 to "unavailable", 204 to "")) {
            val call = FakeCall()
            val body = TrackingBody(text)
            val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitText() }

            call.respond(body, code, "status message")
            val response = result.await()
            assertEquals(code, response.code)
            assertEquals("status message", response.message)
            assertEquals(text, response.body)
            assertEquals(code in 200..299, response.isSuccessful)
            assertTrue(body.closed)
        }
    }

    @Test
    fun textCancellationClosesLateResponseWithoutReading() = runBlocking {
        val call = FakeCall()
        val body = TrackingBody("late", beforeRead = { error("取消后不应继续读取正文") })
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.awaitText() }

        job.cancelAndJoin()
        call.respond(body)
        assertTrue(call.isCanceled())
        assertTrue(body.closed)
    }

    @Test
    fun bodyReadFailureClosesResponseAndPreservesError() = runBlocking {
        val call = FakeCall()
        val error = IOException("broken body")
        val body = TrackingBody("response", beforeRead = { throw error })
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { call.awaitText() } }

        call.respond(body)
        val delivered = result.await().exceptionOrNull()
        // 协程堆栈恢复可复制异常；检查对调用方有效的异常语义，不要求对象身份。
        assertTrue(delivered is IOException)
        assertEquals(error.message, delivered?.message)
        assertTrue(body.closed)
    }

    @Test
    fun networkFailureIsDelivered() = runBlocking {
        val call = FakeCall()
        val error = IOException("connection failed")
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { call.awaitText() } }

        call.fail(error)
        val delivered = result.await().exceptionOrNull()
        assertTrue(delivered is IOException)
        assertEquals(error.message, delivered?.message)
    }

    @Test
    fun synchronousEnqueueFailureIsDelivered() = runBlocking {
        val error = IllegalStateException("already executed")
        val call = object : Call by newUnexecutedCall() {
            override fun enqueue(responseCallback: Callback) {
                throw error
            }
        }
        assertSame(error, runCatching { call.awaitText() }.exceptionOrNull())
    }

    @Test
    fun cancellationDuringBodyReadStopsCallAndReleasesSource() = runBlocking {
        val reading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val call = FakeCall(onCancel = { cancelled.countDown() })
        val body = TrackingBody("slow", beforeRead = {
            reading.countDown()
            check(cancelled.await(5, TimeUnit.SECONDS)) { "未收到请求取消" }
            throw IOException("cancelled while reading")
        })
        val job = launch(start = CoroutineStart.UNDISPATCHED) { call.awaitText() }
        val worker = thread(name = "request-test-response", isDaemon = true) { call.respond(body) }

        try {
            assertTrue("未开始正文读取", reading.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            worker.join(5_000)
            assertFalse("正文读取未随取消结束", worker.isAlive)
            assertTrue(call.isCanceled())
            assertTrue(body.closed)
        } finally {
            cancelled.countDown()
            job.cancelAndJoin()
            worker.join(5_000)
        }
    }

    private class FakeCall(
        private val onCancel: () -> Unit = {},
        private val delegate: Call = newUnexecutedCall(),
    ) : Call by delegate {
        private lateinit var callback: Callback

        @Volatile
        private var cancelled = false

        override fun enqueue(responseCallback: Callback) {
            callback = responseCallback
        }

        override fun cancel() {
            cancelled = true
            onCancel()
        }

        override fun isCanceled(): Boolean = cancelled

        fun respond(body: ResponseBody, code: Int = 200, message: String = "OK") {
            callback.onResponse(
                this,
                Response.Builder()
                    .request(request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(message)
                    .body(body)
                    .build(),
            )
        }

        fun fail(error: IOException) {
            callback.onFailure(this, error)
        }
    }

    private class TrackingBody(text: String, beforeRead: () -> Unit = {}) : ResponseBody() {
        @Volatile
        var closed = false
            private set

        private val content = object : ForwardingSource(Buffer().writeUtf8(text)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                beforeRead()
                return super.read(sink, byteCount)
            }

            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()

        override fun contentType(): MediaType = "text/plain; charset=utf-8".toMediaType()

        override fun contentLength(): Long = -1

        override fun source(): BufferedSource = content
    }

    companion object {
        private val client = OkHttpClient()

        private fun newUnexecutedCall(): Call = client.newCall(
            Request.Builder().url("https://example.invalid/").build(),
        )
    }
}
