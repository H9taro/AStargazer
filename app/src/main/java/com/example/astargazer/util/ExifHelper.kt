package com.example.astargazer.util

import android.location.Location
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExifHelper {

    /**
     * JPEG/PNG ファイルに撮影パラメータ (Exif メタデータ及ひ GPS 位置情報) を自動書き込み・記録する
     * @param file 保存された画像ファイル
     * @param iso 撮影に使用された ISO 感度 (例: 1600)
     * @param exposureSeconds 撮影に使用された露出時間 (秒, 例: 4.0)
     * @param location GPS 位置情報 (Location, オプション)
     */
    fun saveExifAttributes(
        file: File,
        iso: Int,
        exposureSeconds: Double,
        location: Location? = null
    ) {
        if (!file.exists()) return

        try {
            val exif = ExifInterface(file.absolutePath)
            val now = Date()
            val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val subSecFormat = SimpleDateFormat("SSS", Locale.US)

            val formattedDate = exifDateFormat.format(now)
            val formattedSubSec = subSecFormat.format(now)

            // 1. 撮影日時 (秒単位まで記録: 例 2026:10:02 22:53:45)
            exif.setAttribute(ExifInterface.TAG_DATETIME, formattedDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formattedDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formattedDate)

            // 2. 秒未満・ミリ秒精度 (TAG_SUBSEC_TIME)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME, formattedSubSec)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, formattedSubSec)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, formattedSubSec)

            // 3. ISO 感度
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, iso.toString())
            exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, iso.toString())

            // 4. 露出時間 (秒)
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, exposureSeconds.toString())

            // 5. カメラメーカー & 機種名 (AQUOS sense8)
            exif.setAttribute(ExifInterface.TAG_MAKE, "SHARP")
            exif.setAttribute(ExifInterface.TAG_MODEL, "SH-M26")

            // 6. 焦点距離 (AQUOS sense8 標準レンズ 4.3mm)
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "43/10")

            // 7. アプリ名
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "AStargazer 1.0")

            // 8. GPS 位置情報の書き込み (Location が存在する場合)
            if (location != null) {
                exif.setGpsInfo(location)
            }

            exif.saveAttributes()
            Log.d("ExifHelper", "Saved Exif data to ${file.name}: ISO=$iso, Exposure=${exposureSeconds}s, GPS=${location?.latitude},${location?.longitude}")
        } catch (e: Exception) {
            Log.e("ExifHelper", "Failed to save Exif data for ${file.name}", e)
        }
    }
}
