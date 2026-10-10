package jp.hisanet.astargazer.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale

object StorageHelper {

    // 1コマ（非圧縮静止画1枚）あたりの推定ファイルサイズ (PNG非圧縮時は約15〜20MB)
    const val ESTIMATED_BYTES_PER_FRAME = 15 * 1024 * 1024L

    /**
     * パブリックの Pictures/AStargazer ディレクトリを取得
     */
    fun getPublicAStargazerDir(subDirName: String = ""): File {
        val publicPictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val dir = if (subDirName.isEmpty()) {
            File(publicPictures, "AStargazer")
        } else {
            File(publicPictures, "AStargazer/$subDirName")
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 利用可能な空きストレージ容量をバイト単位で取得
     */
    fun getAvailableStorageBytes(): Long {
        val path = getPublicAStargazerDir()
        val stat = StatFs(path.path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    /**
     * 現在の空き容量と下限閾値から残り撮影可能推定枚数を計算
     */
    fun calculateRemainingShots(currentStorageBytes: Long, minAllowedStorageBytes: Long): Int {
        val availableForShooting = currentStorageBytes - minAllowedStorageBytes
        if (availableForShooting <= 0) return 0
        return (availableForShooting / ESTIMATED_BYTES_PER_FRAME).toInt()
    }

    fun getTestShootingFile(iso: Int): File {
        val dir = getPublicAStargazerDir("TestShooting")
        return File(dir, "Test_ISO_${iso}_${System.currentTimeMillis()}.jpg")
    }

    /**
     * ダークフレーム保存用ファイルの取得 (指定された露出時間とISOに応じた個別ファイル名)
     */
    fun getDarkFrameFile(exposureSeconds: Double? = null, iso: Int? = null): File {
        val dir = getPublicAStargazerDir("DarkFrame")
        val filename = if (exposureSeconds != null && iso != null) {
            "dark_exp_${String.format(Locale.US, "%.1f", exposureSeconds)}s_iso_${iso}.png"
        } else {
            "dark_frame.png"
        }
        return File(dir, filename)
    }

    fun getDarkFrameCaptureTempFile(context: Context): File {
        return File(context.cacheDir, "dark_frame_capture_${System.currentTimeMillis()}.jpg")
    }

    /**
     * 指定された露出時間とISO感度に一致する有効なダークフレームが存在するかチェックする
     */
    fun hasValidDarkFrame(exposureSeconds: Double, iso: Int): Boolean {
        val darkFile = getDarkFrameFile(exposureSeconds, iso)
        if (darkFile.exists()) {
            try {
                val exif = ExifInterface(darkFile.absolutePath)
                val exposureTimeStr = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                val isoStr = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                if (exposureTimeStr != null) {
                    val savedExposure = exposureTimeStr.toDoubleOrNull() ?: 0.0
                    val savedIso = isoStr?.toIntOrNull() ?: iso
                    if (kotlin.math.abs(savedExposure - exposureSeconds) < 0.01 && savedIso == iso) {
                        return true
                    }
                } else {
                    return true
                }
            } catch (e: Exception) {
                Log.e("StorageHelper", "Failed to read dark frame exif", e)
                return true
            }
        }

        // レガシーファイルフォールバック
        val legacyFile = File(getPublicAStargazerDir("DarkFrame"), "dark_frame.png")
        if (legacyFile.exists()) {
            try {
                val exif = ExifInterface(legacyFile.absolutePath)
                val exposureTimeStr = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
                if (exposureTimeStr != null) {
                    val savedExposure = exposureTimeStr.toDoubleOrNull() ?: 0.0
                    return kotlin.math.abs(savedExposure - exposureSeconds) < 0.01
                }
                return true
            } catch (_: Exception) {
                return true
            }
        }

        return false
    }

    fun createIntervalJpegFile(index: Int): File {
        val dir = getPublicAStargazerDir("Interval")
        return File(dir, "IMG_%04d_%d.jpg".format(Locale.JAPAN, index, System.currentTimeMillis()))
    }

    /**
     * 比較明合成（Lighten Blend）静止画の保存先ファイル (出力は標準高画質 JPEG)
     */
    fun getCompositeImageFile(): File {
        val dir = getPublicAStargazerDir("Export")
        return File(dir, "Composite_StarTrails_${System.currentTimeMillis()}.jpg")
    }

    /**
     * タイムラプス動画（*.mp4）の保存先ファイル (パブリック Movies/AStargazer/Export/)
     */
    fun getTimelapseVideoFile(): File {
        val publicMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val dir = File(publicMovies, "AStargazer/Export")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "Timelapse_${System.currentTimeMillis()}.mp4")
    }

    /**
     * 保存済みのインターバル撮影写真ファイルの一覧を取得 (非圧縮 PNG 対象)
     * (※ ゴミ箱ファイル .trashed- や ドット隠しファイルは除外)
     */
    fun getIntervalImageFiles(): List<File> {
        val dir = getPublicAStargazerDir("Interval")
        if (!dir.exists()) return emptyList()

        return dir.listFiles { file ->
            val name = file.name
            val ext = file.extension
            file.isFile &&
                    !file.isHidden &&
                    !name.startsWith(".") &&
                    !name.startsWith(".trashed") &&
                    (ext.equals("png", ignoreCase = true) || ext.equals("jpg", ignoreCase = true) || ext.equals("jpeg", ignoreCase = true))
        }?.sortedBy { it.name } ?: emptyList()
    }

    /**
     * 保存されているすべてのインターバル撮影写真ファイルを一括削除する
     */
    fun clearIntervalImages(): Boolean {
        val files = getIntervalImageFiles()
        var allDeleted = true
        for (file in files) {
            if (file.exists() && !file.delete()) {
                allDeleted = false
            }
        }
        return allDeleted
    }
}
