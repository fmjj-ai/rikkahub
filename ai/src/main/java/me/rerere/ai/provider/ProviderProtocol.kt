package me.rerere.ai.provider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 请求协议。多协议服务商下每个协议各自保存一份配置。
 *
 * 序列化名保持小写，便于二维码/备份在版本之间稳定。
 */
@Serializable
enum class ProviderProtocol(val displayName: String) {
    @SerialName("openai")
    OPENAI("OpenAI"),

    @SerialName("google")
    GOOGLE("Google"),

    @SerialName("claude")
    CLAUDE("Claude");

    companion object {
        fun of(setting: ProviderSetting): ProviderProtocol? = when (setting) {
            is ProviderSetting.OpenAI -> OPENAI
            is ProviderSetting.Google -> GOOGLE
            is ProviderSetting.Claude -> CLAUDE
            is ProviderSetting.MultiProtocol -> null
            is ProviderSetting.Account -> null
        }
    }
}
