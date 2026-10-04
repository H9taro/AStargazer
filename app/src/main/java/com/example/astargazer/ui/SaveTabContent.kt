package com.example.astargazer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.astargazer.util.FileViewerHelper
import com.example.astargazer.util.ImageCompositor
import com.example.astargazer.util.StorageHelper
import com.example.astargazer.util.VideoEncoderHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * 出力モード（縦スワイプで切り替え、2Kを廃止）
 * 1. HD（タイムラプス）
 * 2. Full HD（タイムラプス）
 * 3. 4K（タイムラプス）
 * 4. 最高画質（比較明合成）
 */
enum class ExportMode(
    val label: String,
    val isTimelapse: Boolean,
    val resolutionLabel: String
) {
    HD_TIMELAPSE("HD（タイムラプス）", true, "HD (720p)"),
    FULLHD_TIMELAPSE("Full HD（タイムラプス）", true, "Full HD (1080p)"),
    UHD_4K_TIMELAPSE("4K（タイムラプス）", true, "4K"),
    MAX_COMPOSITE("最高画質（比較明合成）", false, "最高画質");

    fun next(): ExportMode {
        val entries = entries
        val currentIndex = entries.indexOf(this)
        return if (currentIndex >= 0 && currentIndex < entries.size - 1) {
            entries[currentIndex + 1]
        } else {
            entries[0]
        }
    }

    fun prev(): ExportMode {
        val entries = entries
        val currentIndex = entries.indexOf(this)
        return if (currentIndex > 0) {
            entries[currentIndex - 1]
        } else {
            entries[entries.size - 1]
        }
    }
}

@Composable
fun SaveTabContent(
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val intervalFiles = remember { StorageHelper.getIntervalImageFiles(context) }
    var selectedMode by remember { mutableStateOf(ExportMode.FULLHD_TIMELAPSE) }

    // プレビュー表示する画像コマのインデックス
    var currentImageIndex by remember { mutableIntStateOf(0) }

    var isGenerating by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusMessage by remember {
        mutableStateOf(
            if (intervalFiles.isNotEmpty()) "撮影済み静止画: ${intervalFiles.size}コマ\n横スワイプ: コマ切替 | 縦スワイプ: 出力モード切替"
            else "保存可能な撮影済み画像がありません。"
        )
    }

    // プレビュー用の安全なサンプリングデコード関数（OOM防止）
    fun decodeSampledBitmapForPreview(file: File, reqWidth: Int = 1080, reqHeight: Int = 1920): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)

            val (height: Int, width: Int) = options.run { outHeight to outWidth }
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight: Int = height / 2
                val halfWidth: Int = width / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            options.inSampleSize = inSampleSize
            options.inJustDecodeBounds = false

            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
            Log.e("SaveTabContent", "Failed to decode preview bitmap", e)
            null
        }
    }

    // 現在のインデックスのBitmapをロード
    val currentBitmap = remember(intervalFiles, currentImageIndex) {
        if (intervalFiles.isNotEmpty() && currentImageIndex in intervalFiles.indices) {
            val file = intervalFiles[currentImageIndex]
            if (file.exists()) {
                decodeSampledBitmapForPreview(file)
            } else {
                null
            }
        } else {
            null
        }
    }

    var totalDragX by remember { mutableFloatStateOf(0f) }
    var totalDragY by remember { mutableFloatStateOf(0f) }

    // 保存処理の実行
    fun executeExport() {
        if (intervalFiles.isEmpty()) return
        isGenerating = true
        progress = 0f
        statusMessage = "${selectedMode.label} を生成中..."

        coroutineScope.launch(Dispatchers.IO) {
            val darkFrameFile = StorageHelper.getDarkFrameFile(context)
            val success: Boolean
            val outputFile: File

            if (selectedMode.isTimelapse) {
                outputFile = StorageHelper.getTimelapseVideoFile(context)
                success = VideoEncoderHelper.createTimelapseVideo(
                    imageFiles = intervalFiles,
                    outputFile = outputFile,
                    darkFrameFile = darkFrameFile.exists().let { if (it) darkFrameFile else null },
                    resolutionLabel = selectedMode.resolutionLabel,
                    frameRate = 30,
                    onProgress = { p -> progress = p }
                )
            } else {
                outputFile = StorageHelper.getCompositeImageFile(context)
                success = ImageCompositor.createLightenBlendComposite(
                    imageFiles = intervalFiles,
                    outputFile = outputFile,
                    darkFrameFile = darkFrameFile.exists().let { if (it) darkFrameFile else null },
                    onProgress = { p -> progress = p }
                )
            }

            withContext(Dispatchers.Main) {
                isGenerating = false
                if (success) {
                    FileViewerHelper.scanFile(context, outputFile)
                    statusMessage = "${selectedMode.label} の生成が完了しました！\n保存先: ${outputFile.name}"
                    FileViewerHelper.openInGoogleFilesOrViewer(context, outputFile)
                } else {
                    statusMessage = "${selectedMode.label} の生成に失敗しました。"
                }
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(intervalFiles) {
                detectHorizontalDragGestures(
                    onDragStart = { totalDragX = 0f },
                    onDragEnd = {
                        if (abs(totalDragX) > 60f && intervalFiles.isNotEmpty() && !isGenerating) {
                            if (totalDragX > 0f) {
                                // 右スワイプ: 前のコマ
                                currentImageIndex = if (currentImageIndex > 0) currentImageIndex - 1 else intervalFiles.size - 1
                            } else {
                                // 左スワイプ: 次のコマ
                                currentImageIndex = if (currentImageIndex < intervalFiles.size - 1) currentImageIndex + 1 else 0
                            }
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalDragX += dragAmount
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { totalDragY = 0f },
                    onDragEnd = {
                        if (abs(totalDragY) > 60f && !isGenerating) {
                            if (totalDragY > 0f) {
                                // 下スワイプ: 前のモード
                                selectedMode = selectedMode.prev()
                            } else {
                                // 上スワイプ: 次のモード
                                selectedMode = selectedMode.next()
                            }
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        totalDragY += dragAmount
                    }
                )
            },
        color = Color.Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ヘッダー情報
            Text(
                text = "仕上げ",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
            )

            // 出力モード表示カード（縦スワイプ切替）
            Surface(
                color = Color(0xFF1E88E5).copy(alpha = 0.3f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = "出力モード (↕ 縦スワイプで切替)", color = Color.LightGray, fontSize = 10.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = selectedMode.label,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. プレビュー（コマ画像 ＋ クロップ枠線）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.DarkGray, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = "プレビュー画像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    // 画質に応じたクロップ枠のオーバーレイ（最高画質時は非表示）
                    SaveCropGuideOverlay(resolutionLabel = selectedMode.resolutionLabel)

                    // コマ番号表示バッジ
                    if (intervalFiles.isNotEmpty()) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "📷 ${currentImageIndex + 1} / ${intervalFiles.size}コマ (↔ 横スワイプで切替)",
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = "プレビュー画像がありません",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ステータスカード
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = statusMessage,
                        color = Color.White,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )

                    if (isGenerating) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF1E88E5),
                            trackColor = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${(progress * 100).toInt()}% 完了",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. 仕上げボタン
            Button(
                onClick = { executeExport() },
                enabled = !isGenerating && intervalFiles.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedMode.isTimelapse) Color(0xFF1E88E5) else Color(0xFF43A047),
                    disabledContainerColor = Color.DarkGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (selectedMode.isTimelapse) "🎬 ${selectedMode.label} を出力" else "🌌 ${selectedMode.label} を出力",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 選択された画質に応じたクロップ枠線をプレビュー上に表示するオーバーレイ
 */
@Composable
private fun SaveCropGuideOverlay(
    resolutionLabel: String
) {
    if (resolutionLabel == "最高画質") return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        if (width <= 0f || height <= 0f) return@BoxWithConstraints

        val scale = when (resolutionLabel) {
            "HD (720p)" -> 0.60f
            "Full HD (1080p)" -> 0.75f
            "4K" -> 0.90f
            else -> {
                Log.w("SaveCropGuideOverlay", "Unknown resolutionLabel: '$resolutionLabel', defaulting scale to 0.75f (Full HD)")
                0.75f
            }
        }

        val borderColor = when (resolutionLabel) {
            "HD (720p)" -> Color(0xFFFF5252)
            "Full HD (1080p)" -> Color(0xFFFFEB3B)
            "4K" -> Color(0xFF00B0FF)
            else -> Color.Transparent
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val guideHeight = height * scale
            val guideWidth = (guideHeight * 9f) / 16f
            val left = (width - guideWidth) / 2f
            val top = (height - guideHeight) / 2f

            drawRect(
                color = borderColor,
                topLeft = Offset(left, top),
                size = Size(guideWidth, guideHeight),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                )
            )
        }
    }
}
