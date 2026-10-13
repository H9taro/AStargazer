package jp.hisanet.astargazer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import jp.hisanet.astargazer.util.FileViewerHelper
import jp.hisanet.astargazer.util.ImageCompositor
import jp.hisanet.astargazer.util.StorageHelper
import jp.hisanet.astargazer.util.VideoEncoderHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 出力モード
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveTabContent(
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    var sessions by remember { mutableStateOf(StorageHelper.getIntervalSessions()) }
    var selectedSession by remember { mutableStateOf(sessions.firstOrNull()) }
    
    // プレビュー表示する画像コマのインデックス
    var currentImageIndex by remember { mutableIntStateOf(0) }
    var intervalFiles by remember { mutableStateOf(StorageHelper.getIntervalImageFiles(selectedSession)) }

    // 選択セッションが変更されたらファイルリストを再読み込み
    LaunchedEffect(selectedSession) {
        intervalFiles = StorageHelper.getIntervalImageFiles(selectedSession)
        currentImageIndex = 0
    }

    var sessionDropdownExpanded by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableStateOf(ExportMode.FULLHD_TIMELAPSE) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    // クロップ位置（正規化オフセット: 0.0f = 左端/上端, 1.0f = 右端/下端）
    var cropOffsetX by remember { mutableFloatStateOf(0.5f) }
    var cropOffsetY by remember { mutableFloatStateOf(0.5f) }

    var isGenerating by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    var statusMessage by remember {
        mutableStateOf(
            if (intervalFiles.isNotEmpty()) "撮影済み静止画: ${intervalFiles.size}コマ (${selectedSession?.name ?: ""})\n画像ドラッグ: クロップ範囲移動"
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

    // 保存処理の実行
    fun executeExport() {
        if (intervalFiles.isEmpty()) return
        isGenerating = true
        progress = 0f
        statusMessage = "${selectedMode.label} を生成中..."

        coroutineScope.launch(Dispatchers.IO) {
            val darkFrameFile = StorageHelper.getDarkFrameFile()
            val success: Boolean
            val outputFile: File

            if (selectedMode.isTimelapse) {
                outputFile = StorageHelper.getTimelapseVideoFile()
                success = VideoEncoderHelper.createTimelapseVideo(
                    imageFiles = intervalFiles,
                    outputFile = outputFile,
                    darkFrameFile = darkFrameFile.exists().let { if (it) darkFrameFile else null },
                    resolutionLabel = selectedMode.resolutionLabel,
                    frameRate = 30,
                    cropOffsetX = cropOffsetX,
                    cropOffsetY = cropOffsetY,
                    onProgress = { p -> progress = p }
                )
            } else {
                outputFile = StorageHelper.getCompositeImageFile()
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

    // 削除確認ダイアログ
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("🗑️ 撮影済み画像の削除", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "誤操作を防ぐため、削除範囲を選択してください。",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            showDeleteDialog = false
                            coroutineScope.launch(Dispatchers.IO) {
                                val success = StorageHelper.clearIntervalImages(selectedSession)
                                val updatedSessions = StorageHelper.getIntervalSessions()
                                val updatedFiles = StorageHelper.getIntervalImageFiles(updatedSessions.firstOrNull())
                                withContext(Dispatchers.Main) {
                                    sessions = updatedSessions
                                    selectedSession = updatedSessions.firstOrNull()
                                    intervalFiles = updatedFiles
                                    currentImageIndex = 0
                                    statusMessage = if (success) "選択中の回の画像を削除しました。" else "一部の削除に失敗しました。"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("現在の回 (${selectedSession?.name ?: ""}) のみ削除", color = Color.White, fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            showDeleteDialog = false
                            coroutineScope.launch(Dispatchers.IO) {
                                val success = StorageHelper.clearAllIntervalImages()
                                val updatedSessions = StorageHelper.getIntervalSessions()
                                val updatedFiles = StorageHelper.getIntervalImageFiles(updatedSessions.firstOrNull())
                                withContext(Dispatchers.Main) {
                                    sessions = updatedSessions
                                    selectedSession = updatedSessions.firstOrNull()
                                    intervalFiles = updatedFiles
                                    currentImageIndex = 0
                                    statusMessage = if (success) "すべての回のインターバル画像を削除しました。" else "一部の削除に失敗しました。"
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("すべての回を削除", color = Color.White, fontSize = 13.sp)
                    }

                    OutlinedButton(
                        onClick = { showDeleteDialog = false },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, Color.Gray),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Text("キャンセル", color = Color.White, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {},
            containerColor = Color(0xFF2C2C2C)
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
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

            // インターバル撮影回（セッション）選択プルダウン
            if (sessions.isNotEmpty()) {
                ExposedDropdownMenuBox(
                    expanded = sessionDropdownExpanded,
                    onExpandedChange = { if (!isGenerating) sessionDropdownExpanded = !sessionDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = Color(0xFF333333),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(text = "撮影回（セッション）", color = Color.LightGray, fontSize = 10.sp)
                                Spacer(modifier = Modifier.height(1.dp))
                                Text(
                                    text = selectedSession?.name ?: "選択なし",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sessionDropdownExpanded)
                        }
                    }

                    ExposedDropdownMenu(
                        expanded = sessionDropdownExpanded,
                        onDismissRequest = { sessionDropdownExpanded = false },
                        modifier = Modifier.background(Color(0xFF1E1E1E))
                    ) {
                        sessions.forEach { session ->
                            val fileCount = StorageHelper.getIntervalImageFiles(session).size
                            val isLatest = session == sessions.firstOrNull()
                            val label = if (isLatest) "${session.name} (最新 - $fileCount コマ)" else "${session.name} ($fileCount コマ)"
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        color = if (session == selectedSession) Color(0xFF1E88E5) else Color.White,
                                        fontWeight = if (session == selectedSession) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    selectedSession = session
                                    sessionDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // 出力モード選択（ドロップダウンメニュー）
            ExposedDropdownMenuBox(
                expanded = dropdownExpanded,
                onExpandedChange = { if (!isGenerating) dropdownExpanded = !dropdownExpanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    color = Color(0xFF1E88E5).copy(alpha = 0.3f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = "出力モード", color = Color.LightGray, fontSize = 10.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = selectedMode.label,
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = dropdownExpanded)
                    }
                }

                ExposedDropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                    modifier = Modifier.background(Color(0xFF1E1E1E))
                ) {
                    ExportMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = mode.label,
                                    color = if (mode == selectedMode) Color(0xFF1E88E5) else Color.White,
                                    fontWeight = if (mode == selectedMode) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                selectedMode = mode
                                dropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // コマ切り替えコントロールバー
            if (intervalFiles.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = {
                            currentImageIndex = if (currentImageIndex > 0) currentImageIndex - 1 else intervalFiles.size - 1
                        },
                        enabled = !isGenerating,
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        border = BorderStroke(1.dp, Color.Gray),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("◀ 前コマ", fontSize = 11.sp, color = Color.White)
                    }

                    Text(
                        text = "📷 ${currentImageIndex + 1} / ${intervalFiles.size}コマ",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedButton(
                        onClick = {
                            currentImageIndex = if (currentImageIndex < intervalFiles.size - 1) currentImageIndex + 1 else 0
                        },
                        enabled = !isGenerating,
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        border = BorderStroke(1.dp, Color.Gray),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("次コマ ▶", fontSize = 11.sp, color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // 2. プレビュー（コマ画像 ＋ クロップ枠線・ドラッグ移動機能）
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.DarkGray, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                val boxWidth = constraints.maxWidth.toFloat()
                val boxHeight = constraints.maxHeight.toFloat()

                if (currentBitmap != null) {
                    Image(
                        bitmap = currentBitmap.asImageBitmap(),
                        contentDescription = "プレビュー画像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    val imgWidth = currentBitmap.width.toFloat()
                    val imgHeight = currentBitmap.height.toFloat()

                    if (imgWidth > 0f && imgHeight > 0f && boxWidth > 0f && boxHeight > 0f) {
                        val imgAspect = imgWidth / imgHeight
                        val boxAspect = boxWidth / boxHeight

                        val (displayedImgWidth, displayedImgHeight) = if (boxAspect > imgAspect) {
                            (boxHeight * imgAspect) to boxHeight
                        } else {
                            boxWidth to (boxWidth / imgAspect)
                        }

                        val scale = when (selectedMode.resolutionLabel) {
                            "HD (720p)" -> 0.60f
                            "Full HD (1080p)" -> 0.75f
                            "4K" -> 0.90f
                            else -> 1.0f
                        }

                        val guideHeight = displayedImgHeight * scale
                        val guideWidth = (guideHeight * 9f) / 16f

                        val movableWidth = (displayedImgWidth - guideWidth).coerceAtLeast(0f)
                        val movableHeight = (displayedImgHeight - guideHeight).coerceAtLeast(0f)

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(selectedMode, isGenerating, movableWidth, movableHeight) {
                                    if (selectedMode.isTimelapse && !isGenerating) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            if (movableWidth > 0f) {
                                                cropOffsetX = (cropOffsetX + dragAmount.x / movableWidth).coerceIn(0f, 1f)
                                            }
                                            if (movableHeight > 0f) {
                                                cropOffsetY = (cropOffsetY + dragAmount.y / movableHeight).coerceIn(0f, 1f)
                                            }
                                        }
                                    }
                                }
                        )

                        // クロップ枠ガイドオーバーレイ（最高画質時は非表示）
                        SaveCropGuideOverlay(
                            resolutionLabel = selectedMode.resolutionLabel,
                            cropOffsetX = cropOffsetX,
                            cropOffsetY = cropOffsetY,
                            bitmapWidth = currentBitmap.width,
                            bitmapHeight = currentBitmap.height
                        )
                    }
                } else {
                    Text(
                        text = "プレビュー画像がありません\n(撮影回を選択してください)",
                        color = Color.LightGray,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
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

            Spacer(modifier = Modifier.height(8.dp))

            // クリアボタン（押下時に削除確認ダイアログを表示）
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                enabled = !isGenerating && sessions.isNotEmpty(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFFFF5252)
                ),
                border = BorderStroke(1.dp, Color(0xFFFF5252)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "🗑️ 撮影済み画像をクリア（削除）",
                    color = Color(0xFFFF5252),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 選択された画質に応じたクロップ枠線および枠外マスクをプレビュー上に表示するオーバーレイ
 */
@Composable
private fun SaveCropGuideOverlay(
    resolutionLabel: String,
    cropOffsetX: Float,
    cropOffsetY: Float,
    bitmapWidth: Int,
    bitmapHeight: Int
) {
    if (resolutionLabel == "最高画質") return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        if (width <= 0f || height <= 0f || bitmapWidth <= 0 || bitmapHeight <= 0) return@BoxWithConstraints

        val imgAspect = bitmapWidth.toFloat() / bitmapHeight.toFloat()
        val boxAspect = width / height

        val (displayedImgWidth, displayedImgHeight) = if (boxAspect > imgAspect) {
            (height * imgAspect) to height
        } else {
            width to (width / imgAspect)
        }

        val imgLeft = (width - displayedImgWidth) / 2f
        val imgTop = (height - displayedImgHeight) / 2f

        val scale = when (resolutionLabel) {
            "HD (720p)" -> 0.60f
            "Full HD (1080p)" -> 0.75f
            "4K" -> 0.90f
            else -> 0.75f
        }

        val borderColor = when (resolutionLabel) {
            "HD (720p)" -> Color(0xFFFF5252)
            "Full HD (1080p)" -> Color(0xFFFFEB3B)
            "4K" -> Color(0xFF00B0FF)
            else -> Color.Transparent
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val guideHeight = displayedImgHeight * scale
            val guideWidth = (guideHeight * 9f) / 16f

            val movableWidth = (displayedImgWidth - guideWidth).coerceAtLeast(0f)
            val movableHeight = (displayedImgHeight - guideHeight).coerceAtLeast(0f)

            val guideLeft = imgLeft + movableWidth * cropOffsetX.coerceIn(0f, 1f)
            val guideTop = imgTop + movableHeight * cropOffsetY.coerceIn(0f, 1f)

            val maskColor = Color.Black.copy(alpha = 0.5f)

            // 1. クロップ枠外領域のマスク描画（画像表示枠内）
            // 上マスク
            if (guideTop > imgTop) {
                drawRect(
                    color = maskColor,
                    topLeft = Offset(imgLeft, imgTop),
                    size = Size(displayedImgWidth, guideTop - imgTop)
                )
            }
            // 下マスク
            val guideBottom = guideTop + guideHeight
            val imgBottom = imgTop + displayedImgHeight
            if (imgBottom > guideBottom) {
                drawRect(
                    color = maskColor,
                    topLeft = Offset(imgLeft, guideBottom),
                    size = Size(displayedImgWidth, imgBottom - guideBottom)
                )
            }
            // 左マスク
            if (guideLeft > imgLeft) {
                drawRect(
                    color = maskColor,
                    topLeft = Offset(imgLeft, guideTop),
                    size = Size(guideLeft - imgLeft, guideHeight)
                )
            }
            // 右マスク
            val guideRight = guideLeft + guideWidth
            val imgRight = imgLeft + displayedImgWidth
            if (imgRight > guideRight) {
                drawRect(
                    color = maskColor,
                    topLeft = Offset(guideRight, guideTop),
                    size = Size(imgRight - guideRight, guideHeight)
                )
            }

            // 2. クロップ枠線の描画
            drawRect(
                color = borderColor,
                topLeft = Offset(guideLeft, guideTop),
                size = Size(guideWidth, guideHeight),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                )
            )
        }
    }
}
