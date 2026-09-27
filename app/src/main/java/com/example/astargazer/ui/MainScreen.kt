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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.example.astargazer.util.ImageContrastAnalyzer
import com.example.astargazer.util.StorageHelper
import com.example.astargazer.util.rememberTtsManager
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 露出時間の選択肢（秒数）
 */
val EXPOSURE_TIMES_SECONDS = listOf(1, 2, 4, 8, 15, 30)

/**
 * 撮影ワークフローの各ステップ
 */
enum class WorkflowStep {
    EXPOSURE_SETTING,               // 1. 露出時間設定
    POLARIS_ALIGNMENT_NOTICE,       // 2. 開始通知（北極星合わせ案内）
    POLARIS_TEST_SHOOTING_ADJUST,   // 3. 試写と自動調整
    TEST_RESULT_DISPLAY,            // 4. 試写結果表示
    DIRECTION_CONFIRM_NOTICE,       // 5. 撮影方向確定案内
    DARK_FRAME_NOTICE,              // 6a. ダークフレーム撮影案内（カバー装着指示）
    DARK_FRAME_SHOOTING,            // 6b. ダークフレーム撮影実行
    INTERVAL_SHOOTING_READY         // 7. インターバル撮影準備完了
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
        // カメラプレビュー & 操作UI
        CameraContent()
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
private fun CameraContent() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val ttsManager = rememberTtsManager()

    // カメラ及び UseCase 保持
    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var imageCaptureInstance by remember { mutableStateOf<ImageCapture?>(null) }

    // 試写キャプチャ画像と調整状態
    var capturedTestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    // 現在のワークフローステップ
    var currentStep by remember { mutableStateOf(WorkflowStep.EXPOSURE_SETTING) }

    // 選択された露出時間 (デフォルト 4秒)
    var selectedExposureSeconds by remember { mutableIntStateOf(4) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    // 現在のステータスメッセージ
    var statusMessage by remember {
        mutableStateOf("露出時間を選択し、開始ボタンを押してください。")
    }

    // ステップ3: 試写と自動調整の実行関数
    fun runTestShootingAndAutoAdjust() {
        val camera = cameraInstance ?: run {
            statusMessage = "カメラの準備ができていません。"
            return
        }
        val imageCapture = imageCaptureInstance ?: run {
            statusMessage = "キャプチャ機能の準備ができていません。"
            return
        }

        isProcessing = true
        statusMessage = "試写を実行中: ピント(無限遠) & ISO(1600) 自動調整..."

        // 1. マニュアルフォーカス(無限遠: 0.0f) と ISO感度(1600) を設定
        CameraControlManager.setManualFocusAndExposure(
            camera = camera,
            focusDistance = 0.0f,
            iso = 1600,
            exposureTimeNs = selectedExposureSeconds * 1_000_000_000L
        )

        // 2. 試写撮影実行
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
                            statusMessage = "試写調整完了 (コントラストスコア: $scoreFormatted)\n画角を確認してください。"

                            // 自動調整成功後、ステップ4（試写結果表示）へ遷移
                            currentStep = WorkflowStep.TEST_RESULT_DISPLAY
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

    // ステップ6: ダークフレーム撮影の実行関数
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
                    currentStep = WorkflowStep.INTERVAL_SHOOTING_READY
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("MainScreen", "Dark frame capture failed", exception)
                    isProcessing = false
                    statusMessage = "ダークフレーム撮影エラー: ${exception.message}"
                }
            }
        )
    }

    // ステップ変更時の処理（音声読み上げなど）
    fun updateStep(newStep: WorkflowStep) {
        currentStep = newStep
        when (newStep) {
            WorkflowStep.EXPOSURE_SETTING -> {
                statusMessage = "露出時間を選択し、開始ボタンを押してください。"
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
                // ステップ5: 音声応答＆案内
                val message = "撮影したい方向を決めて、シャッターを押してください"
                statusMessage = message
                ttsManager.speak(message)
            }
            WorkflowStep.DARK_FRAME_NOTICE -> {
                // ステップ6a: ダークフレーム撮影前の案内
                val message = "レンズを覆って、シャッターを押してください"
                statusMessage = "ダークフレーム撮影準備: レンズ（カメラ）を覆った状態でシャッターを押してください。"
                ttsManager.speak(message)
            }
            WorkflowStep.DARK_FRAME_SHOOTING -> {
                // ステップ6b: ダークフレーム撮影実行
                runDarkFrameShooting()
            }
            WorkflowStep.INTERVAL_SHOOTING_READY -> {
                statusMessage = "インターバル撮影の準備ができました。シャッターを押すと開始します。"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // バックグラウンド：CameraX プレビュー または 試写画像プレビュー
        if (currentStep == WorkflowStep.TEST_RESULT_DISPLAY && capturedTestBitmap != null) {
            // 試写結果画像を表示 (ステップ4)
            Image(
                bitmap = capturedTestBitmap!!.asImageBitmap(),
                contentDescription = "試写結果",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // リアルタイムカメラプレビュー
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onCameraBound = { camera, imageCapture ->
                    cameraInstance = camera
                    imageCaptureInstance = imageCapture
                }
            )
        }

        // 上部コントロールパネル (露出時間設定等)
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
                Text(
                    text = "AStargazer",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                // 露出時間ドロップダウン
                ExposedDropdownMenuBox(
                    expanded = isDropdownExpanded,
                    onExpandedChange = { isDropdownExpanded = !isDropdownExpanded }
                ) {
                    OutlinedTextField(
                        value = "${selectedExposureSeconds}秒",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("露出時間", color = Color.LightGray, fontSize = 12.sp) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDropdownExpanded)
                        },
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
                            .width(130.dp)
                    )

                    ExposedDropdownMenu(
                        expanded = isDropdownExpanded,
                        onDismissRequest = { isDropdownExpanded = false }
                    ) {
                        EXPOSURE_TIMES_SECONDS.forEach { seconds ->
                            DropdownMenuItem(
                                text = { Text("${seconds}秒", color = Color.White) },
                                onClick = {
                                    selectedExposureSeconds = seconds
                                    isDropdownExpanded = false
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
                        WorkflowStep.EXPOSURE_SETTING -> {
                            updateStep(WorkflowStep.POLARIS_ALIGNMENT_NOTICE)
                        }
                        WorkflowStep.POLARIS_ALIGNMENT_NOTICE -> {
                            // シャッター押下後、北極星付近の試写と自動調整を開始
                            updateStep(WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST)
                        }
                        WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST -> {
                            // 調整中のため何もしない
                        }
                        WorkflowStep.TEST_RESULT_DISPLAY -> {
                            // 試写確認完了 -> ステップ5 (撮影方向確定案内) へ進行
                            updateStep(WorkflowStep.DIRECTION_CONFIRM_NOTICE)
                        }
                        WorkflowStep.DIRECTION_CONFIRM_NOTICE -> {
                            // ステップ5のシャッター押下 -> ステップ6a (ダークフレーム案内) へ進行
                            updateStep(WorkflowStep.DARK_FRAME_NOTICE)
                        }
                        WorkflowStep.DARK_FRAME_NOTICE -> {
                            // ステップ6aのシャッター押下 -> ステップ6b (ダークフレーム撮影実行) へ進行
                            updateStep(WorkflowStep.DARK_FRAME_SHOOTING)
                        }
                        WorkflowStep.DARK_FRAME_SHOOTING, WorkflowStep.INTERVAL_SHOOTING_READY -> {
                            // インターバル撮影開始前準備状態
                            updateStep(WorkflowStep.INTERVAL_SHOOTING_READY)
                        }
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
