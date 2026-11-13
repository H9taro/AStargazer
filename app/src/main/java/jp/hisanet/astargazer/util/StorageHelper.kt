package jp.hisanet.astargazer.util

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale

enum class StorageTarget {
    INTERNAL,
    SD_CARD
}

data class SessionCameraParams(
    val exposureSeconds: Double,
    val iso: Int
)

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

    /**
     * 選択中セッションの先頭画像の EXIF (露出時間・ISO感度) を取得する
     */
    fun getSessionCameraParams(sessionDir: File?, context: Context? = null): SessionCameraParams? {
        val files = getIntervalImageFiles(sessionDir, context)
        val firstFile = files.firstOrNull() ?: return null
        try {
            val exif = ExifInterface(firstFile.absolutePath)
            val exposureStr = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
            val isoStr = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                ?: exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS)

            val exposureSeconds = exposureStr?.toDoubleOrNull() ?: return null
            val iso = isoStr?.toIntOrNull() ?: return null
            return SessionCameraParams(exposureSeconds, iso)
        } catch (e: Exception) {
            Log.e("StorageHelper", "Failed to read EXIF params from ${firstFile.name}", e)
            return null
        }
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
     * ※ 画像ファイルが1コマ以上存在する有効なセッションディレクトリのみを抽出（空のディレクトリを除外）
     */
    fun getIntervalSessions(context: Context? = null): List<File> {
        val sessions = mutableListOf<File>()

        fun scanParent(parentDir: File) {
            if (!parentDir.exists() || !parentDir.isDirectory) return
            parentDir.listFiles { file -> file.isDirectory && file.name.startsWith("Interval_") }?.let { dirs ->
                dirs.filter { dir -> getIntervalImageFiles(dir, context).isNotEmpty() }.let { validDirs ->
                    sessions.addAll(validDirs)
                }
            }
            val legacyDir = File(parentDir, "Interval")
            if (legacyDir.exists() && legacyDir.isDirectory && getIntervalImageFiles(legacyDir, context).isNotEmpty() && !sessions.contains(legacyDir)) {
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
        if (!targetDir.exists() || !targetDir.isDirectory) return emptyList()

        return targetDir.listFiles { file ->
            val name = file.name
            val ext = file.extension.lowercase()
            !file.isDirectory &&
                    !file.isHidden &&
                    !name.startsWith(".") &&
                    !name.startsWith(".trashed") &&
                    (ext == "jpg" || ext == "jpeg" || ext == "png")
        }?.sortedBy { file ->
            // ファイル名内の UTC タイムスタンプをメモリ上で抽出してソートキーにする
            // 新形式 (IMG_1740000000000.jpg -> index 1) と旧形式 (IMG_0001_1740000000000.jpg -> index 2) の両方に対応
            val cleanName = file.name.substringBeforeLast('.')
            val parts = cleanName.split('_')
            parts.getOrNull(1)?.toLongOrNull() ?: parts.getOrNull(2)?.toLongOrNull() ?: file.name.hashCode().toLong()
        }?.toList() ?: emptyList()
    }

    @Suppress("UNUSED_PARAMETER")
    fun createIntervalJpegFile(index: Int, sessionDir: File? = null, context: Context? = null): File {
        val dir = sessionDir ?: getNextIntervalSessionDir(context = context)
        // 0001連番を廃止し、よりシンプルな IMG_{timestamp}.jpg 形式で生成
        return File(dir, "IMG_%d.jpg".format(Locale.JAPAN, System.currentTimeMillis()))
    }
}
