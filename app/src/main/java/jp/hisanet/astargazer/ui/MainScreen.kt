package jp.hisanet.astargazer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import jp.hisanet.astargazer.model.IntervalShootingState
import jp.hisanet.astargazer.ui.camera.CameraControlManager
import jp.hisanet.astargazer.ui.camera.CameraPreview
import jp.hisanet.astargazer.util.Camera2BurstSession
import jp.hisanet.astargazer.util.ExifHelper
import jp.hisanet.astargazer.util.FileViewerHelper
import jp.hisanet.astargazer.util.ImageContrastAnalyzer
import jp.hisanet.astargazer.util.IntervalShootingManager
import jp.hisanet.astargazer.util.LocationHelper
import jp.hisanet.astargazer.util.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

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
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
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
@Composable
private fun MainAppContent() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTab by rememberSaveable { mutableStateOf(MainMenuTab.SETUP) }

    val intervalState by IntervalShootingManager.state.collectAsState()
    val isIntervalActive = intervalState !is IntervalShootingState.Idle && intervalState !is IntervalShootingState.Error

    var isSetupCompleted by rememberSaveable { mutableStateOf(false) }
    var isIntervalCompleted by rememberSaveable { mutableStateOf(false) }

    // 前回の撮影画像ファイルまたはインターバル撮影セッションディレクトリがストレージに残っているかどうか
    val hasExistingIntervalFiles = StorageHelper.getIntervalSessions().isNotEmpty() || StorageHelper.getIntervalImageFiles().isNotEmpty()

    var capturedTestBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var latestIntervalBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    var isCamera2BurstActive by remember { mutableStateOf(false) }
    var camera2BurstStatus by remember { mutableStateOf("") }
    var shotCount by remember { mutableIntStateOf(0) }
    var remainingShots by remember { mutableIntStateOf(0) }
    var elapsedSeconds by remember { mutableIntStateOf(0) }

    var currentStep by rememberSaveable { mutableStateOf(WorkflowStep.POLARIS_ALIGNMENT_NOTICE) }

    var selectedExposureSeconds by rememberSaveable { mutableDoubleStateOf(4.0) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    var isTestShootingInterrupted by remember { mutableStateOf(false) }
    var activeTestSession by remember { mutableStateOf<Camera2BurstSession?>(null) }
    var multiTestResults by remember { mutableStateOf<List<Pair<Int, Bitmap>>>(emptyList()) }
    var selectedTestIso by rememberSaveable { mutableIntStateOf(400) }

    var statusMessage by remember {
        mutableStateOf("")
    }

    // 通知権限ランチャー (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val optimalIso = selectedTestIso
            val baseLocation = LocationHelper.getLastKnownLocation(context)
            IntervalShootingManager.startService(
                context = context,
                exposureSeconds = selectedExposureSeconds,
                iso = optimalIso,
                location = baseLocation
            )
        } else {
            statusMessage = "通知権限が拒否されたため、インターバル撮影を開始できません。"
        }
    }

    var previousIntervalState by remember { mutableStateOf<IntervalShootingState>(IntervalShootingState.Idle) }

    // サービスからの StateFlow 変化を UI に同期
    LaunchedEffect(intervalState) {
        val prevState = previousIntervalState
        previousIntervalState = intervalState

        when (val state = intervalState) {
            is IntervalShootingState.Shooting -> {
                shotCount = state.shotCount
                remainingShots = state.remainingShots
                elapsedSeconds = state.elapsedSeconds
                isIntervalCompleted = true
                if (state.latestImagePath != null) {
                    withContext(Dispatchers.IO) {
                        val bmp = BitmapFactory.decodeFile(state.latestImagePath)
                        withContext(Dispatchers.Main) {
                            if (bmp != null) {
                                latestIntervalBitmap?.recycle()
                                latestIntervalBitmap = bmp
                            }
                        }
                    }
                }
            }
            is IntervalShootingState.Idle -> {
                if (prevState is IntervalShootingState.Shooting || prevState is IntervalShootingState.Stopping) {
                    if (shotCount > 0 || isIntervalCompleted) {
                        isIntervalCompleted = true
                        selectedTab = MainMenuTab.SAVE
                    }
                }
            }
            is IntervalShootingState.Error -> {
                statusMessage = "インターバル撮影エラー: ${state.message}"
            }
            else -> {}
        }
    }

    // 設定キャンセル・リセット
    fun cancelSetup() {
        if (isIntervalActive) {
            IntervalShootingManager.stopService(context)
        }
        isProcessing = false
        isSetupCompleted = false
        isIntervalCompleted = false
        elapsedSeconds = 0
        currentStep = WorkflowStep.POLARIS_ALIGNMENT_NOTICE
        capturedTestBitmap = null
        latestIntervalBitmap?.recycle()
        latestIntervalBitmap = null

        statusMessage = ""
        selectedTab = MainMenuTab.SETUP
    }

    // インターバル撮影開始リクエスト (権限チェック込み)
    fun requestStartIntervalShooting() {
        latestIntervalBitmap?.recycle()
        latestIntervalBitmap = null
        shotCount = 0
        elapsedSeconds = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionCheck = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }

        val optimalIso = selectedTestIso
        val baseLocation = LocationHelper.getLastKnownLocation(context)
        IntervalShootingManager.startService(
            context = context,
            exposureSeconds = selectedExposureSeconds,
            iso = optimalIso,
            location = baseLocation
        )
    }

    fun finalizeSetup(targetIso: Int) {
        isProcessing = true
        statusMessage = "設定を保存中..."
        coroutineScope.launch {
            try {
                val bitmap = capturedTestBitmap ?: error("試写画像がありません")
                val finalBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)

                val score = withContext(Dispatchers.Default) {
                    ImageContrastAnalyzer.calculateContrastScore(finalBitmap)
                }
                val scoreFormatted = String.format(Locale.JAPAN, "%.1f", score)
                capturedTestBitmap = finalBitmap

                delay(600.milliseconds)

                statusMessage = "試写完了 (スコア: $scoreFormatted, ISO: $targetIso)。設定完了！"
                currentStep = WorkflowStep.SETUP_COMPLETED
                isSetupCompleted = true
                isCamera2BurstActive = false
                selectedTab = MainMenuTab.INTERVAL
            } catch (exception: Exception) {
                Log.e("MainScreen", "Setup completion failed", exception)
                statusMessage = "設定完了エラー: ${exception.message}"
            } finally {
                isProcessing = false
            }
        }
    }

    // ダークフレーム撮影実行（GPS情報付与）
    fun runDarkFrameShooting() {
        val targetIso = selectedTestIso
        val exposureSeconds = selectedExposureSeconds
        val exposureTimeNs = (exposureSeconds * 1_000_000_000.0).toLong()
        val currentLocation = LocationHelper.getLastKnownLocation(context)
        val darkFrameFile = StorageHelper.getDarkFrameFile(exposureSeconds, targetIso)
        val tempJpegFile = StorageHelper.getDarkFrameCaptureTempFile(context)
        isProcessing = true
        isCamera2BurstActive = true
        statusMessage =
            "レンズを覆ってください。ダークフレーム撮影中 (${formatExposureSeconds(exposureSeconds)}, ISO: $targetIso)..."

        coroutineScope.launch {
            var session: Camera2BurstSession? = null
            try {
                delay(500.milliseconds)
                session = Camera2BurstSession.open(
                    context = context,
                    exposureSeconds = exposureSeconds,
                    iso = targetIso,
                    location = currentLocation
                )
                activeTestSession = session
                val frame = session.captureSingleFrame(tempJpegFile)
                session.close()
                session = null
                activeTestSession = null

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

                    ExifHelper.saveExifAttributes(
                        file = darkFrameFile,
                        iso = frame.iso ?: targetIso,
                        exposureSeconds =
                            (frame.exposureTimeNs ?: exposureTimeNs) / 1_000_000_000.0
                    )
                }
                tempJpegFile.delete()
                FileViewerHelper.scanFile(context, darkFrameFile)

                statusMessage = "ダークフレーム撮影完了。レンズカバーを外してください。"
                delay(300.milliseconds)
                finalizeSetup(targetIso)
            } catch (exception: Exception) {
                Log.e("MainScreen", "Dark frame capture failed", exception)
                statusMessage = "ダークフレーム撮影エラー: ${exception.message}"
                isProcessing = false
                isCamera2BurstActive = false
            } finally {
                session?.close()
                activeTestSession = null
            }
        }
    }

    val onTriggerShutter: () -> Unit = {
        if (isProcessing) {
            when (currentStep) {
                WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST,
                WorkflowStep.TEST_RESULT_DISPLAY -> {
                    isTestShootingInterrupted = true
                    activeTestSession?.close()
                    activeTestSession = null
                    isProcessing = false
                    isCamera2BurstActive = false
                    statusMessage = "試写を中断しました。撮影できた画像を確認してください。"
                }
                WorkflowStep.DARK_FRAME_SHOOTING -> {
                    activeTestSession?.close()
                    activeTestSession = null
                    isProcessing = false
                    isCamera2BurstActive = false
                    currentStep = WorkflowStep.POLARIS_ALIGNMENT_NOTICE
                    statusMessage = "ダークフレーム撮影をキャンセルしました。再度試写を行ってください。"
                }
                else -> {
                    cancelSetup()
                }
            }
        } else {
            when (selectedTab) {
                MainMenuTab.SETUP -> {
                    when (currentStep) {
                        WorkflowStep.DARK_FRAME_NOTICE -> {
                            currentStep = WorkflowStep.DARK_FRAME_SHOOTING
                        }

                        WorkflowStep.DARK_FRAME_SHOOTING -> {
                            activeTestSession?.close()
                            activeTestSession = null
                            isProcessing = false
                            isCamera2BurstActive = false
                            currentStep = WorkflowStep.POLARIS_ALIGNMENT_NOTICE
                            statusMessage =
                                "ダークフレーム撮影をキャンセルしました。再度試写を行ってください。"
                        }

                        WorkflowStep.POLARIS_ALIGNMENT_NOTICE -> currentStep =
                            WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST

                        WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST,
                        WorkflowStep.TEST_RESULT_DISPLAY -> {
                            val targetIso = selectedTestIso
                            if (StorageHelper.hasValidDarkFrame(
                                    selectedExposureSeconds,
                                    targetIso
                                )
                            ) {
                                finalizeSetup(targetIso)
                            } else {
                                currentStep = WorkflowStep.DARK_FRAME_NOTICE
                                statusMessage =
                                    "この露出・ISO($targetIso)のダークフレームがありません。\nカメラのレンズを覆ってから、シャッターを押してください。"
                            }
                        }

                        WorkflowStep.SETUP_COMPLETED -> currentStep =
                            WorkflowStep.POLARIS_ALIGNMENT_NOTICE
                    }
                }

                MainMenuTab.INTERVAL -> {
                    if (isIntervalActive) {
                        IntervalShootingManager.stopService(context)
                        statusMessage =
                            "停止要求を受け付けました。撮影中のコマを保存して停止します。"
                        if (shotCount > 0) {
                            isIntervalCompleted = true
                            selectedTab = MainMenuTab.SAVE
                        }
                    } else {
                        requestStartIntervalShooting()
                    }
                }

                MainMenuTab.SAVE -> {}
            }
        }
    }

    // 試写と自動調整（マルチISO試写、最低ISOから最高ISOまで1段ずつ、途中中断対応）
    fun runTestShootingAndAutoAdjust() {
        val baseIso =
            CameraControlManager.calculateOptimalIsoForExposure(selectedExposureSeconds)
        isProcessing = true
        isCamera2BurstActive = true
        isTestShootingInterrupted = false
        statusMessage = "マルチISO試写を実行中...（シャッター押下で中断可能）"

        val currentLocation = LocationHelper.getLastKnownLocation(context)

        coroutineScope.launch {
            try {
                val cameraManager =
                    context.getSystemService(android.hardware.camera2.CameraManager::class.java)
                val cameraId = cameraManager?.cameraIdList?.firstOrNull { id ->
                    cameraManager.getCameraCharacteristics(id)[android.hardware.camera2.CameraCharacteristics.LENS_FACING] ==
                            android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                } ?: cameraManager?.cameraIdList?.firstOrNull()
                val characteristics = cameraId?.let { cameraManager?.getCameraCharacteristics(it) }
                val sensitivityRange =
                    characteristics?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val minIso = sensitivityRange?.lower ?: 100
                val maxIso = sensitivityRange?.upper ?: 6400

                val minTestIso = maxOf(baseIso, minIso)
                val list = mutableListOf<Int>()
                var currentIso = minTestIso
                while (currentIso <= maxIso) {
                    list.add(currentIso)
                    if (currentIso >= maxIso) break
                    val nextIso = currentIso * 2
                    if (nextIso > maxIso) {
                        list.add(maxIso)
                        break
                    }
                    currentIso = nextIso
                }
                val isos = list.distinct()

                val results = mutableListOf<Pair<Int, Bitmap>>()
                for (iso in isos) {
                    if (isTestShootingInterrupted) {
                        Log.i("MainScreen", "Test shooting interrupted by user at ISO $iso")
                        break
                    }
                    var session: Camera2BurstSession? = null
                    try {
                        session = Camera2BurstSession.open(
                            context = context,
                            exposureSeconds = selectedExposureSeconds,
                            iso = iso,
                            location = currentLocation
                        )
                        activeTestSession = session
                        if (isTestShootingInterrupted) {
                            session.close()
                            activeTestSession = null
                            break
                        }
                        val testFile = StorageHelper.getTestShootingFile(iso)
                        session.captureBatch(1) { testFile }.single()
                        val bmp = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(testFile.absolutePath)
                        } ?: continue
                        results.add(iso to bmp)

                        // 試写を1枚撮影するごとに撮影結果の画像を表示する
                        multiTestResults = results.toList()
                        selectedTestIso = iso
                        capturedTestBitmap = bmp
                        currentStep = WorkflowStep.TEST_RESULT_DISPLAY
                        statusMessage = "試写撮影中（${results.size}/${isos.size}枚）: ISO $iso 撮影完了。スワイプで切り替え、シャッターで確定または中断"

                        FileViewerHelper.scanFile(context, testFile)
                    } catch (e: Exception) {
                        if (isTestShootingInterrupted) {
                            Log.i("MainScreen", "Test shooting capture aborted by user")
                            break
                        } else {
                            throw e
                        }
                    } finally {
                        session?.close()
                        activeTestSession = null
                    }
                }

                if (results.isEmpty()) {
                    if (isTestShootingInterrupted) {
                        statusMessage = "試写を中断しました。撮影前設定に戻ります。"
                        currentStep = WorkflowStep.POLARIS_ALIGNMENT_NOTICE
                        return@launch
                    }
                    error("試写画像を取得できませんでした")
                }

                multiTestResults = results
                val initialResult = results.first()
                selectedTestIso = initialResult.first
                capturedTestBitmap = initialResult.second

                statusMessage =
                    "試写完了（${results.size}枚）。左右スワイプで画像を切り替え、シャッターで確定します。"
                currentStep = WorkflowStep.TEST_RESULT_DISPLAY
            } catch (exception: Exception) {
                Log.e("MainScreen", "Test capture failed", exception)
                statusMessage = "試写撮影エラー: ${exception.message}"
            } finally {
                isProcessing = false
                isCamera2BurstActive = false
                activeTestSession = null
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

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when (selectedTab) {
            MainMenuTab.SETUP -> {
                SetupTabContent(
                    isCamera2BurstActive = isCamera2BurstActive,
                    isIntervalActive = isIntervalActive,
                    currentStep = currentStep,
                    capturedTestBitmap = capturedTestBitmap,
                    selectedExposureSeconds = selectedExposureSeconds,
                    isDropdownExpanded = isDropdownExpanded,
                    isProcessing = isProcessing,
                    statusMessage = statusMessage,
                    multiTestResults = multiTestResults,
                    selectedTestIso = selectedTestIso,
                    onIsoSelect = { iso, bmp ->
                        selectedTestIso = iso
                        capturedTestBitmap = bmp
                    },
                    onExposureChange = { selectedExposureSeconds = it },
                    onDropdownToggle = { isDropdownExpanded = it },
                    onShutterClick = onTriggerShutter,
                    isLandscape = isLandscape,
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    isSetupCompleted = isSetupCompleted,
                    isIntervalCompleted = isIntervalCompleted,
                    hasExistingIntervalFiles = hasExistingIntervalFiles,
                    onCancelSetup = { cancelSetup() }
                )
            }

            MainMenuTab.INTERVAL -> {
                IntervalTabContent(
                    isIntervalActive = isIntervalActive,
                    isCamera2BurstActive = isCamera2BurstActive,
                    camera2BurstStatus = camera2BurstStatus,
                    shotCount = shotCount,
                    remainingShots = remainingShots,
                    elapsedSeconds = elapsedSeconds,
                    selectedExposureSeconds = selectedExposureSeconds,
                    latestIntervalBitmap = latestIntervalBitmap,
                    onTriggerShutter = onTriggerShutter,
                    isLandscape = isLandscape,
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    isSetupCompleted = isSetupCompleted,
                    isIntervalCompleted = isIntervalCompleted,
                    hasExistingIntervalFiles = hasExistingIntervalFiles,
                    onCancelSetup = { cancelSetup() }
                )
            }

            MainMenuTab.SAVE -> {
                SaveTabContent(
                    context = context,
                    coroutineScope = coroutineScope,
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    isSetupCompleted = isSetupCompleted,
                    isIntervalCompleted = isIntervalCompleted,
                    hasExistingIntervalFiles = hasExistingIntervalFiles,
                    onCancelSetup = { cancelSetup() }
                )
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenSwitchDropdown(
    selectedTab: MainMenuTab,
    onTabSelected: (MainMenuTab) -> Unit,
    isCamera2BurstActive: Boolean,
    isIntervalActive: Boolean,
    isSetupCompleted: Boolean,
    isIntervalCompleted: Boolean,
    hasExistingIntervalFiles: Boolean,
    onCancelSetup: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            color = Color(0xFF2C2C2C),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "画面", color = Color.LightGray, fontSize = 9.sp)
                    Text(
                        text = selectedTab.label,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        }

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF1E1E1E))
        ) {
            MainMenuTab.entries.forEach { tab ->
                val enabled = !isCamera2BurstActive && !isIntervalActive && when (tab) {
                    MainMenuTab.SETUP -> true
                    MainMenuTab.INTERVAL -> isSetupCompleted
                    MainMenuTab.SAVE -> true
                }

                DropdownMenuItem(
                    text = {
                        Text(
                            text = tab.label,
                            color = if (tab == selectedTab) Color(0xFF1E88E5) else if (enabled) Color.White else Color.Gray,
                            fontWeight = if (tab == selectedTab) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    enabled = enabled,
                    onClick = {
                        onTabSelected(tab)
                        expanded = false
                        if (tab == MainMenuTab.SETUP) {
                            onCancelSetup()
                        }
                    }
                )
            }
        }
    }
}

/**
 * クロップエリア枠線を同時に表示するガイドオーバーレイ（横画面時は「動画の上・下」ガイダンスなし、横向きアスペクト比に対応）
 */
@Composable
private fun PortraitCropGuidesOverlay(
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
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
                if (isLandscape) {
                    val guideWidth = width * scale
                    val guideHeight = (guideWidth * 9f) / 16f
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
                } else {
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
        }

        // 縦画面時のみ動画の上下方向インジケーターを表示（横画面では不要）
        if (!isLandscape) {
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
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = if (isLandscape) 16.dp else 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isLandscape) "📏 クロップガイド (4K / Full HD / HD)" else "📏 縦位置クロップガイド (4K / Full HD / HD)",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
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
    isIntervalActive: Boolean = false,
    currentStep: WorkflowStep,
    capturedTestBitmap: Bitmap?,
    selectedExposureSeconds: Double,
    isDropdownExpanded: Boolean,
    isProcessing: Boolean,
    statusMessage: String,
    multiTestResults: List<Pair<Int, Bitmap>>,
    selectedTestIso: Int,
    onIsoSelect: (Int, Bitmap) -> Unit,
    onExposureChange: (Double) -> Unit,
    onDropdownToggle: (Boolean) -> Unit,
    onShutterClick: () -> Unit,
    isLandscape: Boolean = false,
    selectedTab: MainMenuTab = MainMenuTab.SETUP,
    onTabSelected: (MainMenuTab) -> Unit = {},
    isSetupCompleted: Boolean = false,
    isIntervalCompleted: Boolean = false,
    hasExistingIntervalFiles: Boolean = false,
    onCancelSetup: () -> Unit = {}
) {
    val latestSelectedTestIso = rememberUpdatedState(selectedTestIso)

    val displayCapturedBitmap = remember(capturedTestBitmap, isLandscape) {
        if (capturedTestBitmap != null) {
            val rotation = if (isLandscape) -90f else 0f
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(
                capturedTestBitmap,
                0, 0,
                capturedTestBitmap.width, capturedTestBitmap.height,
                matrix,
                true
            )
        } else {
            null
        }
    }

    val previewContent: @Composable () -> Unit = {
        if (currentStep == WorkflowStep.TEST_RESULT_DISPLAY && displayCapturedBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(multiTestResults) {
                        var totalDrag = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { totalDrag = 0f },
                            onHorizontalDrag = { change, dragAmount ->
                                totalDrag += dragAmount
                                change.consume()
                            },
                            onDragEnd = {
                                if (multiTestResults.size > 1 &&
                                    (totalDrag > 60f || totalDrag < -60f)
                                ) {
                                    val currentIndex = multiTestResults
                                        .indexOfFirst { it.first == latestSelectedTestIso.value }
                                        .coerceAtLeast(0)
                                    val nextIndex = if (totalDrag > 0f) {
                                        (currentIndex - 1 + multiTestResults.size) % multiTestResults.size
                                    } else {
                                        (currentIndex + 1) % multiTestResults.size
                                    }
                                    val (iso, bmp) = multiTestResults[nextIndex]
                                    onIsoSelect(iso, bmp)
                                }
                            },
                            onDragCancel = { totalDrag = 0f }
                        )
                    }
            ) {
                Image(
                    bitmap = displayCapturedBitmap.asImageBitmap(),
                    contentDescription = "試写結果",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )

                if (multiTestResults.size > 1) {
                    val currentIndex = multiTestResults.indexOfFirst { it.first == selectedTestIso }
                        .let { if (it < 0) 0 else it }
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    ) {
                        Text(
                            text = "ISO: $selectedTestIso (${currentIndex + 1}/${multiTestResults.size})",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        } else if (isCamera2BurstActive || isIntervalActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isIntervalActive) "インターバル撮影中...\n(バックグラウンドで継続中)" else statusMessage,
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
            PortraitCropGuidesOverlay(isLandscape = isLandscape)
        }
    }

    val shutterButtonContent: @Composable () -> Unit = {
        Button(
            onClick = onShutterClick,
            enabled = !isCamera2BurstActive || currentStep == WorkflowStep.POLARIS_TEST_SHOOTING_ADJUST || currentStep == WorkflowStep.TEST_RESULT_DISPLAY || currentStep == WorkflowStep.DARK_FRAME_SHOOTING,
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

    val headerContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            val isChangeable =
                currentStep == WorkflowStep.DARK_FRAME_NOTICE || currentStep == WorkflowStep.POLARIS_ALIGNMENT_NOTICE

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
                    label = { Text("露出", color = Color.LightGray, fontSize = 9.sp) },
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
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = isDropdownExpanded && isChangeable,
                    onDismissRequest = { onDropdownToggle(false) }
                ) {
                    EXPOSURE_TIMES_SECONDS.forEach { seconds ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    formatExposureSeconds(seconds),
                                    color = Color.White
                                )
                            },
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

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                previewContent()
            }

            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF121212))
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ScreenSwitchDropdown(
                        selectedTab = selectedTab,
                        onTabSelected = onTabSelected,
                        isCamera2BurstActive = isCamera2BurstActive,
                        isIntervalActive = false,
                        isSetupCompleted = isSetupCompleted,
                        isIntervalCompleted = isIntervalCompleted,
                        hasExistingIntervalFiles = hasExistingIntervalFiles,
                        onCancelSetup = onCancelSetup
                    )
                    headerContent()

                    if (currentStep != WorkflowStep.POLARIS_ALIGNMENT_NOTICE && statusMessage.isNotEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.75f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = statusMessage,
                                color = Color.White,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    shutterButtonContent()
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            previewContent()
            PortraitCropGuidesOverlay(isLandscape = false)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ScreenSwitchDropdown(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    isCamera2BurstActive = isCamera2BurstActive,
                    isIntervalActive = false,
                    isSetupCompleted = isSetupCompleted,
                    isIntervalCompleted = isIntervalCompleted,
                    hasExistingIntervalFiles = hasExistingIntervalFiles,
                    onCancelSetup = onCancelSetup
                )
                headerContent()
            }

            if (currentStep != WorkflowStep.POLARIS_ALIGNMENT_NOTICE && statusMessage.isNotEmpty()) {
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
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                shutterButtonContent()
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
    latestIntervalBitmap: Bitmap?,
    onTriggerShutter: () -> Unit,
    isLandscape: Boolean = false,
    selectedTab: MainMenuTab = MainMenuTab.INTERVAL,
    onTabSelected: (MainMenuTab) -> Unit = {},
    isSetupCompleted: Boolean = false,
    isIntervalCompleted: Boolean = false,
    hasExistingIntervalFiles: Boolean = false,
    onCancelSetup: () -> Unit = {}
) {
    val displayIntervalBitmap = remember(latestIntervalBitmap, isLandscape) {
        if (latestIntervalBitmap != null) {
            val rotation = if (isLandscape) -90f else 0f
            val matrix = Matrix().apply { postRotate(rotation) }
            Bitmap.createBitmap(
                latestIntervalBitmap,
                0, 0,
                latestIntervalBitmap.width, latestIntervalBitmap.height,
                matrix,
                true
            )
        } else {
            null
        }
    }

    val previewContent: @Composable () -> Unit = {
        if (displayIntervalBitmap != null) {
            Image(
                bitmap = displayIntervalBitmap.asImageBitmap(),
                contentDescription = "直近のインターバル撮影画像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else if (isCamera2BurstActive || isIntervalActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isIntervalActive) "インターバル撮影中...\n(バックグラウンドで継続中)" else camera2BurstStatus,
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
            PortraitCropGuidesOverlay(isLandscape = isLandscape)
        }
    }

    val headerContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "露出: ${formatExposureSeconds(selectedExposureSeconds)}",
                    color = Color.White,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "撮影: ${shotCount}コマ (${elapsedSeconds}秒)",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "残り: 約${remainingShots}枚",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                )
            }
        }
    }

    val shutterButtonContent: @Composable () -> Unit = {
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

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                previewContent()

                if (latestIntervalBitmap != null) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 16.dp)
                    ) {
                        Text(
                            text = "📷 撮影確認: ${shotCount}コマ目",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF121212))
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
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
                    headerContent()
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    shutterButtonContent()
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            previewContent()

            if (latestIntervalBitmap != null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 90.dp)
                ) {
                    Text(
                        text = "📷 撮影確認: ${shotCount}コマ目",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
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
                headerContent()
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                shutterButtonContent()
            }
        }
    }
}




