@file:OptIn(ExperimentalCamera2Interop::class)

package com.example.astargazer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 露出時間の選択肢（秒数）
 */
val EXPOSURE_TIMES_SECONDS = listOf(1, 2, 4, 8, 15, 30)

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

    // 選択された露出時間 (デフォルト 4秒)
    var selectedExposureSeconds by remember { mutableIntStateOf(4) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    // ステータスメッセージ
    var statusMessage by remember {
        mutableStateOf("露出時間を選択し、開始ボタンを押してください。")
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
    suspend fun captureIntervalFrame(imageCapture: ImageCapture, index: Int): Boolean {
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

        // ★ 仕様変更: 撮影開始時点の空き容量の50%を撮影可能上限（下限閾値）として設定
        val initialStorageBytes = StorageHelper.getAvailableStorageBytes(context)
        val minAllowedStorageBytes = (initialStorageBytes * 0.5f).toLong()

        isIntervalShootingActive = true
        shotCount = 0

        coroutineScope.launch {
            val startMsg = "インターバル撮影を開始しました。(撮影上限: 空き容量の50%)"
            statusMessage = startMsg
            ttsManager.speak(startMsg)

            while (isIntervalShootingActive) {
                val currentStorageBytes = StorageHelper.getAvailableStorageBytes(context)

                // 1. 容量チェック (空き容量の50%に達したら自動停止)
                if (currentStorageBytes <= minAllowedStorageBytes) {
                    isIntervalShootingActive = false
                    isIntervalCompleted = shotCount > 0
                    val stopMsg = "空き容量の50%に達したため、撮影を自動終了しました。(合計: ${shotCount}枚)"
                    statusMessage = stopMsg
                    ttsManager.speak("撮影上限容量に達したためインターバル撮影を終了しました")
                    break
                }

                shotCount++

                // 2. 残り撮影可能枚数の計算
                val remainingShots = StorageHelper.calculateRemainingShots(currentStorageBytes, minAllowedStorageBytes)
                val storageStr = StorageHelper.getFormattedAvailableStorage(context)
                statusMessage = "インターバル撮影中... [撮影数: ${shotCount}枚 / 残り撮影可能: 約${remainingShots}枚 / 残容量: $storageStr]"

                // 3. 露出1コマ分撮影 (露出 + JPEGエンコード・ファイル保存)
                val success = captureIntervalFrame(imageCapture, shotCount)
                if (success) {
                    isIntervalCompleted = true
                } else {
                    Log.w("MainScreen", "Failed to capture frame $shotCount")
                }

                // ★ 改善: 余分な1秒ウェイトを廃止し、露出完了後すぐに次のコマの撮影へ移行 (コマ間ギャップを最小化)
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

        // ★ 仕様変更: 選択された露出時間から最適ISO感度を自動算出
        val optimalIso = CameraControlManager.calculateOptimalIsoForExposure(selectedExposureSeconds)

        isProcessing = true
        statusMessage = "試写を実行中: ピント(無限遠) & ISO($optimalIso) 自動調整..."

        CameraControlManager.setManualFocusAndExposure(
            camera = camera,
            focusDistance = 0.0f,
            iso = optimalIso,
            exposureTimeNs = selectedExposureSeconds * 1_000_000_000L
        )

        val executor = ContextCompat.getMainExecutor(context)
        imageCapture.takePicture(
            executor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    coroutineScope.launch {
                        val bitmap = BitmapUtils.imageProxyToBitmap(image)
                        image.close()

                        if (bitmap != null) {
                            capturedTestBitmap = bitmap
                            val score = ImageContrastAnalyzer.calculateContrastScore(bitmap)
                            val scoreFormatted = String.format(Locale.JAPAN, "%.1f", score)

                            isProcessing = false
                            
                            val message = "試写調整完了 (ISO: $optimalIso, スコア: $scoreFormatted)。レンズ（カメラ）を覆った状態でシャッターを押してください。"
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
        statusMessage = "ダークフレーム撮影中 (${selectedExposureSeconds}秒)..."

        val darkFrameFile = StorageHelper.getDarkFrameFile(context)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(darkFrameFile).build()

        val executor = ContextCompat.getMainExecutor(context)
        imageCapture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    isProcessing = false
                    val message = "ダークフレーム撮影が完了しました。カバーを外してください。"
                    statusMessage = message
                    ttsManager.speak(message)
                    currentStep = WorkflowStep.SETUP_COMPLETED
                    isSetupCompleted = true // 撮影前設定完了
                    
                    // ダークフレーム撮影完了時に自動でヘッダー/画面を「インターバル撮影」へ切り替え
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
                // ★ 提案1適用: 露出時間設定と北極星合わせの案内を最初から統合
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
                        MainMenuTab.INTERVAL -> isSetupCompleted // 撮影前設定完了まで Disabled
                        MainMenuTab.SAVE -> isIntervalCompleted // インターバル撮影完了まで Disabled
                    }

                    NavigationBarItem(
                        selected = selectedTab == tab,
                        enabled = enabled,
                        onClick = {
                            selectedTab = tab
                            if (tab == MainMenuTab.SETUP) {
                                // ★ 仕様変更: メニューで撮影前設定を選択した場合、初期ステップに戻してドロップダウンのロックを解除
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
                    // メニュー1: 撮影前設定画面 (上向きスワイプでキャンセル)
                    SetupTabContent(
                        currentStep = currentStep,
                        capturedTestBitmap = capturedTestBitmap,
                        selectedExposureSeconds = selectedExposureSeconds,
                        isDropdownExpanded = isDropdownExpanded,
                        isProcessing = isProcessing,
                        statusMessage = statusMessage,
                        onExposureChange = { selectedExposureSeconds = it },
                        onDropdownToggle = { isDropdownExpanded = it },
                        onStepTrigger = { updateSetupStep(it) },
                        onCancelSetup = { cancelSetup() },
                        onCameraBound = { camera, imageCapture ->
                            cameraInstance = camera
                            imageCaptureInstance = imageCapture
                        }
                    )
                }

                MainMenuTab.INTERVAL -> {
                    // メニュー2: インターバル撮影画面 (シャッターボタンで開始/停止)
                    IntervalTabContent(
                        isIntervalActive = isIntervalShootingActive,
                        shotCount = shotCount,
                        selectedExposureSeconds = selectedExposureSeconds,
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
                    // メニュー3: 保存画面（*.mp4 動画 ＆ 比較明合成 *.jpg）
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
 * タブ1: 撮影前設定コンテンツ (ステップ1-6)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupTabContent(
    currentStep: WorkflowStep,
    capturedTestBitmap: Bitmap?,
    selectedExposureSeconds: Int,
    isDropdownExpanded: Boolean,
    isProcessing: Boolean,
    statusMessage: String,
    onExposureChange: (Int) -> Unit,
    onDropdownToggle: (Boolean) -> Unit,
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
        // バックグラウンド：CameraX プレビュー または 試写画像
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
        }

        // 上部コントロールパネル (露出時間設定 ＆ 上スワイプキャンセルのガイド)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "AStargazer - 撮影前設定",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "↑ 上スワイプでキャンセル",
                        color = Color(0xFFFF8A80),
                        fontSize = 11.sp
                    )
                }

                // 露出時間ドロップダウン (ステップ1のみ操作可能)
                val isExposureChangeable = currentStep == WorkflowStep.EXPOSURE_SETTING

                ExposedDropdownMenuBox(
                    expanded = isDropdownExpanded && isExposureChangeable,
                    onExpandedChange = {
                        if (isExposureChangeable) {
                            onDropdownToggle(!isDropdownExpanded)
                        }
                    }
                ) {
                    OutlinedTextField(
                        value = "${selectedExposureSeconds}秒",
                        onValueChange = {},
                        readOnly = true,
                        enabled = isExposureChangeable,
                        label = { Text("露出時間", color = Color.LightGray, fontSize = 10.sp) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded && isExposureChangeable)
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
                            .width(110.dp)
                    )

                    ExposedDropdownMenu(
                        expanded = isDropdownExpanded && isExposureChangeable,
                        onDismissRequest = { onDropdownToggle(false) }
                    ) {
                        EXPOSURE_TIMES_SECONDS.forEach { seconds ->
                            DropdownMenuItem(
                                text = { Text("${seconds}秒", color = Color.White) },
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

        // 中央：処理中のプログレス表示
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

        // 中央〜下部：案内メッセージカード
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

        // 下部：メイン操作（シャッター）ボタン
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
                        // ★ 提案1適用: 初回シャッター押下で直接試写＆自動調整へ進む (シャッター1回分削減)
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
    selectedExposureSeconds: Int,
    statusMessage: String,
    onStartInterval: () -> Unit,
    onStopInterval: () -> Unit,
    onCameraBound: (Camera, ImageCapture) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // カメラプレビュー
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            onCameraBound = onCameraBound
        )

        // 上部情報表示
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
                text = "露出時間: ${selectedExposureSeconds}秒 | 撮影数: ${shotCount}コマ",
                color = Color.LightGray,
                fontSize = 12.sp
            )
        }

        // 中央〜下部：ステータスメッセージカード
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

        // 下部：撮影開始/停止ボタン
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
                    lastExportedFile = outputFile
                    val msg = "タイムラプス動画(*.mp4)の生成が完了しました！\n保存先: ${outputFile.name}"
                    exportStatusMessage = msg
                    ttsManager.speak("タイムラプス動画の書き出しが完了しました")
                    // 自動で Google Files / ビューアを開いて表示
                    com.example.astargazer.util.FileViewerHelper.openInGoogleFilesOrViewer(context, outputFile)
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
                    lastExportedFile = outputFile
                    val msg = "比較明合成静止画(*.jpg)の生成が完了しました！\n保存先: ${outputFile.name}"
                    exportStatusMessage = msg
                    ttsManager.speak("比較明合成画像の書き出しが完了しました")
                    // 自動で Google Files / ビューアを開いて表示
                    com.example.astargazer.util.FileViewerHelper.openInGoogleFilesOrViewer(context, outputFile)
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
                        com.example.astargazer.util.FileViewerHelper.openInGoogleFilesOrViewer(context, lastExportedFile!!)
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
