package me.rerere.rikkahub.data.sync.s3

import kotlinx.serialization.Serializable

@Serializable
data class S3Config(
    val endpoint: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val bucket: String = "",
    val region: String = "auto",
    val pathStyle: Boolean = true,
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    val host: String
        get() = endpoint
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')

    val isHttps: Boolean
        get() = endpoint.startsWith("https://")

    /** 上传地址未写协议时使用 HTTPS，不改变旧备份配置的解析规则。 */
    fun withHttpsByDefault(): S3Config =
        if (endpoint.isBlank() || "://" in endpoint) this else copy(endpoint = "https://$endpoint")

    fun bucketUrl(): String {
        return if (pathStyle) {
            "${endpoint.trimEnd('/')}/$bucket"
        } else {
            val scheme = if (isHttps) "https://" else "http://"
            "$scheme$bucket.$host"
        }
    }

    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}
