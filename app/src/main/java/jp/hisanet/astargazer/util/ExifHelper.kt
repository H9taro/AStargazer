package jp.hisanet.astargazer.util

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExifHelper {

    /**
     * 画像ファイルから Exif の撮影日時文字列 (yyyy-MM-dd HH:mm:ss 形式) を取得する
     */
    fun getDateTime(file: File): String {
        try {
            val exif = ExifInterface(file.absolutePath)
            val dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (dt != null) {
                // "2026:10:03 18:42:00" -> "2026-10-03 18:42:00" に置換
                val formatted = dt.replace(":", "-")
                if (formatted.length >= 19) {
                    return formatted.substring(0, 4) + "-" + formatted.substring(5, 7) + "-" + formatted.substring(8)
                }
                return formatted
            }
        } catch (e: Exception) {
            Log.e("ExifHelper", "Failed to read datetime from exif", e)
        }
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return sdf.format(Date(file.lastModified()))
    }

    /**
     * 画像ファイルに撮影パラメータ (Exif メータデータ) および GPS 位置情報を自動書き込み・記録する
     * @param file 保存された画像ファイル (PNG / JPEG)
     * @param iso 撮影に使用された ISO 感度 (例: 1600)
     * @param exposureSeconds 撮影に使用された露出時間 (秒, 例: 4.0)
     * @param location 取得された GPS 位置情報 (Location?)
     */
    fun saveExifAttributes(
        file: File,
        iso: Int,
        exposureSeconds: Double,
        location: android.location.Location? = null
    ) {
        if (!file.exists()) return

        try {
            val exif = ExifInterface(file.absolutePath)
            val now = Date()
            val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val subSecFormat = SimpleDateFormat("SSS", Locale.US)

            val formattedDate = exifDateFormat.format(now)
            val formattedSubSec = subSecFormat.format(now)

            // 1. 撮影日時
            exif.setAttribute(ExifInterface.TAG_DATETIME, formattedDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formattedDate)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formattedDate)

            // 2. 秒未満・ミリ秒精度
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

            // 8. GPS 位置情報の書き込み
            if (location != null) {
                exif.setGpsInfo(location)
            }

            exif.saveAttributes()
            Log.d("ExifHelper", "Saved Exif data to ${file.name}: ISO=$iso, Exposure=${exposureSeconds}s")
        } catch (e: Exception) {
            Log.e("ExifHelper", "Failed to save Exif data for ${file.name}", e)
        }
    }
}
