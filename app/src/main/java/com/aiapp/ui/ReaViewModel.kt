package com.aiapp.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aiapp.builder.BuildRequest
import com.aiapp.builder.BuildRunner
import com.aiapp.builder.BuildService
import com.aiapp.builder.ReaEngine
import com.aiapp.data.AttachmentStore
import com.aiapp.data.Downloads
import com.aiapp.llm.Attachment
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Providers
import com.aiapp.llm.Role
import com.aiapp.llm.THOUGHT
import com.aiapp.llm.WebTools
import com.aiapp.llm.alternativeModel
import com.aiapp.llm.resilient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ReaEvidenceTab(val label: String) {
    OVERVIEW("📋 Overview & Sig"),
    MANIFEST("📄 Manifest XML"),
    DEX("🏗 DEX & Methods"),
    RESOURCES("🎨 Layouts & Assets"),
}

/**
 * ViewModel for the dedicated **R.E.A (Reverse Engineer Anything)** tab (`github.com/morluto/rea` Android Port).
 *
 * Allows the user to:
 * 1. Upload any `.apk` file (or specify a website/app description) and inspect all 4 REA Evidence families
 *    (`inspect_apk_signature`, `inspect_android_package`, `apktool_resource_analysis`, `project_android_application_graph`).
 * 2. Customize the **New App Name**, **New Package ID**, and **Custom Modifications** while bundling all reusable
 *    resources (`res/drawable/*`, `res/font/*`, `res/raw/*`, `assets/*`) from the original APK and using live Internet research.
 * 3. Either **Compile the Modified APK** directly on GitHub Actions OR **Generate a Modified Android Studio `.zip`**
 *    saved directly to `Downloads/AIApp` on the phone.
 */
class ReaViewModel(private val app: Application) : AndroidViewModel(app) {

    var uploadedApk by mutableStateOf<Attachment?>(null)
        private set
    var apkSummary by mutableStateOf<ReaEngine.ReaApkSummary?>(null)
        private set
    var extractedBundle by mutableStateOf<ReaEngine.ExtractedResourcesBundle?>(null)
        private set

    var activeEvidenceTab by mutableStateOf(ReaEvidenceTab.OVERVIEW)

    var newAppName by mutableStateOf("")
    var newPackageName by mutableStateOf("")
    var modificationsPrompt by mutableStateOf(
        "Recreate this app so it works identically to the original app under the new name, with a clean modern Material 3 UI.",
    )
    var webTargetUrlOrDesc by mutableStateOf("")

    /** Bundle all extracted drawables, icons, fonts, raw media, and assets from the uploaded APK. */
    var bundleApkResources by mutableStateOf(true)

    /** Use live internet search, webpage fetching, and external asset downloading while reverse engineering & modifying. */
    var useInternetResources by mutableStateOf(true)

    var inspecting by mutableStateOf(false)
        private set
    var generatingZip by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var liveThinking by mutableStateOf("")
        private set
    var liveCodePreview by mutableStateOf("")
        private set
    var generatedZipAttachment by mutableStateOf<Attachment?>(null)
        private set
    var generatedZipSavedNote by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    fun importApk(uri: Uri) {
        if (inspecting) return
        inspecting = true
        error = null
        status = "Deconstructing APK with REA Engine (Manifest, DEX, Layouts, Signature & Assets)…"
        viewModelScope.launch {
            try {
                val att = AttachmentStore.import(app, uri)
                val file = File(att.path)
                if (!file.exists()) {
                    error = "Could not read selected file."
                    return@launch
                }
                val summary = withContext(Dispatchers.Default) {
                    ReaEngine.inspectApk(file)
                }
                val bundle = withContext(Dispatchers.Default) {
                    ReaEngine.extractReusableResources(file)
                }
                uploadedApk = att.copy(
                    mime = "application/vnd.android.package-archive",
                    text = summary.fullMarkdownSpec,
                )
                apkSummary = summary
                extractedBundle = bundle

                val baseLabel = summary.appLabel.ifBlank {
                    att.name.removeSuffix(".apk").replace(Regex("[^A-Za-z0-9 ]"), " ").trim()
                }.ifBlank { "My Rebuilt App" }

                newAppName = "$baseLabel Custom"
                val slug = newAppName.lowercase().replace(Regex("[^a-z0-9]+"), "").ifBlank { "rebuiltapp" }.take(24)
                newPackageName = "com.reabuilt.${if (slug.first().isLetter()) slug else "a$slug"}"

                status = "✅ REA deconstructed ${att.name}: ${summary.classesCount} classes, ${summary.methodsCount} methods, ${summary.layouts.size} layouts, and ${bundle.files.size} reusable resource files ready."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "Failed to inspect APK: ${e.message ?: "Invalid APK archive"}"
                status = null
            } finally {
                inspecting = false
            }
        }
    }

    fun updateNewAppName(name: String) {
        newAppName = name
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "").ifBlank { "rebuiltapp" }.take(24)
        newPackageName = "com.reabuilt.${if (slug.first().isLetter()) slug else "a$slug"}"
    }

    fun clearApk() {
        uploadedApk = null
        apkSummary = null
        extractedBundle = null
        generatedZipAttachment = null
        generatedZipSavedNote = null
        status = null
        error = null
    }

    private fun buildFullReaPrompt(targetPkg: String, targetName: String): String = buildString {
        val summary = apkSummary
        val bundle = extractedBundle
        appendLine("Use REA (Reverse Engineer Anything — `github.com/morluto/rea` Android Reconstruction Engine) to reverse engineer and rebuild this Android application in complete, compilable Kotlin + Jetpack Compose.")
        appendLine()
        appendLine("### Target Rebuilt Application Identity:")
        appendLine("- **New App Name**: `$targetName`")
        appendLine("- **New Package Name**: `$targetPkg`")
        appendLine()
        appendLine("### Requested Behaviour & Custom Modifications:")
        appendLine(
            modificationsPrompt.trim().ifBlank {
                "Recreate the original app so it works identically under the new name `$targetName`."
            },
        )
        if (webTargetUrlOrDesc.isNotBlank()) {
            appendLine()
            appendLine("### Additional Target Website / App Reference:")
            appendLine(webTargetUrlOrDesc.trim())
        }
        if (summary != null) {
            appendLine()
            appendLine("### Complete REA Deconstruction Evidence from Original APK (`${summary.fileName}`):")
            appendLine(summary.fullMarkdownSpec)
        }
        if (bundleApkResources && bundle != null && bundle.promptSummary.isNotBlank()) {
            appendLine(bundle.promptSummary)
        }
        appendLine()
        appendLine("### Mandatory Reconstruction Instructions:")
        appendLine("1. Reconstruct every screen, activity/navigation route, state flow, calculation, and user interaction discovered in the REA Evidence above so the rebuilt app works like the original app, with the requested modifications applied.")
        appendLine("2. Use package `$targetPkg` and app title `$targetName`.")
        if (bundleApkResources && bundle != null && bundle.files.isNotEmpty()) {
            appendLine("3. Reference the bundled original APK resources (`R.drawable.*`, `R.font.*`, `R.raw.*`, `assets/*`) listed above where appropriate.")
        }
        if (useInternetResources) {
            appendLine("4. If you need to verify any API endpoint, library, or web protocol mentioned in the APK or prompt, use `<search>query</search>` or `<fetch>https://...</fetch>` first.")
        }
        appendLine("5. Write complete, production-ready Kotlin files with zero placeholders or TODOs.")
    }

    /**
     * Pipeline 1: Starts a full GitHub Actions APK build using the REA Evidence, custom App Name,
     * custom Package Name, bundled APK resources (`res/drawable`, `res/font`, `res/raw`, `assets`),
     * and live internet access!
     */
    fun startReaApkBuild(chat: ChatViewModel, onRequireGithubSignIn: () -> Unit) {
        if (BuildRunner.running) return
        val summary = apkSummary
        if (summary == null && webTargetUrlOrDesc.isBlank() && modificationsPrompt.isBlank()) {
            error = "Upload an APK file (or enter a website/app description) first."
            return
        }
        val token = chat.githubToken()
        if (token.isBlank()) {
            error = "Sign in with GitHub first (tap Sign in below or in Settings) to compile the rebuilt APK."
            onRequireGithubSignIn()
            return
        }
        error = null
        val finalAppName = newAppName.trim().ifBlank { summary?.appLabel?.let { "$it Custom" } ?: "REA Rebuilt App" }
        val slug = finalAppName.lowercase().replace(Regex("[^a-z0-9]+"), "").ifBlank { "reabuilt" }.take(24)
        val finalPkg = newPackageName.trim().takeIf { it.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")) }
            ?: "com.reabuilt.${if (slug.first().isLetter()) slug else "a$slug"}"

        val fullPrompt = buildFullReaPrompt(finalPkg, finalAppName)
        val chosenModel = if (chat.provider == ProviderId.FREE_CLOUD) "rea-agent" else chat.model

        BuildRunner.newProject(app)
        BuildService.start(
            app,
            BuildRequest(
                description = fullPrompt,
                appName = finalAppName,
                provider = chat.provider,
                model = chosenModel,
                apiKey = chat.key(chat.provider),
                effort = Effort.MAX,
                githubToken = token,
                allowOtherSites = useInternetResources,
                customPackageName = finalPkg,
                sourceApkPath = if (bundleApkResources) uploadedApk?.path else null,
            ),
        )
        status = "🚀 REA APK build started for \"$finalAppName\" ($finalPkg) — watch the live build timeline below!"
    }

    /**
     * Pipeline 2: Runs the REA Agent (with live internet search & page fetch), generates the complete
     * reconstructed & modified Kotlin + Jetpack Compose source files, bundles the extracted APK resources,
     * and saves a ready-to-compile Android Studio `.zip` directly to `Downloads/AIApp/` on the phone!
     */
    fun generateReaSourceZip(chat: ChatViewModel) {
        if (generatingZip) return
        val summary = apkSummary
        if (summary == null && webTargetUrlOrDesc.isBlank() && modificationsPrompt.isBlank()) {
            error = "Upload an APK file (or enter a website/app description) first."
            return
        }
        val p = chat.provider
        val k = chat.key(p)
        if (p.needsKey && k.isBlank()) {
            error = "Add your ${p.label} key in Settings, or switch Provider to Free Cloud (No Key) or Offline."
            return
        }

        val finalAppName = newAppName.trim().ifBlank { summary?.appLabel?.let { "$it Custom" } ?: "ReaRebuiltApp" }
        val slug = finalAppName.lowercase().replace(Regex("[^a-z0-9]+"), "").ifBlank { "reabuilt" }.take(24)
        val finalPkg = newPackageName.trim().takeIf { it.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$")) }
            ?: "com.reabuilt.${if (slug.first().isLetter()) slug else "a$slug"}"

        generatingZip = true
        error = null
        generatedZipAttachment = null
        generatedZipSavedNote = null
        liveThinking = ""
        liveCodePreview = ""
        status = "🔍 REA Agent analyzing architecture & generating source code for \"$finalAppName\"…"

        val chosenModel = if (p == ProviderId.FREE_CLOUD) "rea-agent" else chat.model
        val systemPrompt = buildString {
            append(ReaEngine.REA_SYSTEM_PROMPT)
            append(
                "\nOutput every Kotlin/XML file in standard ```kotlin code blocks with a top comment like:\n" +
                    "// FILE: app/src/main/java/${finalPkg.replace('.', '/')}/MainActivity.kt\n" +
                    "package $finalPkg\n",
            )
            if (useInternetResources) {
                append(WebTools.instructions("today"))
            }
        }

        val userPrompt = buildFullReaPrompt(finalPkg, finalAppName)

        job = viewModelScope.launch {
            try {
                val working = mutableListOf(ChatMessage(Role.USER, userPrompt))
                var steps = 0
                var finalReply = ""

                while (true) {
                    val textBuf = StringBuilder()
                    val thinkBuf = StringBuilder()
                    resilient(
                        attempt = { mId ->
                            Providers.get(p).stream(
                                apiKey = k,
                                model = mId,
                                messages = working.toList(),
                                effort = Effort.MAX,
                                system = systemPrompt,
                            )
                        },
                        model = chosenModel,
                        fallback = { alternativeModel(p, k, chosenModel) },
                        onRetry = { status = it },
                    ).collect { delta ->
                        if (delta.startsWith(THOUGHT)) {
                            thinkBuf.append(delta.substring(1))
                            liveThinking = thinkBuf.toString().takeLast(1200)
                            status = "🧠 REA Agent thinking step by step…"
                        } else {
                            textBuf.append(delta)
                            liveCodePreview = textBuf.toString().takeLast(1600)
                            status = "📝 REA Agent writing reconstructed Kotlin + Compose code…"
                        }
                    }

                    val reply = textBuf.toString()
                    finalReply = reply
                    if (!useInternetResources || steps >= 3) break

                    val search = WebTools.searchTag.find(reply)?.groupValues?.get(1)?.trim()
                    val fetch = WebTools.fetchTag.find(reply)?.groupValues?.get(1)?.trim()
                    val toolResult = when {
                        search != null -> {
                            status = "🌐 REA searching the web: $search"
                            "Web search results for \"$search\":\n\n" + WebTools.search(search).take(6_000)
                        }
                        fetch != null -> {
                            status = "🌐 REA reading $fetch…"
                            "Content of $fetch:\n\n" + WebTools.fetch(fetch).take(6_000)
                        }
                        else -> break
                    }
                    steps++
                    working += ChatMessage(Role.ASSISTANT, reply.trim())
                    working += ChatMessage(
                        Role.USER,
                        "[Internet research result]\n$toolResult\n\nNow output the complete reconstructed Kotlin + Jetpack Compose files for `$finalAppName` (`$finalPkg`).",
                    )
                }

                status = "📦 Packaging reconstructed code + extracted APK resources into Android Studio .zip…"
                val compiled = Downloads.compileChatToSourceZip(
                    context = app,
                    messages = working,
                    latestReply = finalReply,
                    preferredAppName = finalAppName,
                    preferredPackageName = finalPkg,
                    sourceApkPath = if (bundleApkResources) uploadedApk?.path else null,
                )
                if (compiled != null) {
                    generatedZipAttachment = compiled.first
                    generatedZipSavedNote = compiled.second
                    status = "✅ REA Android Studio .zip ready: ${compiled.first.name} (${compiled.second})"
                } else {
                    error = "The model finished replying, but no code blocks were detected. Try tapping Generate .zip again."
                    status = null
                }
            } catch (e: CancellationException) {
                status = null
                throw e
            } catch (e: Throwable) {
                error = e.message ?: "Failed to generate REA source .zip."
                status = null
            } finally {
                generatingZip = false
            }
        }
    }

    fun cancelGeneration() {
        job?.cancel()
        if (BuildRunner.running) {
            BuildService.stop(app)
        }
    }
}
