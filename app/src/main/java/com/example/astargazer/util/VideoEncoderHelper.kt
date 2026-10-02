package com.example.astargazer.util

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

object VideoEncoderHelper {

    /**
     * 静止画ファイル群から MP4 タイムラプス動画(フルHD 1080p)を生成する
     * ダークフレーム画像が存在する場合は自動的にダーク減算ノイズ除去を実行する
     */
    fun createTimelapseVideo(
        imageFiles: List<File>,
        outputFile: File,
        darkFrameFile: File? = null,
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

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFiles[0].absolutePath, options)
            val srcWidth = options.outWidth
            val srcHeight = options.outHeight

            if (srcWidth <= 0 || srcHeight <= 0) return false

            // 互換性の高い 1080p (フルHD) 解像度をターゲットにする
            val targetWidth: Int
            val targetHeight: Int
            if (srcWidth >= srcHeight) {
                targetWidth = 1920
                targetHeight = 1080
            } else {
                targetWidth = 1080
                targetHeight = 1920
            }

            val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
            val format = MediaFormat.createVideoFormat(mimeType, targetWidth, targetHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000) // 6 Mbps
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1秒キーフレーム
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

            val dstRect = Rect(0, 0, targetWidth, targetHeight)

            for ((index, file) in imageFiles.withIndex()) {
                val rawBitmap = BitmapFactory.decodeFile(file.absolutePath) ?: continue
                val bitmap = ImageCompositor.subtractDarkFrame(rawBitmap, darkBitmap)
                if (rawBitmap != bitmap) rawBitmap.recycle()

                // Surface への描画 (フルHD 1080p にスケーリング)
                val canvas: Canvas = inputSurface.lockCanvas(null)
                canvas.drawColor(Color.BLACK)
                val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                inputSurface.unlockCanvasAndPost(canvas)
                bitmap.recycle()

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

            // EOS (流し込み終了通知)
            encoder.signalEndOfInputStream()

            // 残りバッファの完全ドレイン
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

            Log.d("VideoEncoder", "Timelapse video with dark frame subtraction created at ${outputFile.absolutePath} (size: ${outputFile.length()} bytes)")
            return outputFile.exists() && outputFile.length() > 0
        } catch (e: Exception) {
            Log.e("VideoEncoder", "Failed to create timelapse video", e)
            if (outputFile.exists()) outputFile.delete()
            return false
        }
    }
}
