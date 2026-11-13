package jp.hisanet.astargazer.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import jp.hisanet.astargazer.util.ExportManager
import jp.hisanet.astargazer.util.ExportState
import jp.hisanet.astargazer.util.FileViewerHelper
import jp.hisanet.astargazer.util.ImageCompositor
import jp.hisanet.astargazer.util.StorageHelper
import jp.hisanet.astargazer.util.VideoEncoderHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

enum class AutoPageDirection { NONE, FORWARD, BACKWARD }

/**
 * 出力モード
 * 1. HDタイムラプス
 * 2. Full HDタイムラプス
 * 3. 4Kタイムラプス
 * 4. 比較明合成
 */
enum class ExportMode(
    val label: String,
    val isTimelapse: Boolean,
    val resolutionLabel: String
) {
    HD_TIMELAPSE("HDタイムラプス", true, "HD (720p)"),
    FULLHD_TIMELAPSE("Full HDタイムラプス", true, "Full HD (1080p)"),
    UHD_4K_TIMELAPSE("4Kタイムラプス", true, "4K"),
    MAX_COMPOSITE("比較明合成", false, "比較明合成");
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaveTabContent(
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    selectedTab: MainMenuTab = MainMenuTab.SAVE,
    onTabSelected: (MainMenuTab) -> Unit = {},
    onNavigateToDarkFrameShooting: (exposureSeconds: Double, iso: Int) -> Unit = { _, _ -> },
    isSetupCompleted: Boolean = false,
    isIntervalCompleted: Boolean = false,
    hasExistingIntervalFiles: Boolean = false,
    isIntervalActive: Boolean = false,
    isCamera2BurstActive: Boolean = false,
    onCancelSetup: () -> Unit = {}
) {
    DisposableEffect(Unit) {
        Log.d("OrientationDebug", "SaveTabContent MOUNTED")
        onDispose {
            Log.d("OrientationDebug", "SaveTabContent UNMOUNTED")
        }
    }

    val currentConfig = LocalConfiguration.current
    LaunchedEffect(currentConfig.orientation) {
        Log.d("OrientationDebug", "SaveTabContent orientation changed: orientation=${currentConfig.orientation}")
    }

    var sessions by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedSessionPath by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedSession = remember(sessions, selectedSessionPath) {
        sessions.find { it.absolutePath == selectedSessionPath } ?: sessions.firstOrNull()
    }
    
    // プレビュー表示する画像コマのインデックス
    var currentImageIndex by rememberSaveable { mutableIntStateOf(0) }
    var intervalFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    var previousSessionPath by remember { mutableStateOf<String?>(null) }
    var autoPageDirection by remember { mutableStateOf(AutoPageDirection.NONE) }
    var autoPageStep by remember { mutableIntStateOf(1) }

    fun getSkipStep(holdDurationMs: Long): Int {
        return when {
            holdDurationMs >= 5000L -> 32
            holdDurationMs >= 4000L -> 16
            holdDurationMs >= 3000L -> 8
            holdDurationMs >= 2000L -> 4
            holdDurationMs >= 1000L -> 2
            else -> 1
        }
    }

    // 自動ページ送り (スライドショー) タイマー
    LaunchedEffect(autoPageDirection, autoPageStep, intervalFiles.size) {
        if (autoPageDirection != AutoPageDirection.NONE && intervalFiles.isNotEmpty()) {
            while (isActive && autoPageDirection != AutoPageDirection.NONE) {
                delay(400.milliseconds)
                if (autoPageDirection == AutoPageDirection.FORWARD) {
                    currentImageIndex = (currentImageIndex + autoPageStep) % intervalFiles.size
                } else if (autoPageDirection == AutoPageDirection.BACKWARD) {
                    currentImageIndex = (currentImageIndex - autoPageStep).let { idx ->
                        if (idx < 0) (intervalFiles.size + (idx % intervalFiles.size)) % intervalFiles.size else idx
                    }
                }
            }
        }
    }

    // 選択セッションまたはタブ切り替え時にファイルリスト・セッション一覧を再読み込み (Dispatchers.IO)
    LaunchedEffect(selectedSessionPath, selectedTab) {
        autoPageDirection = AutoPageDirection.NONE
        val targetPath = selectedSessionPath
        withContext(Dispatchers.IO) {
            val freshSessions = StorageHelper.getIntervalSessions(context)
            val currentSess = freshSessions.find { it.absolutePath == targetPath } ?: freshSessions.firstOrNull()
            val files = StorageHelper.getIntervalImageFiles(currentSess, context)

            withContext(Dispatchers.Main) {
                sessions = freshSessions
                intervalFiles = files
                val effectivePath = currentSess?.absolutePath
                if (effectivePath != previousSessionPath) {
                    previousSessionPath = effectivePath
                    currentImageIndex = 0
                }
                Log.i("SaveTabContent", "Refreshed SaveTabContent: sessions=${freshSessions.size}, currentSession=${currentSess?.name}, intervalFiles=${files.size}")
            }
        }
    }

    val isSdCardAvailable = remember(context) { StorageHelper.getSdCardRoot(context) != null }

    var sessionDropdownExpanded by remember { mutableStateOf(false) }
    var selectedMode by rememberSaveable { mutableStateOf(ExportMode.UHD_4K_TIMELAPSE) }
    var dropdownExpanded by remember { mutableStateOf(false) }

    // フレームレート選択 (デフォルト: 24 fps)
    var selectedFrameRate by rememberSaveable { mutableIntStateOf(24) }
    var frameRateDropdownExpanded by remember { mutableStateOf(false) }

    // クロップ位置（正規化オフセット: 0.0f = 左端/上端, 1.0f = 右端/下端）
    var cropOffsetX by rememberSaveable { mutableFloatStateOf(0.5f) }
    var cropOffsetY by rememberSaveable { mutableFloatStateOf(0.5f) }

    val exportState by ExportManager.state.collectAsState()
    val isGenerating = exportState is ExportState.Generating
    val progress = (exportState as? ExportState.Generating)?.progress ?: 0f
    var showMissingDarkFrameDialog by remember { mutableStateOf(false) }
    var missingParams by remember { mutableStateOf<jp.hisanet.astargazer.util.SessionCameraParams?>(null) }

    val defaultStatusMessage = remember(intervalFiles, selectedSession) {
        if (intervalFiles.isNotEmpty()) "元画像: ${intervalFiles.size}コマ (${selectedSession?.name ?: ""})"
        else "元画像がありません。"
    }

    val statusMessage = when (val state = exportState) {
        is ExportState.Generating -> state.message
        is ExportState.Completed -> "生成完了: ${state.outputFile.name}"
        is ExportState.Error -> state.message
        is ExportState.Idle -> defaultStatusMessage
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

        // 選択中セッションの撮影条件 (EXIF) から必要ダークフレームを取得
        val sessionParams = StorageHelper.getSessionCameraParams(selectedSession, context)
        if (sessionParams == null) {
            return
        }

        val darkFrameFile = StorageHelper.getDarkFrameFile(sessionParams.exposureSeconds, sessionParams.iso, context)
        if (!darkFrameFile.exists() || darkFrameFile.length() == 0L) {
            missingParams = sessionParams
            showMissingDarkFrameDialog = true
            return
        }

        if (selectedMode.isTimelapse) {
            val outputFile = StorageHelper.getTimelapseVideoFile()
            ExportManager.startTimelapseExport(
                context = context,
                imageFiles = intervalFiles,
                outputFile = outputFile,
                darkFrameFile = darkFrameFile,
                resolutionLabel = selectedMode.resolutionLabel,
                frameRate = selectedFrameRate,
                cropOffsetX = cropOffsetX,
                cropOffsetY = cropOffsetY
            )
        } else {
            val outputFile = StorageHelper.getCompositeImageFile()
            ExportManager.startCompositeExport(
                context = context,
                imageFiles = intervalFiles,
                outputFile = outputFile,
                darkFrameFile = darkFrameFile
            )
        }
    }

    // 現在プレビュー中のコマを Google Files / ギャラリーで直接表示（コピーなし）
    fun executeOpenCurrentFrame() {
        if (intervalFiles.isEmpty() || currentImageIndex !in intervalFiles.indices) return
        val currentFile = intervalFiles[currentImageIndex]
        FileViewerHelper.openInGoogleFilesOrViewer(context, currentFile)
    }

    // ダークフレーム未検出ダイアログ
    if (showMissingDarkFrameDialog && missingParams != null) {
        val params = missingParams!!
        AlertDialog(
            onDismissRequest = { showMissingDarkFrameDialog = false },
            title = { Text("⚠️ ダークフレーム撮影が必要です", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "このセッションに対応するダークフレームが見つかりません。\n\n【必要条件】\n露出：${params.exposureSeconds}秒\nISO：${params.iso}\n\nダークフレームを撮影してください。",
                    color = Color.LightGray,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMissingDarkFrameDialog = false
                        onNavigateToDarkFrameShooting(params.exposureSeconds, params.iso)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
                ) {
                    Text("ダークフレーム撮影へ", color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showMissingDarkFrameDialog = false },
                    border = BorderStroke(1.dp, Color.Gray),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("キャンセル", color = Color.White)
                }
            },
            containerColor = Color(0xFF2C2C2C)
        )
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val controlsContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                            val isLatest = session == sessions.firstOrNull()
                            val isOnSd = isSdCardAvailable && StorageHelper.getSdCardRoot(context)?.let { session.absolutePath.startsWith(it.absolutePath) } == true
                            val locTag = if (isOnSd) "[SDカード]" else "[内部]"
                            val label = if (isLatest) "${session.name} (最新) $locTag" else "${session.name} $locTag"
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        color = if (session == selectedSession) Color(0xFF1E88E5) else Color.White,
                                        fontWeight = if (session == selectedSession) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    selectedSessionPath = session.absolutePath
                                    sessionDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // 出力モード選択
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

            // フレームレート選択
            if (selectedMode.isTimelapse) {
                ExposedDropdownMenuBox(
                    expanded = frameRateDropdownExpanded,
                    onExpandedChange = { if (!isGenerating) frameRateDropdownExpanded = !frameRateDropdownExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        color = Color(0xFF1E88E5).copy(alpha = 0.2f),
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
                                Text(text = "フレームレート", color = Color.LightGray, fontSize = 10.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when (selectedFrameRate) {
                                        15 -> "15 fps (スロー)"
                                        24 -> "24 fps (標準)"
                                        30 -> "30 fps (高速)"
                                        else -> "$selectedFrameRate fps"
                                    },
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = frameRateDropdownExpanded)
                        }
                    }

                    ExposedDropdownMenu(
                        expanded = frameRateDropdownExpanded,
                        onDismissRequest = { frameRateDropdownExpanded = false },
                        modifier = Modifier.background(Color(0xFF1E1E1E))
                    ) {
                        listOf(
                            24 to "24 fps (標準)",
                            15 to "15 fps (スロー)",
                            30 to "30 fps (高速)"
                        ).forEach { (fps, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = label,
                                        color = if (fps == selectedFrameRate) Color(0xFF1E88E5) else Color.White,
                                        fontWeight = if (fps == selectedFrameRate) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    selectedFrameRate = fps
                                    frameRateDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // コマ切り替えコントロールバー (長押し時間に応じたコマ飛ばし自動移動、タップで1コマ移動または停止)
            if (intervalFiles.isNotEmpty()) {
                val skipLabel = when (autoPageStep) {
                    32 -> "31コマ飛ばし"
                    16 -> "15コマ飛ばし"
                    8 -> "7コマ飛ばし"
                    4 -> "3コマ飛ばし"
                    2 -> "1コマ飛ばし"
                    else -> "自動"
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // [◀ 前] ボタン
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(
                            1.dp,
                            if (autoPageDirection == AutoPageDirection.BACKWARD) Color(0xFF1E88E5) else Color.Gray
                        ),
                        color = if (autoPageDirection == AutoPageDirection.BACKWARD) Color(0xFF1E88E5).copy(alpha = 0.35f) else Color.Transparent,
                        modifier = Modifier
                            .height(34.dp)
                            .pointerInput(autoPageDirection, isGenerating, intervalFiles) {
                                if (!isGenerating && intervalFiles.isNotEmpty()) {
                                    detectTapGestures(
                                        onPress = {
                                            val pressTime = System.currentTimeMillis()
                                            val released = try {
                                                tryAwaitRelease()
                                            } catch (_: Exception) {
                                                false
                                            }
                                            val holdDuration = System.currentTimeMillis() - pressTime
                                            if (released) {
                                                if (autoPageDirection != AutoPageDirection.NONE) {
                                                    autoPageDirection = AutoPageDirection.NONE
                                                } else if (holdDuration >= 800L) {
                                                    autoPageStep = getSkipStep(holdDuration)
                                                    autoPageDirection = AutoPageDirection.BACKWARD
                                                } else {
                                                    currentImageIndex = if (currentImageIndex > 0) currentImageIndex - 1 else intervalFiles.size - 1
                                                }
                                            } else {
                                                if (autoPageDirection != AutoPageDirection.NONE) {
                                                    autoPageDirection = AutoPageDirection.NONE
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (autoPageDirection == AutoPageDirection.BACKWARD) "◀◀ $skipLabel" else "◀ 前",
                                fontSize = 11.sp,
                                color = Color.White,
                                fontWeight = if (autoPageDirection == AutoPageDirection.BACKWARD) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    Text(
                        text = when (autoPageDirection) {
                            AutoPageDirection.FORWARD -> "⏩ ${currentImageIndex + 1} / ${intervalFiles.size}コマ ($skipLabel)"
                            AutoPageDirection.BACKWARD -> "⏪ ${currentImageIndex + 1} / ${intervalFiles.size}コマ ($skipLabel)"
                            AutoPageDirection.NONE -> "📷 ${currentImageIndex + 1} / ${intervalFiles.size}コマ"
                        },
                        color = if (autoPageDirection != AutoPageDirection.NONE) Color(0xFF64B5F6) else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    // [次 ▶] ボタン
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(
                            1.dp,
                            if (autoPageDirection == AutoPageDirection.FORWARD) Color(0xFF1E88E5) else Color.Gray
                        ),
                        color = if (autoPageDirection == AutoPageDirection.FORWARD) Color(0xFF1E88E5).copy(alpha = 0.35f) else Color.Transparent,
                        modifier = Modifier
                            .height(34.dp)
                            .pointerInput(autoPageDirection, isGenerating, intervalFiles) {
                                if (!isGenerating && intervalFiles.isNotEmpty()) {
                                    detectTapGestures(
                                        onPress = {
                                            val pressTime = System.currentTimeMillis()
                                            val released = try {
                                                tryAwaitRelease()
                                            } catch (_: Exception) {
                                                false
                                            }
                                            val holdDuration = System.currentTimeMillis() - pressTime
                                            if (released) {
                                                if (autoPageDirection != AutoPageDirection.NONE) {
                                                    autoPageDirection = AutoPageDirection.NONE
                                                } else if (holdDuration >= 800L) {
                                                    autoPageStep = getSkipStep(holdDuration)
                                                    autoPageDirection = AutoPageDirection.FORWARD
                                                } else {
                                                    currentImageIndex = if (currentImageIndex < intervalFiles.size - 1) currentImageIndex + 1 else 0
                                                }
                                            } else {
                                                if (autoPageDirection != AutoPageDirection.NONE) {
                                                    autoPageDirection = AutoPageDirection.NONE
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (autoPageDirection == AutoPageDirection.FORWARD) "$skipLabel ▶▶" else "次 ▶",
                                fontSize = 11.sp,
                                color = Color.White,
                                fontWeight = if (autoPageDirection == AutoPageDirection.FORWARD) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // ステータスカード
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = statusMessage,
                        color = Color.White,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )

                    if (isGenerating) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF1E88E5),
                            trackColor = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${(progress * 100).toInt()}% 完了",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // 仕上げボタン
            Button(
                onClick = { executeExport() },
                enabled = !isGenerating && intervalFiles.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selectedMode.isTimelapse) Color(0xFF1E88E5) else Color(0xFF43A047),
                    disabledContainerColor = Color.DarkGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (selectedMode.isTimelapse) "🎬 ${selectedMode.label} を出力" else "🌌 ${selectedMode.label} を出力",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 現在のコマを開くボタン（Google Files / ギャラリーで直接表示・コピーなし）
            Button(
                onClick = { executeOpenCurrentFrame() },
                enabled = !isGenerating && selectedSession != null && intervalFiles.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0288D1)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "👁️ このコマを開く",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }


        }
    }

    val previewBoxContent: @Composable () -> Unit = {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            val boxWidth = constraints.maxWidth.toFloat()
            val boxHeight = constraints.maxHeight.toFloat()

            val displayBitmap = remember(currentBitmap, isLandscape) {
                if (currentBitmap != null) {
                    val matrix = Matrix().apply { postRotate(-90f) }
                    Bitmap.createBitmap(
                        currentBitmap,
                        0, 0,
                        currentBitmap.width, currentBitmap.height,
                        matrix,
                        true
                    )
                } else {
                    null
                }
            }

            if (displayBitmap != null && currentBitmap != null) {
                Image(
                    bitmap = displayBitmap.asImageBitmap(),
                    contentDescription = "元画像",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                val rotWidth = currentBitmap.height.toFloat()
                val rotHeight = currentBitmap.width.toFloat()

                if (rotWidth > 0f && rotHeight > 0f && boxWidth > 0f && boxHeight > 0f) {
                    val imgAspect = rotWidth / rotHeight
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

                    val guideWidth = displayedImgWidth * scale
                    val guideHeight = guideWidth * 9f / 16f

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
                                            cropOffsetY = (cropOffsetY + dragAmount.x / movableWidth).coerceIn(0f, 1f)
                                        }
                                        if (movableHeight > 0f) {
                                            cropOffsetX = (cropOffsetX - dragAmount.y / movableHeight).coerceIn(0f, 1f)
                                        }
                                    }
                                }
                            }
                    )

                    if (selectedMode.isTimelapse) {
                        SaveCropGuideOverlay(
                            resolutionLabel = selectedMode.resolutionLabel,
                            cropOffsetX = cropOffsetX,
                            cropOffsetY = cropOffsetY,
                            bitmapWidth = currentBitmap.width,
                            bitmapHeight = currentBitmap.height
                        )
                    }
                }
            } else {
                Text(
                    text = "元画像がありません\n(撮影回を選択してください)",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    previewBoxContent()
                }

                Column(
                    modifier = Modifier
                        .width(320.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF121212), RoundedCornerShape(12.dp))
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ScreenSwitchDropdown(
                        selectedTab = selectedTab,
                        onTabSelected = onTabSelected,
                        isCamera2BurstActive = isCamera2BurstActive,
                        isIntervalActive = isIntervalActive,
                        isSetupCompleted = isSetupCompleted,
                        isIntervalCompleted = isIntervalCompleted,
                        hasExistingIntervalFiles = hasExistingIntervalFiles,
                        onCancelSetup = onCancelSetup
                    )
                    controlsContent()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. 処理選択プルダウンを一番上に配置（撮影前設定・インターバル撮影と位置を統一）
                ScreenSwitchDropdown(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    isCamera2BurstActive = isCamera2BurstActive,
                    isIntervalActive = isIntervalActive,
                    isSetupCompleted = isSetupCompleted,
                    isIntervalCompleted = isIntervalCompleted,
                    hasExistingIntervalFiles = hasExistingIntervalFiles,
                    onCancelSetup = onCancelSetup
                )

                // 2. 画像の表示エリア
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                ) {
                    previewBoxContent()
                }

                // 3. その他のコントロール
                controlsContent()
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
    if (resolutionLabel == "最高画質" || resolutionLabel == "比較明合成") return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        if (width <= 0f || height <= 0f || bitmapWidth <= 0 || bitmapHeight <= 0) return@BoxWithConstraints

        val rotWidth = bitmapHeight.toFloat()
        val rotHeight = bitmapWidth.toFloat()

        val imgAspect = rotWidth / rotHeight
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
            val guideWidth = displayedImgWidth * scale
            val guideHeight = guideWidth * 9f / 16f

            val movableWidth = (displayedImgWidth - guideWidth).coerceAtLeast(0f)
            val movableHeight = (displayedImgHeight - guideHeight).coerceAtLeast(0f)

            val guideLeft = imgLeft + movableWidth * cropOffsetY.coerceIn(0f, 1f)
            val guideTop = imgTop + movableHeight * (1f - cropOffsetX.coerceIn(0f, 1f))

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
