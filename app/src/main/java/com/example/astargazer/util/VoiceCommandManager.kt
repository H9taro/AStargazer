package com.example.astargazer.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class VoiceCommandManager(
    private val context: Context,
    private val onShutterCommandTriggered: () -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    fun startListening() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w("VoiceCommand", "Speech recognition is not available on this device")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        Log.d("VoiceCommand", "Speech recognition error code: $error")
                        isListening = false
                        // エラー時（無音タイムアウト等）は少し間を置いて自動リスニング再開
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            restartListeningWithDelay()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (matches != null) {
                            for (text in matches) {
                                val cleanText = text.trim()
                                Log.d("VoiceCommand", "Recognized voice text: $cleanText")
                                if (cleanText.contains("とります") ||
                                    cleanText.contains("取ります") ||
                                    cleanText.contains("撮ります") ||
                                    cleanText.contains("シャッター") ||
                                    cleanText.contains("撮影")
                                ) {
                                    Log.i("VoiceCommand", "Shutter command matched!")
                                    onShutterCommandTriggered()
                                    break
                                }
                            }
                        }
                        restartListeningWithDelay()
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (matches != null) {
                            for (text in matches) {
                                val cleanText = text.trim()
                                if (cleanText.contains("とります") ||
                                    cleanText.contains("取ります") ||
                                    cleanText.contains("撮ります")
                                ) {
                                    Log.i("VoiceCommand", "Shutter partial command matched!")
                                    onShutterCommandTriggered()
                                    speechRecognizer?.stopListening()
                                    break
                                }
                            }
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPANESE.toString())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            speechRecognizer?.startListening(intent)
            isListening = true
            Log.d("VoiceCommand", "Started voice command listener")
        } catch (e: Exception) {
            Log.e("VoiceCommand", "Failed to start speech recognition", e)
            isListening = false
        }
    }

    fun stopListening() {
        isListening = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            Log.e("VoiceCommand", "Failed to stop speech recognition", e)
        }
    }

    private fun restartListeningWithDelay() {
        android.os.Handler(context.mainLooper).postDelayed({
            startListening()
        }, 500L)
    }
}
