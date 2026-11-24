package me.rerere.rikkahub.data.ai.account

import me.rerere.ai.provider.OAuthCredential
import me.rerere.oauth.OAuthHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ChatGPTAuthTest {
    private fun jwt(payload: String) = "e30.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())}.test"

    @Test
    fun `access claims remain available when id token has only identity claims`() {
        val token = OAuthHttpClient.TokenResponse(
            accessToken = jwt("""{"exp":12345,"https://api.openai.com/auth":{"chatgpt_account_id":"account","chatgpt_plan_type":"plus"}}"""),
            idToken = jwt("""{"email":"example@example.com"}"""),
            refreshToken = "refresh",
        )
        val credential = ChatGPTAuth.toCredential(token, null, now = 0)
        assertEquals("account", credential.accountId)
        assertEquals("plus", credential.plan)
        assertEquals("example@example.com", credential.email)
        assertEquals(12345000L, credential.expiresAt)
    }

    @Test
    fun `refresh without identity or refresh token retains prior metadata`() {
        val previous = OAuthCredential("old", "refresh", accountId = "account", email = "a@example.com", plan = "plus")
        val result = ChatGPTAuth.toCredential(OAuthHttpClient.TokenResponse("new", expiresIn = 60), previous, now = 1000)
        assertEquals("refresh", result.refreshToken)
        assertEquals("account", result.accountId)
        assertEquals("a@example.com", result.email)
        assertEquals(61000L, result.expiresAt)
        assertNull(decodeJwtPayload("invalid"))
    }
}
