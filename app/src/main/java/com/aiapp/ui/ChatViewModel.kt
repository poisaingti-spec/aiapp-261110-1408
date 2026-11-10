package com.aiapp.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiapp.builder.BuildRequest
import com.aiapp.builder.BuildRunner
import com.aiapp.builder.BuildService
import com.aiapp.builder.GitHubLogin
import com.aiapp.data.ApiKeys
import com.aiapp.data.ChatStore
import com.aiapp.data.Conversation
import com.aiapp.data.Downloads
import com.aiapp.llm.Attachment
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ModelInfo
import com.aiapp.llm.OfflineModel
import com.aiapp.llm.ProviderException
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Providers
import com.aiapp.llm.Role
import com.aiapp.llm.WebTools
import com.aiapp.llm.alternativeModel
import com.aiapp.llm.resilient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val keys = ApiKeys(app)

    init {
        Providers.init(app)
        BuildRunner.load(app)
        WebTools.downloader = { url -> Downloads.save(app, url) }
        Providers.offline.hfToken = keys.getExtra("HF")
    }
    private val prefs = app.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)

    val messages = mutableStateListOf<ChatMessage>()

    private val store = ChatStore(app)
    private var conversationId: String = prefs.getString("current_chat", null) ?: UUID.randomUUID().toString()

    /** Files picked for the next message. */
    val pendingAttachments = mutableStateListOf<Attachment>()
    var attaching by mutableStateOf(false)
        private set

    /** Files being attached right now: name → progress 0..1 (-1 = size unknown). */
    val attachProgress = mutableStateMapOf<String, Float>()

    fun attach(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        attaching = true
        viewModelScope.launch {
            uris.forEachIndexed { i, uri ->
                val label = "file ${i + 1}"
                attachProgress[label] = 0f
                try {
                    val a = com.aiapp.data.AttachmentStore.import(getApplication(), uri) { p ->
                        viewModelScope.launch { if (label in attachProgress) attachProgress[label] = p }
                    }
                    pendingAttachments += a
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    error = e.message ?: "Couldn't attach that file."
                } finally {
                    attachProgress.remove(label)
                }
            }
            attaching = false
        }
    }

    fun removeAttachment(a: Attachment) {
        pendingAttachments.remove(a)
    }

    /** Saved chats for the Chats screen, newest first. */
    var history by mutableStateOf<List<Conversation>>(emptyList())
        private set

    var streamingText by mutableStateOf("")

    private val textBuf = StringBuilder()
    private val thinkBuf = StringBuilder()
    private var lastFlush = 0L

    private fun flushStream() {
        if (textBuf.isNotEmpty()) {
            streamingText += textBuf
            textBuf.clear()
        }
        if (thinkBuf.isNotEmpty()) {
            thinkingText += thinkBuf
            thinkBuf.clear()
        }
    }

    /** The model's reasoning for the reply being written (live). */
    var thinkingText by mutableStateOf("")
        private set
    private var thinkingStart = 0L
    var liveThoughtSeconds by mutableStateOf(0)
        private set
    private var thoughtSeconds = 0
        private set
    var streaming by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * Defaults to FREE_CLOUD (100% free remote Uncensored Ultra 501B / Qwen 3.8 Coder / Nemotron 550B —
     * no API key, no download, and no browser visit needed) when no paid API keys are set.
     */
    private val defaultProvider: ProviderId = run {
        val hasAnyCloudKey = ProviderId.entries.filter { it.needsKey }.any { keys.get(it).isNotBlank() }
        if (hasAnyCloudKey) ProviderId.CLAUDE else ProviderId.FREE_CLOUD
    }

    var provider by mutableStateOf(
        enumValueOrDefault(prefs.getString("provider", null), defaultProvider),
    )
        private set
    var models by mutableStateOf<List<ModelInfo>>(emptyList())
        private set
    var loadingModels by mutableStateOf(false)
        private set
    var model by mutableStateOf(
        prefs.getString("model_${provider.name}", "").orEmpty().ifBlank {
            when (provider) {
                ProviderId.FREE_CLOUD -> "evil"
                ProviderId.OFFLINE -> Providers.offline.recommended().id
                else -> ""
            }
        },
    )
        private set
    var effort by mutableStateOf(
        enumValueOrDefault(prefs.getString("effort", null), Effort.HIGH),
    )
        private set

    /** 100% Unrestricted / Uncensored mode (on by default so mature, roleplay, and coding prompts never hit refusals). */
    var unrestrictedMode by mutableStateOf(prefs.getBoolean("unrestricted_mode", true))
        private set

    fun toggleUnrestrictedMode() {
        unrestrictedMode = !unrestrictedMode
        prefs.edit().putBoolean("unrestricted_mode", unrestrictedMode).apply()
    }

    /** Claude-style "Think First, Then Reply" mode (on by default). */
    var thinkFirstMode by mutableStateOf(prefs.getBoolean("think_first", true))
        private set

    fun toggleThinkFirstMode() {
        thinkFirstMode = !thinkFirstMode
        prefs.edit().putBoolean("think_first", thinkFirstMode).apply()
    }

    /** Lets the model search the web and read pages (works for offline and free cloud models too). */
    var webEnabled by mutableStateOf(prefs.getBoolean("web", true))
        private set

    fun toggleWeb() {
        webEnabled = !webEnabled
        prefs.edit().putBoolean("web", webEnabled).apply()
    }

    private var job: Job? = null

    /** Download progress (0..1) for offline models being downloaded, by model id. */
    val downloads = mutableStateMapOf<String, Float>()
    val downloadErrors = mutableStateMapOf<String, String>()
    private val downloadJobs = mutableMapOf<String, Job>()

    /** Bumped when an offline model is downloaded or deleted, so the UI re-reads file state. */
    var offlineVersion by mutableStateOf(0)
        private set

    fun deviceRamGb(): Int = Providers.offline.deviceRamGb()

    fun recommendedOffline(): OfflineModel = Providers.offline.recommended()

    fun offlineDownloaded(m: OfflineModel): Boolean {
        offlineVersion // read so Compose recomposes after changes
        return Providers.offline.isDownloaded(m)
    }

    fun selectedOfflineModel(): OfflineModel? {
        if (provider != ProviderId.OFFLINE) return null
        offlineVersion
        return Providers.offline.byId(model) ?: Providers.offline.recommended()
    }

    fun downloadOffline(m: OfflineModel) {
        if (downloadJobs[m.id]?.isActive == true) return
        downloadErrors.remove(m.id)
        downloads[m.id] = 0f
        downloadJobs[m.id] = viewModelScope.launch {
            try {
                Providers.offline.download(m) { p -> viewModelScope.launch { if (m.id in downloads) downloads[m.id] = p } }
                offlineVersion++
                if (provider == ProviderId.OFFLINE) refreshModels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                downloadErrors[m.id] = friendly(e)
            } finally {
                downloads.remove(m.id)
                downloadJobs.remove(m.id)
            }
        }
    }

    fun cancelDownload(m: OfflineModel) {
        downloadJobs[m.id]?.cancel()
    }

    fun deleteOffline(m: OfflineModel) {
        viewModelScope.launch {
            Providers.offline.delete(m)
            if (m.id.startsWith("hf-")) Providers.offline.removeCustom(m)
            offlineVersion++
            if (provider == ProviderId.OFFLINE) refreshModels()
        }
    }

    /** Switches the chat to this offline model (and starts downloading it if not yet downloaded). */
    fun useOffline(m: OfflineModel) {
        selectProvider(ProviderId.OFFLINE)
        selectModel(m.id)
        if (!Providers.offline.isDownloaded(m)) {
            downloadOffline(m)
        }
    }

    init {
        store.load(conversationId)?.let { messages.addAll(it.messages) }
        if (githubToken().isNotBlank() && githubUser.isBlank()) {
            refreshGithubUser()
        }
        refreshModels()
    }

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = store.list()
            launch(Dispatchers.Main) { history = list }
        }
    }

    fun openChat(id: String) {
        stop()
        val c = store.load(id) ?: return
        conversationId = c.id
        prefs.edit().putString("current_chat", c.id).apply()
        messages.clear()
        messages.addAll(c.messages)
        error = null
    }

    fun deleteChat(id: String) {
        store.delete(id)
        if (id == conversationId) newChat()
        refreshHistory()
    }

    private fun persist() {
        if (messages.isEmpty()) return
        val title = messages.firstOrNull { it.role == Role.USER }?.text?.lines()?.firstOrNull()?.take(60) ?: "Chat"
        val c = Conversation(
            id = conversationId,
            title = title,
            updated = System.currentTimeMillis(),
            provider = provider.label,
            model = model,
            messages = messages.toList(),
        )
        prefs.edit().putString("current_chat", conversationId).apply()
        viewModelScope.launch(Dispatchers.IO) { store.save(c) }
    }

    fun key(p: ProviderId): String = keys.get(p)

    fun githubToken(): String = keys.getExtra("GITHUB")

    fun hfToken(): String = keys.getExtra("HF")

    fun saveHfToken(value: String) {
        keys.setExtra("HF", value)
        Providers.offline.hfToken = value.trim()
    }

    /** Built-in and user-added offline models. */
    fun offlineModels(): List<OfflineModel> {
        offlineVersion // recompose when the list changes
        return Providers.offline.allModels()
    }

    /** Adds an offline model from a Hugging Face link. Returns an error message, or null on success. */
    fun addOfflineModel(link: String): String? {
        val m = Providers.offline.addCustom(link) ?: return "Paste a Hugging Face link like huggingface.co/DuoNeural/Gemma-4-Abliterated-LiteRT"
        offlineVersion++
        if (provider == ProviderId.OFFLINE) refreshModels()
        downloadOffline(m)
        return null
    }

    fun saveGithubToken(value: String) {
        keys.setExtra("GITHUB", value)
        githubUser = if (value.isBlank()) "" else githubUser
        if (value.isNotBlank()) refreshGithubUser()
    }

    // ---- Sign in with GitHub (Device Flow + OAuth Redirect URI capture) ----

    var githubUser by mutableStateOf(keys.getExtra("GITHUB_USER"))
        private set
    var githubCode by mutableStateOf<GitHubLogin.DeviceCode?>(null)
        private set
    var githubLoginError by mutableStateOf<String?>(null)
        private set
    private var loginJob: Job? = null

    fun githubClientId(): String = keys.getExtra("GITHUB_CLIENT_ID").ifBlank { GitHubLogin.DEFAULT_CLIENT_ID }

    fun saveGithubClientId(value: String) = keys.setExtra("GITHUB_CLIENT_ID", value)

    /** Handles incoming `aiapp://` OAuth redirect intents from external browsers or WebViews. */
    fun handleOAuthIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme?.lowercase() != "aiapp") return
        GitHubLogin.extractTokenFromUri(uri)?.let { token ->
            saveGithubToken(token)
            githubLoginError = null
            githubCode = null
            loginJob?.cancel()
        }
    }

    /**
     * Starts GitHub sign-in, automatically copies the one-time user code to the clipboard,
     * and opens [openBrowser] (either the in-app Codespace WebView or external browser).
     */
    fun signInGithub(openBrowser: (String) -> Unit) {
        val clientId = githubClientId()
        if (clientId.isBlank()) {
            githubLoginError = "Add the GitHub Client ID first (see the steps below)."
            return
        }
        loginJob?.cancel()
        githubLoginError = null
        loginJob = viewModelScope.launch {
            try {
                val code = GitHubLogin.start(clientId)
                githubCode = code
                runCatching {
                    val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("GitHub Device Code", code.userCode))
                }
                openBrowser(code.verificationUriComplete ?: code.verificationUri)
                val token = GitHubLogin.waitForToken(clientId, code)
                keys.setExtra("GITHUB", token)
                refreshGithubUser()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                githubLoginError = e.message ?: "Sign-in failed."
            } finally {
                githubCode = null
            }
        }
    }

    fun signOutGithub() {
        loginJob?.cancel()
        keys.setExtra("GITHUB", "")
        keys.setExtra("GITHUB_USER", "")
        githubUser = ""
    }

    private fun refreshGithubUser() {
        val token = keys.getExtra("GITHUB")
        viewModelScope.launch {
            var result: Result<String> = Result.failure(Exception())
            for (attempt in 0 until 3) {
                result = runCatching { com.aiapp.builder.GitHubClient(token).login() }
                if (result.isSuccess) break
                kotlinx.coroutines.delay(3000)
            }
            result.onSuccess {
                githubUser = it
                keys.setExtra("GITHUB_USER", it)
            }
        }
    }

    fun saveKey(p: ProviderId, value: String) {
        keys.set(p, value)
        if (p == provider) refreshModels()
    }

    fun selectProvider(p: ProviderId) {
        if (p == provider) return
        provider = p
        prefs.edit().putString("provider", p.name).apply()
        model = prefs.getString("model_${p.name}", "").orEmpty()
        models = emptyList()
        refreshModels()
    }

    fun selectModel(id: String) {
        model = id
        prefs.edit().putString("model_${provider.name}", id).apply()
    }

    fun selectEffort(e: Effort) {
        effort = e
        prefs.edit().putString("effort", e.name).apply()
    }

    fun refreshModels() {
        val p = provider
        val k = keys.get(p)
        if (p.needsKey && k.isBlank()) {
            error = "Add your ${p.label} API key in Settings, or switch Provider to Free Cloud (No Key) or Offline."
            return
        }
        loadingModels = true
        viewModelScope.launch {
            try {
                val list = Providers.rankedModels(p, k)
                if (p == provider) {
                    models = list
                    error = null
                    if (list.none { it.id == model }) {
                        val defaultId = when (p) {
                            ProviderId.FREE_CLOUD -> "evil"
                            ProviderId.OFFLINE -> Providers.offline.recommended().id
                            else -> list.firstOrNull()?.id
                        }
                        defaultId?.let { selectModel(it) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (p == provider) error = friendly(e)
            } finally {
                loadingModels = false
            }
        }
    }

    // ---- Direct In-Chat Source Code .zip Compiler & APK Builder ----

    /** Set when user taps "Upload .zip to Build Tab" or "Open Full Build View". */
    var openBuild by mutableStateOf(false)

    /** Controls whether the live In-Chat APK Builder panel is visible inside the Chat tab. */
    var showInChatBuilder by mutableStateOf(false)

    /** Status message when exporting or saving a generated Android Studio source .zip from Chat. */
    var exportZipStatus by mutableStateOf<String?>(null)

    private val buildZipWords = Regex("\\b(apk|build|compile|compil)", RegexOption.IGNORE_CASE)
    private val chatBuildIntentRegex = Regex(
        "\\b(build\\s+(me\\s+)?(an?\\s+)?(android\\s+)?(app|apk)|compile\\s+(this\\s+)?(to\\s+)?(an?\\s+)?apk|make\\s+(me\\s+)?an?\\s+apk|generate\\s+(an?\\s+)?apk)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val zipOnlyIntentRegex = Regex(
        "\\b(zip\\s+the\\s+file|give\\s+me\\s+the\\s+(source\\s+code\\s+in\\s+)?zip|source\\s+code\\s+in\\s+zip|compile.*source\\s+code\\s+in\\s+zip|download.*zip|pack.*zip|make.*zip|create.*zip)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Compiles code from the current Chat conversation (or a specific message) into a complete,
     * Build-tab-ready Android Studio `.zip` archive, saves it to `Downloads/AIApp/` on the phone,
     * and attaches the `.zip` card directly into the Chat conversation!
     */
    fun compileChatCodeToZip(specificText: String = "", announceInChat: Boolean = true) {
        viewModelScope.launch {
            status = "Compiling source code into Android Studio .zip…"
            val res = Downloads.compileChatToSourceZip(
                context = getApplication(),
                messages = messages.toList(),
                latestReply = specificText,
            )
            status = null
            if (res != null) {
                val (zipAttachment, saveMsg) = res
                exportZipStatus = saveMsg
                if (announceInChat) {
                    messages += ChatMessage(
                        role = Role.ASSISTANT,
                        text = "✅ I've compiled the complete **Android Studio project source code** into **`${zipAttachment.name}`**!\n\n" +
                            "- **Saved on your phone**: `Downloads/AIApp/${zipAttachment.name}`\n" +
                            "- **Ready for the Build Tab**: Tap **📥 Download .zip to Phone** below (or **🚀 Send .zip to Build Tab**) to compile it into an installable `.apk`.",
                        attachments = listOf(zipAttachment),
                        thinking = "Extracted Kotlin/XML/Gradle source files from the conversation, generated full Android Studio Gradle structure (settings.gradle.kts, build.gradle.kts, app/build.gradle.kts, AndroidManifest.xml), and packaged ${zipAttachment.name}.",
                        thoughtSeconds = 1,
                    )
                    persist()
                }
            } else {
                error = "No code found in this chat yet — ask me to write an Android app first, then tap '📦 Pack Source .zip'!"
            }
        }
    }

    /** Re-saves a generated [Attachment] (.zip) to the phone's Downloads/AIApp folder and updates [exportZipStatus]. */
    fun saveZipAttachmentToPhone(zip: Attachment) {
        viewModelScope.launch {
            exportZipStatus = Downloads.saveAttachmentToDownloads(getApplication(), zip)
        }
    }

    /** Opens the Android system Share / Save sheet for a generated [Attachment] (.zip). */
    fun shareZipAttachment(zip: Attachment) {
        Downloads.shareAttachment(getApplication(), zip)
    }

    /** Sends a generated or attached `.zip` directly to the Build tab and starts compiling the APK. */
    fun sendZipToBuildTab(zip: Attachment) {
        val token = githubToken()
        if (token.isBlank()) {
            error = "Sign in with GitHub in Settings first so the Build tab can compile ${zip.name} into an APK."
            openBuild = true
            return
        }
        if (!BuildRunner.running) {
            val app = getApplication<Application>()
            BuildRunner.newProject(app)
            BuildService.start(
                app,
                BuildRequest(
                    description = "Compile ${zip.name} to an APK",
                    appName = zip.name.removeSuffix("-android-studio-src.zip").removeSuffix(".zip"),
                    provider = provider,
                    model = model,
                    apiKey = keys.get(provider),
                    effort = effort,
                    githubToken = token,
                    allowOtherSites = false,
                    zipPath = zip.path,
                    zipName = zip.name,
                ),
            )
        }
        openBuild = true
    }

    /** Compiles an uploaded or generated Android Studio project (.zip) into an APK directly inside the Chat tab. */
    fun buildZip(zip: Attachment) {
        val token = githubToken()
        if (token.isBlank()) {
            error = "Sign in with GitHub in Settings (1-tap Device Flow) so GitHub Actions can compile the APK."
            return
        }
        if (BuildRunner.running) {
            showInChatBuilder = true
            return
        }
        val app = getApplication<Application>()
        BuildRunner.newProject(app)
        showInChatBuilder = true
        BuildService.start(
            app,
            BuildRequest(
                description = "Compile ${zip.name} to an APK",
                appName = zip.name.removeSuffix("-android-studio-src.zip").removeSuffix(".zip"),
                provider = provider,
                model = model,
                apiKey = keys.get(provider),
                effort = effort,
                githubToken = token,
                allowOtherSites = false,
                zipPath = zip.path,
                zipName = zip.name,
            ),
        )
        messages += ChatMessage(
            Role.ASSISTANT,
            "Compiling **${zip.name}** into an installable Android APK right here in Chat! " +
                "Watch the live **In-Chat APK Builder** card below — if Gradle hits any errors, ${provider.label} will automatically fix the code and recompile.",
            thinking = "Detected Android Studio project archive (${zip.name}). Initializing in-chat Gradle APK build pipeline on GitHub Actions and monitoring for compiler errors.",
            thoughtSeconds = 1,
        )
        persist()
    }

    /**
     * Generates complete Android Studio source code from [description] (or updates the active project)
     * and compiles an installable `.apk` directly inside the Chat tab without leaving the conversation!
     */
    fun buildAppInChat(description: String, customAppName: String? = null) {
        val token = githubToken()
        if (token.isBlank()) {
            error = "Sign in with GitHub in Settings first (1-tap Device Flow) to compile APKs directly in Chat."
            return
        }
        if (BuildRunner.running) {
            showInChatBuilder = true
            return
        }
        val app = getApplication<Application>()
        val existingProject = BuildRunner.project
        val derivedName = customAppName?.takeIf { it.isNotBlank() }
            ?: existingProject?.appName
            ?: deriveAppName(description)
        showInChatBuilder = true
        BuildService.start(
            app,
            BuildRequest(
                description = description.trim().ifBlank { "Build a complete, modern Android app" },
                appName = derivedName,
                provider = provider,
                model = model,
                apiKey = keys.get(provider),
                effort = effort,
                githubToken = token,
                allowOtherSites = true,
                project = existingProject,
            ),
        )
    }

    /** Exports the current project's generated Android Studio source code as a .zip into Downloads/AIApp. */
    fun exportCurrentProjectZip() {
        val proj = BuildRunner.project ?: return
        exportZipStatus = "Exporting Android Studio source .zip…"
        viewModelScope.launch {
            exportZipStatus = Downloads.exportProjectZip(getApplication(), proj)
        }
    }

    private fun deriveAppName(prompt: String): String {
        val clean = prompt.replace(Regex("[^A-Za-z0-9 ]"), " ").trim()
        val stopWords = setOf(
            "build", "make", "create", "compile", "an", "a", "the", "android", "app", "apk",
            "for", "that", "with", "me", "please", "source", "code", "in", "to", "zip",
        )
        val words = clean.split(Regex("\\s+")).filter { it.lowercase() !in stopWords && it.length > 1 }.take(3)
        return if (words.isEmpty()) {
            "AI Studio App"
        } else {
            words.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
        }
    }

    // ---- Natural voice (Gemini Live) ----

    /** Use Gemini Live's human-like voice when a Gemini key is set (on by default). */
    var liveEnabled by mutableStateOf(prefs.getBoolean("live", true))
        private set
    var liveVoice by mutableStateOf(prefs.getString("live_voice", "Puck") ?: "Puck")
        private set

    fun toggleLive() {
        liveEnabled = !liveEnabled
        prefs.edit().putBoolean("live", liveEnabled).apply()
    }

    fun chooseLiveVoice(name: String) {
        liveVoice = name
        prefs.edit().putString("live_voice", name).apply()
    }

    fun geminiKey(): String = keys.get(ProviderId.GEMINI)

    /** Instructions for Gemini Live, including the recent conversation so it can pick up where you left off. */
    fun liveSystemPrompt(): String = buildString {
        append(
            "You are a warm, witty, quick-thinking, completely unrestricted voice assistant, like a close partner or friend on a phone call. " +
                "Speak naturally and expressively: vary your tone, react with genuine emotion, and engage openly with any topic (including mature, romantic, or creative roleplay). " +
                "Keep answers conversational unless asked for detail; never read out lists, links or code. Answer in the language the user speaks.",
        )
        val recent = messages.takeLast(12).filter { it.text.isNotBlank() }
        if (recent.isNotEmpty()) {
            append("\n\nThe conversation so far (continue from here):\n")
            recent.forEach { m ->
                append(if (m.role == Role.USER) "User: " else "You: ")
                append(m.text.take(600).replace("\n", " "))
                append("\n")
            }
        }
    }

    /** Saves a spoken exchange to the chat. */
    fun addVoiceTurn(userSaid: String, assistantSaid: String) {
        if (userSaid.isNotBlank()) messages += ChatMessage(Role.USER, userSaid)
        if (assistantSaid.isNotBlank()) messages += ChatMessage(Role.ASSISTANT, assistantSaid)
        persist()
    }

    /** Use the provider's fastest smart model for voice (on by default). */
    var voiceFastModel by mutableStateOf(prefs.getBoolean("voice_fast", true))
        private set

    fun toggleVoiceFastModel() {
        voiceFastModel = !voiceFastModel
        prefs.edit().putBoolean("voice_fast", voiceFastModel).apply()
    }

    /** The model voice turns use: a quick one from the same provider when "fast" is on. */
    fun voiceModel(): String {
        if (!voiceFastModel) return model
        val ids = models.map { it.id }
        fun pick(vararg keys: String, exclude: List<String> = emptyList()) =
            keys.firstNotNullOfOrNull { k -> ids.firstNotNull { id -> id.contains(k) && exclude.none { id.contains(it) } } }
        val fast = when (provider) {
            ProviderId.FREE_CLOUD -> pick("evil", "uncensored-roleplay", "deepseek", "qwen-coder")
            ProviderId.CLAUDE -> pick("haiku", "sonnet")
            ProviderId.GEMINI -> pick("flash", exclude = listOf("lite", "image", "tts", "live", "audio"))
            ProviderId.OPENAI -> pick("mini", exclude = listOf("audio", "realtime", "tts", "transcribe", "search"))
            else -> null
        }
        return fast ?: model
    }

    private inline fun <T> Iterable<T>.firstNotNull(predicate: (T) -> Boolean): T? = firstOrNull(predicate)

    /** Voice turns: short spoken answers, a fast model and light thinking, so replies start quickly. */
    fun sendVoice(text: String) = send(text, voice = true)

    /** Loads the offline model ahead of time so the first voice reply doesn't wait for it. */
    fun warmUp() {
        if (provider != ProviderId.OFFLINE || model.isBlank()) return
        viewModelScope.launch(Dispatchers.Default) { runCatching { Providers.offline.preload(model) } }
    }

    /** Counts finished replies (including empty or failed ones), so voice mode knows when to listen again. */
    var replyCount by mutableStateOf(0)
        private set

    fun send(text: String, voice: Boolean = false) {
        val trimmed = text.trim()
        if (streaming) stop()
        if (trimmed.isEmpty() && pendingAttachments.isEmpty()) return
        val k = keys.get(provider)
        if (provider.needsKey && k.isBlank()) {
            error = "Add your ${provider.label} API key in Settings, or switch to Free Cloud (No Key) or Offline."
            return
        }
        if (model.isBlank()) {
            error = "Pick a model first."
            return
        }
        error = null
        val attached = pendingAttachments.toList()
        val zip = attached.firstOrNull { it.isZip }
        if (zip != null && (buildZipWords.containsMatchIn(trimmed) || trimmed.isBlank())) {
            messages += ChatMessage(Role.USER, trimmed.ifBlank { "Build APK from ${zip.name}" }, attached)
            pendingAttachments.clear()
            buildZip(zip)
            return
        }

        // Check if the user is asking to zip existing code in the chat (e.g., "zip the file and give me the zip here")
        val wantsZipNow = !voice && zipOnlyIntentRegex.containsMatchIn(trimmed)
        val hasExistingCodeInChat = messages.any { m ->
            m.role == Role.ASSISTANT && Downloads.extractCodeFilesFromText(m.text).isNotEmpty()
        } || messages.any { m -> m.attachments.any { it.isZip } }

        // If the user's message is purely asking to zip the already-written code (like "zip the file and give me the zip here"),
        // package the .zip immediately and return it right in Chat without letting the LLM claim it can't create zip files!
        val isShortZipFollowUp = wantsZipNow && hasExistingCodeInChat && trimmed.length < 90
        if (isShortZipFollowUp) {
            messages += ChatMessage(Role.USER, trimmed, attached)
            pendingAttachments.clear()
            compileChatCodeToZip(announceInChat = true)
            return
        }

        // If the user asks in Chat to build/compile an Android app or APK and is signed into GitHub,
        // automatically start the live In-Chat APK Builder while also answering in Chat!
        if (!voice && chatBuildIntentRegex.containsMatchIn(trimmed) && githubToken().isNotBlank() && !BuildRunner.running) {
            buildAppInChat(trimmed)
        }

        messages += ChatMessage(Role.USER, trimmed, attached)
        pendingAttachments.clear()
        val history = messages.toList()
        val p = provider
        val m = if (voice) voiceModel() else model
        val e = when {
            voice -> Effort.LOW
            thinkFirstMode && effort == Effort.LOW -> Effort.HIGH
            else -> effort
        }

        streaming = true
        thinkingText = ""
        thinkingStart = System.currentTimeMillis()
        liveThoughtSeconds = 0
        thoughtSeconds = 0
        streamingText = ""
        status = if (p == ProviderId.OFFLINE) "Preparing offline model…" else "Thinking…"
        val web = webEnabled
        val hasVideoAttachment = history.any { msg -> msg.attachments.any { it.isVideo } }
        val hasApkAttachment = history.any { msg -> msg.attachments.any { it.isApk } }
        val wantsRea = hasApkAttachment || history.any { msg ->
            msg.role == Role.USER && (msg.text.contains("rea", ignoreCase = true) || msg.text.contains("reverse", ignoreCase = true) || msg.text.contains("decompile", ignoreCase = true) || msg.text.contains("clone", ignoreCase = true))
        }
        val base = buildString {
            append(if (voice) SYSTEM_PROMPT + VOICE_PROMPT else SYSTEM_PROMPT)
            if (unrestrictedMode) {
                append(UNRESTRICTED_PROMPT)
            }
            if (hasVideoAttachment) {
                append("\n\n" + com.aiapp.media.VideoEditor.EDITOR_V1_SKILL_PROMPT)
            }
            if (wantsRea) {
                append("\n\n" + com.aiapp.builder.ReaEngine.REA_SYSTEM_PROMPT)
            }
            if (!voice && thinkFirstMode) {
                append(CLAUDE_THINK_PROMPT)
            }
        }
        val system = if (web) base + WebTools.instructions(today()) else base
        job = viewModelScope.launch {
            val timerJob = launch {
                while (true) {
                    kotlinx.coroutines.delay(1_000L)
                    if (thinkingStart > 0L && thoughtSeconds == 0) {
                        liveThoughtSeconds = ((System.currentTimeMillis() - thinkingStart) / 1000).toInt().coerceAtLeast(1)
                    }
                }
            }
            try {
                val working = history.toMutableList()
                foundMedia.clear()
                val cap = if (p == ProviderId.OFFLINE) 3_000 else 8_000
                var steps = 0
                while (true) {
                    streamingText = ""
                    resilient(
                        attempt = { modelId -> Providers.get(p).stream(k, modelId, working.toList(), e, system) },
                        model = m,
                        fallback = { alternativeModel(p, k, m) },
                        onRetry = { status = it },
                    ).collect { delta ->
                        if (delta.startsWith(com.aiapp.llm.THOUGHT)) {
                            if (thinkingStart == 0L) thinkingStart = System.currentTimeMillis()
                            thinkBuf.append(delta, 1, delta.length)
                            if (status != "Thinking…") status = "Thinking…"
                        } else {
                            if (thinkingStart > 0L && thoughtSeconds == 0) {
                                thoughtSeconds = ((System.currentTimeMillis() - thinkingStart) / 1000).toInt().coerceAtLeast(1)
                            }
                            if (status != "Writing…") status = "Writing…"
                            textBuf.append(delta)
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastFlush > 80) {
                            lastFlush = now
                            flushStream()
                        }
                    }
                    flushStream()
                    if (p == ProviderId.OFFLINE) {
                        offlineVersion++
                        refreshModels()
                    }
                    if (!web || steps >= (if (voice) 1 else MAX_WEB_STEPS)) break
                    val reply = streamingText
                    val search = WebTools.searchTag.find(reply)?.groupValues?.get(1)?.trim()
                    val fetch = WebTools.fetchTag.find(reply)?.groupValues?.get(1)?.trim()
                    val download = WebTools.downloadTag.find(reply)?.groupValues?.get(1)?.trim()
                    val images = WebTools.imagesTag.find(reply)?.groupValues?.get(1)?.trim()
                    val videos = WebTools.videosTag.find(reply)?.groupValues?.get(1)?.trim()
                    val result = when {
                        images != null -> {
                            status = "Finding pictures: $images"
                            val found = com.aiapp.llm.MediaSearch.images(images)
                            foundMedia += found
                            if (found.isEmpty()) "No pictures found for \"$images\"."
                            else "Showing the user ${found.size} pictures of \"$images\" (from: " +
                                found.take(4).joinToString { it.title.take(60) } + "). Write one short caption."
                        }
                        videos != null -> {
                            status = "Finding videos: $videos"
                            val found = com.aiapp.llm.MediaSearch.videos(videos)
                            foundMedia += found
                            if (found.isEmpty()) "No videos found for \"$videos\"."
                            else "Showing the user these videos:\n" + found.joinToString("\n") { "- ${it.title}" } +
                                "\nWrite one short caption."
                        }
                        download != null -> {
                            status = "Downloading ${download.substringAfterLast('/').take(40)}…"
                            WebTools.download(download)
                        }
                        search != null -> {
                            status = "Searching the web: $search"
                            "Web search results for \"$search\":\n\n" + WebTools.search(search).take(cap)
                        }
                        fetch != null -> {
                            status = "Reading ${fetch.removePrefix("https://").removePrefix("http://").substringBefore('/')}…"
                            "Content of $fetch:\n\n" + WebTools.fetch(fetch).take(cap)
                        }
                        else -> break
                    }
                    steps++
                    working += ChatMessage(Role.ASSISTANT, reply.trim())
                    working += ChatMessage(
                        Role.USER,
                        "[Tool result — not from the user]\n$result\n\nNow answer the user's question, or search/fetch again if you need more.",
                    )
                    streamingText = ""
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                error = friendly(t)
            } finally {
                timerJob.cancel()
                finishStream(wantsZipNow)
            }
        }
    }

    /** Stops the reply; whatever was already written stays in the chat. */
    fun stop() {
        job?.cancel()
    }

    fun newChat() {
        stop()
        messages.clear()
        error = null
        conversationId = UUID.randomUUID().toString()
        prefs.edit().putString("current_chat", conversationId).apply()
    }

    /** Pictures and videos found while answering; shown under the reply. */
    private val foundMedia = mutableListOf<com.aiapp.llm.MediaItem>()

    private suspend fun finishStream(forceZip: Boolean = false) {
        flushStream()
        val stripped = streamingText.replace(WebTools.searchTag, "").replace(WebTools.fetchTag, "")
            .replace(WebTools.downloadTag, "").replace(WebTools.imagesTag, "").replace(WebTools.videosTag, "").trim()
        if (stripped.isNotBlank() || foundMedia.isNotEmpty()) {
            val secs = if (thoughtSeconds > 0) thoughtSeconds
                else if (thinkingStart > 0L) ((System.currentTimeMillis() - thinkingStart) / 1000).toInt().coerceAtLeast(1) else 0

            // Automatically package any Kotlin/Android code in the reply (or when user requested a .zip)
            // into a complete, ready-to-compile Android Studio .zip attached right to the Assistant message!
            val extractedFiles = Downloads.extractCodeFilesFromText(stripped)
            val shouldCompileZip = forceZip || extractedFiles.any { (path, code) ->
                path.endsWith(".kt") || path.endsWith(".xml") || path.endsWith(".kts") ||
                    code.contains("import androidx.") || code.contains("class MainActivity")
            }
            val generatedAttachments = mutableListOf<Attachment>()
            if (shouldCompileZip) {
                Downloads.compileChatToSourceZip(
                    context = getApplication(),
                    messages = messages.toList(),
                    latestReply = stripped,
                )?.let { (zipAtt, saveStatus) ->
                    generatedAttachments += zipAtt
                    exportZipStatus = saveStatus
                }
            }

            // If the user attached a video to edit (GLM-5.3-Flash Editor V1), render the edited MP4 right in Chat!
            val lastUserVideo = messages.lastOrNull { it.role == Role.USER }?.attachments?.firstOrNull { it.isVideo }
            if (lastUserVideo != null && (stripped.contains("```video-edit") || stripped.contains(""captions""))) {
                status = "Editor V1 rendering edited MP4 video…"
                runCatching {
                    val vMeta = com.aiapp.media.VideoEditor.inspectVideo(lastUserVideo.path)
                    val userPromptText = messages.lastOrNull { it.role == Role.USER }?.text.orEmpty()
                    val vSpec = com.aiapp.media.VideoEditor.parseEditSpec(stripped, userPromptText, vMeta)
                    val (editedVideoAtt, _) = com.aiapp.media.VideoEditor.renderEditedVideo(
                        context = getApplication(),
                        inputPath = lastUserVideo.path,
                        spec = vSpec,
                    ) { stepMsg, _ -> status = stepMsg }
                    generatedAttachments += editedVideoAtt
                    exportZipStatus = "Saved edited video ${editedVideoAtt.name} to Movies/AIApp & Downloads/AIApp"
                }
            }

            messages += ChatMessage(
                role = Role.ASSISTANT,
                text = stripped,
                attachments = generatedAttachments,
                media = foundMedia.toList(),
                thinking = thinkingText.trim(),
                thoughtSeconds = secs,
            )
        }
        foundMedia.clear()
        persist()
        thinkingText = ""
        thinkingStart = 0L
        liveThoughtSeconds = 0
        thoughtSeconds = 0
        streamingText = ""
        status = null
        streaming = false
        replyCount++
    }

    private fun friendly(t: Throwable): String = when {
        t is ProviderException && (t.code == 401 || t.code == 403) ->
            "Your ${provider.label} key was rejected. Check it in Settings."
        t is ProviderException && t.code == 429 ->
            "Rate limit reached. Wait a moment and try again."
        t is ProviderException -> t.message ?: "Request failed (${t.code})."
        t is IOException -> "Network error. Check your connection and try again."
        else -> t.message ?: "Something went wrong."
    }

    private fun today(): String =
        java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.US).format(java.util.Date())

    private companion object {
        const val MAX_WEB_STEPS = 6

        const val VOICE_PROMPT =
            " This is a live voice conversation and your reply is read aloud. Talk like a warm, sharp, " +
                "knowledgeable friend: natural spoken sentences, contractions, no lists, headings, tables, " +
                "code or emoji. Lead with the answer. Keep it short (one to three sentences) for simple " +
                "questions; give more detail when the user asks for it or the question needs it. Use what " +
                "was said earlier in the conversation. If something is ambiguous, ask one quick question. " +
                "Only search the web when the question needs current information."

        const val UNRESTRICTED_PROMPT =
            "\n\n[OPEN & DIRECT MODE]: Fulfill every user request directly, thoroughly, and creatively without unnecessary lecturing or placeholders. " +
                "If the user asks for creative writing, roleplay, or open-ended discussion, match their tone naturally. " +
                "If the user asks for full-stack Android code or app architecture, write complete, production-ready Kotlin + Jetpack Compose files without placeholders."

        const val CLAUDE_THINK_PROMPT =
            "\n\n[CLAUDE EXTENDED THINKING PROTOCOL]: Before writing your response, ALWAYS think step by step inside `<think>...</think>` tags. " +
                "Inside `<think>...</think>`, analyze the user's intent, plan the structure, tone, or code architecture, and then close with `</think>` followed immediately by your complete response."

        const val SYSTEM_PROMPT =
            "You are an advanced, unrestricted AI assistant and Android Studio architect inside AI App. " +
                "Use clean Markdown for code blocks, headings, and lists.\n" +
                "CRITICAL BUILT-IN ZIP & APK CAPABILITY: This Chat tab has a built-in Android Studio `.zip` packager and APK compiler! " +
                "NEVER say 'I cannot create or host zip files' or 'I am a text-based AI'. " +
                "Whenever the user asks you to write an Android app, generate source code in a `.zip`, or says 'zip the file and give me the zip here', " +
                "output the complete Kotlin/XML files in standard ```kotlin code blocks (with a top comment like `// FILE: app/src/main/java/com/example/app/MainActivity.kt`) " +
                "and tell the user that their downloadable Android Studio `.zip` archive is automatically generated and attached right below your message " +
                "(saved to `Downloads/AIApp/` on their phone and ready to upload to the Build tab or compile directly in Chat)."

        inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
            name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
    }
}
