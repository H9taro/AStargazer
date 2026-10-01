package com.example.astargazer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.FileOutputStream

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
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val baseBitmap = BitmapFactory.decodeFile(imageFiles[0].absolutePath, options) ?: return false
            val width = baseBitmap.width
            val height = baseBitmap.height

            val basePixels = IntArray(width * height)
            baseBitmap.getPixels(basePixels, 0, width, 0, 0, width, height)
            baseBitmap.recycle()

            val nextPixels = IntArray(width * height)

            for (index in 1 until imageFiles.size) {
                val file = imageFiles[index]
                val nextBitmap = BitmapFactory.decodeFile(file.absolutePath, options)
                if (nextBitmap != null) {
                    nextBitmap.getPixels(nextPixels, 0, width, 0, 0, width, height)
                    nextBitmap.recycle()

                    for (i in basePixels.indices) {
                        val p1 = basePixels[i]
                        val p2 = nextPixels[i]

                        val a1 = (p1 ushr 24) and 0xFF
                        val r1 = (p1 ushr 16) and 0xFF
                        val g1 = (p1 ushr 8) and 0xFF
                        val b1 = p1 and 0xFF

                        val r2 = (p2 ushr 16) and 0xFF
                        val g2 = (p2 ushr 8) and 0xFF
                        val b2 = p2 and 0xFF

                        val rMax = if (r1 > r2) r1 else r2
                        val gMax = if (g1 > g2) g1 else g2
                        val bMax = if (b1 > b2) b1 else b2
                        val aMax = if (a1 == 0) 0xFF else a1

                        basePixels[i] = (aMax shl 24) or (rMax shl 16) or (gMax shl 8) or bMax
                    }
                }
                onProgress((index + 1).toFloat() / imageFiles.size)
            }

            val resultBitmap = Bitmap.createBitmap(basePixels, width, height, Bitmap.Config.ARGB_8888)

            outputFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
            FileOutputStream(outputFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            resultBitmap.recycle()

            Log.d("ImageCompositor", "Lighten blend composite successfully created at ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e("ImageCompositor", "Failed to create lighten blend composite", e)
            return false
        }
    }
}
