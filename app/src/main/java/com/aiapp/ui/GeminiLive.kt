package com.aiapp.ui

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aiapp.llm.Http
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

/**
 * Natural, human-sounding live conversation with Gemini Live (native audio): you talk, it talks
 * back with expression, and you can cut in any time just by speaking. Needs a Gemini key + internet.
 */
class GeminiLive(
    private val apiKey: String,
    private val voiceName: String,
    private val systemText: String,
    /** Called with (what you said, what it said) after each exchange, to save in the chat. */
    private val onTurn: (String, String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    var state by mutableStateOf(VoiceState.IDLE)
        private set
    var level by mutableStateOf(0f)
        private set
    var heard by mutableStateOf("")
        private set
    var said by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var modelName by mutableStateOf("")
        private set

    private var ws: WebSocket? = null
    @Volatile private var running = false
    @Volatile private var muteUntilNextTurn = false
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private var lastAudioAt = 0L
    private val userText = StringBuilder()
    private val modelText = StringBuilder()

    private fun ui(block: () -> Unit) = main.post(block)

    /** Finds a Live (native audio) model on your key, preferring the newest native-audio one. */
    private fun findModel(): String? = runCatching {
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=500")
            .header("x-goog-api-key", apiKey).build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            val models = Http.json.parseToJsonElement(r.body!!.string()).jsonObject["models"] as? JsonArray ?: return@use null
            val live = models.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val methods = (o["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if ("bidiGenerateContent" in methods && !name.contains("translate") && !name.contains("transcribe")) name.removePrefix("models/") else null
            }
            live.sortedWith(compareByDescending<String> { it.contains("native-audio") || it.contains("live") }.thenByDescending { it })
                .firstOrNull()
        }
    }.getOrNull()

    fun start() {
        if (running) return
        running = true
        error = null
        state = VoiceState.THINKING
        Thread {
            val model = findModel()
            if (model == null) {
                ui {
                    error = "Your Gemini key has no Live voice model available. Using the phone's voice instead."
                    state = VoiceState.IDLE
                    running = false
                }
                return@Thread
            }
            ui { modelName = model }
            val req = Request.Builder()
                .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey")
                .build()
            ws = Http.client.newWebSocket(req, listener(model))
        }.start()
    }

    private fun listener(model: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val setup = buildJsonObject {
                putJsonObject("setup") {
                    put("model", "models/$model")
                    putJsonObject("generationConfig") {
                        putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                        putJsonObject("speechConfig") {
                            putJsonObject("voiceConfig") {
                                putJsonObject("prebuiltVoiceConfig") { put("voiceName", voiceName) }
                            }
                        }
                    }
                    putJsonObject("systemInstruction") {
                        putJsonArray("parts") { addJsonObject { put("text", systemText) } }
                    }
                    putJsonArray("tools") { addJsonObject { putJsonObject("googleSearch") {} } }
                    putJsonObject("inputAudioTranscription") {}
                    putJsonObject("outputAudioTranscription") {}
                }
            }
            webSocket.send(setup.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!running) return
            ui {
                error = "Live voice disconnected: ${t.message ?: "network error"}. Tap Talk to reconnect."
                state = VoiceState.IDLE
            }
            stopAudio()
            running = false
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!running) return
            ui {
                if (code != 1000) error = "Live voice ended (${reason.ifBlank { "code $code" }}). Tap Talk to reconnect."
                state = VoiceState.IDLE
            }
            stopAudio()
            running = false
        }
    }

    private fun handle(text: String) {
        val msg = runCatching { Http.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        if (msg.containsKey("setupComplete")) {
            startAudio()
            ui { state = VoiceState.LISTENING }
            return
        }
        val content = msg["serverContent"] as? JsonObject ?: return
        (content["inputTranscription"] as? JsonObject)?.let { t ->
            val s = (t["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (s.isNotEmpty()) {
                userText.append(s)
                ui { heard = userText.toString() }
            }
        }
        (content["outputTranscription"] as? JsonObject)?.let { t ->
            val s = (t["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (s.isNotEmpty()) {
                modelText.append(s)
                ui { said = modelText.toString() }
            }
        }
        if ((content["interrupted"] as? JsonPrimitive)?.booleanOrNull == true) {
            // You started talking: stop playing right away.
            flushPlayback()
            ui { state = VoiceState.LISTENING }
        }
        val parts = ((content["modelTurn"] as? JsonObject)?.get("parts") as? JsonArray).orEmpty()
        for (p in parts) {
            val inline = (p as? JsonObject)?.get("inlineData") as? JsonObject ?: continue
            val data = (inline["data"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (muteUntilNextTurn) continue
            playQueue.put(Base64.getDecoder().decode(data))
            lastAudioAt = System.currentTimeMillis()
            if (state != VoiceState.SPEAKING) ui { state = VoiceState.SPEAKING }
        }
        if ((content["turnComplete"] as? JsonPrimitive)?.booleanOrNull == true) {
            muteUntilNextTurn = false
            val u = userText.toString().trim()
            val m = modelText.toString().trim()
            userText.clear()
            modelText.clear()
            if (u.isNotEmpty() || m.isNotEmpty()) ui { onTurn(u, m) }
            // Go back to "Listening" once the queued audio has finished playing.
            Thread {
                while (running && playQueue.isNotEmpty()) Thread.sleep(50)
                Thread.sleep(250)
                ui { if (running) { state = VoiceState.LISTENING; heard = "" } }
            }.start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val inRate = 16_000
        val minIn = AudioRecord.getMinBufferSize(inRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, inRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, 6400),
        )
        // Echo cancelling so its own voice doesn't count as you interrupting.
        if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true }
        if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.enabled = true }
        recorder = rec

        val outRate = 24_000
        val minOut = AudioTrack.getMinBufferSize(outRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(outRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setBufferSizeInBytes(maxOf(minOut, 9600))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setSessionId(rec.audioSessionId)
            .build()
        track = t
        t.play()

        // Microphone → Gemini, 100 ms at a time.
        Thread {
            val buf = ByteArray(3200)
            runCatching { rec.startRecording() }
            while (running) {
                val n = try { rec.read(buf, 0, buf.size) } catch (e: Exception) { break }
                if (n <= 0) {
                    if (!running) break
                    continue
                }
                var sum = 0.0
                var i = 0
                while (i + 1 < n) {
                    val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
                    sum += s.toDouble() * s
                    i += 2
                }
                val rms = sqrt(sum / (n / 2)) / 32768.0
                ui { level = (level * 0.5f + (rms * 6).toFloat().coerceIn(0f, 1f) * 0.5f) }
                val b64 = Base64.getEncoder().encodeToString(buf.copyOf(n))
                ws?.send("{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}")
            }
        }.start()

        // Gemini → speaker.
        Thread {
            while (running) {
                val chunk = runCatching { playQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
                runCatching { track?.write(chunk, 0, chunk.size) }
            }
        }.start()
    }

    private fun flushPlayback() {
        playQueue.clear()
        runCatching {
            track?.pause()
            track?.flush()
            track?.play()
        }
    }

    /** Tap to interrupt: stop talking now and ignore the rest of this answer. */
    fun interrupt() {
        muteUntilNextTurn = true
        flushPlayback()
        state = VoiceState.LISTENING
    }

    private fun stopAudio() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        playQueue.clear()
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
    }

    fun stop() {
        running = false
        runCatching { ws?.close(1000, "bye") }
        ws = null
        stopAudio()
        state = VoiceState.IDLE
        level = 0f
    }

    companion object {
        /** Gemini's built-in voices. */
        val voices = listOf(
            "Puck" to "Male · upbeat",
            "Charon" to "Male · calm",
            "Fenrir" to "Male · excitable",
            "Orus" to "Male · firm",
            "Kore" to "Female · firm",
            "Aoede" to "Female · breezy",
            "Leda" to "Female · youthful",
            "Zephyr" to "Female · bright",
        )
    }
}
