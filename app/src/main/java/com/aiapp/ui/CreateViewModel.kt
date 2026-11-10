package com.aiapp.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiapp.data.ApiKeys
import com.aiapp.data.AttachmentStore
import com.aiapp.llm.Attachment
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ModelInfo
import com.aiapp.llm.ProviderException
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Providers
import com.aiapp.llm.Role
import com.aiapp.llm.THOUGHT
import com.aiapp.media.MediaGen
import com.aiapp.media.VideoEditor
import com.aiapp.media.VideoMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException

enum class CreateTabMode {
    FREE_VIDEO_EDITOR,
    IMAGE,
    VEO_VIDEO,
}

/**
 * Supports:
 * 1. **🎬 Free AI Video Editor ($0 · GLM-5.3-Flash + Editor V1 Skill)** — zero API key required!
 *    Imports any phone video, runs GLM-5.3-Flash with the Editor V1 skill to plan captions, zoom
 *    keyframes, hook header, and color grading, and renders the edited `.mp4` directly on the phone.
 * 2. **Image Generation** (Gemini / OpenAI)
 * 3. **Veo Video Generation** (Gemini)
 */
class CreateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val keys = ApiKeys(app)

    var tabMode by mutableStateOf(CreateTabMode.FREE_VIDEO_EDITOR)
        private set

    var video by mutableStateOf(false)
    var imageProvider by mutableStateOf(ProviderId.GEMINI)
        private set
    var models by mutableStateOf<List<ModelInfo>>(emptyList())
        private set
    var model by mutableStateOf("")
    var aspect by mutableStateOf("16:9")
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var resultUri by mutableStateOf<Uri?>(null)
        private set
    var resultBytes by mutableStateOf<ByteArray?>(null)
        private set
    var resultIsVideo by mutableStateOf(false)
        private set

    // ---- Free AI Video Editor (GLM-5.3-Flash + Editor V1) state ----
    var sourceVideo by mutableStateOf<Attachment?>(null)
        private set
    var sourceVideoMeta by mutableStateOf<VideoMetadata?>(null)
        private set
    var sourceVideoThumb by mutableStateOf<Bitmap?>(null)
        private set
    var editorProgress by mutableStateOf(0f)
        private set
    var editorThinking by mutableStateOf("")
        private set
    var editorPlanSummary by mutableStateOf("")
        private set
    var selectedColorGrade by mutableStateOf("cinematic")
    var selectedTargetAspect by mutableStateOf("original")

    private var job: Job? = null

    /** A photo to edit (images) or animate (Veo video). */
    var reference by mutableStateOf<Attachment?>(null)

    fun selectTabMode(mode: CreateTabMode) {
        if (tabMode == mode) return
        tabMode = mode
        error = null
        status = null
        when (mode) {
            CreateTabMode.FREE_VIDEO_EDITOR -> {
                models = listOf(
                    ModelInfo("glm-5.3-flash", "★ GLM-5.3-Flash Free 321B (Editor V1 · $0)"),
                    ModelInfo("qwen-coder", "★ Qwen 3.8 Coder 27B / 397B (Free)"),
                    ModelInfo("deepseek-reasoning", "★ DeepSeek R1 / Nemotron 550B (Free)"),
                )
                model = "glm-5.3-flash"
            }
            CreateTabMode.IMAGE -> {
                video = false
                loadModels()
            }
            CreateTabMode.VEO_VIDEO -> {
                video = true
                loadModels()
            }
        }
    }

    fun attachVideoForEdit(uri: Uri) {
        viewModelScope.launch {
            try {
                status = "Importing video from phone…"
                error = null
                val att = AttachmentStore.import(app, uri)
                if (!att.isVideo && !att.name.lowercase().let {
                        it.endsWith(".mp4") || it.endsWith(".mov") || it.endsWith(".webm") || it.endsWith(".mkv")
                    }
                ) {
                    error = "Please pick a video file (MP4, MOV, WebM, or MKV)."
                    status = null
                    return@launch
                }
                val fixedAtt = if (att.isVideo) att else att.copy(mime = "video/mp4")
                val meta = VideoEditor.inspectVideo(fixedAtt.path)
                val thumb = VideoEditor.extractThumbnail(fixedAtt.path)
                sourceVideo = fixedAtt
                sourceVideoMeta = meta
                sourceVideoThumb = thumb
                status = "Loaded ${fixedAtt.name} (${"%.1f".format(meta.durationSec)}s · ${meta.displayWidth}×${meta.displayHeight}) — Estimated edit time: ~${meta.estimatedRenderSec}s"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "Couldn't open that video."
                status = null
            }
        }
    }

    fun clearSourceVideo() {
        sourceVideo = null
        sourceVideoMeta = null
        sourceVideoThumb = null
        status = null
    }

    /**
     * Runs the complete $0 GLM-5.3-Flash + Editor V1 video editing pipeline on [sourceVideo]:
     * 1. Queries GLM-5.3-Flash Free with the Editor V1 Skill + real video metadata + user prompt.
     * 2. Parses the `video-edit` JSON timeline (captions, zooms, hook title, color grade, trim).
     * 3. Renders the edited `.mp4` on the phone and saves it to `Movies/AIApp` and `Downloads/AIApp`.
     */
    fun runFreeVideoEdit(userPrompt: String) {
        val vid = sourceVideo
        val meta = sourceVideoMeta
        if (busy) return
        if (vid == null || meta == null) {
            error = "Pick a video from your phone first (+ Pick Video to Edit)."
            return
        }
        val cleanPrompt = userPrompt.trim().ifBlank {
            "Edit this video with Editor V1: add dynamic captions, a punchy hook header, timestamped zooms, and $selectedColorGrade color grading."
        }
        busy = true
        error = null
        resultUri = null
        resultBytes = null
        editorThinking = ""
        editorPlanSummary = ""
        editorProgress = 0.04f
        status = "GLM-5.3-Flash (Editor V1) analyzing ${vid.name} (est. ~${meta.estimatedRenderSec}s)…"

        job = viewModelScope.launch {
            try {
                val chosenModel = model.ifBlank { "glm-5.3-flash" }
                val promptWithPrefs = buildString {
                    append(cleanPrompt)
                    append("\nPreferred colorGrade: \"$selectedColorGrade\", targetAspect: \"$selectedTargetAspect\".")
                }
                val msg = ChatMessage(
                    role = Role.USER,
                    text = promptWithPrefs,
                    attachments = listOf(vid),
                )
                val fullAiText = StringBuilder()
                val thinkBuilder = StringBuilder()

                runCatching {
                    Providers.get(ProviderId.FREE_CLOUD).stream(
                        apiKey = "",
                        model = chosenModel,
                        messages = listOf(msg),
                        effort = Effort.MAX,
                        system = VideoEditor.EDITOR_V1_SKILL_PROMPT,
                    ).collect { delta ->
                        if (delta.startsWith(THOUGHT)) {
                            thinkBuilder.append(delta. drop(1))
                            editorThinking = thinkBuilder.toString().takeLast(900)
                            status = "GLM-5.3-Flash thinking (Editor V1 skill)…"
                        } else {
                            fullAiText.append(delta)
                            status = "GLM-5.3-Flash generating Editor V1 timeline…"
                        }
                    }
                }

                editorProgress = 0.20f
                val parsedSpec = VideoEditor.parseEditSpec(
                    aiResponse = fullAiText.toString(),
                    userPrompt = cleanPrompt,
                    meta = meta,
                ).let { spec ->
                    spec.copy(
                        colorGrade = if (selectedColorGrade != "auto") selectedColorGrade else spec.colorGrade,
                        targetAspect = if (selectedTargetAspect != "original") selectedTargetAspect else spec.targetAspect,
                    )
                }

                val (_, savedUri) = VideoEditor.renderEditedVideo(
                    context = app,
                    inputPath = vid.path,
                    spec = parsedSpec,
                ) { stepText, pct ->
                    viewModelScope.launch {
                        status = stepText
                        editorProgress = 0.20f + pct * 0.80f
                    }
                }

                resultUri = savedUri
                resultIsVideo = true
                editorPlanSummary = fullAiText.toString()
                    .replace(Regex("```video-edit[\\s\\S]*?```"), "")
                    .trim()
                    .ifBlank {
                        "Edited with GLM-5.3-Flash (Editor V1): applied ${parsedSpec.colorGrade} grade, " +
                            "${parsedSpec.captions.size} dynamic captions, and ${parsedSpec.zooms.size} zoom keyframes."
                    }
                status = "✅ Saved edited MP4 to Movies/AIApp & Downloads/AIApp ($0 · GLM-5.3-Flash Editor V1)"
            } catch (e: CancellationException) {
                status = null
                throw e
            } catch (e: Throwable) {
                status = null
                error = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    fun attach(uri: Uri) {
        viewModelScope.launch {
            try {
                val a = AttachmentStore.import(app, uri)
                if (!a.isImage) error = "Attach a photo (JPG, PNG or WebP) to edit or animate." else reference = a
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "Couldn't attach that file."
            }
        }
    }

    init {
        selectTabMode(CreateTabMode.FREE_VIDEO_EDITOR)
    }

    fun setMode(isVideo: Boolean) {
        selectTabMode(if (isVideo) CreateTabMode.VEO_VIDEO else CreateTabMode.IMAGE)
    }

    fun setProvider(p: ProviderId) {
        imageProvider = p
        loadModels()
    }

    fun loadModels() {
        if (tabMode == CreateTabMode.FREE_VIDEO_EDITOR) return
        val p = if (video) ProviderId.GEMINI else imageProvider
        val key = keys.get(p)
        models = emptyList()
        model = ""
        if (key.isBlank()) {
            error = "Add your ${p.label} API key in Settings to create ${if (video) "Veo videos" else "images"} (or use 🎬 Free AI Video Editor for $0)."
            return
        }
        error = null
        viewModelScope.launch {
            try {
                val list = if (video) MediaGen.videoModels(key) else MediaGen.imageModels(p, key)
                models = list
                model = list.firstOrNull()?.id.orEmpty()
                if (list.isEmpty()) {
                    error = if (video) "Your Gemini key has no video models (Veo) available." else "No image models available for this key."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = friendly(e)
            }
        }
    }

    fun generate(prompt: String) {
        if (tabMode == CreateTabMode.FREE_VIDEO_EDITOR) {
            runFreeVideoEdit(prompt)
            return
        }
        if (busy || prompt.isBlank() || model.isBlank()) return
        val p = if (video) ProviderId.GEMINI else imageProvider
        val key = keys.get(p)
        val isVideo = video
        busy = true
        error = null
        resultUri = null
        resultBytes = null
        status = if (isVideo) "Starting video (this takes a few minutes)…" else "Creating image…"
        job = viewModelScope.launch {
            try {
                val bytes = if (isVideo) {
                    MediaGen.video(model, key, prompt.trim(), aspect, reference) { status = it }
                } else {
                    MediaGen.image(p, model, key, prompt.trim(), reference)
                }
                status = "Saving to your gallery…"
                resultUri = MediaGen.saveToGallery(app, bytes, isVideo)
                resultBytes = if (isVideo) null else bytes
                resultIsVideo = isVideo
                status = "Saved to ${if (isVideo) "Movies" else "Pictures"}/AIApp"
            } catch (e: CancellationException) {
                status = null
                throw e
            } catch (e: Throwable) {
                status = null
                error = friendly(e)
            } finally {
                busy = false
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private fun friendly(t: Throwable): String = when {
        t is ProviderException && (t.code == 401 || t.code == 403) -> "Key rejected or no access to this model: ${t.message}"
        t is ProviderException && t.code == 429 -> "Rate limit or quota reached. Try again in a moment."
        t is IOException -> "Network error. Check your connection and try again."
        else -> t.message ?: "Something went wrong."
    }
}
