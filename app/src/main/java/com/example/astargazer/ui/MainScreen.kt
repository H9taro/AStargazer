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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.example.astargazer.util.VideoEncoderHelper
import com.example.astargazer.util.rememberTtsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 露出時間の選択肢（秒数: 0.25秒, 0.5秒, 1秒, 2秒, 4秒, 8秒, 15秒, 30秒）
 */
val EXPOSURE_TIMES_SECONDS = listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 15.0, 30.0)

/**
 * 撮影解像度/クロップサイズの選択肢
 */
enum class CaptureResolution(val label: String, val shortLabel: String, val width: Int, val height: Int) {
    FULL("最大画質 (センサー解像度)", "最大画質", 0, 0),
    FHD("フルHD (1920×1080 / 16:9)", "フルHD 1080p", 1920, 1080),
    HD("HD画質 (1280×720 / 16:9)", "HD 720p", 1280, 720)
}

/**
 * 露出時間のフォーマット（整数の場合は "1秒", 小数の場合は "0.25秒" など）
 */
fun formatExposureSeconds(seconds: Double): String {
    return if (seconds % 1.0 == 0.0) {
        "${seconds.toInt()}秒"
    } else {
        "${seconds}秒"
    }
}

/**
 * アプリのメインメニュータブ
 */
enum class MainMenuTab(val label: String) {
    SETUP("撮影前設定"),
    INTERVAL("インターバル撮影"),
    SAVE("保存")
}

/**
 * 撮影前設定ワークフローの各ステップ
 */
enum class WorkflowStep {
    EXPOSURE_SETTING,               // 1. 露出時間設定
    POLARIS_ALIGNMENT_NOTICE,       // 2. 開始通知（北極星合わせ案内）
    POLARIS_TEST_SHOOTING_ADJUST,   // 3. 試写と自動調整
    TEST_RESULT_DISPLAY,            // 4. 試写結果表示
    DIRECTION_CONFIRM_NOTICE,       // 5. 撮影方向確定案内
    DARK_FRAME_NOTICE,              // 6a. ダークフレーム撮影案内
    DARK_FRAME_SHOOTING,            // 6b. ダークフレーム撮影実行
    SETUP_COMPLETED                 // 6c. 撮影前設定完了
}

@Composable
fun MainScreen() {
    val context = LocalContext.current

    // カメラパーミッション状態
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
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
        // パーミッション未許可画面
        PermissionRequestContent {
            launcher.launch(Manifest.permission.CAMERA)
        }
    } else {
        // メインコンテンツ
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
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E88E5)
                )
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
    val ttsManager = rememberTtsManager()

    // 現在選択中のメニュータブ
    var selectedTab by remember { mutableStateOf(MainMenuTab.SETUP) }

    // 設定・撮影完了状態のフラグ
    var isSetupCompleted by remember { mutableStateOf(false) }
    var isIntervalCompleted by remember { mutableStateOf(false) }

    // カメラ及び UseCase 保持
    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureInstance by remember { mutableStateOf<ImageCapture?>(null) }

    // 試写キャプチャ画像と処理状態
    var capturedTestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    // インターバル撮影状態
    var isIntervalShootingActive by remember { mutableStateOf(false) }
    var shotCount by remember { mutableIntStateOf(0) }

    // 現在の撮影前設定ステップ
    var currentStep by remember { mutableStateOf(WorkflowStep.EXPOSURE_SETTING) }

    // 選択された露出時間 (デフォルト 4.0秒)
    var selectedExposureSeconds by remember { mutableDoubleStateOf(4.0) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    // 選択された撮影解像度/クロップサイズ (デフォルト HD画質)
    var selectedResolution by remember { mutableStateOf(CaptureResolution.HD) }
    var isResolutionMenuExpanded by remember { mutableStateOf(false) }

    // ステータスメッセージ
    var statusMessage by remember {
        mutableStateOf("露出時間・画質を選択し、開始ボタンを押してください。")
    }

    // 必要に応じて画像をクロップ・リサイズして指定ファイルに書き込む共通関数
    fun processAndSaveFile(outputFile: File, rawFile: File) {
        if (selectedResolution == CaptureResolution.FULL) {
            FileViewerHelper.scanFile(context, rawFile)
            return
        }

        try {
            val srcBitmap = BitmapFactory.decodeFile(rawFile.absolutePath)
            if (srcBitmap != null) {
                val cropped = BitmapUtils.cropTo169(srcBitmap, selectedResolution.width, selectedResolution.height)
                srcBitmap.recycle()

                FileOutputStream(outputFile).use { out ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                cropped.recycle()
                FileViewerHelper.scanFile(context, outputFile)
            }
        } catch (e: Exception) {
            Log.e("MainScreen", "Crop/Save failed for ${outputFile.name}", e)
        }
    }

    // 撮影前設定の強制的キャンセル
    fun cancelSetup() {
        isProcessing = false
        isSetupCompleted = false
        isIntervalCompleted = false
        currentStep = WorkflowStep.EXPOSURE_SETTING
        capturedTestBitmap = null
        val msg = "撮影前設定をキャンセルしました。「インターバル撮影」「保存」が無効化されました。"
        statusMessage = msg
        ttsManager.speak("撮影前設定をキャンセルしました")
        selectedTab = MainMenuTab.SETUP
    }

    // 単発撮影用サスペンド関数（インターバル撮影の1コマ分）
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
                        // アノテーション・クロップ処理
                        if (selectedResolution != CaptureResolution.FULL) {
                            processAndSaveFile(outputFile, outputFile)
                        } else {
                            FileViewerHelper.scanFile(context, outputFile)
                        }

                        // Exif メタデータ (日時, ISO, 露出時間, 機種名, 焦点距離) を自動記録
                        com.example.astargazer.util.ExifHelper.saveExifAttributes(
                            file = outputFile,
                            iso = iso,
                            exposureSeconds = selectedExposureSeconds
                        )

                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("MainScreen", "Interval frame $index capture error", exception)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            )
        }
    }

    // インターバル撮影ループ関数
    fun startIntervalShootingLoop() {
        val imageCapture = imageCaptureInstance ?: run {
            statusMessage = "キャプチャ機能の準備ができていません。"
            return
        }

        val initialStorageBytes = StorageHelper.getAvailableStorageBytes(context)
        val minAllowedStorageBytes = (initialStorageBytes * 0.5f).toLong()
        val optimalIso = CameraControlManager.calculateOptimalIsoForExposure(selectedExposureSeconds)

        isIntervalShootingActive = true
        shotCount = 0

        coroutineScope.launch {
            val startMsg = "インターバル撮影を開始しました。(画質: ${selectedResolution.shortLabel})"
            statusMessage = startMsg
            ttsManager.speak(startMsg)

            while (isIntervalShootingActive) {
                val currentStorageBytes = StorageHelper.getAvailableStorageBytes(context)

                if (currentStorageBytes <= minAllowedStorageBytes) {
                    isIntervalShootingActive = false
                    isIntervalCompleted = shotCount > 0
                    val stopMsg = "空き容量の50%に達したため、撮影を自動終了しました。(合計: ${shotCount}枚)"
                    statusMessage = stopMsg
                    ttsManager.speak("撮影上限容量に達したためインターバル撮影を終了しました")
                    break
                }

                shotCount++

                val remainingShots = StorageHelper.calculateRemainingShots(currentStorageBytes, minAllowedStorageBytes)
                val storageStr = StorageHelper.getFormattedAvailableStorage(context)
                statusMessage = "インターバル撮影中... [撮影数: ${shotCount}枚 / 残り撮影可能: 約${remainingShots}枚 / 残容量: $storageStr]"

                val success = captureIntervalFrame(imageCapture, shotCount, optimalIso)
                if (success) {
                    isIntervalCompleted = true
                } else {
                    Log.w("MainScreen", "Failed to capture frame $shotCount")
                }

                delay(100L)
            }
        }
    }

    // インターバル撮影完了/正常停止処理
    fun stopIntervalShooting() {
        if (isIntervalShootingActive) {
            isIntervalShootingActive = false
            if (shotCount > 0) isIntervalCompleted = true
            val msg = "インターバル撮影を正常停止しました。(合計撮影数: ${shotCount}枚)"
            statusMessage = msg
            ttsManager.speak("インターバル撮影を終了しました")
        }
    }

    // 試写と自動調整の実行関数
    fun runTestShootingAndAutoAdjust() {
        val camera = cameraInstance ?: run {
            statusMessage = "カメラの準備ができていません。"
            return
        }
        val imageCapture = imageCaptureInstance ?: run {
            statusMessage = "キャプチャ機能の準備ができていません。"
            return
        }

        val optimalIso = CameraControlManager.calculateOptimalIsoForExposure(selectedExposureSeconds)

        isProcessing = true
        statusMessage = "試写を実行中: ピント(無限遠) & ISO($optimalIso) 自動調整..."

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
                    coroutineScope.launch {
                        var bitmap = BitmapUtils.imageProxyToBitmap(image)
                        image.close()

                        if (bitmap != null) {
                            if (selectedResolution != CaptureResolution.FULL) {
                                bitmap = BitmapUtils.cropTo169(bitmap, selectedResolution.width, selectedResolution.height)
                            }
                            capturedTestBitmap = bitmap

                            val testFile = StorageHelper.getTestShootingFile()
                            try {
                                FileOutputStream(testFile).use { out ->
                                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                }
                                com.example.astargazer.util.ExifHelper.saveExifAttributes(testFile, optimalIso, selectedExposureSeconds)
                                FileViewerHelper.scanFile(context, testFile)
                            } catch (e: Exception) {
                                Log.e("MainScreen", "Failed to save test shooting image", e)
                            }

                            val score = ImageContrastAnalyzer.calculateContrastScore(bitmap)
                            val scoreFormatted = String.format(Locale.JAPAN, "%.1f", score)

                            val avgLuminance = ImageContrastAnalyzer.calculateAverageLuminance(bitmap)
                            val adjustedIso = ImageContrastAnalyzer.adjustIsoForLuminance(optimalIso, avgLuminance)

                            if (adjustedIso != optimalIso) {
                                CameraControlManager.setManualFocusAndExposure(
                                    camera = camera,
                                    focusDistance = 0.0f,
                                    iso = adjustedIso,
                                    exposureTimeNs = (selectedExposureSeconds * 1_000_000_000L).toLong()
                                )
                            }

                            isProcessing = false

                            val statusNotice = if (adjustedIso < optimalIso) "白飛び補正: ISO $adjustedIso" else "ISO $optimalIso"
                            val message = "試写調整完了 ($statusNotice, スコア: $scoreFormatted)。レンズ（カメラ）を覆った状態でシャッターを押してください。"
                            statusMessage = message
                            currentStep = WorkflowStep.DARK_FRAME_NOTICE
                            ttsManager.speak("試写調整が完了しました。レンズを覆って、シャッターを押してください")
                        } else {
                            isProcessing = false
                            statusMessage = "試写画像の取得に失敗しました。"
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

    // ダークフレーム撮影実行関数
    fun runDarkFrameShooting() {
        val imageCapture = imageCaptureInstance ?: run {
            statusMessage = "キャプチャ機能の準備ができていません。"
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
                    if (selectedResolution != CaptureResolution.FULL) {
                        processAndSaveFile(darkFrameFile, darkFrameFile)
                    }

                    val optimalIso = CameraControlManager.calculateOptimalIsoForExposure(selectedExposureSeconds)
                    com.example.astargazer.util.ExifHelper.saveExifAttributes(
                        file = darkFrameFile,
                        iso = optimalIso,
                        exposureSeconds = selectedExposureSeconds
                    )
                    FileViewerHelper.scanFile(context, darkFrameFile)

                    val message = "ダークフレーム撮影が完了しました。カバーを外してください。"
                    statusMessage = message
                    ttsManager.speak(message)
                    currentStep = WorkflowStep.SETUP_COMPLETED
                    isSetupCompleted = true

                    selectedTab = MainMenuTab.INTERVAL
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("MainScreen", "Dark frame capture failed", exception)
                    isProcessing = false
                    statusMessage = "ダークフレーム撮影エラー: ${exception.message}"
                }
            }
        )
    }

    // 撮影前設定のステップ変更処理
    fun updateSetupStep(newStep: WorkflowStep) {
        currentStep = newStep
        when (newStep) {
            WorkflowStep.EXPOSURE_SETTING -> {
                val message = "露出時間を選択し、北極星に合わせてシャッターを押してください。"
                statusMessage = message
                ttsManager.speak("北極星に合わせてシャッターを押してください")
            }
            WorkflowStep.POLARIS_ALIGNMENT_NOTICE -> {
                val message = "北極星に合わせてシャッターを押してください"
                statusMessage = message
                ttsManager.speak(message)
            }
            WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST -> {
                runTestShootingAndAutoAdjust()
            }
            WorkflowStep.TEST_RESULT_DISPLAY -> {
                // 試写結果表示中
            }
            WorkflowStep.DIRECTION_CONFIRM_NOTICE -> {
                val message = "撮影したい方向を決めて、シャッターを押してください"
                statusMessage = message
                ttsManager.speak(message)
            }
            WorkflowStep.DARK_FRAME_NOTICE -> {
                val message = "レンズを覆って、シャッターを押してください"
                statusMessage = "ダークフレーム撮影準備: レンズ（カメラ）を覆った状態でシャッターを押してください。"
                ttsManager.speak(message)
            }
            WorkflowStep.DARK_FRAME_SHOOTING -> {
                runDarkFrameShooting()
            }
            WorkflowStep.SETUP_COMPLETED -> {
                isSetupCompleted = true
                statusMessage = "撮影前設定が完了しました！インターバル撮影を開始できます。"
                selectedTab = MainMenuTab.INTERVAL
            }
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
                                currentStep = WorkflowStep.EXPOSURE_SETTING
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
                        selectedResolution = selectedResolution,
                        isDropdownExpanded = isDropdownExpanded,
                        isResolutionMenuExpanded = isResolutionMenuExpanded,
                        isProcessing = isProcessing,
                        statusMessage = statusMessage,
                        onExposureChange = { selectedExposureSeconds = it },
                        onResolutionChange = { selectedResolution = it },
                        onDropdownToggle = { isDropdownExpanded = it },
                        onResolutionMenuToggle = { isResolutionMenuExpanded = it },
                        onStepTrigger = { updateSetupStep(it) },
                        onCancelSetup = { cancelSetup() },
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
                        selectedExposureSeconds = selectedExposureSeconds,
                        selectedResolution = selectedResolution,
                        statusMessage = statusMessage,
                        onStartInterval = { startIntervalShootingLoop() },
                        onStopInterval = { stopIntervalShooting() },
                        onCameraBound = { camera, imageCapture ->
                            cameraInstance = camera
                            imageCaptureInstance = imageCapture
                        }
                    )
                }

                MainMenuTab.SAVE -> {
                    SaveTabContent(
                        context = context,
                        coroutineScope = coroutineScope,
                        ttsManager = ttsManager
                    )
                }
            }
        }
    }
}

/**
 * プレビュー画面上にクロップ切り取り範囲を示す枠線・マスクを描画するコンポーザブル
 */
@Composable
private fun CropGuideOverlay(
    selectedResolution: CaptureResolution,
    modifier: Modifier = Modifier
) {
    if (selectedResolution == CaptureResolution.FULL) return

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        if (width <= 0f || height <= 0f) return@BoxWithConstraints

        // 16:9 画角のトリミング領域を画面中央に設定
        val cropWidth: Float
        val cropHeight: Float

        if (width * 9f > height * 16f) {
            cropHeight = height
            cropWidth = (height * 16f) / 9f
        } else {
            cropWidth = width
            cropHeight = (width * 9f) / 16f
        }

        val left = (width - cropWidth) / 2f
        val top = (height - cropHeight) / 2f

        Canvas(modifier = Modifier.fillMaxSize()) {
            // クロップ枠外の上下/左右を半透明黒でマスキング
            val maskColor = Color.Black.copy(alpha = 0.5f)

            if (top > 0) {
                // 上部マスク
                drawRect(color = maskColor, topLeft = Offset(0f, 0f), size = Size(width, top))
                // 下部マスク
                drawRect(color = maskColor, topLeft = Offset(0f, top + cropHeight), size = Size(width, height - (top + cropHeight)))
            }

            if (left > 0) {
                // 左側マスク
                drawRect(color = maskColor, topLeft = Offset(0f, 0f), size = Size(left, height))
                // 右側マスク
                drawRect(color = maskColor, topLeft = Offset(left + cropWidth, 0f), size = Size(width - (left + cropWidth), height))
            }

            // 16:9 クロップ境界線 (赤い破線ガイド枠)
            drawRect(
                color = Color(0xFFFF5252),
                topLeft = Offset(left, top),
                size = Size(cropWidth, cropHeight),
                style = Stroke(
                    width = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                )
            )
        }

        // ガイドラベル表示
        Text(
            text = "✂ クロップ領域 (${selectedResolution.shortLabel})",
            color = Color(0xFFFF8A80),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = (top / 1.5f).coerceAtLeast(60f).dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * タブ1: 撮影前設定コンテンツ (ステップ1-6)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupTabContent(
    currentStep: WorkflowStep,
    capturedTestBitmap: Bitmap?,
    selectedExposureSeconds: Double,
    selectedResolution: CaptureResolution,
    isDropdownExpanded: Boolean,
    isResolutionMenuExpanded: Boolean,
    isProcessing: Boolean,
    statusMessage: String,
    onExposureChange: (Double) -> Unit,
    onResolutionChange: (CaptureResolution) -> Unit,
    onDropdownToggle: (Boolean) -> Unit,
    onResolutionMenuToggle: (Boolean) -> Unit,
    onStepTrigger: (WorkflowStep) -> Unit,
    onCancelSetup: () -> Unit,
    onCameraBound: (Camera, ImageCapture) -> Unit
) {
    var totalDragY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { totalDragY = 0f },
                    onDragEnd = {
                        if (totalDragY < -120f) {
                            onCancelSetup()
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        totalDragY += dragAmount
                    }
                )
            }
    ) {
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

            // ★ クロップ枠線オーバーレイ描画
            CropGuideOverlay(selectedResolution = selectedResolution)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.60f))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "AStargazer",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "↑ 上スワイプでキャンセル",
                        color = Color(0xFFFF8A80),
                        fontSize = 10.sp
                    )
                }

                val isChangeable = currentStep == WorkflowStep.EXPOSURE_SETTING

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // クロップ画質/サイズドロップダウン
                    ExposedDropdownMenuBox(
                        expanded = isResolutionMenuExpanded && isChangeable,
                        onExpandedChange = {
                            if (isChangeable) onResolutionMenuToggle(!isResolutionMenuExpanded)
                        }
                    ) {
                        OutlinedTextField(
                            value = selectedResolution.shortLabel,
                            onValueChange = {},
                            readOnly = true,
                            enabled = isChangeable,
                            label = { Text("画質・サイズ", color = Color.LightGray, fontSize = 9.sp) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = isResolutionMenuExpanded && isChangeable)
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
                                .width(120.dp)
                        )

                        ExposedDropdownMenu(
                            expanded = isResolutionMenuExpanded && isChangeable,
                            onDismissRequest = { onResolutionMenuToggle(false) }
                        ) {
                            CaptureResolution.entries.forEach { res ->
                                DropdownMenuItem(
                                    text = { Text(res.label, color = Color.White, fontSize = 12.sp) },
                                    onClick = {
                                        onResolutionChange(res)
                                        onResolutionMenuToggle(false)
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 露出時間ドロップダウン
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
                                .width(90.dp)
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
                        text = if (currentStep == WorkflowStep.DARK_FRAME_SHOOTING) "ダークフレーム撮影中..." else "星像自動調整中...",
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
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.75f)
            ),
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    when (currentStep) {
                        WorkflowStep.EXPOSURE_SETTING -> onStepTrigger(WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST)
                        WorkflowStep.POLARIS_ALIGNMENT_NOTICE -> onStepTrigger(WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST)
                        WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST -> {}
                        WorkflowStep.TEST_RESULT_DISPLAY -> onStepTrigger(WorkflowStep.DARK_FRAME_NOTICE)
                        WorkflowStep.DIRECTION_CONFIRM_NOTICE -> onStepTrigger(WorkflowStep.DARK_FRAME_NOTICE)
                        WorkflowStep.DARK_FRAME_NOTICE -> onStepTrigger(WorkflowStep.DARK_FRAME_SHOOTING)
                        WorkflowStep.DARK_FRAME_SHOOTING -> {}
                        WorkflowStep.SETUP_COMPLETED -> onStepTrigger(WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST)
                    }
                },
                enabled = !isProcessing,
                modifier = Modifier.size(72.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Red
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
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

/**
 * タブ2: インターバル撮影コンテンツ (ステップ7)
 */
@Composable
private fun IntervalTabContent(
    isIntervalActive: Boolean,
    shotCount: Int,
    selectedExposureSeconds: Double,
    selectedResolution: CaptureResolution,
    statusMessage: String,
    onStartInterval: () -> Unit,
    onStopInterval: () -> Unit,
    onCameraBound: (Camera, ImageCapture) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onCameraBound = onCameraBound
        )

        // ★ クロップ枠線オーバーレイ描画
        CropGuideOverlay(selectedResolution = selectedResolution)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "AStargazer - インターバル撮影",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "露出時間: ${formatExposureSeconds(selectedExposureSeconds)} | 画質: ${selectedResolution.shortLabel} | 撮影数: ${shotCount}コマ",
                color = Color.LightGray,
                fontSize = 12.sp
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .align(Alignment.BottomCenter)
                .padding(bottom = 120.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color.Black.copy(alpha = 0.75f)
            ),
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    if (isIntervalActive) {
                        onStopInterval()
                    } else {
                        onStartInterval()
                    }
                },
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

/**
 * タブ3: 保存コンテンツ（*.mp4 タイムラプス動画 ＆ 比較明合成 *.jpg）
 */
@Composable
private fun SaveTabContent(
    context: android.content.Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    ttsManager: com.example.astargazer.util.TtsManager
) {
    val intervalFiles = remember { StorageHelper.getIntervalImageFiles(context) }
    var isGenerating by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var lastExportedFile by remember { mutableStateOf<java.io.File?>(null) }
    var exportStatusMessage by remember {
        mutableStateOf(
            if (intervalFiles.isNotEmpty()) "撮影済み静止画: ${intervalFiles.size}コマ\n保存するファイル形式を選択してください。"
            else "保存可能な撮影済み画像がありません。"
        )
    }

    // タイムラプス動画（*.mp4）生成
    fun generateTimelapseVideo() {
        if (intervalFiles.isEmpty()) return
        isGenerating = true
        progress = 0f
        exportStatusMessage = "タイムラプス動画(*.mp4)を生成中..."

        coroutineScope.launch(Dispatchers.IO) {
            val outputFile = StorageHelper.getTimelapseVideoFile(context)
            val success = VideoEncoderHelper.createTimelapseVideo(
                imageFiles = intervalFiles,
                outputFile = outputFile,
                frameRate = 30,
                onProgress = { p -> progress = p }
            )

            withContext(Dispatchers.Main) {
                isGenerating = false
                if (success) {
                    FileViewerHelper.scanFile(context, outputFile)
                    lastExportedFile = outputFile
                    val msg = "タイムラプス動画(*.mp4)の生成が完了しました！\n保存先: ${outputFile.name}"
                    exportStatusMessage = msg
                    ttsManager.speak("タイムラプス動画の書き出しが完了しました")
                    FileViewerHelper.openInGoogleFilesOrViewer(context, outputFile)
                } else {
                    exportStatusMessage = "タイムラプス動画の生成に失敗しました。"
                }
            }
        }
    }

    // 比較明合成（*.jpg）生成
    fun generateLightenBlendComposite() {
        if (intervalFiles.isEmpty()) return
        isGenerating = true
        progress = 0f
        exportStatusMessage = "比較明合成静止画(*.jpg)を生成中..."

        coroutineScope.launch(Dispatchers.IO) {
            val outputFile = StorageHelper.getCompositeImageFile(context)
            val success = ImageCompositor.createLightenBlendComposite(
                imageFiles = intervalFiles,
                outputFile = outputFile,
                onProgress = { p -> progress = p }
            )

            withContext(Dispatchers.Main) {
                isGenerating = false
                if (success) {
                    FileViewerHelper.scanFile(context, outputFile)
                    lastExportedFile = outputFile
                    val msg = "比較明合成静止画(*.jpg)の生成が完了しました！\n保存先: ${outputFile.name}"
                    exportStatusMessage = msg
                    ttsManager.speak("比較明合成画像の書き出しが完了しました")
                    FileViewerHelper.openInGoogleFilesOrViewer(context, outputFile)
                } else {
                    exportStatusMessage = "比較明合成画像の生成に失敗しました。"
                }
            }
        }
    }

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
                text = "AStargazer - ファイル保存・出力",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = exportStatusMessage,
                        color = Color.White,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center
                    )

                    if (isGenerating) {
                        Spacer(modifier = Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF1E88E5),
                            trackColor = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${(progress * 100).toInt()}% 完了",
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 1. タイムラプス動画(*.mp4) ボタン
            Button(
                onClick = { generateTimelapseVideo() },
                enabled = !isGenerating && intervalFiles.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E88E5),
                    disabledContainerColor = Color.DarkGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "🎬 タイムラプス動画(*.mp4)を出力",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. 比較明合成静止画(*.jpg) ボタン
            Button(
                onClick = { generateLightenBlendComposite() },
                enabled = !isGenerating && intervalFiles.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF43A047),
                    disabledContainerColor = Color.DarkGray
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "🌌 比較明合成の静止画(*.jpg)を出力",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // 保存完了ファイルがある場合に「Google Filesで開く」ボタンを表示
            if (lastExportedFile != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        FileViewerHelper.openInGoogleFilesOrViewer(context, lastExportedFile!!)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF9800)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "📁 Google Filesで保存ファイルを開く",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
