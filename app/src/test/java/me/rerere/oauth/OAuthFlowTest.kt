package me.rerere.oauth

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import kotlin.time.Duration.Companion.seconds

/** 使用宿主应用的实际 Android 依赖组合验证授权流程，不引入第二套测试依赖。 */
class OAuthFlowTest {
    @Test
    fun `pkce state and refresh json follow OAuth requirements`() = runBlocking {
        var recorded: Request? = null
        val oauth = OAuthHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            recorded = chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"access_token":"new","refresh_token":"rotated"}""".toResponseBody()).build()
        }.build())
        val pkce = oauth.generatePkce()
        assertTrue(pkce.verifier.length in 43..128)
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(pkce.verifier.toByteArray(Charsets.US_ASCII))), pkce.challenge)
        val state = oauth.generateState()
        assertNotEquals(state, oauth.generateState())
        val url = oauth.buildAuthorizationUrl(OAuthHttpClient.AuthorizationRequest("https://example.com/authorize",
            "client", "http://127.0.0.1:1455/auth/callback", pkce, state)).toHttpUrl()
        assertEquals(state, url.queryParameter("state"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        val token = oauth.refreshToken(OAuthHttpClient.RefreshTokenRequest("https://example.com/token", "client",
            refreshToken = "old", encoding = OAuthHttpClient.TokenEncoding.JSON))
        assertEquals("rotated", token.refreshToken)
        assertEquals("json", recorded!!.body!!.contentType()!!.subtype)
        assertTrue(Buffer().also { recorded!!.body!!.writeTo(it) }.readUtf8().contains("\"refresh_token\":\"old\""))
    }

    @Test(timeout = 15000)
    fun `loopback rejects unknown state and replay then releases its port`() = runBlocking {
        val server = OAuthLoopbackCallbackServer(redirectHost = "127.0.0.1")
        val session = server.openSession("expected-state")
        val http = OkHttpClient()
        fun callback(query: String): Int = http.newCall(Request.Builder().url(session.redirectUri + query).build())
            .execute().use { it.code }
        try {
            assertEquals(400, callback("?code=ignored&state=wrong"))
            assertEquals(200, callback("?code=accepted&state=expected-state"))
            assertEquals(409, callback("?code=replayed&state=expected-state"))
            assertEquals("accepted", session.awaitCallback(1.seconds)!!.code)
        } finally { session.close() }
        val reopened = server.openSession("second")
        reopened.close()
    }
}
