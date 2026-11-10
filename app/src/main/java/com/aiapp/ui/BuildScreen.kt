package com.aiapp.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiapp.builder.BuildEvent
import com.aiapp.builder.BuildRequest
import com.aiapp.builder.BuildRunner
import com.aiapp.builder.TimelineItem
import com.aiapp.llm.ProviderId
import kotlinx.coroutines.launch

private val resumeWords = Regex("^\\s*(retry|resume|continue|try again|go on|carry on|build now|rebuild)\\b", RegexOption.IGNORE_CASE)

/** Starts a build that continues the open project from its last checkpoint. */
private fun resumeBuild(chat: ChatViewModel, vm: BuildViewModel, text: String = "Retry") {
    val project = vm.project ?: return
    vm.start(
        BuildRequest(
            description = text,
            appName = project.appName,
            provider = chat.provider,
            model = chat.model,
            apiKey = chat.key(chat.provider),
            effort = chat.effort,
            githubToken = chat.githubToken(),
            allowOtherSites = false,
            project = project,
            resume = true,
        ),
    )
}

/**
 * Build Mode, laid out like an IDE agent chat:
 * - Answers specific questions directly in chat (`💬 Ask`) without starting an unwanted build.
 * - Starts building/compiling the APK (`🔨 Build`) only when the user directs it to do so!
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildScreen(
    chat: ChatViewModel,
    vm: BuildViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCodespace: (String?) -> Unit = {},
) {
    var showFiles by rememberSaveable { mutableStateOf(false) }
    var showProjects by rememberSaveable { mutableStateOf(false) }
    var openFile by rememberSaveable { mutableStateOf<String?>(null) }
    val project = vm.project

    BackHandler(enabled = showFiles || openFile != null || showProjects) {
        if (openFile != null) openFile = null else if (showFiles) showFiles = false else showProjects = false
    }

    if (showProjects) {
        ProjectList(vm, onBack = { showProjects = false })
        return
    }

    openFile?.let { path ->
        CodeViewer(path, project?.files?.get(path).orEmpty(), onBack = { openFile = null })
        return
    }
    if (showFiles && project != null) {
        FileList(project.files.keys.toList(), onOpen = { openFile = it }, onBack = { showFiles = false })
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(vm.timeline.size, vm.answeringText) {
        if (vm.timeline.isNotEmpty()) listState.animateScrollToItem(vm.timeline.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(project?.appName ?: "Build app")
                        Text(
                            "${chat.provider.label} · ${chat.models.firstOrNull { it.id == chat.model }?.name ?: chat.model} · ${chat.effort.label}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = { onOpenCodespace(null) }) { Text("Codespace") }
                    TextButton(onClick = { showProjects = true }) { Text("Projects") }
                    if (project != null) {
                        TextButton(onClick = { showFiles = true }) { Text("Files") }
                    }
                    if (vm.timeline.isNotEmpty()) {
                        TextButton(onClick = vm::newProject, enabled = !vm.running && !vm.answering) { Text("New") }
                    }
                },
            )
        },
        bottomBar = { BuildComposer(chat, vm, onOpenSettings, onOpenCodespace) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OfflineReadyBanner(chat)
            if (vm.timeline.isEmpty() && !vm.answering) {
                EmptyState(chat, Modifier.weight(1f), onOpenCodespace)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(vm.timeline) { index, item ->
                        val isLast = index == vm.timeline.lastIndex
                        when (item) {
                            is TimelineItem.User -> UserBubble(item.text)
                            is TimelineItem.Event -> {
                                EventRow(item.event, active = isLast && vm.running)
                                val prog = BuildRunner.progress
                                if (isLast && vm.running && prog != null) {
                                    Column(Modifier.padding(start = 30.dp)) { UploadBar(BuildRunner.progressText.ifBlank { "Working…" }, prog) }
                                }
                            }
                            is TimelineItem.Reply -> ReplyCard(
                                reply = item,
                                canBuildNow = !vm.running && !vm.answering && chat.githubToken().isNotBlank(),
                                onBuildFromReply = {
                                    val buildPrompt = "Implement the features and improvements discussed in your last reply:\n\n${item.text.take(2500)}"
                                    val appTitle = project?.appName ?: "AI App"
                                    vm.start(
                                        BuildRequest(
                                            description = buildPrompt,
                                            appName = appTitle,
                                            provider = chat.provider,
                                            model = chat.model,
                                            apiKey = chat.key(chat.provider),
                                            effort = chat.effort,
                                            githubToken = chat.githubToken(),
                                            allowOtherSites = false,
                                            project = project,
                                        ),
                                    )
                                },
                            )
                            is TimelineItem.Result -> ResultCard(
                                item,
                                onRetry = if (!item.success && isLast && !vm.running && vm.project != null) {
                                    { resumeBuild(chat, vm) }
                                } else null,
                                onOpenCodespace = onOpenCodespace,
                            )
                        }
                    }
                    if (vm.answering) {
                        item {
                            LiveAnswerCard(
                                thinking = vm.answeringThinking,
                                text = vm.answeringText,
                                providerLabel = chat.provider.label,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyCard(
    reply: TimelineItem.Reply,
    canBuildNow: Boolean,
    onBuildFromReply: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (reply.thinking.isNotBlank()) {
            ThinkingBox(text = reply.thinking, seconds = 0, live = false)
        }
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    MarkdownText(reply.text, modifier = Modifier.fillMaxWidth())
                }
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(reply.text)) }) {
                        Text("📋 Copy Reply")
                    }
                    if (canBuildNow) {
                        Button(onClick = onBuildFromReply) {
                            Text("🔨 Build Suggested Changes")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveAnswerCard(
    thinking: String,
    text: String,
    providerLabel: String,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text("$providerLabel · Answering your question…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (thinking.isNotBlank()) {
            ThinkingBox(text = thinking, seconds = 0, live = true)
        }
        if (text.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    MarkdownText(text, modifier = Modifier.padding(14.dp).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun EmptyState(chat: ChatViewModel, modifier: Modifier, onOpenCodespace: (String?) -> Unit) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ask questions or build an Android APK", style = MaterialTheme.typography.titleLarge)
        Text(
            "• Tap 💬 Ask to brainstorm ideas, review files, or ask questions about your project without starting a build.\n" +
                "• Tap 🔨 Build whenever you're ready to write the code, compile the APK on GitHub Actions, and get an installable download link.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Builds keep running if you close the app; you'll get a notification when it's ready.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (chat.provider == ProviderId.OFFLINE) {
            val m = chat.selectedOfflineModel()
            Text(
                "Using offline model: ${m?.name ?: chat.model} (Free · No API key needed).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onOpenCodespace(null) }) {
                Text("Open GitHub Codespaces")
            }
            OutlinedButton(onClick = { onOpenCodespace(CodespaceSessionManager.URL_CHATGPT_CODEX) }) {
                Text("Open ChatGPT Codex")
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Spacer(Modifier.weight(1f))
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) { Text(text, modifier = Modifier.padding(12.dp)) }
    }
}

private fun iconFor(kind: BuildEvent.Kind): ImageVector = when (kind) {
    BuildEvent.Kind.THINKING -> Icons.Filled.Create
    BuildEvent.Kind.WEB -> Icons.Filled.Search
    BuildEvent.Kind.FILE -> Icons.Filled.Edit
    BuildEvent.Kind.LIBRARY -> Icons.Filled.Add
    BuildEvent.Kind.DOWNLOAD -> Icons.Filled.KeyboardArrowDown
    BuildEvent.Kind.BUILD -> Icons.Filled.Build
    BuildEvent.Kind.ERROR -> Icons.Filled.Warning
    BuildEvent.Kind.DONE -> Icons.Filled.Done
    BuildEvent.Kind.STEP -> Icons.Filled.CheckCircle
}

@Composable
private fun EventRow(e: BuildEvent, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (active) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            } else {
                Icon(
                    iconFor(e.kind),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (e.kind == BuildEvent.Kind.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            e.text + if (active) "…" else "",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (e.kind == BuildEvent.Kind.FILE) FontFamily.Monospace else null,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultCard(
    r: TimelineItem.Result,
    onRetry: (() -> Unit)? = null,
    onOpenCodespace: (String?) -> Unit = {},
) {
    val uri = LocalUriHandler.current
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                r.message,
                style = MaterialTheme.typography.titleMedium,
                color = if (r.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            r.apkUrl?.let { url -> ApkDownload(url) }
            if (onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry from where it stopped") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.apkUrl?.let { url -> TextButton(onClick = { uri.openUri(url) }) { Text("Open in browser") } }
                r.apkUrl?.let { url ->
                    val clip = LocalClipboardManager.current
                    TextButton(onClick = { clip.setText(AnnotatedString(url)) }) { Text("Copy link") }
                }
                r.repoUrl?.let { url ->
                    TextButton(onClick = { uri.openUri(url) }) { Text("GitHub") }
                    TextButton(onClick = { onOpenCodespace(url.replace("github.com", "github.dev")) }) { Text("Codespace") }
                }
            }
            if (r.success) {
                Text(
                    "Ask a question below (💬 Ask) or request code changes and tap 🔨 Build — the new version installs over this one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BuildComposer(
    chat: ChatViewModel,
    vm: BuildViewModel,
    onOpenSettings: () -> Unit,
    onOpenCodespace: (String?) -> Unit,
) {
    val project = vm.project
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var allowOther by rememberSaveable { mutableStateOf(false) }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var unpacking by rememberSaveable { mutableStateOf(false) }

    val attached = remember { androidx.compose.runtime.mutableStateListOf<com.aiapp.llm.Attachment>() }
    var attaching by rememberSaveable { mutableStateOf(false) }
    var attachPct by remember { mutableStateOf(0f) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        attaching = true
        scope.launch {
            uris.forEach { u ->
                try {
                    attachPct = 0f
                    attached += com.aiapp.data.AttachmentStore.import(context, u) { v -> scope.launch { attachPct = v } }
                } catch (e: Exception) {
                    problem = e.message ?: "Couldn't attach that file."
                }
            }
            attaching = false
        }
    }

    fun readyForBuild(): String? = when {
        chat.githubToken().isBlank() -> "Sign in with GitHub in Settings (or tap Sign in below) first to build APKs."
        chat.provider.needsKey && chat.key(chat.provider).isBlank() -> "Add your ${chat.provider.label} key in Settings (or switch to Free Cloud / Offline)."
        chat.model.isBlank() -> "Pick a model on the chat screen first."
        else -> null
    }

    fun triggerAsk() {
        if (text.isBlank() && attached.isEmpty()) {
            problem = "Type a question or message first."
            return
        }
        if (chat.provider.needsKey && chat.key(chat.provider).isBlank()) {
            problem = "Add your ${chat.provider.label} key in Settings (or switch to Free Cloud / Offline)."
            return
        }
        problem = null
        val q = text.trim()
        val files = attached.toList()
        text = ""
        attached.clear()
        vm.askQuestion(
            question = q,
            provider = chat.provider,
            model = chat.model,
            apiKey = chat.key(chat.provider),
            effort = chat.effort,
            attachments = files,
        )
    }

    fun triggerBuild() {
        val zip = attached.firstOrNull { it.isZip }
        if (project == null && zip != null) {
            problem = readyForBuild()
            if (problem == null) {
                if (android.os.Build.VERSION.SDK_INT >= 33) notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                vm.newProject()
                vm.start(
                    BuildRequest(
                        description = text.trim().ifBlank { "Compile ${zip.name} to an APK" },
                        appName = zip.name.removeSuffix(".zip"),
                        provider = chat.provider,
                        model = chat.model,
                        apiKey = chat.key(chat.provider),
                        effort = chat.effort,
                        githubToken = chat.githubToken(),
                        allowOtherSites = allowOther,
                        zipPath = zip.path,
                        zipName = zip.name,
                    ),
                )
                attached.clear()
                text = ""
            }
            return
        }
        if (project != null && attached.isEmpty() && resumeWords.containsMatchIn(text)) {
            problem = readyForBuild()
            if (problem == null) {
                resumeBuild(chat, vm, text.trim())
                text = ""
            }
            return
        }
        problem = when {
            text.isBlank() && attached.isEmpty() -> "Describe the app or change you want to build."
            project == null && name.isBlank() -> {
                // Auto-derive an app name from the prompt if the user forgot to type one
                name = text.trim().split(Regex("\\s+")).take(3).joinToString(" ") { w ->
                    w.replace(Regex("[^A-Za-z0-9]"), "").replaceFirstChar { it.uppercase() }
                }.ifBlank { "AI App" }
                readyForBuild()
            }
            else -> readyForBuild()
        }
        if (problem == null) {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            vm.start(
                BuildRequest(
                    description = text.trim(),
                    appName = name.trim().ifBlank { project?.appName ?: "AI App" },
                    provider = chat.provider,
                    model = chat.model,
                    apiKey = chat.key(chat.provider),
                    effort = chat.effort,
                    githubToken = chat.githubToken(),
                    allowOtherSites = allowOther,
                    project = project,
                    attachments = attached.toList(),
                ),
            )
            text = ""
            attached.clear()
        }
    }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        problem = readyForBuild()
        if (problem != null) return@rememberLauncherForActivityResult
        unpacking = true
        scope.launch {
            try {
                attachPct = 0f
                val a = com.aiapp.data.AttachmentStore.import(context, uri) { v -> scope.launch { attachPct = v } }
                if (a.isApk) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    vm.newProject()
                    val apkAppName = a.name.removeSuffix(".apk").replace(Regex("[^A-Za-z0-9]"), " ").trim().split(Regex("\\s+")).take(3).joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }.ifBlank { "Recreated App" }
                    val reaPrompt = "Use REA (Reverse Engineer Anything) to deconstruct ${a.name} and rebuild a complete, working Android app in Kotlin + Jetpack Compose matching this specification:\n\n" + (a.text ?: a.name)
                    vm.start(
                        BuildRequest(
                            description = reaPrompt,
                            appName = apkAppName,
                            provider = chat.provider,
                            model = chat.model,
                            apiKey = chat.key(chat.provider),
                            effort = chat.effort,
                            githubToken = chat.githubToken(),
                            allowOtherSites = allowOther,
                            attachments = listOf(a),
                        ),
                    )
                } else if (!a.name.endsWith(".zip", true)) {
                    problem = "Pick an Android Studio project (.zip) or Android binary (.apk) to deconstruct & build."
                } else {
                    if (android.os.Build.VERSION.SDK_INT >= 33) notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    vm.newProject()
                    vm.start(
                        BuildRequest(
                            description = "Compile ${a.name} to an APK",
                            appName = a.name.removeSuffix(".zip"),
                            provider = chat.provider,
                            model = chat.model,
                            apiKey = chat.key(chat.provider),
                            effort = chat.effort,
                            githubToken = chat.githubToken(),
                            allowOtherSites = allowOther,
                            zipPath = a.path,
                            zipName = a.name,
                        ),
                    )
                }
            } catch (e: Exception) {
                problem = e.message ?: "Couldn't read that file."
            } finally {
                unpacking = false
            }
        }
    }

    Surface(tonalElevation = 2.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            problem?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (chat.githubToken().isBlank()) {
                        TextButton(onClick = {
                            problem = null
                            chat.signInGithub { link -> onOpenCodespace(link) }
                        }) { Text("Sign in") }
                    }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
            if (vm.running || vm.answering) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (vm.answering) "Answering your question…" else "Building APK…",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = vm::stop) { Text("Stop") }
                }
                return@Column
            }
            if (project == null) {
                OutlinedButton(
                    onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "application/vnd.android.package-archive")) },
                    enabled = !unpacking,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (unpacking) "Deconstructing project/APK with REA…" else "🔍 Upload .zip or .apk (REA Reverse Engineer & Build)") }
                if (unpacking) UploadBar("Copying your project…", attachPct)
                Text(
                    "…or describe a new app (or ask a question):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("App name (optional for questions)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (attaching) UploadBar("Attaching…", attachPct)
            if (attached.isNotEmpty() && !attaching) {
                Text(
                    "✓ ${attached.size} file${if (attached.size == 1) "" else "s"} ready",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (attached.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    attached.toList().forEach { a ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                                Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                TextButton(onClick = { attached.remove(a) }) { Text("✕") }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Attach files")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = {
                        Text(
                            if (project == null) "Ask a question or describe an app to build…"
                            else "Ask a question (💬 Ask) or request a build (🔨 Build)…",
                        )
                    },
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                )
            }
            // Explicit dual-action bar: 💬 Ask / Reply (no build) vs 🔨 Build APK (only builds when directed!)
            val looksLikeQuestion = vm.isQuestionIntent(text) && attached.none { it.isZip }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { triggerAsk() },
                    enabled = text.isNotBlank() || attached.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (looksLikeQuestion) "💬 Ask / Reply (Recommended)" else "💬 Ask Question Only")
                }
                Button(
                    onClick = {
                        // If the user typed a pure question like "what more can we add? reply" and tapped the main button,
                        // answer the question first unless they explicitly used a build command!
                        if (looksLikeQuestion) {
                            triggerAsk()
                        } else {
                            triggerBuild()
                        }
                    },
                    enabled = text.isNotBlank() || attached.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (looksLikeQuestion) "💬 Reply to Question" else "🔨 Start Build APK")
                }
                if (looksLikeQuestion && (text.isNotBlank() || attached.isNotEmpty())) {
                    TextButton(onClick = { triggerBuild() }) {
                        Text("Force Build")
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Allow downloads from any site",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = allowOther, onCheckedChange = { allowOther = it })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileList(paths: List<String>, onOpen: (String) -> Unit, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(paths.sorted()) { _, path ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(path) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(path.substringAfterLast('/'), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    Text(
                        path.removePrefix("app/src/main/").substringBeforeLast('/', ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CodeViewer(path: String, content: String, onBack: () -> Unit) {
    val lines = content.lines()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(path.substringAfterLast('/'), fontFamily = FontFamily.Monospace) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(padding).fillMaxSize()) {
            SelectionContainer {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    lines.forEachIndexed { i, line ->
                        Row {
                            Text(
                                "${i + 1}".padStart(4),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectList(vm: BuildViewModel, onBack: () -> Unit) {
    var version by remember { mutableStateOf(0) }
    val projects = remember(version) { vm.projects() }
    val fmt = remember { java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(onClick = {
                        vm.newProject()
                        onBack()
                    }, enabled = !vm.running) { Text("New project") }
                },
            )
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Text("No projects yet. Describe an app or upload a .zip to start one.", modifier = Modifier.padding(padding).padding(24.dp))
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            itemsIndexed(projects) { _, p ->
                val current = p.id == BuildRunner.projectId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !vm.running || current) {
                            if (vm.openProject(p.id) || current) onBack()
                        }
                        .padding(start = 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title + if (current) "  (open)" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        val status = when {
                            current && vm.running -> "Building now…"
                            p.lastResult?.success == true -> "APK ready"
                            p.lastResult != null -> "Last build failed"
                            else -> "In progress"
                        }
                        Text(
                            "${fmt.format(java.util.Date(p.updated))} · $status" + (if (p.project?.imported == true) " · uploaded" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!(current && vm.running)) {
                        TextButton(onClick = {
                            vm.deleteProject(p.id)
                            version++
                        }) { Text("Delete") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** Downloads the APK in the app with a progress bar, checks it, then opens Android's installer. */
@Composable
private fun ApkDownload(url: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var size by remember(url) { mutableStateOf<Long?>(null) }
    var progress by remember(url) { mutableStateOf<Float?>(null) }
    var file by remember(url) { mutableStateOf<java.io.File?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    LaunchedEffect(url) { size = com.aiapp.data.ApkInstaller.check(url) }

    fun install(f: java.io.File) {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:" + context.packageName),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            error = "Allow \"Install unknown apps\" for AI App, come back, and tap Install."
            return
        }
        runCatching { com.aiapp.data.ApkInstaller.install(context, f) }.onFailure { error = it.message }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val f = file
        val p = progress
        when {
            f != null -> {
                Text("✓ Downloaded ${f.name} — also saved to Downloads/AIApp", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Button(onClick = { install(f) }, modifier = Modifier.fillMaxWidth()) { Text("Install app") }
            }
            p != null -> UploadBar("Downloading APK…", p)
            else -> Button(
                onClick = {
                    error = null
                    progress = 0f
                    scope.launch {
                        try {
                            val downloaded = com.aiapp.data.ApkInstaller.download(context, url) { v ->
                                scope.launch { progress = v }
                            }
                            file = downloaded
                            install(downloaded)
                        } catch (e: Exception) {
                            error = e.message ?: "Download failed."
                        } finally {
                            progress = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                val s = size
                Text("Download & install APK" + if (s != null) " (" + (if (s < 1048576) "${s / 1024} KB" else "%.1f MB".format(s / 1048576.0)) + ")" else "")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
