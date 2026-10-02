package com.example.astargazer.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

class VoiceCommandManager(
    private val context: Context,
    private val onRecognizedStatusChanged: (String) -> Unit = {},
    private val onShutterCommandTriggered: () -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private val handler = Handler(Looper.getMainLooper())

    fun startListening() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w("VoiceCommand", "Speech recognition is not available on this device")
            onRecognizedStatusChanged("音声非対応")
            return
        }

        handler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            onRecognizedStatusChanged("音声受付中...")
                        }

                        override fun onBeginningOfSpeech() {
                            onRecognizedStatusChanged("音声検出中...")
                        }

                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}

                        override fun onError(error: Int) {
                            Log.d("VoiceCommand", "Speech error code: $error")
                            isListening = false
                            onRecognizedStatusChanged("再接続中...")
                            restartListeningWithDelay(400L)
                        }

                        override fun onResults(results: Bundle?) {
                            isListening = false
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            var matched = false
                            if (!matches.isNullOrEmpty()) {
                                for (text in matches) {
                                    val cleanText = text.replace("\\s+".toRegex(), "").lowercase(Locale.JAPANESE)
                                    Log.d("VoiceCommand", "Recognized text: $cleanText")
                                    onRecognizedStatusChanged("「$cleanText」")

                                    if (checkIfShutterCommand(cleanText)) {
                                        Log.i("VoiceCommand", "Shutter command matched: $cleanText")
                                        onRecognizedStatusChanged("「$cleanText」-> 撮影起動!")
                                        onShutterCommandTriggered()
                                        matched = true
                                        break
                                    }
                                }
                            }
                            restartListeningWithDelay(if (matched) 1500L else 400L)
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            if (!matches.isNullOrEmpty()) {
                                for (text in matches) {
                                    val cleanText = text.replace("\\s+".toRegex(), "").lowercase(Locale.JAPANESE)
                                    onRecognizedStatusChanged("「$cleanText」")

                                    if (checkIfShutterCommand(cleanText)) {
                                        Log.i("VoiceCommand", "Shutter partial command matched: $cleanText")
                                        onRecognizedStatusChanged("「$cleanText」-> 撮影起動!")
                                        onShutterCommandTriggered()
                                        speechRecognizer?.stopListening()
                                        isListening = false
                                        restartListeningWithDelay(1500L)
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
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                }

                speechRecognizer?.startListening(intent)
                isListening = true
                Log.d("VoiceCommand", "Started listening successfully")
            } catch (e: Exception) {
                Log.e("VoiceCommand", "Failed to start speech recognition", e)
                isListening = false
                restartListeningWithDelay(1000L)
            }
        }
    }

    private fun checkIfShutterCommand(text: String): Boolean {
        return text.contains("とり") ||
                text.contains("撮り") ||
                text.contains("取り") ||
                text.contains("トリ") ||
                text.contains("とります") ||
                text.contains("撮ります") ||
                text.contains("取ります") ||
                text.contains("シャッター") ||
                text.contains("撮影") ||
                text.contains("はい") ||
                text.contains("ハイ") ||
                text.contains("撮る") ||
                text.contains("とる")
    }

    fun stopListening() {
        isListening = false
        handler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.e("VoiceCommand", "Failed to stop speech recognition", e)
            }
        }
    }

    private fun restartListeningWithDelay(delayMs: Long) {
        handler.postDelayed({
            startListening()
        }, delayMs)
    }
}
