package com.example.astargazer.util

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

    /**
     * 試写調整画像の保存先ファイル (非圧縮 PNG)
     */
    fun getTestShootingFile(): File {
        val dir = getPublicAStargazerDir("TestShooting")
        return File(dir, "Test_${System.currentTimeMillis()}.png")
    }

    /**
     * ダークフレーム保存用ファイルの取得 (非圧縮 PNG)
     */
    fun getDarkFrameFile(): File {
        val dir = getPublicAStargazerDir("DarkFrame")
        return File(dir, "dark_frame.png")
    }

    fun getDarkFrameCaptureTempFile(context: Context): File {
        return File(context.cacheDir, "dark_frame_capture_${System.currentTimeMillis()}.jpg")
    }

    /**
     * 保存されているダークフレームファイルが存在し、かつ指定された露出時間と一致するかチェックする
     */
    fun hasValidDarkFrame(exposureSeconds: Double): Boolean {
        val darkFile = getDarkFrameFile()
        if (!darkFile.exists()) return false

        try {
            val exif = ExifInterface(darkFile.absolutePath)
            val exposureTimeStr = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
            if (exposureTimeStr != null) {
                val savedExposure = exposureTimeStr.toDoubleOrNull() ?: 0.0
                return kotlin.math.abs(savedExposure - exposureSeconds) < 0.01
            }
        } catch (e: Exception) {
            Log.e("StorageHelper", "Failed to read dark frame exif", e)
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
}
