package me.rerere.rikkahub.data.files

import java.io.File

/** 持久化路径只允许访问媒体专属目录，防止旧备份或错误素材路径越界。 */
internal fun resolveMediaCreationFile(filesDir: File, path: String): File {
    require(!File(path).isAbsolute) { "媒体路径必须相对于应用目录" }
    val root = File(filesDir, FileFolders.MEDIA_CREATION).canonicalFile
    val target = File(filesDir, path).canonicalFile
    require(target != root && target.toPath().startsWith(root.toPath())) { "媒体路径越过专属目录" }
    return target
}
