package jp.hisanet.astargazer.ui.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
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
            exposureSeconds <= 8.0 -> 1600
            exposureSeconds <= 15.0 -> 1600
            exposureSeconds <= 30.0 -> 1600
            else -> 800
        }
    }

    /**
     * ライブプレビュー専用設定（撮影条件とは完全に分離し、星の視認性を最大化する）:
     * - ISO: カメラデバイスがサポートする最大ISO感度 (SENSOR_INFO_SENSITIVITY_RANGE.upper)
     * - 露出時間: 0.25秒固定 (250_000_000L ナノ秒)
     * - AF: OFF (CONTROL_AF_MODE_OFF)
     * - フォーカス: 無限遠 (LENS_FOCUS_DISTANCE = 0.0f)
     *
     * @return プレビューに適用された最大ISO感度値
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun setPreviewStarVisibilityParams(camera: Camera): Int {
        val camera2CameraControl = Camera2CameraControl.from(camera.cameraControl)
        val camera2CameraInfo = Camera2CameraInfo.from(camera.cameraInfo)

        val sensitivityRange = camera2CameraInfo.getCameraCharacteristic(
            CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )
        val maxIso = sensitivityRange?.upper ?: 3200
        val fixedExposureNs = 250_000_000L // 0.25秒固定

        val optionsBuilder = CaptureRequestOptions.Builder()
            // AF OFF & 無限遠 (0.0f)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                0.0f
            )
            // AE OFF & マニュアル固定露光 (最大ISO & 1秒)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_SENSITIVITY,
                maxIso
            )
            .setCaptureRequestOption(
                CaptureRequest.SENSOR_EXPOSURE_TIME,
                fixedExposureNs
            )

        camera2CameraControl.setCaptureRequestOptions(optionsBuilder.build())

        Log.i(
            "CameraPreview",
            "Live Preview configured: Fixed Exposure=1.0s, Max ISO=$maxIso, Manual Infinity Focus=0.0f"
        )
        return maxIso
    }
}
