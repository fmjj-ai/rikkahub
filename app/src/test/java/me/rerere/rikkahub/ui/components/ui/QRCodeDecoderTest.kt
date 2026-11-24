package me.rerere.rikkahub.ui.components.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

class QRCodeDecoderTest {
    @Test
    fun readsPaddedCameraFramesWithUnpaddedLastRow() {
        val text = "rikkahub-provider-test"
        val size = 240
        val stride = 256
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = ByteArray((size - 1) * stride + size) { 0xff.toByte() }
        for (y in 0 until size) for (x in 0 until size) {
            pixels[y * stride + x] = if (matrix[x, y]) 0 else 0xff.toByte()
        }
        assertEquals(text, QRCodeDecoder().decode(pixels, stride, size, size))
        assertNull(QRCodeDecoder().decode(ByteArray(4), stride, size, size))
    }
}
