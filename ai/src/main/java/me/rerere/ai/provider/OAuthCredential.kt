package me.rerere.ai.provider

import kotlinx.serialization.Serializable

/** 仅由账号登录和刷新流程写入；日志不得包含令牌。 */
@Serializable
data class OAuthCredential(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long = 0,
    val accountId: String? = null,
    val email: String? = null,
    val plan: String? = null,
) {
    override fun toString(): String = "OAuthCredential(expiresAt=$expiresAt, tokens=***)"
}

/** 分享或导入提供商配置时不能复制账号会话，包括模型覆盖中的会话。 */
fun ProviderSetting.withoutCredential(): ProviderSetting {
    val clean = if (this is ProviderSetting.Account) withCredential(null) else this
    return clean.copyProvider(models = clean.models.map { model ->
        model.copy(providerOverwrite = model.providerOverwrite?.withoutCredential())
    })
}
