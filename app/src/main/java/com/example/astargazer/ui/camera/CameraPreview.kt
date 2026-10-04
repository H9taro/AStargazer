package com.example.astargazer.ui.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
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

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    exposureTimeNs: Long,
    iso: Int,
    onCameraBound: (Camera, ImageCapture) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, exposureTimeNs, iso) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val imageCaptureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)

                val imageCaptureInterop = Camera2Interop.Extender(imageCaptureBuilder)
                imageCaptureInterop
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_OFF
                    )
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF
                    )
                    .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
                    .setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult
                        ) {
                            if (request.get(CaptureRequest.CONTROL_CAPTURE_INTENT) !=
                                CaptureRequest.CONTROL_CAPTURE_INTENT_STILL_CAPTURE
                            ) {
                                return
                            }

                            val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                            val sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                            val exposureSeconds = exposureTimeNs?.div(1_000_000_000.0)
                            Log.i(
                                "CaptureMetadata",
                                "Still capture result: frame=${result.frameNumber}, " +
                                    "timestampNs=${result.get(CaptureResult.SENSOR_TIMESTAMP)}, " +
                                    "exposure=${exposureSeconds ?: "unavailable"}s, " +
                                    "iso=${sensitivityIso ?: "unavailable"}"
                            )
                        }
                    })

                val imageCapture = imageCaptureBuilder.build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                onCameraBound(camera, imageCapture)
            } catch (e: Exception) {
                Log.e("CameraPreview", "Camera binding failed", e)
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
