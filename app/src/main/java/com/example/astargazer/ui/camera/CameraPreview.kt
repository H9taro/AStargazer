package com.example.astargazer.ui.camera

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * AQUOS sense8 バックカメラセンサーの種類 (フロントカメラ除外)
 */
enum class CameraSensorType(val label: String, val shortLabel: String) {
    STANDARD("標準カメラ (メイン)", "標準"),
    ULTRA_WIDE("超広角カメラ (ウルトラワイド)", "超広角")
}

@OptIn(ExperimentalCamera2Interop::class)
object UltraWideCameraFilter {
    fun filter(cameraInfos: List<CameraInfo>): List<CameraInfo> {
        val wideInfos = cameraInfos.filter { info ->
            try {
                val camera2Info = Camera2CameraInfo.from(info)
                val focalLengths = camera2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                focalLengths != null && focalLengths.any { it < 3.2f }
            } catch (_: Exception) {
                false
            }
        }
        return wideInfos.ifEmpty { cameraInfos }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    sensorType: CameraSensorType = CameraSensorType.STANDARD,
    onCameraBound: (Camera, ImageCapture) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, sensorType) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()

                // カメラセレクターの構築 (標準 vs 超広角)
                val cameraSelector = if (sensorType == CameraSensorType.ULTRA_WIDE) {
                    CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .addCameraFilter { cameraInfos -> UltraWideCameraFilter.filter(cameraInfos) }
                        .build()
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                onCameraBound(camera, imageCapture)
            } catch (e: Exception) {
                Log.e("CameraPreview", "Camera binding failed for sensor: ${sensorType.name}", e)
            }
        }, executor)

        onDispose {
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                Log.e("CameraPreview", "Camera unbind failed", e)
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    ) { _ -> }
}
