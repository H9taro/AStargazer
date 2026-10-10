package jp.hisanet.astargazer.ui.camera

import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import com.google.common.util.concurrent.ListenableFuture

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
     * ピント(Focus Distance)のみを手動（無限遠など）に設定し、露出（AE）は自動（ON）のままにする（ライブプレビュー用）
     */
    fun setManualFocusOnly(
        camera: Camera,
        focusDistance: Float = 0.0f
    ): ListenableFuture<Void?> {
        val camera2CameraControl = Camera2CameraControl.from(camera.cameraControl)

        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF
            )
            .setCaptureRequestOption(
                CaptureRequest.LENS_FOCUS_DISTANCE,
                focusDistance
            )
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            .build()

        return camera2CameraControl.setCaptureRequestOptions(options)
    }

}
