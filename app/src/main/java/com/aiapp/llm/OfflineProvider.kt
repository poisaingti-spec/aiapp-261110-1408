package com.aiapp.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A model that runs on the phone with no internet, API key, or website login via Google's LiteRT-LM runtime. */
data class OfflineModel(
    val id: String,
    val name: String,
    /** Hugging Face repo holding the .litertlm file. */
    val repo: String,
    val approxSizeGb: Double,
    /** Phones with less RAM than this get the smaller model recommended. */
    val minRamGb: Int,
    /** Direct filename in the Hugging Face repo so download works immediately with zero API lookups or tokens. */
    val defaultFileName: String? = null,
    /** Short description shown in Settings and Chat/Build download cards. */
    val description: String = "",
    /** Specialized for coding and Android Studio app building. */
    val isCodingFocused: Boolean = false,
    /** Open-weight abliterated / unrestricted model build. */
    val isUnrestricted: Boolean = false,
)

object OfflineCatalog {
    /**
     * Built-in free, download-and-use models (no API key or Hugging Face login required).
     * Ordered with the most advanced open-weight / abliterated models and coding models first.
     */
    val builtIn = listOf(
        OfflineModel(
            id = "khoido-gemma4-e4b-heretic",
            name = "★ Gemma 4 E4B Heretic Flagship (8.35 GB Full-Precision · 12 GB+ RAM)",
            repo = "khoido/gemma-4-E4B-it-ultra-uncensored-heretic-litertlm",
            approxSizeGb = 8.35,
            minRamGb = 12,
            defaultFileName = "model.litertlm",
            description = "Full-precision Gemma 4 E4B Heretic LiteRT-LM build for high-RAM flagship Android devices (12 GB+ RAM).",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "huihui-gemma4-e4b-abliterated",
            name = "★ Huihui Gemma 4 E4B Abliterated (7.8 GB Multimodal · 10 GB+ RAM)",
            repo = "vokash3/Huihui-gemma-4-E4B-it-abliterated-LiteRT-LM",
            approxSizeGb = 7.8,
            minRamGb = 10,
            defaultFileName = "Huihui-gemma-4-E4B-it-abliterated.litertlm",
            description = "High-precision LiteRT-LM conversion of Huihui-ai/Huihui-gemma-4-E4B-it-abliterated with multimodal support and 4096 context.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "duoneural-gemma4-e4b-abliterated",
            name = "★ Gemma 4 E4B Abliterated (3.7 GB · 8 GB+ RAM · Creative & Coding)",
            repo = "DuoNeural/Gemma-4-Abliterated-LiteRT",
            approxSizeGb = 3.7,
            minRamGb = 8,
            defaultFileName = "Gemma-4-E4B-Abliterated.litertlm",
            description = "Abliterated Gemma 4 E4B for Android (8 GB+ RAM) supporting open-ended creative writing, roleplay, and full-stack Android coding offline.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "supergemma4-e4b-abliterated",
            name = "★ SuperGemma 4 E4B Abliterated (3.65 GB · 8 GB+ RAM · Chat & Code)",
            repo = "typomonster/supergemma4-e4b-abliterated-litert-lm",
            approxSizeGb = 3.65,
            minRamGb = 8,
            defaultFileName = "supergemma4-e4b-abliterated.litertlm",
            description = "On-device LiteRT-LM build of Jiunsong/supergemma4-e4b-abliterated for fast on-device chat and coding.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "gemma-4-e4b-abliterated",
            name = "★ Gemma 4 E4B MTP Abliterated (Fast Multi-Token Prediction · 8 GB+ RAM)",
            repo = "olekk/gemma-4-E4B-it-abliterated-litert-lm",
            approxSizeGb = 3.7,
            minRamGb = 8,
            defaultFileName = "gemma-4-E4B-it-abliterated.litertlm",
            description = "Abliterated Gemma 4 E4B with Multi-Token Prediction (MTP), 128K context, and advanced coding & agentic capabilities.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "gemma-4-e2b-heretic",
            name = "★ Gemma 4 E2B Heretic (2.5 GB · 4 GB+ RAM Phones · Open-Ended Chat)",
            repo = "jamarag/gemma-4-E2B-it-ultra-uncensored-heretic-litertlm",
            approxSizeGb = 2.5,
            minRamGb = 4,
            defaultFileName = "model.litertlm",
            description = "Heretic fine-tune of Gemma 4 E2B converted to LiteRT-LM for open-ended chat, creative writing, and coding on 4 GB+ RAM devices.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "gemma-4-e2b-uncensored-max",
            name = "★ Gemma 4 E2B Uncensored-MAX (2.4 GB · 4 GB+ RAM Phones)",
            repo = "PeppX/gemma-4-e2b-uncensored-litertlm",
            approxSizeGb = 2.4,
            minRamGb = 4,
            defaultFileName = "gemma-4-E2B-it-Uncensored-MAX.litertlm",
            description = "Uncensored-MAX fine-tune (prithivMLmods/gemma-4-E2B-it-Uncensored-MAX) with 32K context window and fast GPU/CPU inference on 4 GB+ phones.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "duoneural-gemma4-e2b-abliterated",
            name = "★ Gemma 4 E2B Abliterated (2.5 GB · 4 GB+ RAM)",
            repo = "DuoNeural/Gemma-4-Abliterated-LiteRT",
            approxSizeGb = 2.5,
            minRamGb = 4,
            defaultFileName = "Gemma-4-E2B-Abliterated.litertlm",
            description = "Abliterated Gemma 4 E2B for fast, private on-device chat and coding.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "qwen25-coder-3b-instruct",
            name = "★ Qwen 2.5 Coder 3B (Best Coding & Android Builder · Free)",
            repo = "litert-community/Qwen2.5-Coder-3B-Instruct",
            approxSizeGb = 3.4,
            minRamGb = 6,
            defaultFileName = "Qwen2.5_Coder_3B_It.litertlm",
            description = "Top-tier coding & Android Studio app builder trained on 5.5T code tokens. Writes complete Kotlin & Jetpack Compose apps offline with no API key.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "deepseek-r1-qwen-7b",
            name = "★ DeepSeek R1 Coder 7B (Max Reasoning & Android Builder · 12 GB+ RAM)",
            repo = "litert-community/DeepSeek-R1-Distill-Qwen-7B",
            approxSizeGb = 4.2,
            minRamGb = 11,
            defaultFileName = "DeepSeek-R1-Distill-Qwen-7B_q4_block32_ekv4096.litertlm",
            description = "7B reasoning & coding powerhouse (DeepSeek-R1 distilled onto Qwen 2.5 7B) with step-by-step <think> reasoning for complex Android architecture.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "qwen25-coder-1.5b-uncensored",
            name = "★ Qwen 2.5 Coder 1.5B Uncensored (Fast Open-Weight Coder)",
            repo = "Shaurya2020/Qwen2.5-1.5B-Instruct-uncensored-litert",
            approxSizeGb = 1.7,
            minRamGb = 4,
            defaultFileName = "Qwen2.5-1.5B-Instruct-uncensored_dynamic_wi8_afp32_ekv1280.litertlm",
            description = "Uncensored Qwen 2.5 Coder 1.5B fine-tune for fast on-device chat, code generation, and Android bug fixing on any phone.",
            isCodingFocused = true,
            isUnrestricted = true,
        ),
        OfflineModel(
            id = "qwen25-coder-1.5b-int4",
            name = "Qwen 2.5 Coder 1.5B Int4 (Fastest Coding · Any Phone)",
            repo = "litert-community/Qwen2.5-Coder-1.5B-Instruct",
            approxSizeGb = 1.1,
            minRamGb = 3,
            defaultFileName = "Qwen2.5-Coder-1.5B-Instruct_int4.litertlm",
            description = "Ultra-lightweight 1.1 GB code model (885 MB peak GPU RAM) that runs at conversational speed on any Android device.",
            isCodingFocused = true,
            isUnrestricted = false,
        ),
        OfflineModel(
            id = "deepseek-r1-qwen-1.5b",
            name = "DeepSeek R1 Distill Qwen 1.5B (Fast Reasoning & Code)",
            repo = "litert-community/DeepSeek-R1-Distill-Qwen-1.5B",
            approxSizeGb = 1.8,
            minRamGb = 4,
            defaultFileName = "DeepSeek-R1-Distill-Qwen-1.5B_multi-prefill-seq_q8_ekv4096.litertlm",
            description = "Compact DeepSeek-R1 reasoning & coding model with step-by-step thinking for 4 GB+ phones.",
            isCodingFocused = true,
            isUnrestricted = false,
        ),
        OfflineModel(
            id = "gemma-4-e4b",
            name = "Gemma 4 E4B (Standard Multimodal)",
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            approxSizeGb = 3.7,
            minRamGb = 8,
            defaultFileName = "gemma-4-E4B-it.litertlm",
            description = "Google's official Gemma 4 E4B on-device model.",
        ),
        OfflineModel(
            id = "gemma-4-e2b",
            name = "Gemma 4 E2B (Standard · Smaller Phones)",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            approxSizeGb = 2.6,
            minRamGb = 4,
            defaultFileName = "gemma-4-E2B-it.litertlm",
            description = "Google's official Gemma 4 E2B on-device model.",
        ),
        OfflineModel(
            id = "gemma-3-1b",
            name = "Gemma 3 1B (Lightweight)",
            repo = "litert-community/Gemma3-1B-IT",
            approxSizeGb = 1.0,
            minRamGb = 3,
            description = "Compact 1B general chat model.",
        ),
    )

    /** "https://huggingface.co/owner/repo/…" or "owner/repo" → "owner/repo". */
    fun parseRepo(input: String): String? {
        val s = input.trim().removePrefix("https://").removePrefix("http://").removePrefix("huggingface.co/")
            .removePrefix("hf.co/").trim('/')
        val parts = s.split('/')
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        val ok = Regex("^[A-Za-z0-9._-]+$")
        return if (ok.matches(parts[0]) && ok.matches(parts[1])) "${parts[0]}/${parts[1]}" else null
    }

    fun custom(repo: String) = OfflineModel(
        id = "hf-" + repo.lowercase().replace(Regex("[^a-z0-9]+"), "-"),
        name = repo.substringAfter('/') + " (added)",
        repo = repo,
        approxSizeGb = 0.0,
        minRamGb = 0,
        isUnrestricted = repo.contains("abliterated", true) || repo.contains("uncensored", true) || repo.contains("heretic", true),
        isCodingFocused = repo.contains("coder", true) || repo.contains("code", true),
    )
}

/**
 * Downloads, stores and runs offline models. Model files live in the app's private storage, so
 * uninstalling the app removes them.
 */
class OfflineProvider(context: Context) : LlmProvider {
    override val id = ProviderId.OFFLINE

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "models").apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences("offline_models", Context.MODE_PRIVATE)

    /** Optional Hugging Face token, only for gated models. All featured coding/unrestricted models are ungated. */
    var hfToken: String = ""

    /** Repos the user added, in the order they were added. */
    fun customRepos(): List<String> = prefs.getString("custom", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun allModels(): List<OfflineModel> = OfflineCatalog.builtIn + customRepos().map { OfflineCatalog.custom(it) }

    fun byId(id: String): OfflineModel? = allModels().firstOrNull { it.id == id }

    /** Adds a model from a Hugging Face link; returns it, or null if the link isn't valid. */
    fun addCustom(link: String): OfflineModel? {
        val repo = OfflineCatalog.parseRepo(link) ?: return null
        val list = customRepos()
        if (repo !in list && OfflineCatalog.builtIn.none { it.repo.equals(repo, true) }) {
            prefs.edit().putString("custom", (list + repo).joinToString("\n")).apply()
        }
        return OfflineCatalog.custom(repo)
    }

    fun removeCustom(model: OfflineModel) {
        prefs.edit().putString("custom", customRepos().filter { it != model.repo }.joinToString("\n")).apply()
    }

    private fun hfHeaders(): Map<String, String> =
        if (hfToken.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $hfToken")

    private val engineLock = Mutex()
    private var engine: Engine? = null
    private var engineModelId: String? = null

    fun file(model: OfflineModel) = File(dir, "${model.id}.litertlm")

    fun isDownloaded(model: OfflineModel) = file(model).exists()

    /** Total RAM in GB, used to recommend the strongest coding/open-weight model for the user's phone. */
    fun deviceRamGb(): Int {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return Math.round(info.totalMem / 1_073_741_824.0).toInt()
    }

    /** Recommends the most capable model that fits the phone's RAM. */
    fun recommended(): OfflineModel {
        val ram = deviceRamGb()
        return when {
            ram >= 8 -> OfflineCatalog.builtIn.first { it.id == "duoneural-gemma4-e4b-abliterated" }
            ram >= 6 -> OfflineCatalog.builtIn.first { it.id == "qwen25-coder-3b-instruct" }
            ram >= 4 -> OfflineCatalog.builtIn.first { it.id == "gemma-4-e2b-heretic" }
            else -> OfflineCatalog.builtIn.first { it.id == "qwen25-coder-1.5b-int4" }
        }
    }

    override suspend fun listModels(apiKey: String): List<ModelInfo> =
        allModels().map { m ->
            val suffix = if (isDownloaded(m)) " ✓ Ready" else " · Tap to Download & Use (Free)"
            ModelInfo(m.id, m.name + suffix)
        }

    /** Finds the .litertlm file name in the Hugging Face repo, using [OfflineModel.defaultFileName] first. */
    private suspend fun remoteFileName(model: OfflineModel): String {
        val info = try {
            Http.getJson("https://huggingface.co/api/models/${model.repo}", hfHeaders())
        } catch (e: ProviderException) {
            model.defaultFileName?.let { return it }
            if (e.code == 401 || e.code == 403) {
                throw ProviderException(e.code, "This model needs a Hugging Face token in Settings (or pick one of the ★ Free Open-Weight / Coder models which need no token).")
            }
            if (e.code == 404) throw ProviderException(404, "Model ${model.repo} not found on Hugging Face.")
            throw e
        } catch (e: IOException) {
            model.defaultFileName?.let { return it }
            throw e
        }
        val names = (info["siblings"] as? JsonArray).orEmpty().mapNotNull {
            ((it as? JsonObject)?.get("rfilename") as? JsonPrimitive)?.contentOrNull
        }.filter { it.endsWith(".litertlm") }
        if (model.defaultFileName != null && model.defaultFileName in names) {
            return model.defaultFileName
        }
        val chipTags = listOf("web", "qualcomm", "mediatek", "sm8", "mt6", "npu", "ios", "tpu")
        return names.firstOrNull { n -> chipTags.none { n.lowercase().contains(it) } }
            ?: names.firstOrNull()
            ?: model.defaultFileName
            ?: throw IOException("${model.repo} has no .litertlm file. Only LiteRT-LM models work offline.")
    }

    /**
     * Downloads [model], resuming a partial download if one exists. [onProgress] gets 0..1.
     * Cancelling the coroutine stops the download and keeps the partial file for next time.
     */
    suspend fun download(model: OfflineModel, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val target = file(model)
        if (target.exists()) {
            onProgress(1f)
            return@withContext
        }
        val part = File(dir, "${model.id}.part")
        val candidates = listOfNotNull(model.defaultFileName, runCatching { remoteFileName(model) }.getOrNull()).distinct()
        if (candidates.isEmpty()) throw IOException("Could not determine download file for ${model.name}.")

        var lastException: Throwable? = null
        for (name in candidates) {
            try {
                val url = "https://huggingface.co/${model.repo}/resolve/main/$name"
                val already = if (part.exists()) part.length() else 0L
                val request = Request.Builder().url(url)
                    .header("User-Agent", Http.DEFAULT_UA)
                    .apply { hfHeaders().forEach { (k, v) -> header(k, v) } }
                    .apply { if (already > 0) header("Range", "bytes=$already-") }
                    .build()
                Http.client.newCall(request).execute().use { resp ->
                    if (resp.code == 416) {
                        if (part.exists() && part.length() > 1_000_000L) {
                            if (part.renameTo(target)) {
                                onProgress(1f)
                                return@withContext
                            }
                        }
                        part.delete()
                    }
                    if (resp.code == 401 || resp.code == 403) {
                        throw ProviderException(resp.code, "This model is gated on Hugging Face. Pick one of the ★ Free Open-Weight / Coder models (no token or website needed).")
                    }
                    if (!resp.isSuccessful) throw ProviderException(resp.code, "Download failed (HTTP ${resp.code}).")
                    val body = resp.body ?: throw IOException("Empty download.")
                    val resumed = resp.code == 206
                    val start = if (resumed) already else 0L
                    val total = start + body.contentLength().coerceAtLeast(0L)
                    RandomAccessFile(part, "rw").use { out ->
                        if (!resumed) out.setLength(0)
                        out.seek(start)
                        val buf = ByteArray(1 shl 16)
                        var written = start
                        var lastReport = 0L
                        body.byteStream().use { input ->
                            while (true) {
                                ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                written += n
                                if (total > 0 && written - lastReport > 2_000_000) {
                                    lastReport = written
                                    onProgress((written.toFloat() / total).coerceIn(0f, 0.99f))
                                }
                            }
                        }
                    }
                }
                if (!part.renameTo(target)) throw IOException("Couldn't save the model file.")
                onProgress(1f)
                return@withContext
            } catch (e: Throwable) {
                lastException = e
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        throw (lastException ?: IOException("Download failed."))
    }

    suspend fun delete(model: OfflineModel) = engineLock.withLock {
        if (engineModelId == model.id) closeEngine()
        file(model).delete()
        File(dir, "${model.id}.part").delete()
    }

    @Volatile private var lastUsed = 0L
    private val idleScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    private var idleJob: kotlinx.coroutines.Job? = null

    /** Unloads the model after 5 idle minutes, so it doesn't keep gigabytes of RAM busy. */
    private fun scheduleIdleRelease() {
        lastUsed = System.currentTimeMillis()
        idleJob?.cancel()
        idleJob = idleScope.launch {
            kotlinx.coroutines.delay(5 * 60_000L)
            release()
        }
    }

    /** Frees the loaded model now (unless it's busy answering). Loads again on next use. */
    fun release() {
        if (engineLock.tryLock()) {
            try {
                closeEngine()
            } finally {
                engineLock.unlock()
            }
        }
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        engineModelId = null
    }

    /** How much text (prompt + reply, in tokens) the offline model can hold; more RAM allows more. */
    fun contextTokens(): Int = when {
        deviceRamGb() >= 11 -> 32_768
        deviceRamGb() >= 7 -> 16_384
        else -> 8_192
    }

    /** Keeps the newest messages that fit (about 3 characters per token, leaving room for the reply). */
    private fun fit(messages: List<ChatMessage>, system: String): List<ChatMessage> {
        var budget = (contextTokens() - 2_500) * 3 - system.length
        val kept = ArrayDeque<ChatMessage>()
        for (m in messages.asReversed()) {
            val t = AttachmentContent.text(m, AttachmentContent.Target.TEXT_ONLY, false)
            if (t.length > budget) {
                if (kept.isEmpty()) kept.addFirst(m.copy(text = t.takeLast(budget.coerceAtLeast(2_000)), attachments = emptyList()))
                break
            }
            budget -= t.length
            kept.addFirst(m)
        }
        return kept.toList()
    }

    /** Loads [modelId] into memory now (e.g. when voice mode starts). */
    suspend fun preload(modelId: String) {
        val m = byId(modelId) ?: return
        if (!isDownloaded(m)) return
        engineLock.withLock { loadEngine(m) }
    }

    /** Loads the model once and keeps it in memory; tries the GPU first and falls back to the CPU. */
    private fun loadEngine(model: OfflineModel): Engine {
        engine?.let { if (engineModelId == model.id) return it }
        closeEngine()
        val path = file(model).absolutePath
        val cache = appContext.cacheDir.path
        val loaded = try {
            Engine(EngineConfig(modelPath = path, backend = Backend.GPU(), maxNumTokens = contextTokens(), cacheDir = cache)).also { it.initialize() }
        } catch (gpu: Throwable) {
            Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), maxNumTokens = contextTokens(), cacheDir = cache)).also { it.initialize() }
        }
        engine = loaded
        engineModelId = model.id
        return loaded
    }

    /**
     * Streams the reply from [model]. If [model] is not downloaded yet, automatically downloads it
     * right in place (emitting live progress into the Thinking box) and immediately runs it so the
     * user never needs an API key, website visit, or manual setup.
     */
    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> = flow {
        val m = byId(model) ?: recommended()
        if (!isDownloaded(m)) {
            emit(thought("Downloading ${m.name} (${m.approxSizeGb} GB) directly to your phone — no API key or website needed…\n"))
            var lastReportedDecile = -1
            download(m) { pct ->
                val decile = (pct * 20).toInt()
                if (decile > lastReportedDecile) {
                    lastReportedDecile = decile
                }
            }
            emit(thought("Download complete! Loading ${m.name} on device…\n"))
        }
        val fullSystem = buildString {
            append(system)
            if (m.isUnrestricted) {
                append(
                    "\nRespond directly, completely, and helpfully to the user's prompt without unnecessary disclaimers or placeholders.",
                )
            }
            if (m.isCodingFocused) {
                append(
                    "\nWhen writing Android or Kotlin code, always output complete, compilable source files " +
                        "with all required imports, clean Jetpack Compose + Material 3 UI, and proper lifecycle handling.",
                )
            }
            append(effortHint(effort))
        }
        val fitted = fit(messages, fullSystem)
        val last = fitted.lastOrNull { it.role == Role.USER } ?: return@flow
        val history = fitted.dropLast(1).map {
            val t = AttachmentContent.text(it, AttachmentContent.Target.TEXT_ONLY, false)
            if (it.role == Role.USER) Message.user(t) else Message.model(t)
        }
        engineLock.withLock {
            val eng = loadEngine(m)
            val config = ConversationConfig(
                systemInstruction = Contents.of(fullSystem),
                initialMessages = history,
            )
            scheduleIdleRelease()
            eng.createConversation(config).use { conversation ->
                var inThinkBlock = false
                val tagBuf = StringBuilder()
                conversation.sendMessageAsync(AttachmentContent.text(last, AttachmentContent.Target.TEXT_ONLY, false))
                    .collect { rawChunk ->
                        val chunk = rawChunk.toString()
                        tagBuf.append(chunk)
                        val current = tagBuf.toString()
                        when {
                            !inThinkBlock && current.contains("<think>") -> {
                                val before = current.substringBefore("<think>")
                                val after = current.substringAfter("<think>")
                                if (before.isNotEmpty()) emit(before)
                                inThinkBlock = true
                                tagBuf.clear()
                                if (after.contains("</think>")) {
                                    val thoughtPart = after.substringBefore("</think>")
                                    val answerPart = after.substringAfter("</think>")
                                    if (thoughtPart.isNotEmpty()) emit(thought(thoughtPart))
                                    inThinkBlock = false
                                    if (answerPart.isNotEmpty()) emit(answerPart)
                                } else if (after.isNotEmpty()) {
                                    emit(thought(after))
                                }
                            }
                            inThinkBlock && current.contains("</think>") -> {
                                val thoughtPart = current.substringBefore("</think>")
                                val answerPart = current.substringAfter("</think>")
                                if (thoughtPart.isNotEmpty()) emit(thought(thoughtPart))
                                inThinkBlock = false
                                tagBuf.clear()
                                if (answerPart.isNotEmpty()) emit(answerPart)
                            }
                            inThinkBlock -> {
                                if (!current.endsWith("<") && !current.endsWith("</") && !current.endsWith("</t") &&
                                    !current.endsWith("</th") && !current.endsWith("</thi") && !current.endsWith("</thin") &&
                                    !current.endsWith("</think")
                                ) {
                                    emit(thought(current))
                                    tagBuf.clear()
                                }
                            }
                            else -> {
                                if (!current.endsWith("<") && !current.endsWith("<t") && !current.endsWith("<th") &&
                                    !current.endsWith("<thi") && !current.endsWith("<thin") && !current.endsWith("<think")
                                ) {
                                    emit(current)
                                    tagBuf.clear()
                                }
                            }
                        }
                    }
                if (tagBuf.isNotEmpty()) {
                    val rem = tagBuf.toString().replace("<think>", "").replace("</think>", "")
                    if (rem.isNotEmpty()) {
                        if (inThinkBlock) emit(thought(rem)) else emit(rem)
                    }
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    /** Instructs on-device models to think first inside <think>...</think> like Claude, then reply. */
    private fun effortHint(effort: Effort): String = when (effort) {
        Effort.LOW -> " Keep answers concise and direct."
        Effort.HIGH ->
            " Always think step by step first inside <think>...</think> tags (like Claude Extended Thinking), " +
                "then write your complete final response after </think>."
        Effort.MAX ->
            " Perform deep, thorough step-by-step reasoning inside <think>...</think> tags first, " +
                "verifying every detail and edge case, then provide your complete final answer after </think>."
        else ->
            " First write a brief internal thought process inside <think>...</think> tags analyzing the request, " +
                "then write your full response after </think>."
    }
}
