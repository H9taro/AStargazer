package jp.hisanet.astargazer.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

enum class StorageTarget {
    INTERNAL,
    SD_CARD
}

object StorageHelper {

    // 1コマ（非圧縮静止画1枚）あたりの推定ファイルサイズ (PNG非圧縮時は約15〜20MB)
    const val ESTIMATED_BYTES_PER_FRAME = 15 * 1024 * 1024L

    @Volatile
    var currentStorageTarget: StorageTarget = StorageTarget.INTERNAL

    /**
     * SDカードのルートディレクトリを取得
     */
    fun getSdCardRoot(context: Context): File? {
        try {
            val dirs = context.getExternalFilesDirs(null)
            for (dir in dirs) {
                if (dir != null && Environment.isExternalStorageRemovable(dir)) {
                    val path = dir.absolutePath
                    val androidDataIndex = path.indexOf("/Android/data/")
                    if (androidDataIndex != -1) {
                        val rootPath = path.substring(0, androidDataIndex)
                        val root = File(rootPath)
                        if (root.exists() && root.canRead()) return root
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("StorageHelper", "Failed to resolve SD Card via getExternalFilesDirs", e)
        }

        try {
            val storage = File("/storage")
            storage.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name != "emulated" && file.name != "self" && file.canRead()) {
                    return file
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * SDカード上の Pictures/AStargazer ディレクトリを取得
     */
    fun getSdCardAStargazerDir(context: Context, subDirName: String = ""): File? {
        val root = getSdCardRoot(context) ?: return null
        val picturesDir = File(root, "Pictures")
        val dir = if (subDirName.isEmpty()) File(picturesDir, "AStargazer") else File(picturesDir, "AStargazer/$subDirName")
        if (!dir.exists()) dir.mkdirs()
        return if (dir.exists()) dir else null
    }

    /**
     * パブリックの Pictures/AStargazer ディレクトリを取得
     */
    fun getPublicAStargazerDir(subDirName: String = "", context: Context? = null, target: StorageTarget = currentStorageTarget): File {
        if (target == StorageTarget.SD_CARD && context != null) {
            val sdDir = getSdCardAStargazerDir(context, subDirName)
            if (sdDir != null) return sdDir
        }
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
    fun getAvailableStorageBytes(context: Context? = null, target: StorageTarget = currentStorageTarget): Long {
        val path = getPublicAStargazerDir(context = context, target = target)
        return try {
            val stat = StatFs(path.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * 現在の空き容量と下限閾値から残り撮影可能推定枚数を計算
     */
    fun calculateRemainingShots(currentStorageBytes: Long, minAllowedStorageBytes: Long): Int {
        val availableForShooting = currentStorageBytes - minAllowedStorageBytes
        if (availableForShooting <= 0) return 0
        return (availableForShooting / ESTIMATED_BYTES_PER_FRAME).toInt()
    }

    fun getTestShootingFile(iso: Int, context: Context? = null): File {
        val dir = getPublicAStargazerDir("TestShooting", context = context)
        return File(dir, "Test_ISO_${iso}_${System.currentTimeMillis()}.jpg")
    }

    /**
     * ダークフレーム保存用ファイルの取得
     */
    fun getDarkFrameFile(exposureSeconds: Double? = null, iso: Int? = null, context: Context? = null): File {
        val dir = getPublicAStargazerDir("DarkFrame", context = context)
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

    fun hasValidDarkFrame(exposureSeconds: Double, iso: Int, context: Context? = null): Boolean {
        val darkFile = getDarkFrameFile(exposureSeconds, iso, context = context)
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

        val legacyFile = File(getPublicAStargazerDir("DarkFrame", context = context), "dark_frame.png")
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

    fun getCompositeImageFile(context: Context? = null): File {
        val dir = getPublicAStargazerDir("Export", context = context)
        return File(dir, "Composite_StarTrails_${System.currentTimeMillis()}.jpg")
    }

    fun getTimelapseVideoFile(): File {
        val publicMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val dir = File(publicMovies, "AStargazer/Export")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "Timelapse_${System.currentTimeMillis()}.mp4")
    }

    fun getNextIntervalSessionDir(context: Context? = null): File {
        val parentDir = getPublicAStargazerDir(context = context)
        val existingDirs = parentDir.listFiles { file -> file.isDirectory && file.name.startsWith("Interval_") }
        var maxIndex = 0
        existingDirs?.forEach { dir ->
            val numStr = dir.name.removePrefix("Interval_")
            val num = numStr.toIntOrNull()
            if (num != null && num > maxIndex) {
                maxIndex = num
            }
        }
        val nextIndex = maxIndex + 1
        val newDir = File(parentDir, "Interval_%03d".format(Locale.JAPAN, nextIndex))
        if (!newDir.exists()) newDir.mkdirs()
        return newDir
    }

    /**
     * 存在するすべてのインターバル撮影セッションディレクトリのリストを取得する（内部ストレージ・SDカードの Pictures/AStargazer）
     */
    fun getIntervalSessions(context: Context? = null): List<File> {
        val sessions = mutableListOf<File>()

        fun scanParent(parentDir: File) {
            if (!parentDir.exists() || !parentDir.isDirectory) return
            parentDir.listFiles { file -> file.isDirectory && file.name.startsWith("Interval_") }?.let {
                sessions.addAll(it)
            }
            val legacyDir = File(parentDir, "Interval")
            if (legacyDir.exists() && legacyDir.isDirectory && !sessions.contains(legacyDir)) {
                sessions.add(legacyDir)
            }
        }

        scanParent(getPublicAStargazerDir(target = StorageTarget.INTERNAL))
        if (context != null) {
            getSdCardAStargazerDir(context)?.let { scanParent(it) }
        }

        return sessions.distinctBy { it.absolutePath }.sortedByDescending { it.name }
    }

    /**
     * 保存済みのインターバル撮影写真ファイルの一覧を取得 (指定セッション、または最新セッション)
     * 複数セッションからファイルが手動で混在させられた場合でも、ファイル名内の UTC タイムスタンプ順に正しく時系列ソートする
     */
    fun getIntervalImageFiles(sessionDir: File? = null, context: Context? = null): List<File> {
        val targetDir = sessionDir ?: getIntervalSessions(context).firstOrNull() ?: getPublicAStargazerDir("Interval", context = context)
        if (!targetDir.exists()) return emptyList()

        return targetDir.walkTopDown().filter { file ->
            val name = file.name
            val ext = file.extension.lowercase()
            !file.isDirectory &&
                    !file.isHidden &&
                    !name.startsWith(".") &&
                    !name.startsWith(".trashed") &&
                    (ext == "jpg" || ext == "jpeg" || ext == "png")
        }.sortedBy { file ->
            // ファイル名から UTC タイムスタンプ（末尾の数値）を抽出してソートキーにする
            // 例: "IMG_0001_1740000000000.jpg" -> 1740000000000L
            // 抽出できない場合はファイル最終更新日時をフォールバックにする
            val cleanName = file.name.substringBeforeLast('.')
            val parts = cleanName.split('_')
            val timestamp = parts.getOrNull(2)?.toLongOrNull() ?: parts.getOrNull(1)?.toLongOrNull() ?: file.lastModified()
            timestamp
        }.toList()
    }

    fun createIntervalJpegFile(index: Int, sessionDir: File? = null, context: Context? = null): File {
        val dir = sessionDir ?: getNextIntervalSessionDir(context = context)
        return File(dir, "IMG_%04d_%d.jpg".format(Locale.JAPAN, index, System.currentTimeMillis()))
    }

    /**
     * 選択したセッションをSDカードへ退避（移動）する
     */
    fun moveSessionToSdCard(context: Context, sessionDir: File): Boolean {
        if (!sessionDir.exists()) return false
        val sdAStargazer = getSdCardAStargazerDir(context) ?: return false
        val destDir = File(sdAStargazer, sessionDir.name)
        if (!destDir.exists()) destDir.mkdirs()

        val files = getIntervalImageFiles(sessionDir, context)
        if (files.isEmpty()) return false

        var copySuccess = true
        val copiedFiles = mutableListOf<Pair<File, File>>()

        for (srcFile in files) {
            val relPath = srcFile.absolutePath.removePrefix(sessionDir.absolutePath).trimStart('/', '\\')
            val dstFile = File(destDir, relPath)
            dstFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

            try {
                FileInputStream(srcFile).use { input ->
                    FileOutputStream(dstFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (dstFile.exists() && dstFile.length() == srcFile.length()) {
                    copiedFiles.add(srcFile to dstFile)
                } else {
                    copySuccess = false
                    break
                }
            } catch (e: Exception) {
                Log.e("StorageHelper", "Failed to copy ${srcFile.name} to SD card", e)
                copySuccess = false
                break
            }
        }

        if (copySuccess && copiedFiles.size == files.size) {
            for ((srcFile, _) in copiedFiles) {
                srcFile.delete()
            }
            sessionDir.deleteRecursively()
            Log.i("StorageHelper", "Successfully moved session ${sessionDir.name} to SD Card at ${destDir.absolutePath}")
            return true
        } else {
            for ((_, dstFile) in copiedFiles) {
                dstFile.delete()
            }
            if (destDir.listFiles()?.isEmpty() == true) destDir.delete()
            Log.e("StorageHelper", "Failed to move session ${sessionDir.name} to SD Card")
            return false
        }
    }

    /**
     * 指定されたセッションディレクトリの画像を削除する
     */
    fun clearIntervalImages(sessionDir: File? = null, context: Context? = null): Boolean {
        val files = getIntervalImageFiles(sessionDir, context)
        var allDeleted = true
        for (file in files) {
            if (file.exists() && !file.delete()) {
                allDeleted = false
            }
        }
        if (sessionDir != null && sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }
        return allDeleted
    }

    /**
     * すべてのインターバル撮影セッションの画像をすべて削除する
     */
    fun clearAllIntervalImages(context: Context? = null): Boolean {
        val sessions = getIntervalSessions(context)
        var allDeleted = true
        for (session in sessions) {
            if (!clearIntervalImages(session, context)) {
                allDeleted = false
            }
        }
        val legacyDir = getPublicAStargazerDir("Interval", context = context)
        if (legacyDir.exists()) {
            legacyDir.deleteRecursively()
        }
        return allDeleted
    }
}
