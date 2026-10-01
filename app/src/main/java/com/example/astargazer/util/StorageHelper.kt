package com.example.astargazer.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.util.Locale

object StorageHelper {

    // 1コマ（静止画1枚）あたりの推定ファイルサイズ (約5MB)
    const val ESTIMATED_BYTES_PER_FRAME = 5 * 1024 * 1024L

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
    fun getAvailableStorageBytes(context: Context): Long {
        val path = getPublicAStargazerDir()
        val stat = StatFs(path.path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }

    /**
     * 人間が読みやすい形式 (例: "12.5 GB") で空き容量文字列を取得
     */
    fun getFormattedAvailableStorage(context: Context): String {
        val bytes = getAvailableStorageBytes(context)
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.JAPAN, "%.2f GB", gb)
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
     * 空き容量が 1GB (1,073,741,824 bytes) 以上あるかチェック
     */
    fun hasSufficientStorage(context: Context, minRequiredBytes: Long = 1_073_741_824L): Boolean {
        return getAvailableStorageBytes(context) > minRequiredBytes
    }

    /**
     * 試写調整画像の保存先ファイル (パブリック Pictures/AStargazer/TestShooting/)
     */
    fun getTestShootingFile(): File {
        val dir = getPublicAStargazerDir("TestShooting")
        return File(dir, "Test_${System.currentTimeMillis()}.jpg")
    }

    /**
     * ダークフレーム保存用ファイルの取得 (パブリック Pictures/AStargazer/DarkFrame/)
     */
    fun getDarkFrameFile(context: Context? = null): File {
        val dir = getPublicAStargazerDir("DarkFrame")
        return File(dir, "dark_frame.jpg")
    }

    /**
     * インターバル撮影写真の保存先ファイルの生成 (パブリック Pictures/AStargazer/Interval/)
     */
    fun createIntervalImageFile(context: Context? = null, index: Int): File {
        val dir = getPublicAStargazerDir("Interval")
        val timestamp = System.currentTimeMillis()
        return File(dir, "IMG_%04d_%d.jpg".format(Locale.JAPAN, index, timestamp))
    }

    /**
     * 比較明合成（Lighten Blend）静止画の保存先ファイル (パブリック Pictures/AStargazer/Export/)
     */
    fun getCompositeImageFile(context: Context? = null): File {
        val dir = getPublicAStargazerDir("Export")
        return File(dir, "Composite_StarTrails_${System.currentTimeMillis()}.jpg")
    }

    /**
     * タイムラプス動画（*.mp4）の保存先ファイル (パブリック Movies/AStargazer/Export/)
     */
    fun getTimelapseVideoFile(context: Context? = null): File {
        val publicMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val dir = File(publicMovies, "AStargazer/Export")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "Timelapse_${System.currentTimeMillis()}.mp4")
    }

    /**
     * 保存済みのインターバル撮影写真ファイルの一覧を取得
     */
    fun getIntervalImageFiles(context: Context? = null): List<File> {
        val dir = getPublicAStargazerDir("Interval")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file -> file.extension.lowercase(Locale.JAPAN) == "jpg" }?.sortedBy { it.name } ?: emptyList()
    }
}
