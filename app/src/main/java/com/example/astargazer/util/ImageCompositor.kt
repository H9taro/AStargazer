package com.example.astargazer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object ImageCompositor {

    /**
     * 指定された複数の画像ファイルから比較明合成（Lighten Blend）を行い、合成結果の静止画 (*.jpg) を保存する
     * @param imageFiles 撮影された連続写真ファイルのリスト
     * @param outputFile 出力先ファイル
     * @param onProgress 進捗コールバック (0.0 ~ 1.0)
     * @return 合成成功の可否
     */
    fun createLightenBlendComposite(
        imageFiles: List<File>,
        outputFile: File,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        if (imageFiles.isEmpty()) return false

        try {
            // 1. 最初の画像サイズを取得
            val firstBitmap = BitmapFactory.decodeFile(imageFiles[0].absolutePath) ?: return false
            val width = firstBitmap.width
            val height = firstBitmap.height

            val compositePixels = IntArray(width * height)
            firstBitmap.getPixels(compositePixels, 0, width, 0, 0, width, height)
            firstBitmap.recycle()

            val tempPixels = IntArray(width * height)

            // 2. 順次比較明（Lighten Blend）処理
            for (index in 1 until imageFiles.size) {
                val file = imageFiles[index]
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    bitmap.getPixels(tempPixels, 0, width, 0, 0, width, height)
                    bitmap.recycle()

                    for (i in compositePixels.indices) {
                        val p1 = compositePixels[i]
                        val p2 = tempPixels[i]

                        val r1 = (p1 shr 16) and 0xFF
                        val g1 = (p1 shr 8) and 0xFF
                        val b1 = p1 and 0xFF

                        val r2 = (p2 shr 16) and 0xFF
                        val g2 = (p2 shr 8) and 0xFF
                        val b2 = p2 and 0xFF

                        val r = max(r1, r2)
                        val g = max(g1, g2)
                        val b = max(b1, b2)

                        compositePixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                onProgress((index + 1).toFloat() / imageFiles.size)
            }

            // 3. 合成結果ビットマップを作成してファイル保存
            val resultBitmap = Bitmap.createBitmap(compositePixels, width, height, Bitmap.Config.ARGB_8888)
            FileOutputStream(outputFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            resultBitmap.recycle()

            Log.d("ImageCompositor", "Lighten blend composite created at ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e("ImageCompositor", "Failed to create lighten blend composite", e)
            return false
        }
    }
}
