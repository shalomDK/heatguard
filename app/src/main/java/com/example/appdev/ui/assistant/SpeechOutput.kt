package com.example.appdev.ui.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Reads assistant answers aloud with Android's built-in text-to-speech. The engine is created the
 * first time it is needed and released when the screen goes away. Only one answer speaks at a time.
 */
class SpeechOutput(context: Context) {
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<Long, String>? = null

    /** The id of the message currently being read, or null. */
    var speakingId: Long? by mutableStateOf(null)
        private set

    /** Starts reading [text] for message [id], or stops if that message is already being read. */
    fun toggle(id: Long, text: String) {
        if (speakingId == id) {
            stop()
            return
        }
        if (text.isBlank()) return
        speakingId = id
        if (tts == null) {
            pending = id to text
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    configure()
                    ready = true
                    pending?.let { (pid, ptext) -> speak(pid, ptext) }
                } else {
                    speakingId = null
                }
                pending = null
            }
        } else if (ready) {
            speak(id, text)
        } else {
            pending = id to text
        }
    }

    fun stop() {
        pending = null
        tts?.stop()
        speakingId = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        speakingId = null
    }

    private fun configure() {
        val engine = tts ?: return
        // Use the device language when the engine supports it, otherwise leave the engine default.
        val result = engine.setLanguage(Locale.getDefault())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.language = Locale.US
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { if (speakingId?.toString() == utteranceId) speakingId = null }

            @Deprecated("Deprecated in the platform API")
            override fun onError(utteranceId: String?) { if (speakingId?.toString() == utteranceId) speakingId = null }
        })
    }

    private fun speak(id: Long, text: String) {
        val engine = tts ?: return
        val limit = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceAtLeast(500)
        val status = engine.speak(text.take(limit), TextToSpeech.QUEUE_FLUSH, null, id.toString())
        if (status != TextToSpeech.SUCCESS) speakingId = null
    }
}

@Composable
fun rememberSpeechOutput(): SpeechOutput {
    val context = LocalContext.current
    val speech = remember { SpeechOutput(context) }
    DisposableEffect(speech) { onDispose { speech.shutdown() } }
    return speech
}
