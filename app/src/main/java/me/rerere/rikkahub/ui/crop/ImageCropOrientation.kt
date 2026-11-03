package me.rerere.rikkahub.ui.crop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/** 兼容 Android 8 的解码回退：先水平翻转，再按 EXIF 旋转摆正。 */
internal fun Bitmap.upright(context: Context, uri: Uri): Bitmap {
    val exif = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } ?: return this
    if (exif.rotationDegrees == 0 && !exif.isFlipped) return this
    val matrix = Matrix().apply {
        if (exif.isFlipped) postScale(-1f, 1f)
        postRotate(exif.rotationDegrees.toFloat())
    }
    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}
