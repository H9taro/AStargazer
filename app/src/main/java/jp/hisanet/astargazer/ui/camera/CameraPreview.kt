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
                // ライブプレビュー表示は撮影条件とは完全に分離し、星の視認性を最大化する (0.25秒露光 & 最大ISO & 無限遠)
                val usedMaxIso = applyStarVisibilitySettings(camera)
                Log.i("CameraPreview", "CameraPreview applied fixed star visibility settings: Max ISO=$usedMaxIso, Exposure=1.0s, Focus=0.0f")
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
                        val usedMaxIso = applyStarVisibilitySettings(camera)
                        Log.i("CameraPreview", "CameraPreview retry applied fixed star visibility settings: Max ISO=$usedMaxIso, Exposure=1.0s, Focus=0.0f")
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
private fun applyStarVisibilitySettings(camera: Camera): Int {
    return CameraControlManager.setPreviewStarVisibilityParams(camera)
}
