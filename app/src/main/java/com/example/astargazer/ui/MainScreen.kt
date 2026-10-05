package com.example.astargazer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.astargazer.ui.camera.CameraControlManager
import com.example.astargazer.ui.camera.CameraPreview
import com.example.astargazer.util.Camera2BurstSession
import com.example.astargazer.util.FileViewerHelper
import com.example.astargazer.util.ImageCompositor
import com.example.astargazer.util.ImageContrastAnalyzer
import com.example.astargazer.util.LocationHelper
import com.example.astargazer.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 露出時間の選択肢（秒数: 0.25秒, 0.5秒, 1秒, 2秒, 4秒, 8秒, 15秒, 30秒）
 */
val EXPOSURE_TIMES_SECONDS = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0)

/**
 * 露出時間のフォーマット
 */
val formatExposureSeconds: (Double) -> String = { seconds ->
    if (seconds % 1.0 == 0.0) {
        "${seconds.toInt()}秒"
    } else {
        "${seconds}秒"
    }
}

/**
 * メニュータブ（「保存」を「仕上げ」に変更）
 */
enum class MainMenuTab(val label: String) {
    SETUP("撮影前設定"),
    INTERVAL("インターバル撮影"),
    SAVE("仕上げ")
}

/**
 * 撮影前設定ワークフロー
 */
enum class WorkflowStep {
    DARK_FRAME_NOTICE,              // 1a. ダークフレーム撮影案内（レンズを覆う）
    DARK_FRAME_SHOOTING,            // 1b. ダークフレーム撮影実行
    POLARIS_ALIGNMENT_NOTICE,       // 2. 北極星合わせ案内
    POLARIS_TEST_SHOOTING_ADJUST,   // 3. 試写と自動調整（ノイズ減算適用）
    TEST_RESULT_DISPLAY,            // 4. 試写結果表示
    SETUP_COMPLETED                 // 5. 撮影前設定完了
}

@Composable
fun MainScreen() {
    val context = LocalContext.current

    // パーミッション状態 (カメラ & 位置情報)
    var hasPermissions by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        hasPermissions = cameraGranted && locationGranted
    }

    LaunchedEffect(Unit) {
        if (!hasPermissions) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    if (!hasPermissions) {
        PermissionRequestContent {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    } else {
        MainAppContent()
    }
}

@Composable
private fun PermissionRequestContent(onRequestPermission: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "カメラおよび位置情報の権限が必要です",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "星空の試写、インターバル撮影、および写真へのGPS位置情報付与を行うため、権限が必要です。",
                color = Color.LightGray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5))
            ) {
                Text(text = "権限を許可する", color = Color.White)
            }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainAppContent() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(MainMenuTab.SETUP) }

    var isSetupCompleted by remember { mutableStateOf(false) }
    var isIntervalCompleted by remember { mutableStateOf(false) }

    // 前回の撮影画像ファイルがストレージに残っているかどうか
    val hasExistingIntervalFiles = remember { StorageHelper.getIntervalImageFiles().isNotEmpty() }

    var capturedTestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    var isIntervalShootingActive by remember { mutableStateOf(false) }
    var isCamera2BurstActive by remember { mutableStateOf(false) }
    var camera2BurstStatus by remember { mutableStateOf("") }
    var shotCount by remember { mutableIntStateOf(0) }
    var remainingShots by remember { mutableIntStateOf(0) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }

    var currentStep by remember { mutableStateOf(WorkflowStep.DARK_FRAME_NOTICE) }

    var selectedExposureSeconds by remember { mutableDoubleStateOf(4.0) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    var statusMessage by remember {
        mutableStateOf("露出時間を選択し、レンズを覆ってシャッターを押してください（ダーク撮影）。")
    }

    // インターバル撮影中の経過時間タイマー
    LaunchedEffect(isIntervalShootingActive) {
        if (isIntervalShootingActive) {
            elapsedSeconds = 0
            while (isIntervalShootingActive) {
                delay(1.seconds)
                elapsedSeconds++
            }
        }
    }

    // 露出時間が変更されたとき、すでに同じ秒数のダークフレームがあれば案内メッセージに反映
    LaunchedEffect(selectedExposureSeconds) {
        statusMessage = if (StorageHelper.hasValidDarkFrame(selectedExposureSeconds)) {
            "露出時間 ${formatExposureSeconds(selectedExposureSeconds)}: 既存のダークフレームが利用可能です。そのままシャッターを押して北極星合わせへ進むか、露出時間を再選択できます。"
        } else {
            "露出時間 ${formatExposureSeconds(selectedExposureSeconds)}: レンズを覆ってシャッターを押してください（ダーク撮影）。"
        }
    }

    // 設定キャンセル・リセット
    fun cancelSetup() {
        isProcessing = false
        isSetupCompleted = false
        isIntervalCompleted = false
        isIntervalShootingActive = false
        elapsedSeconds = 0
        currentStep = WorkflowStep.DARK_FRAME_NOTICE
        capturedTestBitmap = null

        statusMessage = if (StorageHelper.hasValidDarkFrame(selectedExposureSeconds)) {
            "設定をリセットしました。露出時間を再選択するか、シャッターを押して進んでください（既存ダーク流用可）。"
        } else {
            "設定をリセットしました。露出時間を再選択し、レンズを覆ってシャッターを押してください。"
        }
        selectedTab = MainMenuTab.SETUP
    }

    // インターバル撮影ループ
    fun startIntervalShootingLoop() {
        val initialStorageBytes = StorageHelper.getAvailableStorageBytes()
        val minAllowedStorageBytes = (initialStorageBytes * 0.5f).toLong()
        var currentRemainingShots = StorageHelper.calculateRemainingShots(initialStorageBytes, minAllowedStorageBytes)

        val optimalIso = CameraControlManager.calculateOptimalIsoForExposure(selectedExposureSeconds)
        // インターバル開始時にGPS位置情報を1回だけ取得
        val baseLocation = LocationHelper.getLastKnownLocation(context)
        val exposureSeconds = selectedExposureSeconds

        isIntervalShootingActive = true
        isCamera2BurstActive = false
        camera2BurstStatus = "インターバル撮影を準備中..."
        shotCount = 0
        elapsedSeconds = 0
        remainingShots = currentRemainingShots

        Log.i("IntervalPerf", ">>> START INTERVAL LOOP (Exposure: ${exposureSeconds}s, ISO: $optimalIso) <<<")

        coroutineScope.launch {
            var session: Camera2BurstSession? = null
            try {
                isCamera2BurstActive = true
                camera2BurstStatus = "CameraXを解放してCamera2連続撮影を準備中..."
                delay(500.milliseconds)
                if (!isIntervalShootingActive) return@launch
                session = Camera2BurstSession.open(
                    context = context,
                    exposureSeconds = exposureSeconds,
                    iso = optimalIso,
                    location = baseLocation
                )

                camera2BurstStatus = "インターバル撮影中..."
                Log.i("IntervalPerf", "Camera2 session ready; starting repeating capture")
                session.startRepeatingCapture(
                    onFrameSaved = { frame ->
                        coroutineScope.launch(Dispatchers.Main) {
                            shotCount = maxOf(shotCount, frame.index)
                            currentRemainingShots = (currentRemainingShots - 1).coerceAtLeast(0)
                            remainingShots = currentRemainingShots
                            isIntervalCompleted = true
                        }
                    },
                    onFailure = { exception ->
                        coroutineScope.launch(Dispatchers.Main) {
                            isIntervalShootingActive = false
                            statusMessage = "インターバル撮影エラー: ${exception.message}"
                        }
                    }
                )

                while (isIntervalShootingActive) delay(50.milliseconds)
                val frames = session.stopRepeatingCapture()
                shotCount = maxOf(shotCount, frames.size)
                if (frames.isNotEmpty()) isIntervalCompleted = true
            } catch (exception: Exception) {
                isIntervalShootingActive = false
                statusMessage = "Camera2インターバル撮影エラー: ${exception.message}"
                Log.e("IntervalPerf", "Camera2 interval capture failed", exception)
            } finally {
                session?.close()
                isIntervalShootingActive = false
                isCamera2BurstActive = false
            }
            Log.i("IntervalPerf", ">>> STOP INTERVAL SHOOTING LOOP (Total captured: $shotCount frames) <<<")
        }
    }

    // シャッターボタン押下アクション（撮影中・処理中にももう一度押すとキャンセル）
    val onTriggerShutter: () -> Unit = {
        if (isProcessing) {
            cancelSetup()
        } else {
            when (selectedTab) {
                MainMenuTab.SETUP -> {
                    when (currentStep) {
                        WorkflowStep.DARK_FRAME_NOTICE -> {
                            if (StorageHelper.hasValidDarkFrame(selectedExposureSeconds)) {
                                currentStep = WorkflowStep.POLARIS_ALIGNMENT_NOTICE
                                statusMessage = "既存のダークフレームを流用します。北極星を合わせてシャッターを押してください。"
                            } else {
                                currentStep = WorkflowStep.DARK_FRAME_SHOOTING
                            }
                        }
                        WorkflowStep.DARK_FRAME_SHOOTING -> {}
                        WorkflowStep.POLARIS_ALIGNMENT_NOTICE -> currentStep = WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST
                        WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST -> {}
                        WorkflowStep.TEST_RESULT_DISPLAY -> currentStep = WorkflowStep.SETUP_COMPLETED
                        WorkflowStep.SETUP_COMPLETED -> currentStep = WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST
                    }
                }
                MainMenuTab.INTERVAL -> {
                    if (isIntervalShootingActive) {
                        isIntervalShootingActive = false
                        camera2BurstStatus = "停止要求を受け付けました。撮影中のコマを保存して停止します。"
                        if (shotCount > 0) isIntervalCompleted = true
                    } else {
                        startIntervalShootingLoop()
                    }
                }
                MainMenuTab.SAVE -> {}
            }
        }
    }

    // ダークフレーム撮影実行（GPS情報付与）
    fun runDarkFrameShooting() {
        val exposureSeconds = selectedExposureSeconds
        val optimalIso = CameraControlManager.calculateOptimalIsoForExposure(exposureSeconds)
        val exposureTimeNs = (exposureSeconds * 1_000_000_000.0).toLong()
        val currentLocation = LocationHelper.getLastKnownLocation(context)
        val darkFrameFile = StorageHelper.getDarkFrameFile()
        val tempJpegFile = StorageHelper.getDarkFrameCaptureTempFile(context)
        isProcessing = true
        isCamera2BurstActive = true
        statusMessage = "Camera2でダークフレーム撮影中 (${formatExposureSeconds(exposureSeconds)})..."

        coroutineScope.launch {
            var session: Camera2BurstSession? = null
            try {
                delay(500.milliseconds)
                session = Camera2BurstSession.open(
                    context = context,
                    exposureSeconds = exposureSeconds,
                    iso = optimalIso,
                    location = currentLocation
                )
                val frame = session.captureSingleFrame(tempJpegFile)
                session.close()
                session = null

                withContext(Dispatchers.IO) {
                    val bitmap = BitmapFactory.decodeFile(tempJpegFile.absolutePath)
                        ?: error("ダークフレームJPEGを読み込めませんでした")
                    try {
                        FileOutputStream(darkFrameFile).use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                "ダークフレームPNGを保存できませんでした"
                            }
                        }
                    } finally {
                        bitmap.recycle()
                    }

                    com.example.astargazer.util.ExifHelper.saveExifAttributes(
                        file = darkFrameFile,
                        iso = frame.iso ?: optimalIso,
                        exposureSeconds =
                            (frame.exposureTimeNs ?: exposureTimeNs) / 1_000_000_000.0,
                        location = currentLocation
                    )
                }
                tempJpegFile.delete()
                FileViewerHelper.scanFile(context, darkFrameFile)

                statusMessage = "ダークフレーム撮影完了。レンズカバーを外し、星空に向けてシャッターを押してください。"
                currentStep = WorkflowStep.POLARIS_ALIGNMENT_NOTICE
            } catch (exception: Exception) {
                Log.e("MainScreen", "Dark frame capture failed", exception)
                statusMessage = "ダークフレーム撮影エラー: ${exception.message}"
            } finally {
                session?.close()
                tempJpegFile.delete()
                isProcessing = false
                isCamera2BurstActive = false
            }
        }
    }

    // 試写と自動調整（1秒待機で手ブレ防止、最大画質 ＆ ノイズ減算適用 ＆ GPS情報付与）
    fun runTestShootingAndAutoAdjust() {
        val optimalIso = CameraControlManager.calculateOptimalIsoForExposure(selectedExposureSeconds)

        isProcessing = true
        isCamera2BurstActive = true
        statusMessage = "試写を実行中: 無限遠ピント & ISO($optimalIso)..."

        val currentLocation = LocationHelper.getLastKnownLocation(context)

        coroutineScope.launch {
            var session: Camera2BurstSession? = null
            try {
                delay(500.milliseconds)
                session = Camera2BurstSession.open(
                    context = context,
                    exposureSeconds = selectedExposureSeconds,
                    iso = optimalIso,
                    location = currentLocation
                )
                val frame = session.captureBatch(1).single()
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(frame.file.absolutePath)
                } ?: error("Camera2試写画像をBitmapに変換できませんでした")

                val darkFile = StorageHelper.getDarkFrameFile()
                val finalBitmap = if (darkFile.exists()) {
                    val darkBmp = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(darkFile.absolutePath)
                    }
                    if (darkBmp != null) {
                        val subtracted = withContext(Dispatchers.Default) {
                            ImageCompositor.subtractDarkFrame(bitmap, darkBmp)
                        }
                        bitmap.recycle()
                        darkBmp.recycle()
                        subtracted
                    } else {
                        bitmap
                    }
                } else {
                    bitmap
                }

                val testFile = StorageHelper.getTestShootingFile()
                withContext(Dispatchers.IO) {
                    FileOutputStream(testFile).use { out ->
                        finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    com.example.astargazer.util.ExifHelper.saveExifAttributes(
                        file = testFile,
                        iso = frame.iso ?: optimalIso,
                        exposureSeconds = (frame.exposureTimeNs ?: 0L) / 1_000_000_000.0,
                        location = currentLocation
                    )
                    FileViewerHelper.scanFile(context, testFile)
                }
                session.close()
                session = null

                val score = withContext(Dispatchers.Default) {
                    ImageContrastAnalyzer.calculateContrastScore(finalBitmap)
                }
                val scoreFormatted = String.format(Locale.JAPAN, "%.1f", score)
                capturedTestBitmap = finalBitmap
                statusMessage = "試写調整完了 (スコア: $scoreFormatted, ノイズ減算済)。設定完了！"
                currentStep = WorkflowStep.SETUP_COMPLETED
                isSetupCompleted = true
                selectedTab = MainMenuTab.INTERVAL
            } catch (exception: Exception) {
                Log.e("MainScreen", "Test capture failed", exception)
                statusMessage = "試写撮影エラー: ${exception.message}"
            } finally {
                session?.close()
                isProcessing = false
                isCamera2BurstActive = false
            }
        }
    }

    // ワークフロー監視
    LaunchedEffect(currentStep) {
        when (currentStep) {
            WorkflowStep.DARK_FRAME_SHOOTING -> runDarkFrameShooting()
            WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST -> runTestShootingAndAutoAdjust()
            else -> {}
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF121212),
                contentColor = Color.White
            ) {
                MainMenuTab.entries.forEach { tab ->
                    val enabled = !isCamera2BurstActive && !isIntervalShootingActive && when (tab) {
                        MainMenuTab.SETUP -> true
                        MainMenuTab.INTERVAL -> isSetupCompleted
                        MainMenuTab.SAVE -> isIntervalCompleted || hasExistingIntervalFiles
                    }

                    NavigationBarItem(
                        selected = selectedTab == tab,
                        enabled = enabled,
                        onClick = {
                            selectedTab = tab
                            if (tab == MainMenuTab.SETUP) {
                                cancelSetup()
                            }
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 12.sp,
                                fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        icon = {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        when {
                                            selectedTab == tab -> Color(0xFF1E88E5)
                                            enabled -> Color.LightGray
                                            else -> Color.DarkGray
                                        },
                                        CircleShape
                                    )
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF1E88E5),
                            selectedTextColor = Color(0xFF1E88E5),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray,
                            disabledIconColor = Color.DarkGray,
                            disabledTextColor = Color.DarkGray,
                            indicatorColor = Color(0xFF1E88E5).copy(alpha = 0.2f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            when (selectedTab) {
                MainMenuTab.SETUP -> {
                    SetupTabContent(
                        isCamera2BurstActive = isCamera2BurstActive,
                        currentStep = currentStep,
                        capturedTestBitmap = capturedTestBitmap,
                        selectedExposureSeconds = selectedExposureSeconds,
                        isDropdownExpanded = isDropdownExpanded,
                        isProcessing = isProcessing,
                        statusMessage = statusMessage,
                        onExposureChange = { selectedExposureSeconds = it },
                        onDropdownToggle = { isDropdownExpanded = it },
                        onShutterClick = onTriggerShutter
                    )
                }

                MainMenuTab.INTERVAL -> {
                    IntervalTabContent(
                        isIntervalActive = isIntervalShootingActive,
                        isCamera2BurstActive = isCamera2BurstActive,
                        camera2BurstStatus = camera2BurstStatus,
                        shotCount = shotCount,
                        remainingShots = remainingShots,
                        elapsedSeconds = elapsedSeconds,
                        selectedExposureSeconds = selectedExposureSeconds,
                        onTriggerShutter = onTriggerShutter
                    )
                }

                MainMenuTab.SAVE -> {
                    SaveTabContent(
                        context = context,
                        coroutineScope = coroutineScope
                    )
                }
            }
        }
    }
}

/**
 * 縦位置での 4K, Full HD, HD クロップエリア枠線を同時に表示するガイドオーバーレイ（「上」「下」の向き表示を追加）
 */
@Composable
private fun PortraitCropGuidesOverlay(
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        if (width <= 0f || height <= 0f) return@BoxWithConstraints

        Canvas(modifier = Modifier.fillMaxSize()) {
            val scales = listOf(
                Triple(0.90f, Color(0xFF00E676), "4K ガイド"),
                Triple(0.75f, Color(0xFFFFEB3B), "Full HD ガイド"),
                Triple(0.60f, Color(0xFFFF5252), "HD ガイド")
            )

            scales.forEach { (scale, color, _) ->
                val guideHeight = height * scale
                val guideWidth = (guideHeight * 9f) / 16f
                val left = (width - guideWidth) / 2f
                val top = (height - guideHeight) / 2f

                drawRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(guideWidth, guideHeight),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                )
            }
        }

        // タイムラプス動画変換時の上下方向インジケーター（左90度回転を考慮し右側が上、左側が下）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Text(
                    text = "動画の\n【 上 】",
                    color = Color(0xFF00E676),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                )
            }

            Surface(
                color = Color.Black.copy(alpha = 0.7f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Text(
                    text = "動画の\n【 下 】",
                    color = Color(0xFFFF5252),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "📏 縦位置クロップガイド (4K / Full HD / HD)", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * 撮影前設定タブコンテンツ
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupTabContent(
    isCamera2BurstActive: Boolean,
    currentStep: WorkflowStep,
    capturedTestBitmap: Bitmap?,
    selectedExposureSeconds: Double,
    isDropdownExpanded: Boolean,
    isProcessing: Boolean,
    statusMessage: String,
    onExposureChange: (Double) -> Unit,
    onDropdownToggle: (Boolean) -> Unit,
    onShutterClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isCamera2BurstActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = statusMessage,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else if (currentStep == WorkflowStep.TEST_RESULT_DISPLAY && capturedTestBitmap != null) {
            Image(
                bitmap = capturedTestBitmap.asImageBitmap(),
                contentDescription = "試写結果",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            CameraPreview(
                modifier = Modifier.fillMaxSize()
            )
            PortraitCropGuidesOverlay()
        }

        // ヘッダーレイアウト（ステータス表示: 撮影前設定）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "撮影前設定",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )

                val isChangeable = currentStep == WorkflowStep.DARK_FRAME_NOTICE || currentStep == WorkflowStep.POLARIS_ALIGNMENT_NOTICE

                ExposedDropdownMenuBox(
                    expanded = isDropdownExpanded && isChangeable,
                    onExpandedChange = {
                        if (isChangeable) onDropdownToggle(!isDropdownExpanded)
                    }
                ) {
                    OutlinedTextField(
                        value = formatExposureSeconds(selectedExposureSeconds),
                        onValueChange = {},
                        readOnly = true,
                        enabled = isChangeable,
                        label = { Text("露出時間", color = Color.LightGray, fontSize = 9.sp) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded && isChangeable)
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            disabledTextColor = Color.LightGray,
                            focusedBorderColor = Color(0xFF1E88E5),
                            unfocusedBorderColor = Color.Gray,
                            disabledBorderColor = Color.DarkGray,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .width(135.dp)
                    )

                    ExposedDropdownMenu(
                        expanded = isDropdownExpanded && isChangeable,
                        onDismissRequest = { onDropdownToggle(false) }
                    ) {
                        EXPOSURE_TIMES_SECONDS.forEach { seconds ->
                            DropdownMenuItem(
                                text = { Text(formatExposureSeconds(seconds), color = Color.White) },
                                onClick = {
                                    onExposureChange(seconds)
                                    onDropdownToggle(false)
                                }
                            )
                        }
                    }
                }
            }
        }

        if (isProcessing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF1E88E5))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (currentStep == WorkflowStep.DARK_FRAME_SHOOTING) "ダークフレーム撮影中..." else "試写・ノイズ減算処理中...",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.75f)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = statusMessage,
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
        }

        // シャッターボタン
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = onShutterClick,
                enabled = !isCamera2BurstActive,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isProcessing) Color(0xFFD32F2F) else Color.Red
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                if (isProcessing) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color.White, RoundedCornerShape(4.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.Red, CircleShape)
                        )
                    }
                }
            }
        }
    }
}

/**
 * インターバル撮影タブコンテンツ（撮影数の右に開始からの経過秒数を追加）
 */
@Composable
private fun IntervalTabContent(
    isIntervalActive: Boolean,
    isCamera2BurstActive: Boolean,
    camera2BurstStatus: String,
    shotCount: Int,
    remainingShots: Int,
    elapsedSeconds: Int,
    selectedExposureSeconds: Double,
    onTriggerShutter: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (isCamera2BurstActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = camera2BurstStatus,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            CameraPreview(
                modifier = Modifier.fillMaxSize()
            )
            PortraitCropGuidesOverlay()
        }

        // ヘッダー（2行目：撮影数の右に経過秒数を追加）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "インターバル撮影",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "露出時間: ${formatExposureSeconds(selectedExposureSeconds)}",
                    color = Color.White,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "撮影数: ${shotCount}コマ  (経過: ${elapsedSeconds}秒)",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "残り: 約${remainingShots}枚",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                )
            }
        }

        // シャッターボタン（停止マーク対応）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = onTriggerShutter,
                enabled = isIntervalActive || !isCamera2BurstActive,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isIntervalActive) Color(0xFFD32F2F) else Color.Red
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                if (isIntervalActive) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(Color.White, RoundedCornerShape(4.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .background(Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(Color.Red, CircleShape)
                        )
                    }
                }
            }
        }
    }
}
