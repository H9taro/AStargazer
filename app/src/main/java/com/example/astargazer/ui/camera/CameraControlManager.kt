package com.example.astargazer.ui.camera

import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera

@ExperimentalCamera2Interop
object CameraControlManager {

    /**
     * 選択された露出時間(秒)から適正なISO感度を自動算出
     * 露出時間が長ければISO感度を下げ、ノイズを抑制しつつ露出(EV)を適正に保つ
     */
    fun calculateOptimalIsoForExposure(exposureSeconds: Double): Int {
        return when {
            exposureSeconds <= 0.25 -> 3200
            exposureSeconds <= 0.5 -> 3200
            exposureSeconds <= 1.0 -> 3200
            exposureSeconds <= 2.0 -> 3200
            exposureSeconds <= 4.0 -> 1600
            exposureSeconds <= 8.0 -> 800
            exposureSeconds <= 15.0 -> 800
            exposureSeconds <= 30.0 -> 400
            else -> 200
        }
    }

    /**
     * カメラのピント(Focus Distance)とISO感度、露出時間を手動設定
     * @param camera CameraXのCameraインスタンス
     * @param focusDistance 0.0f = 無限遠(Far), 値が大きくなるほど至近距離(Near)
     * @param iso ISO感度 (例: 800, 1600, 3200 など)
     * @param exposureTimeNs 露出時間(ナノ秒)。例: 4秒 = 4_000_000_000L
     */
    fun setManualFocusAndExposure(
        camera: Camera,
        focusDistance: Float = 0.0f,
        iso: Int = 1600,
        exposureTimeNs: Long? = null,
    ) {
        val camera2CameraControl = Camera2CameraControl.from(camera.cameraControl)

        val optionsBuilder = CaptureRequestOptions.Builder()
            // マニュアルフォーカス設定 (0.0f = 無限遠)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                focusDistance
            )
            // マニュアル露出・ISO感度設定
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                iso
            )

        exposureTimeNs?.let { timeNs ->
            optionsBuilder.setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                timeNs
            )
        }

        camera2CameraControl.setCaptureRequestOptions(optionsBuilder.build())

        Log.d("CameraControlManager", "Manual params set: FocusDist=$focusDistance, ISO=$iso, ExposureNs=$exposureTimeNs")
    }

    /**
     * 自動設定(AE/AF)へリセット
     */
    fun resetToAuto(camera: Camera) {
        val camera2CameraControl = Camera2CameraControl.from(camera.cameraControl)

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            .build()

        camera2CameraControl.setCaptureRequestOptions(options)

        Log.d("CameraControlManager", "Camera reset to Auto mode")
    }
}
