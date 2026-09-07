package com.localgpt.app.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.mutableStateOf
import com.localgpt.app.util.KLog

/**
 * Helper class for speech recognition with automatic fallback to Intent-based recognition.
 */
class SpeechRecognizerHelper(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    var isListening = mutableStateOf(false)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun startListening(
        onResult: (String) -> Unit,
        onFallbackToIntent: (Intent) -> Unit,
    ) {
        handler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to LiteChat...")
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    }
                    onFallbackToIntent(intent)
                    return@post
                }

                destroy()

                val newRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                recognizer = newRecognizer
                newRecognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { isListening.value = true }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { isListening.value = false }
                    override fun onError(error: Int) {
                        isListening.value = false
                        KLog.w("STT", "SpeechRecognizer error: $error")
                        // If error occurred (service missing/busy/permission), trigger intent fallback
                        if (error == SpeechRecognizer.ERROR_SERVER ||
                            error == SpeechRecognizer.ERROR_CLIENT ||
                            error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                            error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                        ) {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to LiteChat...")
                                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            }
                            onFallbackToIntent(intent)
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        isListening.value = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.trim() ?: ""
                        if (text.isNotBlank()) {
                            onResult(text)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                newRecognizer.startListening(intent)
                isListening.value = true
            } catch (t: Throwable) {
                KLog.e("STT", "Failed to start speech recognizer, using intent fallback", t)
                isListening.value = false
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to LiteChat...")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                onFallbackToIntent(intent)
            }
        }
    }

    fun stopListening() {
        handler.post {
            try {
                recognizer?.stopListening()
            } catch (e: Throwable) {
                KLog.e("STT", "Failed to stop listening", e)
            }
            isListening.value = false
        }
    }

    fun destroy() {
        handler.post {
            try {
                recognizer?.cancel()
                recognizer?.destroy()
            } catch (e: Throwable) {
                KLog.e("STT", "Failed to destroy recognizer", e)
            }
            recognizer = null
            isListening.value = false
        }
    }
}
