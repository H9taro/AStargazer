package com.example.astargazer.util

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.pow
import kotlin.math.sqrt

object ImageContrastAnalyzer {

    /**
     * ビットマップのコントラスト（輝度の標準偏差）を計算
     * @param bitmap 解析対象のビットマップ
     * @param sampleStep サンプリング間隔 (処理を高速化するため間引いて計算)
     * @return 輝度の標準偏差 (コントラストスコア)
     */
    fun calculateContrastScore(bitmap: Bitmap, sampleStep: Int = 4): Double {
        val width = bitmap.width
        val height = bitmap.height

        var totalLuminance = 0.0
        var pixelCount = 0

        // 1. 全体(間引き)の平均輝度を算出
        for (y in 0 until height step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                // ITU-R BT.601 輝度変換
                val luminance = 0.299 * r + 0.587 * g + 0.114 * b
                totalLuminance += luminance
                pixelCount++
            }
        }

        if (pixelCount == 0) return 0.0
        val meanLuminance = totalLuminance / pixelCount

        // 2. 輝度の分散を算出
        var varianceSum = 0.0
        for (y in 0 until height step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val luminance = 0.299 * r + 0.587 * g + 0.114 * b
                varianceSum += (luminance - meanLuminance).pow(2)
            }
        }

        val variance = varianceSum / pixelCount
        return sqrt(variance) // 標準偏差をコントラストスコアとして返す
    }

    /**
     * ビットマップの平均輝度 (0.0 ~ 255.0) を計算
     */
    fun calculateAverageLuminance(bitmap: Bitmap, sampleStep: Int = 4): Double {
        val width = bitmap.width
        val height = bitmap.height

        var totalLuminance = 0.0
        var pixelCount = 0

        for (y in 0 until height step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                val luminance = 0.299 * r + 0.587 * g + 0.114 * b
                totalLuminance += luminance
                pixelCount++
            }
        }

        if (pixelCount == 0) return 0.0
        return totalLuminance / pixelCount
    }

    /**
     * 平均輝度に基づいて過露出（白飛び）かどうかを判定し、推奨される適正 ISO 感度を返す
     * @param currentIso 現在設定されている ISO 感度
     * @param averageLuminance 試写画像の平均輝度 (0~255)
     * @return 調整後の最適 ISO 感度
     */
    fun adjustIsoForLuminance(currentIso: Int, averageLuminance: Double): Int {
        return when {
            averageLuminance > 180.0 -> (currentIso / 8).coerceAtLeast(100) // 重度の白飛び -> ISO大幅引き下げ
            averageLuminance > 120.0 -> (currentIso / 4).coerceAtLeast(100) // 中度の白飛び -> ISO引き下げ
            averageLuminance > 80.0 -> (currentIso / 2).coerceAtLeast(100)  // 軽度の過露出 -> ISO半分
            else -> currentIso // 適正露出
        }
    }
}
