package jp.hisanet.astargazer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap

object ImageCompositor {

    /**
     * ダークフレーム画像を使用して、ライトフレーム（撮影画像）からノイズを減算除去する
     */
    fun subtractDarkFrame(src: Bitmap, dark: Bitmap?): Bitmap {
        if (dark == null) return src

        val width = src.width
        val height = src.height

        val scaledDark = if (dark.width != width || dark.height != height) {
            dark.scale(width, height)
        } else {
            dark
        }

        val result = createBitmap(width, height)
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
     * Android の Canvas および PorterDuff.Mode.LIGHTEN を用いてメモリ効率良く高速に合成する
     * ダークフレーム画像が存在する場合は自動的にダーク減算処理を実行する
     * 左下に「開始日時 ~ 終了日時」、右下にアプリ名 "AStargazer" のテロップを1段小さいフォントで焼き込む
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

            // 1枚目の画像をロード＆ダーク減算
            val firstRaw = BitmapFactory.decodeFile(imageFiles[0].absolutePath) ?: return false
            val firstSubtracted = subtractDarkFrame(firstRaw, darkBitmap)
            if (firstRaw != firstSubtracted) firstRaw.recycle()

            val width = firstSubtracted.width
            val height = firstSubtracted.height

            // 合成結果を保持するミュータブルな Bitmap
            val resultBitmap = createBitmap(width, height)
            val canvas = Canvas(resultBitmap)
            canvas.drawBitmap(firstSubtracted, 0f, 0f, null)
            firstSubtracted.recycle()

            // 比較明合成用の Paint (PorterDuff.Mode.LIGHTEN)
            val lightenPaint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
            }

            for (index in 1 until imageFiles.size) {
                val file = imageFiles[index]
                val rawBitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val frameSubtracted = subtractDarkFrame(rawBitmap, darkBitmap)
                if (rawBitmap != frameSubtracted) rawBitmap.recycle()

                // サイズが異なる場合はスケール調整
                val scaledFrame = if (frameSubtracted.width != width || frameSubtracted.height != height) {
                    val scaled = frameSubtracted.scale(width, height)
                    if (scaled != frameSubtracted) frameSubtracted.recycle()
                    scaled
                } else {
                    frameSubtracted
                }

                // 2枚目以降を Lighten モードで重ね合わせ
                canvas.drawBitmap(scaledFrame, 0f, 0f, lightenPaint)
                scaledFrame.recycle()

                onProgress((index + 1).toFloat() / imageFiles.size)
            }

            darkBitmap?.recycle()

            // テロップ（ウォーターマーク）の焼き込み
            val bmpWidth = resultBitmap.width
            val bmpHeight = resultBitmap.height

            // フォントサイズを1段小さく調整 (1/45f -> 1/60f)
            val textSize = (bmpHeight.toFloat() / 60f).coerceAtLeast(28f)
            val padding = bmpWidth * 0.025f

            val textPaint = Paint().apply {
                color = Color.WHITE
                this.textSize = textSize
                isAntiAlias = true
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(6f, 2f, 2f, Color.BLACK)
            }

            val startDateTime = ExifHelper.getDateTime(imageFiles.first())
            val endDateTime = ExifHelper.getDateTime(imageFiles.last())
            val leftText = "$startDateTime ~ $endDateTime"
            val rightText = "AStargazer"

            // 左下：開始日時 ~ 終了日時
            val leftY = bmpHeight - padding
            canvas.drawText(leftText, padding, leftY, textPaint)

            // 右下：アプリ名
            val rightTextWidth = textPaint.measureText(rightText)
            val rightX = (bmpWidth - rightTextWidth - padding).coerceAtLeast(padding)
            canvas.drawText(rightText, rightX, leftY, textPaint)

            FileOutputStream(outputFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            resultBitmap.recycle()
            Log.d("ImageCompositor", "Lighten blend composite (Canvas PorterDuff) created successfully at ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e("ImageCompositor", "Failed to create lighten blend composite", e)
            return false
        }
    }
}
