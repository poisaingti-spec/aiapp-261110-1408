package com.aiapp.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Voice mode: the phone's speech recognizer turns your voice into text and its text-to-speech
 * reads replies aloud. Both work offline on most phones once the language pack is installed.
 */
class VoiceController(context: Context) : TextToSpeech.OnInitListener {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = ctx.getSharedPreferences("voice", Context.MODE_PRIVATE)
    private val tts = TextToSpeech(ctx, this)
    private var recognizer: SpeechRecognizer? = null
    private var onSpoken: (() -> Unit)? = null

    var state by mutableStateOf(VoiceState.IDLE)
    /** Microphone loudness 0..1, for the orb animation. */
    var level by mutableStateOf(0f)
        private set
    var partial by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var voices by mutableStateOf<List<Voice>>(emptyList())
        private set
    var voiceName by mutableStateOf(prefs.getString("voice", null))
        private set
    /** 0.8 = deeper, 1.0 = normal, 1.2 = higher. */
    var pitch by mutableStateOf(prefs.getFloat("pitch", 1.0f))
        private set
    var rate by mutableStateOf(prefs.getFloat("rate", 1.0f))
        private set

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(ctx)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            error = "Text-to-speech isn't available on this phone."
            return
        }
        val lang = Locale.getDefault().language
        val list = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { it.locale.language == lang || it.locale.language == "en" }
            .filter { !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .sortedWith(compareBy<Voice>({ it.locale.language != lang }, { it.isNetworkConnectionRequired }, { it.name }))
        main.post {
            voices = list
            applyVoice()
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == LAST) main.post { finishSpeaking() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { finishSpeaking() }
            }
        })
    }

    private fun applyVoice() {
        voices.firstOrNull { it.name == voiceName }?.let { tts.voice = it }
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
    }

    fun selectVoice(v: Voice) {
        voiceName = v.name
        prefs.edit().putString("voice", v.name).apply()
        applyVoice()
        speak("Hello! This is how I sound.") {}
    }

    fun choosePitch(p: Float) {
        pitch = p
        prefs.edit().putFloat("pitch", p).apply()
        applyVoice()
    }

    fun chooseRate(r: Float) {
        rate = r
        prefs.edit().putFloat("rate", r).apply()
        applyVoice()
    }

    private var preferOffline = true
    private var retries = 0

    /** Listens once; [onText] gets what you said (nothing if you stayed silent). */
    fun listen(onText: (String) -> Unit) {
        stopSpeaking()
        error = null
        partial = ""
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { state = VoiceState.LISTENING }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                // Roughly -2..10 dB from the recognizer → 0..1, smoothed.
                val target = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                level = level * 0.6f + target * 0.4f
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            }
            override fun onResults(results: Bundle?) {
                retries = 0
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                partial = ""
                if (text.isBlank()) {
                    state = VoiceState.IDLE
                } else {
                    state = VoiceState.THINKING
                    onText(text)
                }
            }
            override fun onError(code: Int) {
                // On-device recognition not installed: switch to online recognition and try again.
                if (preferOffline && code in listOf(11, 12, 13, SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_CLIENT)) {
                    preferOffline = false
                    main.postDelayed({ listen(onText) }, 300)
                    return
                }
                // Recognizer busy or its service disconnected (error 11): start a fresh one and try again.
                if (code in listOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 11, SpeechRecognizer.ERROR_CLIENT) && retries < 3) {
                    retries++
                    recognizer?.destroy()
                    recognizer = null
                    main.postDelayed({ listen(onText) }, 400L * retries)
                    return
                }
                partial = ""
                state = VoiceState.IDLE
                error = when (code) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that. Tap the mic to try again."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone access to use voice."
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Speech recognition needs internet, or download your language for offline use in the phone's voice settings."
                    else -> "Voice input error ($code). Tap the mic to try again."
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            // Answer sooner after you stop talking, and use on-device recognition when installed (faster).
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        state = VoiceState.LISTENING
        r.startListening(intent)
    }

    /** Shows a problem on the voice screen (e.g. a missing key). */
    fun showError(message: String) {
        error = message
        if (state == VoiceState.THINKING) state = VoiceState.IDLE
    }

    fun stopListening() {
        recognizer?.cancel()
        partial = ""
        if (state == VoiceState.LISTENING) state = VoiceState.IDLE
    }

    private var queued = 0

    /** Speaks one piece of a reply that is still being written (queued after earlier pieces). */
    fun enqueue(text: String) {
        val clean = plain(text)
        if (clean.isBlank()) return
        if (state != VoiceState.SPEAKING) {
            state = VoiceState.SPEAKING
            applyVoice()
        }
        clean.chunked(3_500).forEach { tts.speak(it, if (queued == 0 && !tts.isSpeaking) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "q${queued++}") }
    }

    /** Call when the reply is complete; [onDone] runs after the last queued piece has been spoken. */
    fun endOfReply(onDone: () -> Unit) {
        if (queued == 0 && !tts.isSpeaking) {
            queued = 0
            state = VoiceState.IDLE
            onDone()
            return
        }
        onSpoken = onDone
        state = VoiceState.SPEAKING
        tts.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, LAST)
    }

    /** Reads [text] aloud; [onDone] runs when it finishes (not when interrupted). */
    fun speak(text: String, onDone: () -> Unit) {
        val clean = plain(text)
        if (clean.isBlank()) {
            onDone()
            return
        }
        onSpoken = onDone
        state = VoiceState.SPEAKING
        applyVoice()
        val parts = clean.chunked(3_500)
        parts.forEachIndexed { i, p ->
            val id = if (i == parts.lastIndex) LAST else "part$i"
            tts.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    private fun finishSpeaking() {
        queued = 0
        if (state != VoiceState.SPEAKING) return
        state = VoiceState.IDLE
        val done = onSpoken
        onSpoken = null
        done?.invoke()
    }

    /** Stops talking immediately (interrupt). */
    fun stopSpeaking() {
        onSpoken = null
        queued = 0
        if (tts.isSpeaking) tts.stop()
        if (state == VoiceState.SPEAKING) state = VoiceState.IDLE
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        tts.stop()
        tts.shutdown()
    }

    companion object {
        private const val LAST = "last"

        /** Markdown and code read badly aloud, so strip them. */
        fun plain(md: String): String = md
            .replace(Regex("```[\\s\\S]*?```"), " (code shown on screen) ")
            .replace(Regex("`([^`]*)`"), "$1")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
            .replace(Regex("[*_~>|]"), "")
            .replace(Regex("https?://\\S+"), "")
            .trim()
    }
}
