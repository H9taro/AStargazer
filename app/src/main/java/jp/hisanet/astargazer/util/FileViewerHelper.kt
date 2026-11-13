package jp.hisanet.astargazer.util

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object FileViewerHelper {

    /**
     * 作成したファイルを MediaScanner に登録（ギャラリーやGoogleフォトで即時に認識させる）
     */
    fun scanFile(context: Context, file: File, onScanned: ((Uri?) -> Unit)? = null) {
        MediaScannerConnection.scanFile(
            context.applicationContext,
            arrayOf(file.absolutePath),
            null
        ) { path, uri ->
            Log.d("FileViewerHelper", "Scanned $path -> uri: $uri")
            onScanned?.invoke(uri)
        }
    }

    /**
     * 保存されたファイルを Google Files や標準ギャラリーで開いて表示
     */
    fun openInGoogleFilesOrViewer(context: Context, file: File) {
        scanFile(context, file) { scannedUri ->
            val contentUri = scannedUri ?: FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val mimeType = when (file.extension.lowercase()) {
                "mp4" -> "video/mp4"
                "jpg", "jpeg" -> "image/jpeg"
                else -> "*/*"
            }

            // 1. Google Files アプリ (com.google.android.apps.nfile) で表示
            val googleFilesIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.google.android.apps.nfile")
            }

            try {
                context.startActivity(googleFilesIntent)
                return@scanFile
            } catch (e: Exception) {
                Log.w("FileViewerHelper", "Google Files package not found, falling back to general chooser", e)
            }

            // 2. フォールバック: ギャラリー / 一般的なメディアビューアで表示
            val generalIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                val chooser = Intent.createChooser(generalIntent, "保存ファイルを開く").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            } catch (e: Exception) {
                Log.e("FileViewerHelper", "No app available to view file", e)
            }
        }
    }

    /**
     * 単一の画像ファイルを Android 標準共有シート (ACTION_SEND) で共有する（TransactionTooLargeException回避）
     */
    fun shareSingleFile(context: Context, file: File) {
        if (!file.exists()) return

        scanFile(context, file) { scannedUri ->
            val contentUri = scannedUri ?: try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } catch (e: Exception) {
                Log.w("FileViewerHelper", "Failed FileProvider getUriForFile", e)
                null
            } ?: return@scanFile

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "画像を共有: ${file.name}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                context.startActivity(chooser)
            } catch (e: Exception) {
                Log.e("FileViewerHelper", "Failed to launch share chooser", e)
            }
        }
    }
}
