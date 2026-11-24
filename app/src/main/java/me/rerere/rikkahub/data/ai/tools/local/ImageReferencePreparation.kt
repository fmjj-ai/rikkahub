package me.rerere.rikkahub.data.ai.tools.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

internal fun prepareImageReference(source: File, output: File): File {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "参考图格式无法解码，请换一张图片" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight).toLong() > 2048L * sample) sample *= 2
    val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }) ?: error("参考图解码失败")
    var upright = decoded
    try {
        val exif = ExifInterface(source)
        if (exif.isFlipped || exif.rotationDegrees != 0) {
            val matrix = Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
            upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        output.outputStream().use { check(upright.compress(Bitmap.CompressFormat.PNG, 100, it)) { "参考图编码失败" } }
        return output
    } finally {
        if (upright !== decoded) upright.recycle()
        decoded.recycle()
    }
}
