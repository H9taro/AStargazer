package jp.hisanet.astargazer.util

import android.content.Context
import android.util.Log
import android.view.OrientationEventListener

object DeviceOrientationMonitor {
    private const val TAG = "DeviceOrientationMon"

    private const val ORIENTATION_UNKNOWN_CONST = -1

    @Volatile
    private var currentOrientationDegrees: Int = 0 // 0, 90, 180, 270

    private var listener: OrientationEventListener? = null

    /**
     * OrientationEventListener の角度 (0..359) から本体の向き (0, 90, 180, 270度) を算出する
     */
    fun computeDeviceRotation(sensorDegrees: Int): Int {
        if (sensorDegrees == ORIENTATION_UNKNOWN_CONST) return 0
        return when (sensorDegrees) {
            in 45..134 -> 270
            in 135..224 -> 180
            in 225..314 -> 90
            else -> 0
        }
    }

    /**
     * カメラのセンサー向き (sensorOrientation) と本体の向き (deviceRotationDegrees: 0, 90, 180, 270度) に合わせて、
     * 撮影結果画像を自然な向き（プレビューと同じ0度）で保存するための Camera2 の JPEG_ORIENTATION を算出する。
     */
    fun computeJpegOrientation(sensorOrientation: Int, deviceRotationDegrees: Int): Int {
        return when (deviceRotationDegrees) {
            180, 270 -> (sensorOrientation + 180) % 360
            else -> sensorOrientation
        }
    }

    /**
     * 端末の物理傾き（OrientationEventListener）の監視を開始する
     */
    fun start(context: Context) {
        if (listener != null) return
        val appContext = context.applicationContext
        try {
            val newListener = object : OrientationEventListener(appContext) {
                override fun onOrientationChanged(orientation: Int) {
                    val deviceRotation = computeDeviceRotation(orientation)
                    if (currentOrientationDegrees != deviceRotation) {
                        currentOrientationDegrees = deviceRotation
                        try {
                            Log.d(TAG, "Device orientation changed: sensorDegrees=$orientation, deviceRotation=$deviceRotation")
                        } catch (_: Throwable) {}
                    }
                }
            }
            if (newListener.canDetectOrientation()) {
                newListener.enable()
                listener = newListener
                try {
                    Log.i(TAG, "DeviceOrientationMonitor enabled")
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            try {
                Log.w(TAG, "Failed to start DeviceOrientationMonitor", e)
            } catch (_: Throwable) {}
        }
    }

    /**
     * 監視を停止する
     */
    fun stop() {
        try {
            listener?.disable()
        } catch (_: Throwable) {}
        listener = null
    }

    /**
     * 現在の端末の物理的な向き（0, 90, 180, 270度）を取得する
     */
    fun getDeviceOrientation(): Int = currentOrientationDegrees

    /**
     * 現在の本体向きおよびセンサー向きに応じた CaptureRequest.JPEG_ORIENTATION を返す
     */
    fun getJpegOrientation(sensorOrientation: Int): Int = computeJpegOrientation(sensorOrientation, currentOrientationDegrees)
}
