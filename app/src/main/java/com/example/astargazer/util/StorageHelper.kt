package com.example.astargazer.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import java.io.File

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
        return File(dir, "IMG_${index}_${timestamp}.jpg")
    }
}
