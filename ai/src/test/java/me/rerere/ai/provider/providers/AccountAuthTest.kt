package me.rerere.ai.provider.providers

import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.AccountCredentialProvider
import me.rerere.ai.provider.OAuthCredential
import me.rerere.ai.provider.ProviderSetting
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test

class AccountAuthTest {
    @Test
    fun `401 retries exactly once with refreshed token and account header`() = runBlocking {
        val setting = ProviderSetting.ChatGPT()
        val rejected = mutableListOf<String?>()
        val credentials = AccountCredentialProvider { _, previous ->
            rejected += previous
            if (previous == null) OAuthCredential("old", accountId = "old-account")
            else OAuthCredential("new", accountId = "new-account")
        }
        val client = OkHttpClient().withAccountAuthenticator(credentials)
        val request = Request.Builder().url("https://chatgpt.com/backend-api/codex/responses").apply {
            configureAccountAuth(credentials, setting, emptyList()) {
                header("ChatGPT-Account-ID", it.accountId!!)
            }
        }.build()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized").build()
        val retry = client.authenticator.authenticate(null, response)!!
        assertEquals("Bearer new", retry.header("Authorization"))
        assertEquals("new-account", retry.header("ChatGPT-Account-ID"))
        val second = response.newBuilder().request(retry).priorResponse(response).build()
        assertNull(client.authenticator.authenticate(null, second))
        assertEquals(listOf(null, "old"), rejected)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }
}
