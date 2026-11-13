package jp.hisanet.astargazer.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class ExportState {
    object Idle : ExportState()
    data class Generating(val progress: Float, val message: String) : ExportState()
    data class Completed(val outputFile: File) : ExportState()
    data class Error(val message: String) : ExportState()
}

/**
 * 仕上げ画面での動画・静止画生成（タイムラプス・比較明合成）のバックグラウンド実行および状態管理を行うシングルトン。
 * 画面のロック／ロック解除や Compose のライフサイクル再構築時でも UI と処理状態の分離を防ぎ、SSOTを保持する。
 */
object ExportManager {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var exportJob: Job? = null

    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    fun isGenerating(): Boolean = _state.value is ExportState.Generating

    fun startTimelapseExport(
        context: Context,
        imageFiles: List<File>,
        outputFile: File,
        darkFrameFile: File?,
        resolutionLabel: String,
        frameRate: Int,
        cropOffsetX: Float,
        cropOffsetY: Float
    ) {
        if (isGenerating()) return
        _state.value = ExportState.Generating(0f, "$resolutionLabel を生成中...")

        exportJob = scope.launch {
            try {
                val success = VideoEncoderHelper.createTimelapseVideo(
                    imageFiles = imageFiles,
                    outputFile = outputFile,
                    darkFrameFile = darkFrameFile,
                    resolutionLabel = resolutionLabel,
                    frameRate = frameRate,
                    cropOffsetX = cropOffsetX,
                    cropOffsetY = cropOffsetY,
                    onProgress = { p ->
                        _state.value = ExportState.Generating(p, "$resolutionLabel を生成中...")
                    }
                )

                if (success) {
                    FileViewerHelper.scanFile(context, outputFile)
                    _state.value = ExportState.Completed(outputFile)
                    withContext(Dispatchers.Main) {
                        FileViewerHelper.openInGoogleFilesOrViewer(context, outputFile)
                    }
                } else {
                    _state.value = ExportState.Error("$resolutionLabel の生成に失敗しました。")
                }
            } catch (e: Exception) {
                Log.e("ExportManager", "Timelapse export failed", e)
                _state.value = ExportState.Error("タイムラプス生成エラー: ${e.message}")
            }
        }
    }

    fun startCompositeExport(
        context: Context,
        imageFiles: List<File>,
        outputFile: File,
        darkFrameFile: File?
    ) {
        if (isGenerating()) return
        _state.value = ExportState.Generating(0f, "比較明合成を生成中...")

        exportJob = scope.launch {
            try {
                val success = ImageCompositor.createLightenBlendComposite(
                    imageFiles = imageFiles,
                    outputFile = outputFile,
                    darkFrameFile = darkFrameFile,
                    onProgress = { p ->
                        _state.value = ExportState.Generating(p, "比較明合成を生成中...")
                    }
                )

                if (success) {
                    FileViewerHelper.scanFile(context, outputFile)
                    _state.value = ExportState.Completed(outputFile)
                    withContext(Dispatchers.Main) {
                        FileViewerHelper.openInGoogleFilesOrViewer(context, outputFile)
                    }
                } else {
                    _state.value = ExportState.Error("比較明合成の生成に失敗しました。")
                }
            } catch (e: Exception) {
                Log.e("ExportManager", "Composite export failed", e)
                _state.value = ExportState.Error("比較明合成エラー: ${e.message}")
            }
        }
    }

    fun resetState() {
        if (!isGenerating()) {
            _state.value = ExportState.Idle
        }
    }
}
