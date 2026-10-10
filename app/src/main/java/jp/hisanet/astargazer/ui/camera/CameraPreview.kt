package jp.hisanet.astargazer.ui.camera

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)

        val bindCamera: () -> Unit = {
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                Log.i("Camera2Perf", "Preview bind start")
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview
                )
                // プレビューは撮影方向の確認（構図決め）目的なので、ピントは無限遠(0.0f)にしつつAEは自動(ON)で短時間露光・明るさを確保
                applyPreviewFocus(camera)
                Log.i("Camera2Perf", "Preview bind complete")
            } catch (e: Exception) {
                Log.e("CameraPreview", "Camera binding failed, retrying in 500ms...", e)
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        cameraProvider.unbindAll()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview
                        )
                        applyPreviewFocus(camera)
                        Log.i("Camera2Perf", "Preview bind retry complete")
                    } catch (retryEx: Exception) {
                        Log.e("CameraPreview", "Camera binding retry failed", retryEx)
                    }
                }, 500)
            }
        }

        cameraProviderFuture.addListener(bindCamera, executor)

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

@OptIn(ExperimentalCamera2Interop::class)
private fun applyPreviewFocus(camera: Camera) {
    CameraControlManager.setManualFocusOnly(
        camera = camera,
        focusDistance = 0.0f
    )
}
