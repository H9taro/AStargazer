package com.example.astargazer.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.util.Locale

object StorageHelper {

    /**
     * 利用可能な空きストレージ容量をバイト単位で取得
     */
    fun getAvailableStorageBytes(context: Context): Long {
        val path = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
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
     * 空き容量が 1GB (1,073,741,824 bytes) 以上あるかチェック
     */
    fun hasSufficientStorage(context: Context, minRequiredBytes: Long = 1_073_741_824L): Boolean {
        return getAvailableStorageBytes(context) > minRequiredBytes
    }

    /**
     * ダークフレーム保存用ファイルの取得
     */
    fun getDarkFrameFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AStargazer")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "dark_frame.jpg")
    }

    /**
     * インターバル撮影写真の保存先ファイルの生成
     */
    fun createIntervalImageFile(context: Context, index: Int): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AStargazer/Interval")
        if (!dir.exists()) dir.mkdirs()
        val timestamp = System.currentTimeMillis()
        return File(dir, "IMG_%04d_%d.jpg".format(Locale.JAPAN, index, timestamp))
    }

    /**
     * 比較明合成（Lighten Blend）静止画の保存先ファイル
     */
    fun getCompositeImageFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AStargazer/Export")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "Composite_StarTrails_${System.currentTimeMillis()}.jpg")
    }

    /**
     * タイムラプス動画（*.mp4）の保存先ファイル
     */
    fun getTimelapseVideoFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "AStargazer/Export")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "Timelapse_${System.currentTimeMillis()}.mp4")
    }

    /**
     * 保存済みのインターバル撮影写真ファイルの一覧を取得
     */
    fun getIntervalImageFiles(context: Context): List<File> {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "AStargazer/Interval")
        if (!dir.exists()) return emptyList()
        return dir.listFiles { file -> file.extension.lowercase(Locale.JAPAN) == "jpg" }?.sortedBy { it.name } ?: emptyList()
    }
}
