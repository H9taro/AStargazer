package com.example.astargazer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
 * 出力画質（解像度）の選択肢
 */
enum class ExportResolution(val label: String, val width: Int, val height: Int) {
    HD("HD (720p)", 720, 1280),
    FULLHD("Full HD (1080p)", 1080, 1920),
    QHD_2K("2K", 1440, 2560),
    UHD_4K("4K", 2160, 3840),
    MAX("最高画質 (オリジナル)", 0, 0);

    fun next(excludeMax: Boolean): ExportResolution {
        val entries = if (excludeMax) listOf(HD, FULLHD, QHD_2K, UHD_4K) else entries
        val currentIndex = entries.indexOf(this)
        return if (currentIndex >= 0 && currentIndex < entries.size - 1) {
            entries[currentIndex + 1]
        } else {
            entries[0]
        }
    }
}

/**
 * 保存形式の選択肢
 */
enum class ExportFormat(val label: String) {
    TIMELAPSE("タイムラプス動画 (*.mp4)"),
    COMPOSITE("比較明合成静止画 (*.jpg)")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveTabContent(
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val intervalFiles = remember { StorageHelper.getIntervalImageFiles(context) }
    var selectedFormat by remember { mutableStateOf(ExportFormat.TIMELAPSE) }
    var selectedResolution by remember { mutableStateOf(ExportResolution.FULLHD) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    var isGenerating by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var statusMessage by remember {
        mutableStateOf(
            if (intervalFiles.isNotEmpty()) "撮影済み静止画: ${intervalFiles.size}コマ\n横スワイプで画質切替が可能です。"
            else "保存可能な撮影済み画像がありません。"
        )
    }

    // 先頭画像のBitmapロード
    val firstImageBitmap = remember(intervalFiles) {
        val firstFile = intervalFiles.firstOrNull()
        if (firstFile != null && firstFile.exists()) {
            try {
                BitmapFactory.decodeFile(firstFile.absolutePath)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    var totalDragX by remember { mutableFloatStateOf(0f) }

    // 保存処理の実行
    fun executeExport() {
        if (intervalFiles.isEmpty()) return
        isGenerating = true
        progress = 0f
        val formatName = if (selectedFormat == ExportFormat.TIMELAPSE) "タイムラプス動画" else "比較明合成静止画"
        statusMessage = "$formatName (${selectedResolution.label}) を生成中..."

        coroutineScope.launch(Dispatchers.IO) {
            val darkFrameFile = StorageHelper.getDarkFrameFile(context)
            val success: Boolean
            val outputFile: File

            if (selectedFormat == ExportFormat.TIMELAPSE) {
                outputFile = StorageHelper.getTimelapseVideoFile(context)
                success = VideoEncoderHelper.createTimelapseVideo(
                    imageFiles = intervalFiles,
                    outputFile = outputFile,
                    darkFrameFile = darkFrameFile.exists().let { if (it) darkFrameFile else null },
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
                    statusMessage = "$formatName の生成が完了しました！\n保存先: ${outputFile.name}"
                    FileViewerHelper.openInGoogleFilesOrViewer(context, outputFile)
                } else {
                    statusMessage = "$formatName の生成に失敗しました。"
                }
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(selectedFormat) {
                detectHorizontalDragGestures(
                    onDragStart = { totalDragX = 0f },
                    onDragEnd = {
                        if (abs(totalDragX) > 80f && !isGenerating) {
                            val excludeMax = (selectedFormat == ExportFormat.TIMELAPSE)
                            selectedResolution = selectedResolution.next(excludeMax)
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalDragX += dragAmount
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
                text = "保存",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
            )

            // 1. 保存形式選択プルダウン ＆ 画質表示（横スワイプ案内）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExposedDropdownMenuBox(
                    expanded = isDropdownExpanded,
                    onExpandedChange = { isDropdownExpanded = !isDropdownExpanded },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = selectedFormat.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("保存形式", color = Color.LightGray, fontSize = 10.sp) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF1E88E5),
                            unfocusedBorderColor = Color.Gray,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )

                    ExposedDropdownMenu(
                        expanded = isDropdownExpanded,
                        onDismissRequest = { isDropdownExpanded = false }
                    ) {
                        ExportFormat.entries.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(format.label, color = Color.White) },
                                onClick = {
                                    selectedFormat = format
                                    isDropdownExpanded = false
                                    // タイムラプス選択時に現在 MAX なら FullHD にフォールバック
                                    if (format == ExportFormat.TIMELAPSE && selectedResolution == ExportResolution.MAX) {
                                        selectedResolution = ExportResolution.FULLHD
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 画質表示バッジ（横スワイプ切替）
                Surface(
                    color = Color(0xFF1E88E5).copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(56.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(text = "画質 (←スワイプ→)", color = Color.LightGray, fontSize = 9.sp)
                        Text(text = selectedResolution.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. プレビュー（先頭画像 ＋ クロップ枠線）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.DarkGray, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (firstImageBitmap != null) {
                    Image(
                        bitmap = firstImageBitmap.asImageBitmap(),
                        contentDescription = "先頭画像プレビュー",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    // 画質に応じたクロップ枠のオーバーレイ
                    SaveCropGuideOverlay(selectedResolution = selectedResolution)
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
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. 保存ボタン
            Button(
                onClick = { executeExport() },
                enabled = !isGenerating && intervalFiles.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E88E5),
                    disabledContainerColor = Color.DarkGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (selectedFormat == ExportFormat.TIMELAPSE) "🎬 タイムラプス動画を出力" else "🌌 比較明合成静止画を出力",
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
    selectedResolution: ExportResolution
) {
    if (selectedResolution == ExportResolution.MAX) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        if (width <= 0f || height <= 0f) return@BoxWithConstraints

        val scale = when (selectedResolution) {
            ExportResolution.HD -> 0.60f
            ExportResolution.FULLHD -> 0.75f
            ExportResolution.QHD_2K -> 0.82f
            ExportResolution.UHD_4K -> 0.90f
            ExportResolution.MAX -> 1.0f
        }

        val borderColor = when (selectedResolution) {
            ExportResolution.HD -> Color(0xFFFF5252)
            ExportResolution.FULLHD -> Color(0xFFFFEB3B)
            ExportResolution.QHD_2K -> Color(0xFF00E676)
            ExportResolution.UHD_4K -> Color(0xFF00B0FF)
            ExportResolution.MAX -> Color.Transparent
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
