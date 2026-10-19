package jp.hisanet.astargazer.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import jp.hisanet.astargazer.model.IntervalShootingState
import jp.hisanet.astargazer.util.Camera2BurstSession
import jp.hisanet.astargazer.util.IntervalNotificationManager
import jp.hisanet.astargazer.util.IntervalShootingManager
import jp.hisanet.astargazer.util.StorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

class IntervalShootingService : Service() {

    companion object {
        private const val TAG = "IntervalService"

        const val ACTION_START = "jp.hisanet.astargazer.action.START_INTERVAL_SHOOTING"
        const val ACTION_STOP = "jp.hisanet.astargazer.action.STOP_INTERVAL_SHOOTING"

        const val EXTRA_EXPOSURE_SECONDS = "extra_exposure_seconds"
        const val EXTRA_ISO = "extra_iso"
        const val EXTRA_LOCATION = "extra_location"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notificationManager: IntervalNotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var burstSession: Camera2BurstSession? = null

    private val isStopping = AtomicBoolean(false)
    private var timerJob: Job? = null
    private var captureJob: Job? = null

    @Volatile
    private var shotCount = 0
    @Volatile
    private var elapsedSeconds = 0
    @Volatile
    private var remainingShots = 0

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service started")
        notificationManager = IntervalNotificationManager(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val exposureSeconds = intent.getDoubleExtra(EXTRA_EXPOSURE_SECONDS, 4.0)
                val iso = intent.getIntExtra(EXTRA_ISO, 400)
                @Suppress("DEPRECATION")
                val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_LOCATION, Location::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_LOCATION)
                }
                startCaptureSession(exposureSeconds, iso, location)
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                stopCaptureSession()
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AStargazer:IntervalShootingWakeLock"
        ).apply {
            acquire(10 * 60 * 60 * 1000L) // 10時間タイムアウト
        }
        Log.i(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.i(TAG, "WakeLock released")
        }
        wakeLock = null
    }

    private fun startCaptureSession(
        exposureSeconds: Double,
        iso: Int,
        location: Location?
    ) {
        acquireWakeLock()

        val initialNotification = notificationManager.buildNotification(IntervalShootingState.Preparing)
        startForeground(
            IntervalNotificationManager.NOTIFICATION_ID,
            initialNotification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
        Log.i(TAG, "Foreground started")

        IntervalShootingManager.updateState(IntervalShootingState.Preparing)

        val initialStorageBytes = StorageHelper.getAvailableStorageBytes()
        val minAllowedStorageBytes = (initialStorageBytes * 0.5f).toLong()
        remainingShots = StorageHelper.calculateRemainingShots(initialStorageBytes, minAllowedStorageBytes)
        shotCount = 0
        elapsedSeconds = 0
        isStopping.set(false)

        // タイマー開始
        timerJob = serviceScope.launch {
            while (!isStopping.get()) {
                delay(1.seconds)
                elapsedSeconds++
                val currentState = IntervalShootingManager.state.value
                if (currentState is IntervalShootingState.Shooting) {
                    val updatedState = currentState.copy(elapsedSeconds = elapsedSeconds)
                    IntervalShootingManager.updateState(updatedState)
                    notificationManager.updateNotification(updatedState)
                }
            }
        }

        captureJob = serviceScope.launch {
            try {
                val session = Camera2BurstSession.open(
                    context = applicationContext,
                    exposureSeconds = exposureSeconds,
                    iso = iso,
                    location = location
                )
                burstSession = session
                Log.i(TAG, "Camera2 session opened")

                val shootingState = IntervalShootingState.Shooting(
                    shotCount = 0,
                    remainingShots = remainingShots,
                    elapsedSeconds = 0,
                    latestImagePath = null,
                    exposureSeconds = exposureSeconds,
                    iso = iso
                )
                IntervalShootingManager.updateState(shootingState)
                notificationManager.updateNotification(shootingState)

                session.startRepeatingCapture(
                    onFrameSaved = { frame ->
                        shotCount = maxOf(shotCount, frame.index)
                        remainingShots = (remainingShots - 1).coerceAtLeast(0)
                        val latestImagePath = frame.file.absolutePath
                        Log.i(TAG, "Frame saved: index=${frame.index}, path=$latestImagePath")

                        val newState = IntervalShootingState.Shooting(
                            shotCount = shotCount,
                            remainingShots = remainingShots,
                            elapsedSeconds = elapsedSeconds,
                            latestImagePath = latestImagePath,
                            exposureSeconds = exposureSeconds,
                            iso = iso
                        )
                        IntervalShootingManager.updateState(newState)
                        notificationManager.updateNotification(newState)

                        // 撮影可能枚数が10枚以下で自動停止
                        if (remainingShots <= 10) {
                            Log.i(TAG, "Remaining shots reached limit ($remainingShots <= 10). Stopping automatically.")
                            stopCaptureSession()
                        }
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "Repeating capture failed", exception)
                        val errorState = IntervalShootingState.Error(
                            exception.message ?: "撮影中にエラーが発生しました"
                        )
                        IntervalShootingManager.updateState(errorState)
                        notificationManager.updateNotification(errorState)
                        stopCaptureSession()
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open Camera2BurstSession", e)
                val errorState = IntervalShootingState.Error(
                    e.message ?: "カメラセッションのオープンに失敗しました"
                )
                IntervalShootingManager.updateState(errorState)
                notificationManager.updateNotification(errorState)
                stopCaptureSession()
            }
        }
    }

    private fun stopCaptureSession() {
        if (!isStopping.compareAndSet(false, true)) {
            Log.d(TAG, "stopCaptureSession called while already stopping. Ignored.")
            return
        }

        IntervalShootingManager.updateState(IntervalShootingState.Stopping)

        serviceScope.launch {
            try {
                timerJob?.cancel()
                burstSession?.let { session ->
                    try {
                        val frames = session.stopRepeatingCapture()
                        Log.i(TAG, "Repeating capture stopped. Captured ${frames.size} frames.")
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception during stopRepeatingCapture", e)
                    } finally {
                        session.close()
                        burstSession = null
                        Log.i(TAG, "Camera2BurstSession closed")
                    }
                }
            } finally {
                releaseWakeLock()
                IntervalShootingManager.updateState(IntervalShootingState.Idle)
                @Suppress("DEPRECATION")
                stopForeground(true)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {

        if (isStopping.compareAndSet(false, true)) {
            burstSession?.close()
            burstSession = null
            releaseWakeLock()
            IntervalShootingManager.updateState(IntervalShootingState.Idle)
        }
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }
}
