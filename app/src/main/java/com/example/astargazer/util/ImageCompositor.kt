package com.example.astargazer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object ImageCompositor {

    /**
     * ダークフレーム画像を使用して、ライトフレーム（撮影画像）からノイズを減算除去する
     */
    fun subtractDarkFrame(src: Bitmap, dark: Bitmap?): Bitmap {
        if (dark == null) return src

        val width = src.width
        val height = src.height

        val scaledDark = if (dark.width != width || dark.height != height) {
            Bitmap.createScaledBitmap(dark, width, height, true)
        } else {
            dark
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val srcPixels = IntArray(width * height)
        val darkPixels = IntArray(width * height)
        val resultPixels = IntArray(width * height)

        src.getPixels(srcPixels, 0, width, 0, 0, width, height)
        scaledDark.getPixels(darkPixels, 0, width, 0, 0, width, height)

        for (i in srcPixels.indices) {
            val srcP = srcPixels[i]
            val darkP = darkPixels[i]

            val srcR = (srcP shr 16) and 0xFF
            val srcG = (srcP shr 8) and 0xFF
            val srcB = srcP and 0xFF

            val darkR = (darkP shr 16) and 0xFF
            val darkG = (darkP shr 8) and 0xFF
            val darkB = darkP and 0xFF

            val r = (srcR - darkR).coerceAtLeast(0)
            val g = (srcG - darkG).coerceAtLeast(0)
            val b = (srcB - darkB).coerceAtLeast(0)

            resultPixels[i] = Color.rgb(r, g, b)
        }

        result.setPixels(resultPixels, 0, width, 0, 0, width, height)

        if (scaledDark != dark) {
            scaledDark.recycle()
        }

        return result
    }

    /**
     * 複数枚の静止画ファイル群から比較明合成 (Lighten Blend) 画像を生成する
     * ダークフレーム画像が存在する場合は自動的にダーク減算処理を実行する
     * 左下に「最高画質 - 開始: [日時] / 終了: [日時]」、右下にアプリ名 "AStargazer" のテロップを焼き込む
     *
     * @param imageFiles ソース画像ファイルリスト
     * @param outputFile 出力先 JPEG ファイル
     * @param darkFrameFile ダークフレーム画像ファイル (任意)
     * @param onProgress 進捗コールバック (0.0 ~ 1.0)
     * @return 合成成功の可否
     */
    fun createLightenBlendComposite(
        imageFiles: List<File>,
        outputFile: File,
        darkFrameFile: File? = null,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        if (imageFiles.isEmpty()) return false

        // 出力フォルダが存在しない場合は作成
        outputFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

        try {
            // ダークフレーム画像の読み込み
            val darkBitmap = if (darkFrameFile != null && darkFrameFile.exists()) {
                BitmapFactory.decodeFile(darkFrameFile.absolutePath)
            } else {
                null
            }

            val firstRaw = BitmapFactory.decodeFile(imageFiles[0].absolutePath) ?: return false
            val firstBitmap = subtractDarkFrame(firstRaw, darkBitmap)
            if (firstRaw != firstBitmap) firstRaw.recycle()

            val width = firstBitmap.width
            val height = firstBitmap.height

            val compositePixels = IntArray(width * height)
            firstBitmap.getPixels(compositePixels, 0, width, 0, 0, width, height)
            firstBitmap.recycle()

            val currentPixels = IntArray(width * height)

            for (index in 1 until imageFiles.size) {
                val file = imageFiles[index]
                val rawBitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val frameBitmap = subtractDarkFrame(rawBitmap, darkBitmap)
                if (rawBitmap != frameBitmap) rawBitmap.recycle()

                if (frameBitmap.width != width || frameBitmap.height != height) {
                    val scaled = Bitmap.createScaledBitmap(frameBitmap, width, height, true)
                    scaled.getPixels(currentPixels, 0, width, 0, 0, width, height)
                    scaled.recycle()
                } else {
                    frameBitmap.getPixels(currentPixels, 0, width, 0, 0, width, height)
                }
                frameBitmap.recycle()

                // 比較明合成 (Lighten Blend)
                for (i in compositePixels.indices) {
                    val compP = compositePixels[i]
                    val currP = currentPixels[i]

                    val compR = (compP shr 16) and 0xFF
                    val compG = (compP shr 8) and 0xFF
                    val compB = compP and 0xFF

                    val currR = (currP shr 16) and 0xFF
                    val currG = (currP shr 8) and 0xFF
                    val currB = currP and 0xFF

                    val maxR = if (currR > compR) currR else compR
                    val maxG = if (currG > compG) currG else compG
                    val maxB = if (currB > compB) currB else compB

                    compositePixels[i] = Color.rgb(maxR, maxG, maxB)
                }

                onProgress((index + 1).toFloat() / imageFiles.size)
            }

            darkBitmap?.recycle()

            // 合成結果 Bitmap の生成 (確実にミュータブルにする)
            val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(compositePixels, 0, width, 0, 0, width, height)
            }

            // テロップ（ウォーターマーク）の焼き込み
            val mutableBitmap = resultBitmap.copy(Bitmap.Config.ARGB_8888, true)
            resultBitmap.recycle()

            val canvas = Canvas(mutableBitmap)
            val bmpWidth = mutableBitmap.width
            val bmpHeight = mutableBitmap.height

            val textSize = (bmpHeight.toFloat() / 45f).coerceAtLeast(36f)
            val padding = bmpWidth * 0.025f

            val paint = Paint().apply {
                color = Color.WHITE
                this.textSize = textSize
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(6f, 2f, 2f, Color.BLACK)
            }

            val startDateTime = ExifHelper.getDateTime(imageFiles.first())
            val endDateTime = ExifHelper.getDateTime(imageFiles.last())
            val leftText = "最高画質 - 開始: $startDateTime / 終了: $endDateTime"
            val rightText = "AStargazer"

            // 左下：最高画質 - 開始日時 / 終了日時
            val leftY = bmpHeight - padding
            canvas.drawText(leftText, padding, leftY, paint)

            // 右下：アプリ名
            val rightTextWidth = paint.measureText(rightText)
            val rightX = (bmpWidth - rightTextWidth - padding).coerceAtLeast(padding)
            canvas.drawText(rightText, rightX, leftY, paint)

            FileOutputStream(outputFile).use { out ->
                mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            mutableBitmap.recycle()
            Log.d("ImageCompositor", "Lighten blend composite with watermarks created at ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e("ImageCompositor", "Failed to create lighten blend composite", e)
            return false
        }
    }
}
