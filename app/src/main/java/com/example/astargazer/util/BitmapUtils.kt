package com.example.astargazer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

object BitmapUtils {
    /**
     * ImageProxy (JPEG format) から Bitmap への変換
     */
    fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val planeProxy = image.planes.getOrNull(0) ?: return null
        val buffer: ByteBuffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}
