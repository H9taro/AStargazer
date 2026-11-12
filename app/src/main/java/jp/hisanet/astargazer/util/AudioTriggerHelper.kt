package jp.hisanet.astargazer.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

/**
 * マイク入力（AudioRecord）による音量（RMS振幅）監視および音検出ヘルパー。
 * 将来的な拍手検出（トランジェントピーク検出等）への拡張も容易な設計。
 */
class AudioTriggerHelper(
    private val context: Context,
    var threshold: Double = 1500.0, // 感度閾値 (振幅RMS)
    private val onSoundDetected: () -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var isListening = false
    private var listeningThread: Thread? = null

    @SuppressLint("MissingPermission")
    fun startListening(): Boolean {
        if (isListening) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("AudioTrigger", "RECORD_AUDIO permission not granted")
            return false
        }

        val sampleRate = 8000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(2048)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("AudioTrigger", "AudioRecord initialization failed")
                return false
            }

            audioRecord?.startRecording()
            isListening = true

            listeningThread = Thread {
                val buffer = ShortArray(bufferSize / 2)
                while (isListening) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        var sum = 0.0
                        for (i in 0 until readSize) {
                            val sample = buffer[i].toDouble()
                            sum += sample * sample
                        }
                        val rms = sqrt(sum / readSize)
                        Log.d("AudioTrigger", "Audio RMS: $rms (Threshold: $threshold)")

                        // 将来的な拍手検出への拡張ポイント（ピーク検出や周波数解析等をここに統合可能）
                        if (rms >= threshold) {
                            Log.i("AudioTrigger", "Sound detected! RMS: $rms")
                            isListening = false
                            onSoundDetected()
                            break
                        }
                    }
                }
            }.apply { start() }

            return true
        } catch (e: Exception) {
            Log.e("AudioTrigger", "Failed to start AudioRecord", e)
            stopListening()
            return false
        }
    }

    fun stopListening() {
        isListening = false
        try {
            listeningThread?.join(500)
        } catch (_: Exception) {}
        listeningThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }
}
