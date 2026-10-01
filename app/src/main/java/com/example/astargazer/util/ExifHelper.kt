package com.example.astargazer.util

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExifHelper {

    /**
     * JPEG ファイルに撮影パラメータ (Exif メタデータ) を自動書き込み・記録する
     * @param file 保存された JPEG ファイル
     * @param iso 撮影に使用された ISO 感度 (例: 1600)
     * @param exposureSeconds 撮影に使用された露出時間 (秒, 例: 4.0)
     */
    fun saveExifAttributes(
        file: File,
        iso: Int,
        exposureSeconds: Double
    ) {
        if (!file.exists()) return

        try {
            val exif = ExifInterface(file.absolutePath)
            val now = Date()
            val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val formattedDate = exifDateFormat.format(now)

            // 1. 撮影日時
            exif.setAttribute(ExifInterface.TAG_DATETIME, formattedDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formattedDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formattedDate)

            // 2. ISO 感度 (旧タグ TAG_ISO_SPEED_RATINGS と推奨タグ TAG_PHOTOGRAPHIC_SENSITIVITY の両方に設定)
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, iso.toString())
            exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())

            // 3. 露出時間 (秒)
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureSeconds.toString())

            // 4. カメラメーカー & 機種名 (AQUOS sense8)
            exif.setAttribute(ExifInterface.TAG_MAKE, "SHARP")
            exif.setAttribute(ExifInterface.TAG_MODEL, "SH-M26")

            // 5. 焦点距離 (AQUOS sense8 標準レンズ 4.3mm)
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "43/10")

            // 6. アプリ名
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "AStargazer 1.0")

            exif.saveAttributes()
            Log.d("ExifHelper", "Saved Exif data to ${file.name}: ISO=$iso, Exposure=${exposureSeconds}s, Date=$formattedDate")
        } catch (e: Exception) {
            Log.e("ExifHelper", "Failed to save Exif data for ${file.name}", e)
        }
    }
}
