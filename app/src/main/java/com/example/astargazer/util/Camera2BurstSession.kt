package com.example.astargazer.util

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.camera.lifecycle.ProcessCameraProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

data class Camera2CapturedFrame(
    val index: Int,
    val file: File,
    val exposureTimeNs: Long?,
    val iso: Int?
)

class Camera2BurstSession private constructor(
    private val cameraDevice: CameraDevice,
    private val captureSession: CameraCaptureSession,
    private val imageReader: ImageReader,
    private val cameraThread: HandlerThread,
    private val cameraHandler: Handler,
    private val requestedExposureNs: Long,
    private val requestedIso: Int,
    private val sensorOrientation: Int,
    private val location: android.location.Location?
) : AutoCloseable {
    private class BatchState(
        val count: Int,
        val firstIndex: Int,
        val outputFileForIndex: (Int) -> File
    ) {
        val imagesReceived = AtomicInteger(0)
        val imagesSaved = AtomicInteger(0)
        val sequenceCompleted = AtomicBoolean(false)
        val metadataByIndex = ConcurrentHashMap<Int, Pair<Long?, Int?>>()
        val filesByIndex = ConcurrentHashMap<Int, File>()
        val completion = CompletableDeferred<Unit>()
    }

    private class ContinuousState(
        val firstIndex: Int,
        val onFrameSaved: (Camera2CapturedFrame) -> Unit,
        val onFailure: (Exception) -> Unit
    ) {
        val imagesReceived = AtomicInteger(0)
        val resultsReceived = AtomicInteger(0)
        val framesProcessed = AtomicInteger(0)
        val sequenceCompleted = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val metadataByIndex = ConcurrentHashMap<Int, Pair<Long?, Int?>>()
        val filesByIndex = ConcurrentHashMap<Int, File>()
        val processingIndices: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        val completion = CompletableDeferred<Unit>()
    }

    companion object {
        const val MAX_BATCH_FRAMES = 3

        private suspend fun awaitCameraXRelease(context: Context) {
            try {
                val cameraProvider = withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
                        val future = ProcessCameraProvider.getInstance(context)
                        future.addListener(
                            {
                                try {
                                    continuation.resume(future.get())
                                } catch (e: Exception) {
                                    continuation.resumeWithException(e)
                                }
                            },
                            Runnable::run
                        )
                    }
                }
                withContext(Dispatchers.Main) {
                    cameraProvider.unbindAll()
                }
                delay(100.milliseconds)
                Log.i("Camera2Perf", "awaitCameraXRelease completed")
            } catch (e: Exception) {
                Log.w("Camera2Burst", "awaitCameraXRelease encountered exception", e)
            }
        }

        suspend fun open(
            context: Context,
            exposureSeconds: Double,
            iso: Int,
            location: android.location.Location? = null
        ): Camera2BurstSession = withContext(Dispatchers.IO) {
            val openStartTime = System.currentTimeMillis()
            Log.i("Camera2Perf", "Camera2BurstSession.open開始")

            awaitCameraXRelease(context)

            val cameraManager = context.getSystemService(CameraManager::class.java)
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)[CameraCharacteristics.LENS_FACING] ==
                    CameraCharacteristics.LENS_FACING_BACK
            } ?: error("背面カメラが見つかりません")

            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val capabilities = characteristics[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
                ?: intArrayOf()
            if (!capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
                Log.w("Camera2Burst", "MANUAL_SENSOR capability is not advertised; validating capture results")
            }

            val streamMap = characteristics[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
                ?: error("カメラのJPEG出力情報を取得できません")
            val jpegSize = streamMap.getOutputSizes(ImageFormat.JPEG)
                ?.maxByOrNull { it.width.toLong() * it.height }
                ?: error("JPEG出力サイズがありません")
            val requestedExposureNs = (exposureSeconds * 1_000_000_000.0).toLong()
            require(requestedExposureNs > 0L) { "露出時間は0より大きい値を指定してください" }
            val sensitivityRange = characteristics[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]
            val exposureRange = characteristics[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]
            val actualRequestIso = sensitivityRange?.let {
                iso.coerceIn(it.lower, it.upper)
            } ?: iso
            if (exposureRange != null && (requestedExposureNs !in exposureRange)) {
                Log.w(
                    "Camera2Burst",
                    "Requested exposure ${exposureSeconds}s is outside the advertised range " +
                        "${exposureRange.lower / 1_000_000_000.0}..${exposureRange.upper / 1_000_000_000.0}s; " +
                        "sending it unchanged and validating CaptureResult"
                )
            }

            val cameraThread = HandlerThread("Camera2BurstSession").apply { start() }
            val cameraHandler = Handler(cameraThread.looper)
            val cameraExecutor = Executor { command -> cameraHandler.post(command) }
            val imageReader = ImageReader.newInstance(
                jpegSize.width,
                jpegSize.height,
                ImageFormat.JPEG,
                MAX_BATCH_FRAMES
            )
            var cameraDevice: CameraDevice? = null
            var captureSession: CameraCaptureSession? = null
            try {
                cameraDevice = openCamera(cameraManager, cameraId, cameraExecutor)
                captureSession = createCaptureSession(cameraDevice, imageReader, cameraHandler)
                val session = Camera2BurstSession(
                    cameraDevice = cameraDevice,
                    captureSession = captureSession,
                    imageReader = imageReader,
                    cameraThread = cameraThread,
                    cameraHandler = cameraHandler,
                    requestedExposureNs = requestedExposureNs,
                    requestedIso = actualRequestIso,
                    sensorOrientation = characteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0,
                    location = location
                ).also { s ->
                    s.jpegSize = jpegSize
                    imageReader.setOnImageAvailableListener(s::onImageAvailable, cameraHandler)
                }
                val openDuration = System.currentTimeMillis() - openStartTime
                Log.i("Camera2Perf", "Camera2BurstSession.open終了")
                Log.i("Camera2Perf", "open=$openDuration ms")
                session
            } catch (exception: Exception) {
                captureSession?.close()
                cameraDevice?.close()
                imageReader.close()
                cameraThread.quitSafely()
                throw exception
            }
        }

        private suspend fun openCamera(
            cameraManager: CameraManager,
            cameraId: String,
            executor: Executor
        ): CameraDevice = suspendCancellableCoroutine { continuation ->
            try {
                cameraManager.openCamera(cameraId, executor, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (continuation.isActive) continuation.resume(camera) else camera.close()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("カメラ接続が切断されました"))
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("カメラを開けませんでした: error=$error"))
                        }
                    }
                })
            } catch (e: SecurityException) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }

        @Suppress("DEPRECATION")
        private suspend fun createCaptureSession(
            camera: CameraDevice,
            imageReader: ImageReader,
            handler: Handler
        ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
            camera.createCaptureSession(
                listOf(imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (continuation.isActive) continuation.resume(session) else session.close()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("Camera2セッションの構成に失敗しました"))
                        }
                    }
                },
                handler
            )
        }
    }

    private val fileWriterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isClosed = AtomicBoolean(false)
    private var jpegSize = android.util.Size(0, 0)
    private var nextFrameIndex = 1

    @Volatile
    private var activeBatch: BatchState? = null

    @Volatile
    private var activeContinuous: ContinuousState? = null

    fun startRepeatingCapture(
        onFrameSaved: (Camera2CapturedFrame) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        Log.i("Camera2Perf", "startRepeatingCapture開始")
        check(!isClosed.get()) { "Camera2セッションはすでに閉じています" }
        check(activeBatch == null && activeContinuous == null) { "別の撮影要求が実行中です" }

        val state = ContinuousState(nextFrameIndex, onFrameSaved, onFailure)
        activeContinuous = state
        val request = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(imageReader.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
            set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, requestedIso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, requestedExposureNs)
            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            setTag("continuous")
        }.build()

        try {
            captureSession.setRepeatingRequest(
                request,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val frameIndex = state.firstIndex + state.resultsReceived.getAndIncrement()
                        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                        state.metadataByIndex[frameIndex] = exposureTimeNs to iso
                        Log.i(
                            "Camera2Burst",
                            "Frame $frameIndex result: timestampNs=${result.get(CaptureResult.SENSOR_TIMESTAMP)}, " +
                                "exposure=${exposureTimeNs?.div(1_000_000_000.0) ?: "unavailable"}s, " +
                                "iso=${iso ?: "unavailable"}"
                        )
                        if (exposureTimeNs != requestedExposureNs || iso != requestedIso) {
                            Log.w(
                                "Camera2Burst",
                                "Frame $frameIndex differs from request: " +
                                    "requestedExposureNs=$requestedExposureNs, requestedIso=$requestedIso"
                            )
                        }
                        processContinuousFrame(state, frameIndex)
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        failContinuousCapture(
                            state,
                            IllegalStateException("Camera2 repeating capture failed: reason=${failure.reason}")
                        )
                    }

                    override fun onCaptureSequenceCompleted(
                        session: CameraCaptureSession,
                        sequenceId: Int,
                        frameNumber: Long
                    ) {
                        state.sequenceCompleted.set(true)
                        completeContinuousCaptureIfReady(state)
                    }

                    override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                        failContinuousCapture(state, IllegalStateException("Camera2 repeating capture was aborted"))
                    }
                },
                cameraHandler
            )
            Log.i(
                "Camera2Burst",
                "Started repeating JPEG capture: size=${jpegSize.width}x${jpegSize.height}, " +
                    "exposureNs=$requestedExposureNs, iso=$requestedIso"
            )
        } catch (exception: Exception) {
            activeContinuous = null
            throw exception
        }
    }

    suspend fun stopRepeatingCapture(): List<Camera2CapturedFrame> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val state = activeContinuous ?: return@withContext emptyList()
        try {
            captureSession.stopRepeating()
            val timeoutMs = maxOf(60_000L, requestedExposureNs / 1_000_000L + 30_000L)
            withTimeout(timeoutMs.milliseconds) { state.completion.await() }

            val frameCount = state.resultsReceived.get()
            val frames = (0 until frameCount).map { offset ->
                val index = state.firstIndex + offset
                val file = state.filesByIndex[index] ?: error("連写画像${index}が保存されませんでした")
                val (exposureTimeNs, iso) = state.metadataByIndex[index] ?: (null to null)
                Camera2CapturedFrame(index, file, exposureTimeNs, iso)
            }
            nextFrameIndex += frameCount
            val duration = System.currentTimeMillis() - startTime
            Log.i("Camera2Perf", "stopRepeatingCapture終了")
            Log.i("Camera2Perf", "stopRepeatingCapture=$duration ms")
            Log.i("Camera2Burst", "Repeating capture stopped: frames=$frameCount")
            frames
        } finally {
            activeContinuous = null
        }
    }

        suspend fun captureBatch(frameCount: Int): List<Camera2CapturedFrame> {
            val startTime = System.currentTimeMillis()
            Log.i("Camera2Perf", "captureBatch開始")
            try {
                val frames = captureBatchInternal(frameCount) { frameIndex ->
                    StorageHelper.createIntervalJpegFile(frameIndex)
                }
            val duration = System.currentTimeMillis() - startTime
            Log.i("Camera2Perf", "captureBatch終了")
            Log.i("Camera2Perf", "captureBatch=$duration ms")
            return frames
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.i("Camera2Perf", "captureBatch終了")
            Log.i("Camera2Perf", "captureBatch=$duration ms")
            throw e
        }
    }

    suspend fun captureSingleFrame(outputFile: File): Camera2CapturedFrame {
        val startTime = System.currentTimeMillis()
        Log.i("Camera2Perf", "captureSingleFrame開始")
        return try {
            val frame = captureBatchInternal(frameCount = 1) { outputFile }.single()
            val duration = System.currentTimeMillis() - startTime
            Log.i("Camera2Perf", "captureSingleFrame終了")
            Log.i("Camera2Perf", "captureSingleFrame=$duration ms")
            frame
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.i("Camera2Perf", "captureSingleFrame終了")
            Log.i("Camera2Perf", "captureSingleFrame=$duration ms")
            throw e
        }
    }

    private suspend fun captureBatchInternal(
        frameCount: Int,
        outputFileForIndex: (Int) -> File
    ): List<Camera2CapturedFrame> = withContext(Dispatchers.IO) {
        require(frameCount in 1..MAX_BATCH_FRAMES) { "バッチ枚数は1〜${MAX_BATCH_FRAMES}枚で指定してください" }
        check(!isClosed.get()) { "Camera2セッションはすでに閉じています" }
        check(activeBatch == null) { "前のCamera2バッチが完了していません" }

        val batch = BatchState(frameCount, nextFrameIndex, outputFileForIndex)
        activeBatch = batch

        fun completeIfReady() {
            if (batch.sequenceCompleted.get() &&
                batch.imagesSaved.get() == batch.count &&
                batch.metadataByIndex.size == batch.count
            ) {
                batch.completion.complete(Unit)
            }
        }

        try {
            val requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, requestedIso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, requestedExposureNs)
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            }
            val requests = (0 until frameCount).map { offset ->
                requestBuilder.setTag(batch.firstIndex + offset)
                requestBuilder.build()
            }

            Log.i(
                "Camera2Burst",
                "Submitting batch: frames=$frameCount, size=${jpegSize.width}x${jpegSize.height}, " +
                    "exposureNs=$requestedExposureNs, iso=$requestedIso"
            )
            captureSession.captureBurst(
                requests,
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val frameIndex = request.tag as? Int ?: return
                        val exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                        batch.metadataByIndex[frameIndex] = exposureTimeNs to iso
                        Log.i(
                            "Camera2Burst",
                            "Frame $frameIndex result: timestampNs=${result.get(CaptureResult.SENSOR_TIMESTAMP)}, " +
                                "exposure=${exposureTimeNs?.div(1_000_000_000.0) ?: "unavailable"}s, " +
                                "iso=${iso ?: "unavailable"}"
                        )
                        if (exposureTimeNs != requestedExposureNs || iso != requestedIso) {
                            Log.w(
                                "Camera2Burst",
                                "Frame $frameIndex differs from request: " +
                                    "requestedExposureNs=$requestedExposureNs, requestedIso=$requestedIso"
                            )
                        }
                        completeIfReady()
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        batch.completion.completeExceptionally(
                            IllegalStateException("Camera2 burst capture failed: reason=${failure.reason}")
                        )
                    }

                    override fun onCaptureSequenceCompleted(
                        session: CameraCaptureSession,
                        sequenceId: Int,
                        frameNumber: Long
                    ) {
                        batch.sequenceCompleted.set(true)
                        completeIfReady()
                    }

                    override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                        batch.completion.completeExceptionally(IllegalStateException("Camera2 burst was aborted"))
                    }
                },
                cameraHandler
            )

            val timeoutMs = maxOf(60_000L, requestedExposureNs / 1_000_000L * frameCount + 30_000L)
            withTimeout(timeoutMs.milliseconds) { batch.completion.await() }

            val frames = (0 until frameCount).map { offset ->
                val index = batch.firstIndex + offset
                val file = batch.filesByIndex[index] ?: error("連写画像${index}が保存されませんでした")
                val (exposureTimeNs, iso) = batch.metadataByIndex[index] ?: (null to null)
                ExifHelper.saveExifAttributes(
                    file = file,
                    iso = iso ?: requestedIso,
                    exposureSeconds = (exposureTimeNs ?: requestedExposureNs) / 1_000_000_000.0,
                    location = location
                )
                Camera2CapturedFrame(index, file, exposureTimeNs, iso)
            }
            nextFrameIndex += frameCount
            frames
        } finally {
            activeBatch = null
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        while (true) {
            val image = reader.acquireNextImage() ?: break
            val continuous = activeContinuous
            if (continuous != null) {
                val offset = continuous.imagesReceived.getAndIncrement()
                val frameIndex = continuous.firstIndex + offset
                val jpegBytes = try {
                    val buffer = image.planes[0].buffer
                    ByteArray(buffer.remaining()).also(buffer::get)
                } catch (exception: Exception) {
                    image.close()
                    failContinuousCapture(continuous, exception)
                    continue
                }
                image.close()
                val outputFile = StorageHelper.createIntervalJpegFile(frameIndex)
                fileWriterScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            FileOutputStream(outputFile).use { it.write(jpegBytes) }
                        }
                        continuous.filesByIndex[frameIndex] = outputFile
                        processContinuousFrame(continuous, frameIndex)
                    } catch (exception: Exception) {
                        failContinuousCapture(continuous, exception)
                    }
                }
                continue
            }

            val batch = activeBatch
            if (batch == null) {
                image.close()
                continue
            }

            val offset = batch.imagesReceived.getAndIncrement()
            if (offset >= batch.count) {
                image.close()
                continue
            }
            val frameIndex = batch.firstIndex + offset
            val jpegBytes = try {
                val buffer = image.planes[0].buffer
                ByteArray(buffer.remaining()).also(buffer::get)
            } catch (exception: Exception) {
                image.close()
                batch.completion.completeExceptionally(exception)
                continue
            }
            image.close()

            val outputFile = batch.outputFileForIndex(frameIndex)
            fileWriterScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        FileOutputStream(outputFile).use { it.write(jpegBytes) }
                    }
                    batch.filesByIndex[frameIndex] = outputFile
                    Log.i("Camera2Burst", "Saved frame $frameIndex: ${outputFile.absolutePath}")
                    batch.imagesSaved.incrementAndGet()
                    if (batch.sequenceCompleted.get() &&
                        batch.imagesSaved.get() == batch.count &&
                        batch.metadataByIndex.size == batch.count
                    ) {
                        batch.completion.complete(Unit)
                    }
                } catch (exception: Exception) {
                    batch.completion.completeExceptionally(exception)
                }
            }
        }
    }

    private fun processContinuousFrame(state: ContinuousState, frameIndex: Int) {
        val file = state.filesByIndex[frameIndex] ?: return
        val metadata = state.metadataByIndex[frameIndex] ?: return
        if (!state.processingIndices.add(frameIndex)) return

        fileWriterScope.launch {
            try {
                val exposureTimeNs = metadata.first
                val iso = metadata.second
                ExifHelper.saveExifAttributes(
                    file = file,
                    iso = iso ?: requestedIso,
                    exposureSeconds = (exposureTimeNs ?: requestedExposureNs) / 1_000_000_000.0,
                    location = location
                )
                state.framesProcessed.incrementAndGet()
                state.onFrameSaved(Camera2CapturedFrame(frameIndex, file, exposureTimeNs, iso))
                completeContinuousCaptureIfReady(state)
            } catch (exception: Exception) {
                failContinuousCapture(state, exception)
            }
        }
    }

    private fun completeContinuousCaptureIfReady(state: ContinuousState) {
        if (state.sequenceCompleted.get() &&
            state.resultsReceived.get() == state.filesByIndex.size &&
            state.resultsReceived.get() == state.framesProcessed.get()
        ) {
            state.completion.complete(Unit)
        }
    }

    private fun failContinuousCapture(state: ContinuousState, exception: Exception) {
        if (state.failed.compareAndSet(false, true)) {
            state.completion.completeExceptionally(exception)
            state.onFailure(exception)
        }
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) return
        activeBatch?.completion?.completeExceptionally(IllegalStateException("Camera2セッションが閉じられました"))
        activeContinuous?.completion?.completeExceptionally(IllegalStateException("Camera2セッションが閉じられました"))
        captureSession.close()
        cameraDevice.close()
        imageReader.close()
        fileWriterScope.cancel()
        cameraThread.quitSafely()
    }
}
