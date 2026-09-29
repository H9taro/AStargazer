package com.example.astargazer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

object VideoEncoderHelper {

    /**
     * 静止画ファイル群から MP4 タイムラプス動画を生成する
     * @param imageFiles ソース静止画ファイルリスト
     * @param outputFile 出力先 MP4 ファイル
     * @param frameRate フレームレート (fps, 例: 30)
     * @param onProgress 進捗コールバック (0.0 ~ 1.0)
     * @return 生成成功の可否
     */
    fun createTimelapseVideo(
        imageFiles: List<File>,
        outputFile: File,
        frameRate: Int = 30,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        if (imageFiles.isEmpty()) return false

        try {
            val firstBitmap = BitmapFactory.decodeFile(imageFiles[0].absolutePath) ?: return false
            // H.264 エンコード規格上、幅と高さは16の倍数(または2の倍数)が推奨されます
            val width = (firstBitmap.width / 16) * 16
            val height = (firstBitmap.height / 16) * 16
            firstBitmap.recycle()

            val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000) // 8 Mbps
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1秒ごとにKeyFrame
            }

            val encoder = MediaCodec.createEncoderByType(mimeType)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val frameDurationUs = 1_000_000L / frameRate

            for ((index, file) in imageFiles.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if (scaledBitmap != bitmap) bitmap.recycle()

                // Surface への描画
                val canvas: Canvas = inputSurface.lockCanvas(null)
                canvas.drawBitmap(scaledBitmap, 0f, 0f, null)
                inputSurface.unlockCanvasAndPost(canvas)
                scaledBitmap.recycle()

                // エンコーダーの出力バッファを処理
                var draining = true
                while (draining) {
                    val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                    if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        draining = false
                    } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) {
                            Log.e("VideoEncoder", "Format changed twice")
                        } else {
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
                            if (bufferInfo.size != 0) {
                                if (!muxerStarted) {
                                    Log.e("VideoEncoder", "Muxer not started yet")
                                } else {
                                    bufferInfo.presentationTimeUs = index * frameDurationUs
                                    encodedData.position(bufferInfo.offset)
                                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                                }
                            }
                            encoder.releaseOutputBuffer(encoderStatus, false)
                        }
                    }
                }

                onProgress((index + 1).toFloat() / imageFiles.size)
            }

            // ストリームの終了通知 (EOS)
            encoder.signalEndOfInputStream()
            encoder.stop()
            encoder.release()

            if (muxerStarted) {
                muxer.stop()
                muxer.release()
            }

            Log.d("VideoEncoder", "Timelapse video created at ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Failed to create timelapse video", e)
            return false
        }
    }
}
