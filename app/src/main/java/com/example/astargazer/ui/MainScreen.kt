@file:OptIn(ExperimentalCamera2Interop::class)

package com.example.astargazer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
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
import com.example.astargazer.util.BitmapUtils
import com.example.astargazer.util.FileViewerHelper
import com.example.astargazer.util.ImageCompositor
import com.example.astargazer.util.ImageContrastAnalyzer
import com.example.astargazer.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 露出時間の選択肢（秒数: 0.25秒, 0.5秒, 1秒, 2秒, 4秒, 8秒, 15秒, 30秒）
 */
val EXPOSURE_TIMES_SECONDS = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0)

/**
 * 露出時間のフォーマット
 */
fun formatExposureSeconds(seconds: Double): String {
    return if (seconds % 1.0 == 0.0) {
        "${seconds.toInt()}秒"
    } else {
        "${seconds}秒"
    }
}

/**
 * メニュータブ
 */
enum class MainMenuTab(val label: String) {
    SETUP("撮影前設定"),
    INTERVAL("インターバル撮影"),
    SAVE("保存")
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

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        PermissionRequestContent {
            launcher.launch(Manifest.permission.CAMERA)
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
                text = "カメラのアクセス権限が必要です",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "星空の試写およびインターバル撮影を行うため、カメラ機能を使用します。",
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCamera2Interop::class)
@Composable
private fun MainAppContent() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by remember { mutableStateOf(MainMenuTab.SETUP) }

    var isSetupCompleted by remember { mutableStateOf(false) }
    var isIntervalCompleted by remember { mutableStateOf(false) }

    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureInstance by remember { mutableStateOf<ImageCapture?>(null) }

    var capturedTestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    var isIntervalShootingActive by remember { mutableStateOf(false) }
    var shotCount by remember { mutableIntStateOf(0) }
    var remainingShots by remember { mutableIntStateOf(0) }

    var currentStep by remember { mutableStateOf(WorkflowStep.DARK_FRAME_NOTICE) }

    var selectedExposureSeconds by remember { mutableDoubleStateOf(4.0) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    var statusMessage by remember {
        mutableStateOf("露出時間を選択し、レンズを覆ってシャッターを押してください（ダーク撮影）。")
    }

    // 露出時間が変更されたとき、すでに同じ秒数のダークフレームがあれば案内メッセージに反映
    LaunchedEffect(selectedExposureSeconds) {
        if (StorageHelper.hasValidDarkFrame(context, selectedExposureSeconds)) {
            statusMessage = "露出時間 ${formatExposureSeconds(selectedExposureSeconds)}: 既存のダークフレームが利用可能です。そのままシャッターを押して北極星合わせへ進むか、露出時間を再選択できます。"
        } else {
            statusMessage = "露出時間 ${formatExposureSeconds(selectedExposureSeconds)}: レンズを覆ってシャッターを押してください（ダーク撮影）。"
        }
    }

    // 設定キャンセル・リセット
    fun cancelSetup() {
        isProcessing = false
        isSetupCompleted = false
        isIntervalCompleted = false
        isIntervalShootingActive = false
        currentStep = WorkflowStep.DARK_FRAME_NOTICE
        capturedTestBitmap = null

        statusMessage = if (StorageHelper.hasValidDarkFrame(context, selectedExposureSeconds)) {
            "設定をリセットしました。露出時間を再選択するか、シャッターを押して進んでください（既存ダーク流用可）。"
        } else {
            "設定をリセットしました。露出時間を再選択し、レンズを覆ってシャッターを押してください。"
        }
        selectedTab = MainMenuTab.SETUP
    }

    // インターバル1コマ撮影（最大画質・クロップ/減算なし）
    suspend fun captureIntervalFrame(imageCapture: ImageCapture, index: Int, iso: Int): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val outputFile = StorageHelper.createIntervalImageFile(context, index)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
            val executor = ContextCompat.getMainExecutor(context)

            imageCapture.takePicture(
                outputOptions,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (continuation.isActive) continuation.resume(true)

                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                FileViewerHelper.scanFile(context, outputFile)
                                com.example.astargazer.util.ExifHelper.saveExifAttributes(
                                    file = outputFile,
                                    iso = iso,
                                    exposureSeconds = selectedExposureSeconds
                                )
                            } catch (e: Exception) {
                                Log.e("MainScreen", "Background post-process failed for $index", e)
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("MainScreen", "Interval frame $index capture error", exception)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            )
        }
    }

    // インターバル撮影ループ
    fun startIntervalShootingLoop() {
        val imageCapture = imageCaptureInstance ?: run {
            statusMessage = "カメラの準備ができていません。"
            return
        }

        val initialStorageBytes = StorageHelper.getAvailableStorageBytes(context)
        val minAllowedStorageBytes = (initialStorageBytes * 0.5f).toLong()
        var currentRemainingShots = StorageHelper.calculateRemainingShots(initialStorageBytes, minAllowedStorageBytes)

        val optimalIso = CameraControlManager.calculateOptimalIsoForExposure(selectedExposureSeconds)

        isIntervalShootingActive = true
        shotCount = 0
        remainingShots = currentRemainingShots

        coroutineScope.launch {
            while (isIntervalShootingActive) {
                shotCount++
                if (currentRemainingShots > 0) {
                    currentRemainingShots--
                    remainingShots = currentRemainingShots
                }

                val success = captureIntervalFrame(imageCapture, shotCount, optimalIso)
                if (success) {
                    isIntervalCompleted = true
                } else {
                    Log.w("MainScreen", "Failed to capture frame $shotCount")
                }
            }
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
                            if (StorageHelper.hasValidDarkFrame(context, selectedExposureSeconds)) {
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
                        if (shotCount > 0) isIntervalCompleted = true
                    } else {
                        startIntervalShootingLoop()
                    }
                }
                MainMenuTab.SAVE -> {}
            }
        }
    }

    // ダークフレーム撮影実行
    fun runDarkFrameShooting() {
        val imageCapture = imageCaptureInstance ?: run {
            statusMessage = "カメラの準備ができていません。"
            return
        }

        isProcessing = true
        statusMessage = "ダークフレーム撮影中 (${formatExposureSeconds(selectedExposureSeconds)})..."

        val darkFrameFile = StorageHelper.getDarkFrameFile(context)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(darkFrameFile).build()
        val executor = ContextCompat.getMainExecutor(context)

        imageCapture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    isProcessing = false
                    val optimalIso = CameraControlManager.calculateOptimalIsoForExposure(selectedExposureSeconds)
                    com.example.astargazer.util.ExifHelper.saveExifAttributes(
                        file = darkFrameFile,
                        iso = optimalIso,
                        exposureSeconds = selectedExposureSeconds
                    )
                    FileViewerHelper.scanFile(context, darkFrameFile)

                    statusMessage = "ダークフレーム撮影完了。レンズカバーを外し、星空に向けてシャッターを押してください。"
                    currentStep = WorkflowStep.POLARIS_ALIGNMENT_NOTICE
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("MainScreen", "Dark frame capture failed", exception)
                    isProcessing = false
                    statusMessage = "ダークフレーム撮影エラー: ${exception.message}"
                }
            }
        )
    }

    // 試写と自動調整（最大画質 ＆ ノイズ減算適用）
    fun runTestShootingAndAutoAdjust() {
        val camera = cameraInstance ?: run {
            statusMessage = "カメラの準備ができていません。"
            return
        }
        val imageCapture = imageCaptureInstance ?: run {
            statusMessage = "カメラの準備ができていません。"
            return
        }

        val optimalIso = CameraControlManager.calculateOptimalIsoForExposure(selectedExposureSeconds)

        isProcessing = true
        statusMessage = "試写を実行中: 無限遠ピント & ISO($optimalIso)..."

        CameraControlManager.setManualFocusAndExposure(
            camera = camera,
            focusDistance = 0.0f,
            iso = optimalIso,
            exposureTimeNs = (selectedExposureSeconds * 1_000_000_000L).toLong()
        )

        val executor = ContextCompat.getMainExecutor(context)
        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    coroutineScope.launch(Dispatchers.IO) {
                        val bitmap = BitmapUtils.imageProxyToBitmap(image)
                        image.close()

                        if (bitmap != null) {
                            val darkFile = StorageHelper.getDarkFrameFile(context)
                            val finalBitmap = if (darkFile.exists()) {
                                val darkBmp = BitmapFactory.decodeFile(darkFile.absolutePath)
                                if (darkBmp != null) {
                                    val subtracted = ImageCompositor.subtractDarkFrame(bitmap, darkBmp)
                                    bitmap.recycle()
                                    darkBmp.recycle()
                                    subtracted
                                } else {
                                    bitmap
                                }
                            } else {
                                bitmap
                            }

                            capturedTestBitmap = finalBitmap

                            val testFile = StorageHelper.getTestShootingFile()
                            try {
                                FileOutputStream(testFile).use { out ->
                                    finalBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                }
                                com.example.astargazer.util.ExifHelper.saveExifAttributes(testFile, optimalIso, selectedExposureSeconds)
                                FileViewerHelper.scanFile(context, testFile)
                            } catch (e: Exception) {
                                Log.e("MainScreen", "Failed to save test image", e)
                            }

                            val score = ImageContrastAnalyzer.calculateContrastScore(finalBitmap)
                            val scoreFormatted = String.format(Locale.JAPAN, "%.1f", score)

                            withContext(Dispatchers.Main) {
                                isProcessing = false
                                statusMessage = "試写調整完了 (スコア: $scoreFormatted, ノイズ減算済)。設定完了！"
                                currentStep = WorkflowStep.SETUP_COMPLETED
                                isSetupCompleted = true
                                selectedTab = MainMenuTab.INTERVAL
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                isProcessing = false
                                statusMessage = "試写画像の取得に失敗しました。"
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("MainScreen", "Test capture failed", exception)
                    isProcessing = false
                    statusMessage = "試写撮影エラー: ${exception.message}"
                }
            }
        )
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
                    val enabled = when (tab) {
                        MainMenuTab.SETUP -> true
                        MainMenuTab.INTERVAL -> isSetupCompleted
                        MainMenuTab.SAVE -> isIntervalCompleted
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
                        currentStep = currentStep,
                        capturedTestBitmap = capturedTestBitmap,
                        selectedExposureSeconds = selectedExposureSeconds,
                        isDropdownExpanded = isDropdownExpanded,
                        isProcessing = isProcessing,
                        statusMessage = statusMessage,
                        onExposureChange = { selectedExposureSeconds = it },
                        onDropdownToggle = { isDropdownExpanded = it },
                        onShutterClick = onTriggerShutter,
                        onCameraBound = { camera, imageCapture ->
                            cameraInstance = camera
                            imageCaptureInstance = imageCapture
                        }
                    )
                }

                MainMenuTab.INTERVAL -> {
                    IntervalTabContent(
                        isIntervalActive = isIntervalShootingActive,
                        shotCount = shotCount,
                        remainingShots = remainingShots,
                        selectedExposureSeconds = selectedExposureSeconds,
                        onTriggerShutter = onTriggerShutter,
                        onCameraBound = { camera, imageCapture ->
                            cameraInstance = camera
                            imageCaptureInstance = imageCapture
                        }
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
    currentStep: WorkflowStep,
    capturedTestBitmap: Bitmap?,
    selectedExposureSeconds: Double,
    isDropdownExpanded: Boolean,
    isProcessing: Boolean,
    statusMessage: String,
    onExposureChange: (Double) -> Unit,
    onDropdownToggle: (Boolean) -> Unit,
    onShutterClick: () -> Unit,
    onCameraBound: (Camera, ImageCapture) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (currentStep == WorkflowStep.TEST_RESULT_DISPLAY && capturedTestBitmap != null) {
            Image(
                bitmap = capturedTestBitmap.asImageBitmap(),
                contentDescription = "試写結果",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onCameraBound = onCameraBound
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
 * インターバル撮影タブコンテンツ（ヘッダーの露出時間と撮影数、残り撮影可能枚数の位置を綺麗に整列）
 */
@Composable
private fun IntervalTabContent(
    isIntervalActive: Boolean,
    shotCount: Int,
    remainingShots: Int,
    selectedExposureSeconds: Double,
    onTriggerShutter: () -> Unit,
    onCameraBound: (Camera, ImageCapture) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onCameraBound = onCameraBound
        )
        PortraitCropGuidesOverlay()

        // ヘッダー（左側に「タイトル・撮影数」、右側に「露出時間・残り撮影可能枚数」を整列）
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
                    text = "撮影数: ${shotCount}コマ",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "残り撮影可能: 約${remainingShots}枚",
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
