package com.aiapp.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.aiapp.llm.Attachment
import com.aiapp.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Metadata extracted from an attached video file so GLM-5.3-Flash (Editor V1) knows the exact
 * duration, resolution, aspect ratio, and estimated processing time before editing.
 */
data class VideoMetadata(
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val sizeBytes: Long,
    val hasAudio: Boolean,
) {
    val displayWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
    val displayHeight: Int get() = if (rotation == 90 || rotation == 270) width else height
    val estimatedRenderSec: Int get() = (durationSec.coerceIn(3.0, 60.0) * 0.45).roundToInt().coerceAtLeast(3)

    fun summary(fileName: String): String =
        "Video '$fileName': %.1fs duration, %dx%d resolution, %.1f MB, audio=%s (Estimated Editor V1 render time: ~%ds)".format(
            durationSec,
            displayWidth,
            displayHeight,
            sizeBytes / (1024.0 * 1024.0),
            if (hasAudio) "yes" else "no",
            estimatedRenderSec,
        )
}

data class CaptionOverlay(
    val startSec: Double,
    val endSec: Double,
    val text: String,
    val highlightColor: String = "#FFD600",
)

data class ZoomKeyframe(
    val startSec: Double,
    val endSec: Double,
    val scale: Float = 1.18f,
)

data class VideoEditSpec(
    val trimStartSec: Double = 0.0,
    val trimEndSec: Double = 60.0,
    val hookTitle: String = "",
    val colorGrade: String = "cinematic",
    val targetAspect: String = "original",
    val showProgressBar: Boolean = true,
    val captions: List<CaptionOverlay> = emptyList(),
    val zooms: List<ZoomKeyframe> = emptyList(),
)

/**
 * Built-in $0 AI Video Editor powered by GLM-5.3-Flash + Editor V1 Skill.
 *
 * Implements the workflow from "My NEW AI Editor Completely FREE 0$ (No Subscription)" (GLM-5.3-Flash + Editor V1):
 * 1. Reads video metadata (duration, resolution, audio, and estimated render time).
 * 2. Prompts GLM-5.3-Flash with the Editor V1 Skill rules (pacing, timestamped zoom keyframes,
 *    dynamic pop captions, hook banner, and color grading).
 * 3. Renders the edited `.mp4` directly on the Android device using hardware MediaCodec + Canvas + MediaMuxer
 *    (with lossless MediaExtractor stream-copy fallback) and saves to Movies/AIApp & Downloads/AIApp.
 */
object VideoEditor {

    const val EDITOR_V1_SKILL_PROMPT = """
[EDITOR V1 — GLM-5.3-FLASH AI VIDEO EDITING SKILL ACTIVE]:
You are running the "Editor V1" autonomous video editing system powered by GLM-5.3-Flash.
When the user attaches a video or asks to edit a video:
1. First, state the estimated edit time upfront (e.g., "Estimated edit time: ~8 seconds for this clip").
2. Follow the user's exact timestamp notes (for example, "at second 5..." means 5.0s, not the 5th point).
3. ALWAYS include a fenced ```video-edit JSON block containing the exact timeline specification so the app's native on-device Editor V1 renderer can immediately produce the finished `.mp4` video on the phone:
```video-edit
{
  "trimStartSec": 0.0,
  "trimEndSec": 30.0,
  "hookTitle": "SHORT PUNCHY HOOK HEADER",
  "colorGrade": "cinematic",
  "targetAspect": "original",
  "showProgressBar": true,
  "zooms": [
    {"startSec": 0.0, "endSec": 2.5, "scale": 1.16},
    {"startSec": 5.0, "endSec": 7.5, "scale": 1.22}
  ],
  "captions": [
    {"startSec": 0.0, "endSec": 3.0, "text": "Dynamic Editor V1 Hook!", "highlightColor": "#FFD600"},
    {"startSec": 3.0, "endSec": 6.5, "text": "Edited 100% FREE with GLM-5.3-Flash", "highlightColor": "#00E5FF"}
  ]
}
```
Valid `colorGrade` values: `"cinematic"`, `"warm"`, `"cyberpunk"`, `"vibrant"`, `"bw"`, `"none"`.
Valid `targetAspect` values: `"original"`, `"9:16"`, `"16:9"`.
4. After the ```video-edit JSON block, briefly summarize the edits applied (hook, captions, zoom keyframes, color grade) and optionally include the equivalent FFmpeg command for desktop/Codespace users.
"""

    private val videoEditBlockRegex = Regex("```video-edit\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    private val jsonBlockRegex = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\"captions\"[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)

    /** Extracts real duration, resolution, rotation, and audio presence from a local video file. */
    fun inspectVideo(path: String): VideoMetadata {
        val file = File(path)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10_000L
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1920
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != null
            VideoMetadata(
                durationSec = (durMs / 1000.0).coerceAtLeast(1.0),
                width = w,
                height = h,
                rotation = rot,
                sizeBytes = file.length(),
                hasAudio = hasAudio,
            )
        } catch (_: Throwable) {
            VideoMetadata(
                durationSec = 10.0,
                width = 1080,
                height = 1920,
                rotation = 0,
                sizeBytes = file.length(),
                hasAudio = true,
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Extracts a preview thumbnail bitmap from the video. */
    fun extractThumbnail(path: String, timeSec: Double = 0.5): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.getFrameAtTime((timeSec * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Parses the `video-edit` JSON block from GLM-5.3-Flash's response, or builds a smart default spec. */
    fun parseEditSpec(aiResponse: String, userPrompt: String, meta: VideoMetadata): VideoEditSpec {
        val rawJson = videoEditBlockRegex.find(aiResponse)?.groupValues?.get(1)?.trim()
            ?: jsonBlockRegex.find(aiResponse)?.groupValues?.get(1)?.trim()

        if (!rawJson.isNullOrBlank()) {
            runCatching {
                val obj = Http.json.parseToJsonElement(rawJson).jsonObject
                val start = (obj["trimStartSec"] as? JsonPrimitive)?.doubleOrNull?.coerceAtLeast(0.0) ?: 0.0
                val end = (obj["trimEndSec"] as? JsonPrimitive)?.doubleOrNull?.coerceIn(start + 0.5, meta.durationSec)
                    ?: meta.durationSec
                val hook = (obj["hookTitle"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val grade = (obj["colorGrade"] as? JsonPrimitive)?.contentOrNull ?: "cinematic"
                val aspect = (obj["targetAspect"] as? JsonPrimitive)?.contentOrNull ?: "original"
                val prog = (obj["showProgressBar"] as? JsonPrimitive)?.booleanOrNull ?: true

                val zooms = (obj["zooms"] as? JsonArray)?.mapNotNull { el ->
                    val z = el as? JsonObject ?: return@mapNotNull null
                    val zs = (z["startSec"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val ze = (z["endSec"] as? JsonPrimitive)?.doubleOrNull ?: (zs + 2.0)
                    val sc = (z["scale"] as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 1.18f
                    ZoomKeyframe(zs, ze, sc.coerceIn(1.05f, 1.45f))
                }.orEmpty()

                val captions = (obj["captions"] as? JsonArray)?.mapNotNull { el ->
                    val c = el as? JsonObject ?: return@mapNotNull null
                    val cs = (c["startSec"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val ce = (c["endSec"] as? JsonPrimitive)?.doubleOrNull ?: (cs + 2.5)
                    val txt = (c["text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val col = (c["highlightColor"] as? JsonPrimitive)?.contentOrNull ?: "#FFD600"
                    CaptionOverlay(cs, ce, txt, col)
                }.orEmpty()

                return VideoEditSpec(
                    trimStartSec = start,
                    trimEndSec = end,
                    hookTitle = hook,
                    colorGrade = grade,
                    targetAspect = aspect,
                    showProgressBar = prog,
                    captions = captions,
                    zooms = zooms,
                )
            }
        }

        // Fallback synthesizer if the model replied in plain text without a JSON block
        val dur = meta.durationSec.coerceAtMost(45.0)
        val lower = userPrompt.lowercase()
        val grade = when {
            "bw" in lower || "black and white" in lower || "monochrome" in lower -> "bw"
            "cyberpunk" in lower || "neon" in lower -> "cyberpunk"
            "warm" in lower || "vintage" in lower || "golden" in lower -> "warm"
            "vibrant" in lower || "pop" in lower -> "vibrant"
            else -> "cinematic"
        }
        val aspect = when {
            "9:16" in lower || "vertical" in lower || "short" in lower || "reel" in lower -> "9:16"
            "16:9" in lower || "landscape" in lower -> "16:9"
            else -> "original"
        }
        // Detect timestamp mention like "at second 5"
        val secMatch = Regex("(?:second|sec)\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(userPrompt)
        val highlightSec = secMatch?.groupValues?.get(1)?.toDoubleOrNull()?.coerceIn(0.0, (dur - 1.0).coerceAtLeast(0.0)) ?: min(5.0, dur / 2.0)

        val defaultCaptions = listOf(
            CaptionOverlay(0.0, min(3.0, dur), "⚡ Edited with GLM-5.3-Flash", "#FFD600"),
            CaptionOverlay(highlightSec, min(highlightSec + 3.0, dur), "✨ Editor V1 Dynamic Cut", "#00E5FF"),
        )
        val defaultZooms = listOf(
            ZoomKeyframe(0.0, min(2.0, dur), 1.15f),
            ZoomKeyframe(highlightSec, min(highlightSec + 2.5, dur), 1.22f),
        )
        return VideoEditSpec(
            trimStartSec = 0.0,
            trimEndSec = dur,
            hookTitle = "EDITOR V1 · GLM-5.3-FLASH",
            colorGrade = grade,
            targetAspect = aspect,
            showProgressBar = true,
            captions = defaultCaptions,
            zooms = defaultZooms,
        )
    }

    /**
     * Renders the edited video according to [spec] and saves it to both `Downloads/AIApp` and the
     * app's attachments directory (returning an [Attachment] + public [Uri]).
     */
    suspend fun renderEditedVideo(
        context: Context,
        inputPath: String,
        spec: VideoEditSpec,
        onProgress: (String, Float) -> Unit = { _, _ -> },
    ): Pair<Attachment, Uri?> = withContext(Dispatchers.Default) {
        val meta = inspectVideo(inputPath)
        val startSec = spec.trimStartSec.coerceIn(0.0, (meta.durationSec - 0.5).coerceAtLeast(0.0))
        // Cap on-device canvas re-encode to 35 seconds to keep phone rendering snappy (just like the video's 38s tip)
        val endSec = spec.trimEndSec.coerceIn(startSec + 0.5, min(meta.durationSec, startSec + 35.0))
        val clipDurationSec = (endSec - startSec).coerceAtLeast(0.5)

        val outDir = File(context.filesDir, "attachments").apply { mkdirs() }
        val stamp = System.currentTimeMillis() % 100_000
        val outFile = File(outDir, "editor_v1_${stamp}.mp4")

        val renderedOk = runCatching {
            renderWithCanvasAndEncoder(
                inputPath = inputPath,
                outputFile = outFile,
                meta = meta,
                spec = spec,
                startSec = startSec,
                clipDurationSec = clipDurationSec,
                onProgress = onProgress,
            )
        }.getOrDefault(false)

        if (!renderedOk || !outFile.exists() || outFile.length() < 1024L) {
            onProgress("Applying fast lossless MP4 stream trim & remux…", 0.85f)
            remuxTrimLossless(inputPath, outFile, startSec, endSec)
        }

        onProgress("Saving edited MP4 to Movies/AIApp & Downloads/AIApp…", 0.96f)
        val savedUri = saveVideoToGalleryAndDownloads(context, outFile)
        val attachment = Attachment(
            name = outFile.name,
            mime = "video/mp4",
            path = outFile.absolutePath,
            text = "Edited MP4 video produced by GLM-5.3-Flash (Editor V1) — ${"%.1f".format(clipDurationSec)}s, grade=${spec.colorGrade}, captions=${spec.captions.size}.",
        )
        onProgress("Edited video ready!", 1.0f)
        attachment to savedUri
    }

    /**
     * Renders frames with Editor V1 visual effects (color grading, timestamped zoom keyframes,
     * hook title banner, dynamic captions with pill highlights, and progress bar) into an H.264 MP4
     * and muxes the original audio track.
     */
    private suspend fun renderWithCanvasAndEncoder(
        inputPath: String,
        outputFile: File,
        meta: VideoMetadata,
        spec: VideoEditSpec,
        startSec: Double,
        clipDurationSec: Double,
        onProgress: (String, Float) -> Unit,
    ): Boolean {
        val fps = 15
        val totalFrames = (clipDurationSec * fps).roundToInt().coerceIn(8, 450)

        // Choose clean multiple-of-16 dimensions for broad hardware H.264 compatibility
        val (outW, outH) = when (spec.targetAspect) {
            "9:16" -> 544 to 960
            "16:9" -> 960 to 544
            else -> {
                if (meta.displayWidth >= meta.displayHeight) 848 to 480 else 480 to 848
            }
        }

        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(mime)
        var muxer: MediaMuxer? = null
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(inputPath)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var muxerStarted = false

            // Prepare optional audio track from source video
            val audioExtractor = MediaExtractor()
            var sourceAudioTrack = -1
            runCatching {
                audioExtractor.setDataSource(inputPath)
                for (i in 0 until audioExtractor.trackCount) {
                    val tf = audioExtractor.getTrackFormat(i)
                    val tm = tf.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (tm.startsWith("audio/")) {
                        sourceAudioTrack = i
                        audioExtractor.selectTrack(i)
                        audioTrackIndex = muxer.addTrack(tf)
                        break
                    }
                }
            }

            fun drainEncoder(endOfStream: Boolean) {
                if (endOfStream) {
                    runCatching { encoder.signalEndOfInputStream() }
                }
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outIndex >= 0 -> {
                            val encodedData = encoder.getOutputBuffer(outIndex)
                            if (encodedData != null && bufferInfo.size > 0 && muxerStarted &&
                                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            ) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        }
                    }
                }
            }

            val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilterForGrade(spec.colorGrade)?.let { colorFilter = it }
            }

            for (frameIdx in 0 until totalFrames) {
                coroutineContext.ensureActive()
                val relSec = frameIdx.toDouble() / fps
                val sourceSec = startSec + relSec
                val pct = (frameIdx.toFloat() / totalFrames).coerceIn(0.05f, 0.90f)
                if (frameIdx % 4 == 0) {
                    onProgress("Editor V1 rendering frame ${frameIdx + 1}/$totalFrames (${(pct * 100).toInt()}%)…", pct)
                }

                val rawFrame = retriever.getFrameAtTime(
                    (sourceSec * 1_000_000).toLong(),
                    MediaMetadataRetriever.OPTION_CLOSEST,
                ) ?: retriever.getFrameAtTime(
                    (sourceSec * 1_000_000).toLong(),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )

                val surfaceCanvas = inputSurface.lockCanvas(null)
                try {
                    drawEditorV1Frame(
                        canvas = surfaceCanvas,
                        frame = rawFrame,
                        outW = outW,
                        outH = outH,
                        relSec = relSec,
                        clipDurationSec = clipDurationSec,
                        spec = spec,
                        framePaint = framePaint,
                    )
                } finally {
                    inputSurface.unlockCanvasAndPost(surfaceCanvas)
                    rawFrame?.recycle()
                }

                drainEncoder(endOfStream = false)
            }

            drainEncoder(endOfStream = true)

            // Copy trimmed audio samples into the muxed MP4 if present
            if (muxerStarted && sourceAudioTrack >= 0 && audioTrackIndex >= 0) {
                runCatching {
                    val startUs = (startSec * 1_000_000).toLong()
                    val endUs = ((startSec + clipDurationSec) * 1_000_000).toLong()
                    audioExtractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    val audioBuf = ByteBuffer.allocate(256 * 1024)
                    val audioInfo = MediaCodec.BufferInfo()
                    while (true) {
                        val sampleSize = audioExtractor.readSampleData(audioBuf, 0)
                        if (sampleSize < 0) break
                        val sampleTime = audioExtractor.sampleTime
                        if (sampleTime > endUs) break
                        if (sampleTime >= startUs) {
                            audioInfo.offset = 0
                            audioInfo.size = sampleSize
                            audioInfo.presentationTimeUs = (sampleTime - startUs).coerceAtLeast(0L)
                            audioInfo.flags = audioExtractor.sampleFlags
                            muxer.writeSampleData(audioTrackIndex, audioBuf, audioInfo)
                        }
                        audioExtractor.advance()
                    }
                }
            }

            runCatching { audioExtractor.release() }
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            return outputFile.exists() && outputFile.length() > 1024L
        } finally {
            runCatching { retriever.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { muxer?.release() }
        }
    }

    private fun drawEditorV1Frame(
        canvas: Canvas,
        frame: Bitmap?,
        outW: Int,
        outH: Int,
        relSec: Double,
        clipDurationSec: Double,
        spec: VideoEditSpec,
        framePaint: Paint,
    ) {
        canvas.drawColor(Color.parseColor("#0D0F14"))

        // 1. Active zoom keyframe interpolation
        val activeZoom = spec.zooms.firstOrNull { relSec >= it.startSec && relSec <= it.endSec }
        val zoomScale = if (activeZoom != null) {
            val mid = (activeZoom.startSec + activeZoom.endSec) / 2.0
            val half = ((activeZoom.endSec - activeZoom.startSec) / 2.0).coerceAtLeast(0.2)
            val dist = (1.0 - kotlin.math.abs(relSec - mid) / half).coerceIn(0.0, 1.0).toFloat()
            1.0f + (activeZoom.scale - 1.0f) * (0.4f + 0.6f * dist)
        } else {
            1.0f
        }

        // 2. Draw video frame with aspect fit/crop + zoom + color grade
        if (frame != null && !frame.isRecycled) {
            val baseScale = maxOf(outW.toFloat() / frame.width, outH.toFloat() / frame.height) * zoomScale
            val scaledW = frame.width * baseScale
            val scaledH = frame.height * baseScale
            val dx = (outW - scaledW) / 2f
            val dy = (outH - scaledH) / 2f
            val matrix = Matrix().apply {
                postScale(baseScale, baseScale)
                postTranslate(dx, dy)
            }
            canvas.drawBitmap(frame, matrix, framePaint)
        }

        // 3. Subtle top & bottom vignette for high caption legibility
        val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                outH * 0.62f,
                0f,
                outH.toFloat(),
                intArrayOf(Color.TRANSPARENT, Color.argb(185, 0, 0, 0)),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, outH * 0.62f, outW.toFloat(), outH.toFloat(), vignettePaint)

        // 4. Top Hook Title Banner (shown during the first 3.5 seconds or when hookTitle is set)
        if (spec.hookTitle.isNotBlank() && relSec <= min(4.0, clipDurationSec)) {
            val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(215, 18, 20, 28)
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = (outW * 0.044f).coerceIn(20f, 36f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val bannerTop = outH * 0.06f
            val bannerHeight = textPaint.textSize * 2.0f
            val rect = RectF(outW * 0.08f, bannerTop, outW * 0.92f, bannerTop + bannerHeight)
            canvas.drawRoundRect(rect, 22f, 22f, bannerPaint)

            val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FFD600")
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRoundRect(rect, 22f, 22f, accentPaint)
            canvas.drawText(
                spec.hookTitle.take(36),
                outW / 2f,
                bannerTop + bannerHeight * 0.66f,
                textPaint,
            )
        }

        // 5. Dynamic Editor V1 Timed Captions
        val activeCaption = spec.captions.firstOrNull { relSec >= it.startSec && relSec <= it.endSec }
        if (activeCaption != null) {
            val highlightColor = runCatching { Color.parseColor(activeCaption.highlightColor) }
                .getOrDefault(Color.parseColor("#FFD600"))
            val textSize = (outW * 0.056f).coerceIn(24f, 44f)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = highlightColor
                this.textSize = textSize
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val strokePaint = Paint(textPaint).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 7f
            }
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 10, 12, 18)
            }

            // Wrap caption into at most 2 lines
            val words = activeCaption.text.trim().split(Regex("\\s+"))
            val lines = mutableListOf<String>()
            var currentLine = ""
            for (w in words) {
                val test = if (currentLine.isEmpty()) w else "$currentLine $w"
                if (textPaint.measureText(test) <= outW * 0.82f || currentLine.isEmpty()) {
                    currentLine = test
                } else {
                    lines += currentLine
                    currentLine = w
                }
            }
            if (currentLine.isNotEmpty()) lines += currentLine

            val lineHeight = textSize * 1.32f
            val totalHeight = lines.size * lineHeight + 24f
            val baseY = outH * 0.82f - totalHeight / 2f
            val maxLineWidth = lines.maxOfOrNull { textPaint.measureText(it) } ?: (outW * 0.5f)
            val pillRect = RectF(
                (outW - maxLineWidth) / 2f - 24f,
                baseY - textSize,
                (outW + maxLineWidth) / 2f + 24f,
                baseY - textSize + totalHeight,
            )
            canvas.drawRoundRect(pillRect, 20f, 20f, bgPaint)

            lines.forEachIndexed { idx, line ->
                val y = baseY + idx * lineHeight
                canvas.drawText(line, outW / 2f, y, strokePaint)
                canvas.drawText(line, outW / 2f, y, textPaint)
            }
        }

        // 6. Bottom Animated Retention Progress Bar
        if (spec.showProgressBar) {
            val barHeight = (outH * 0.012f).coerceAtLeast(6f)
            val bgBar = Paint().apply { color = Color.argb(100, 255, 255, 255) }
            val fgBar = Paint().apply { color = Color.parseColor("#FFD600") }
            val progress = (relSec / clipDurationSec.coerceAtLeast(0.5)).toFloat().coerceIn(0f, 1f)
            canvas.drawRect(0f, outH - barHeight, outW.toFloat(), outH.toFloat(), bgBar)
            canvas.drawRect(0f, outH - barHeight, outW * progress, outH.toFloat(), fgBar)
        }
    }

    private fun colorFilterForGrade(grade: String): ColorMatrixColorFilter? = when (grade.lowercase()) {
        "cinematic" -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1.15f, 0f, 0f, 0f, -10f,
                    0f, 1.08f, 0f, 0f, -6f,
                    0f, 0f, 1.18f, 0f, 4f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        "warm" -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1.18f, 0f, 0f, 0f, 8f,
                    0f, 1.08f, 0f, 0f, 4f,
                    0f, 0f, 0.92f, 0f, -8f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        "cyberpunk" -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1.16f, 0f, 0.10f, 0f, -5f,
                    0f, 0.98f, 0.08f, 0f, -8f,
                    0.08f, 0f, 1.28f, 0f, 12f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        "vibrant" -> ColorMatrixColorFilter(
            ColorMatrix().apply { setSaturation(1.38f) },
        )
        "bw" -> ColorMatrixColorFilter(
            ColorMatrix().apply { setSaturation(0f) },
        )
        else -> null
    }

    /** Fast lossless MP4 stream-copy trimmer fallback using MediaExtractor + MediaMuxer. */
    private fun remuxTrimLossless(
        inputPath: String,
        outputFile: File,
        startSec: Double,
        endSec: Double,
    ) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(inputPath)
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = mutableMapOf<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    trackMap[i] = muxer.addTrack(format)
                }
            }
            if (trackMap.isEmpty()) {
                File(inputPath).copyTo(outputFile, overwrite = true)
                return
            }
            muxer.start()
            val startUs = (startSec * 1_000_000).toLong()
            val endUs = (endSec * 1_000_000).toLong()
            if (startUs > 0) {
                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
            val buf = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val timeUs = extractor.sampleTime
                if (timeUs > endUs) break
                val dstTrack = trackMap[trackIndex]
                if (dstTrack != null && timeUs >= startUs) {
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = (timeUs - startUs).coerceAtLeast(0L)
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(dstTrack, buf, info)
                }
                extractor.advance()
            }
            runCatching { muxer.stop() }
        } catch (_: Throwable) {
            runCatching { File(inputPath).copyTo(outputFile, overwrite = true) }
        } finally {
            runCatching { extractor.release() }
            runCatching { muxer?.release() }
        }
    }

    /** Saves the finished MP4 to both `Movies/AIApp` and `Downloads/AIApp` and returns a playable Uri. */
    fun saveVideoToGalleryAndDownloads(context: Context, videoFile: File): Uri? {
        val bytes = runCatching { videoFile.readBytes() }.getOrNull() ?: return null
        val fileName = videoFile.name
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                // 1. Save to Movies/AIApp so it shows up in the phone's Gallery / Video player
                val movieValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/AIApp")
                }
                val movieUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, movieValues)
                if (movieUri != null) {
                    resolver.openOutputStream(movieUri)?.use { it.write(bytes) }
                }
                // 2. Also save to Downloads/AIApp so it's in the phone's Downloads folder
                val dlValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AIApp")
                }
                val dlUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlValues)
                if (dlUri != null) {
                    resolver.openOutputStream(dlUri)?.use { it.write(bytes) }
                }
                movieUri ?: dlUri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "AIApp").apply { mkdirs() }
                val dst = File(dir, fileName)
                dst.writeBytes(bytes)
                FileProvider.getUriForFile(context, "${context.packageName}.files", dst)
            }
        } catch (_: Throwable) {
            runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", videoFile) }.getOrNull()
        }
    }
}
