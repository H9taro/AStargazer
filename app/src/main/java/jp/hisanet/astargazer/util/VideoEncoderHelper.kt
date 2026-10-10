package jp.hisanet.astargazer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

object VideoEncoderHelper {

    /**
     * 静止画ファイル群から選択された解像度（HD, Full HD, 4K）の9:16クロップを適用して MP4 タイムラプス動画を生成する
     * テレビ等の視聴用に、縦位置画像を時計回りに90度回転させて横長（ランドスケープ）動画として出力する
     * 左下に「解像度 - 撮影日時」、右下にアプリ名 "AStargazer" のテロップを正確な位置に焼き込む
     */
    fun createTimelapseVideo(
        imageFiles: List<File>,
        outputFile: File,
        darkFrameFile: File? = null,
        resolutionLabel: String = "Full HD (1080p)",
        frameRate: Int = 30,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        if (imageFiles.isEmpty()) return false

        // 出力フォルダが存在しない場合は作成
        outputFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

        // 失敗時に古い0バイトファイルが残らないよう事前削除
        if (outputFile.exists()) outputFile.delete()

        try {
            // ダークフレーム画像の読み込み
            val darkBitmap = if (darkFrameFile != null && darkFrameFile.exists()) {
                BitmapFactory.decodeFile(darkFrameFile.absolutePath)
            } else {
                null
            }

            // 選択された解像度に応じたターゲット解像度、クロップスケール、ビットレートを厳密に決定
            val targetWidth: Int
            val targetHeight: Int
            val cropScale: Float
            val bitRate: Int
            val shortResName: String

            when (resolutionLabel.trim()) {
                "HD (720p)" -> {
                    targetWidth = 1280
                    targetHeight = 720
                    cropScale = 0.60f
                    bitRate = 3_000_000 // 3 Mbps
                    shortResName = "HD"
                }
                "4K" -> {
                    targetWidth = 3840
                    targetHeight = 2160
                    cropScale = 0.90f
                    bitRate = 15_000_000 // 15 Mbps
                    shortResName = "4K"
                }
                else -> { // Full HD (1080p)
                    targetWidth = 1920
                    targetHeight = 1080
                    cropScale = 0.75f
                    bitRate = 6_000_000 // 6 Mbps
                    shortResName = "Full HD"
                }
            }

            Log.d("VideoEncoder", "Timelapse Config: $resolutionLabel -> ${targetWidth}x${targetHeight}, Bitrate: $bitRate")

            val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mimeType, targetWidth, targetHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val encoder = MediaCodec.createEncoderByType(mimeType)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply {
                setOrientationHint(0)
            }
            var trackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val frameDurationUs = 1_000_000L / frameRate

            val dstRect = Rect(0, 0, targetWidth, targetHeight)
            val rotateMatrix = Matrix().apply { postRotate(90f) } // 時計回り90度回転

            for ((index, file) in imageFiles.withIndex()) {
                val rawBitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val subtractedBitmap = ImageCompositor.subtractDarkFrame(rawBitmap, darkBitmap)
                if (rawBitmap != subtractedBitmap) rawBitmap.recycle()

                // 1. プレビューの SaveCropGuideOverlay と完全に一致する 9:16 クロップ計算
                val srcWidth = subtractedBitmap.width
                val srcHeight = subtractedBitmap.height
                val cropHeight = (srcHeight * cropScale).toInt()
                val cropWidth = (cropHeight * 9) / 16
                val cropLeft = (srcWidth - cropWidth) / 2
                val cropTop = (srcHeight - cropHeight) / 2

                val croppedBitmap = Bitmap.createBitmap(
                    subtractedBitmap,
                    cropLeft.coerceAtLeast(0),
                    cropTop.coerceAtLeast(0),
                    cropWidth.coerceAtMost(srcWidth),
                    cropHeight.coerceAtMost(srcHeight)
                )
                if (subtractedBitmap != croppedBitmap) subtractedBitmap.recycle()

                // 2. 回転（クロップ画像を横長に変換）
                val rotatedBitmap = Bitmap.createBitmap(
                    croppedBitmap,
                    0, 0,
                    croppedBitmap.width, croppedBitmap.height,
                    rotateMatrix,
                    true
                )
                if (croppedBitmap != rotatedBitmap) croppedBitmap.recycle()

                // 3. 作業用 Mutable Bitmap を作成してテロップ（ウォーターマーク）を焼き込む
                val frameWithText = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, true)
                rotatedBitmap.recycle()

                val canvas = Canvas(frameWithText)
                val bmpWidth = frameWithText.width
                val bmpHeight = frameWithText.height

                // 解像度に応じたフォントサイズとパディングを実 Bitmap のサイズを基準に計算
                val textSize = (bmpHeight.toFloat() / 28f).coerceAtLeast(28f)
                val padding = bmpWidth * 0.025f // 左右上下に 2.5% のマージン

                val paint = Paint().apply {
                    color = Color.WHITE
                    this.textSize = textSize
                    isAntiAlias = true
                    typeface = Typeface.DEFAULT_BOLD
                    setShadowLayer(6f, 2f, 2f, Color.BLACK)
                }

                val dateTimeStr = ExifHelper.getDateTime(file)
                val leftText = "$shortResName - $dateTimeStr"
                val rightText = "AStargazer"

                // 左下：解像度 - 日時
                val leftY = bmpHeight - padding
                canvas.drawText(leftText, padding, leftY, paint)

                // 右下：アプリ名 (実 Bitmap の幅から文字幅とパディングを引いた正確な右端位置)
                val rightTextWidth = paint.measureText(rightText)
                val rightX = (bmpWidth - rightTextWidth - padding).coerceAtLeast(padding)
                canvas.drawText(rightText, rightX, leftY, paint)

                // 4. Surface への描画
                val surfaceCanvas: Canvas = inputSurface.lockCanvas(null)
                surfaceCanvas.drawColor(Color.BLACK)
                val srcRect = Rect(0, 0, bmpWidth, bmpHeight)
                surfaceCanvas.drawBitmap(frameWithText, srcRect, dstRect, null)
                inputSurface.unlockCanvasAndPost(surfaceCanvas)
                frameWithText.recycle()

                // エンコーダーバッファ読み出し＆Muxer書き込み
                var draining = true
                while (draining) {
                    val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                    if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        draining = false
                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (encoderStatus >= 0) {
                        val encodedData = encoder.getOutputBuffer(encoderStatus)
                        if (encodedData != null) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size != 0 && muxerStarted) {
                                bufferInfo.presentationTimeUs = index * frameDurationUs
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(encoderStatus, false)
                        }
                    }
                }

                onProgress((index + 1).toFloat() / imageFiles.size)
            }

            darkBitmap?.recycle()

            encoder.signalEndOfInputStream()

            var eosReached = false
            while (!eosReached) {
                val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    eosReached = true
                } else if (encoderStatus >= 0) {
                    val encodedData = encoder.getOutputBuffer(encoderStatus)
                    if (encodedData != null && bufferInfo.size != 0 && muxerStarted) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(encoderStatus, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        eosReached = true
                    }
                }
            }

            encoder.stop()
            encoder.release()

            if (muxerStarted) {
                muxer.stop()
                muxer.release()
            }

            Log.d("VideoEncoder", "Landscape timelapse video created at ${outputFile.absolutePath}")
            return outputFile.exists() && outputFile.length() > 0
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Failed to create timelapse video", e)
            if (outputFile.exists()) outputFile.delete()
            return false
        }
    }
}
