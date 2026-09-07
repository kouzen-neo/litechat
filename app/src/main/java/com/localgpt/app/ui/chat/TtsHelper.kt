package com.localgpt.app.ui.chat

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import com.localgpt.app.util.KLog
import java.util.Locale

/**
 * Helper class for Text-to-Speech functionality.
 * Strips markdown formatting before speaking.
 */
class TtsHelper(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    var isInitialized = mutableStateOf(false)
    var currentlySpeakingId = mutableStateOf<String?>(null)

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Throwable) {
            KLog.e("TTS", "Failed to create TextToSpeech instance", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            try {
                var langResult = tts?.setLanguage(Locale.getDefault())
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    langResult = tts?.setLanguage(Locale.US)
                    if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.ENGLISH)
                    }
                }
                isInitialized.value = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        currentlySpeakingId.value = utteranceId
                    }
                    override fun onDone(utteranceId: String?) {
                        if (currentlySpeakingId.value == utteranceId) {
                            currentlySpeakingId.value = null
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (currentlySpeakingId.value == utteranceId) {
                            currentlySpeakingId.value = null
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (currentlySpeakingId.value == utteranceId) {
                            currentlySpeakingId.value = null
                        }
                    }
                })
                KLog.d("TTS", "TextToSpeech initialized successfully")
            } catch (e: Throwable) {
                KLog.e("TTS", "Error setting up TextToSpeech", e)
            }
        } else {
            isInitialized.value = false
            KLog.w("TTS", "TextToSpeech init failed with status: $status")
        }
    }

    fun speak(id: String, text: String) {
        if (currentlySpeakingId.value == id) {
            stop()
            return
        }
        stop()

        // Strip markdown code blocks, tags, and formatting before speaking
        val cleanText =
            text
                .replace(Regex("```[\\s\\S]*?```"), " Code block omitted. ")
                .replace(Regex("<think>[\\s\\S]*?</think>"), "")
                .replace(Regex("<think>[\\s\\S]*"), "")
                .replace(Regex("`[^`]*`"), "")
                .replace(Regex("[#*_~>\\[\\]]"), "")
                .trim()

        if (cleanText.isBlank()) return

        if (tts == null || !isInitialized.value) {
            try {
                tts = TextToSpeech(context.applicationContext, this)
            } catch (e: Throwable) {
                KLog.e("TTS", "Failed to create TTS instance", e)
            }
            Toast.makeText(context, "TTS engine initializing. Tap again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
            }
            val result = tts?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, id)
            if (result == TextToSpeech.SUCCESS) {
                currentlySpeakingId.value = id
            } else {
                KLog.w("TTS", "tts.speak returned non-success code: $result")
                currentlySpeakingId.value = null
                Toast.makeText(context, "Text-to-speech error (code $result)", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Throwable) {
            KLog.e("TTS", "Failed to speak utterance", e)
            currentlySpeakingId.value = null
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Throwable) {
            KLog.e("TTS", "Failed to stop TTS", e)
        }
        currentlySpeakingId.value = null
    }

    fun destroy() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Throwable) {
            KLog.e("TTS", "Failed to shutdown TTS", e)
        }
        tts = null
        currentlySpeakingId.value = null
        isInitialized.value = false
    }
}
