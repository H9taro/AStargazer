package jp.hisanet.astargazer.util

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

    /**
     * ビットマップを中央の 16:9 領域で切り抜き (クロップ) し、指定解像度にスケーリングする
     * @param targetWidth 0 の場合は元の解像度を保持
     * @param targetHeight 0 の場合は元の解像度を保持
     */
    fun cropTo169(src: Bitmap, targetWidth: Int = 0, targetHeight: Int = 0): Bitmap {
        val width = src.width
        val height = src.height

        // 中央 16:9 領域の計算
        val cropWidth: Int
        val cropHeight: Int

        if (width * 9 > height * 16) {
            // 横長すぎる場合 (幅を詰める)
            cropHeight = height
            cropWidth = (height * 16) / 9
        } else {
            // 縦長すぎる場合 (高さを詰める)
            cropWidth = width
            cropHeight = (width * 9) / 16
        }

        val startX = (width - cropWidth) / 2
        val startY = (height - cropHeight) / 2

        val croppedBitmap = Bitmap.createBitmap(src, startX, startY, cropWidth, cropHeight)

        if (targetWidth > 0 && targetHeight > 0) {
            val scaled = Bitmap.createScaledBitmap(croppedBitmap, targetWidth, targetHeight, true)
            if (scaled != croppedBitmap) {
                croppedBitmap.recycle()
            }
            return scaled
        }

        return croppedBitmap
    }
}
