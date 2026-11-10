package com.aiapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiapp.builder.BuildEvent
import com.aiapp.builder.BuildRunner
import com.aiapp.builder.BuildService
import com.aiapp.builder.TimelineItem
import com.aiapp.llm.Attachment
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Role
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onOpenSettings: () -> Unit,
    onOpenBuild: () -> Unit,
    onOpenRea: () -> Unit = {},
    onOpenCodespace: () -> Unit = {},
    onOpenHistory: () -> Unit,
    onOpenCreate: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val voice = remember { VoiceController(context) }
    var voiceMode by rememberSaveable { mutableStateOf(false) }
    var spokenCount by rememberSaveable { mutableStateOf(-1) }
    var live by remember { mutableStateOf<GeminiLive?>(null) }
    fun newLive() = GeminiLive(vm.geminiKey(), vm.liveVoice, vm.liveSystemPrompt()) { u, m -> vm.addVoiceTurn(u, m) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            voice.destroy()
            live?.stop()
        }
    }

    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> vm.attach(uris) }

    LaunchedEffect(vm.openBuild) {
        if (vm.openBuild) {
            vm.openBuild = false
            onOpenBuild()
        }
    }

    // What has already been read aloud from the reply being written.
    var spoken by remember { mutableStateOf("") }
    var saidWait by remember { mutableStateOf(false) }

    fun startListening() {
        spoken = ""
        saidWait = false
        voice.listen { said -> vm.sendVoice(said) }
    }

    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceMode = true
            spokenCount = vm.messages.size
            if (vm.liveEnabled && vm.geminiKey().isNotBlank()) {
                live = newLive().also { it.start() }
            } else {
                vm.warmUp()
                startListening()
            }
        }
    }

    // Voice mode: read each finished sentence aloud while the reply is still being written.
    LaunchedEffect(vm.streamingText, voiceMode) {
        if (!voiceMode || live != null || !vm.streaming) return@LaunchedEffect
        val liveText = vm.streamingText
        if (listOf("<search", "<fetch", "<download", "<images", "<videos").any { liveText.contains(it, true) }) {
            if (!saidWait) {
                saidWait = true
                voice.enqueue("One moment, let me check.")
            }
            return@LaunchedEffect
        }
        if (!liveText.startsWith(spoken)) spoken = ""
        val rest = liveText.substring(spoken.length)
        val end = Regex("[.!?।][\"')\\]]?\\s|\n").findAll(rest).lastOrNull()?.range?.last ?: return@LaunchedEffect
        val piece = rest.substring(0, end + 1)
        if (piece.trim().length < 12) return@LaunchedEffect
        voice.enqueue(piece)
        spoken += piece
    }

    // Problems (missing key, network…) show on the voice screen instead of leaving it stuck.
    LaunchedEffect(vm.error, voiceMode) {
        val e = vm.error
        if (voiceMode && live == null && e != null) voice.showError(e)
    }

    // When the reply is complete: speak whatever is left, then listen again.
    LaunchedEffect(vm.replyCount, voiceMode) {
        if (!voiceMode || live != null || vm.streaming) return@LaunchedEffect
        val last = vm.messages.lastOrNull()
        if (last == null || last.role != Role.ASSISTANT || vm.messages.size <= spokenCount) {
            if (voice.state == VoiceState.THINKING || voice.state == VoiceState.SPEAKING) {
                voice.endOfReply { if (voiceMode && vm.error == null) startListening() }
            }
            return@LaunchedEffect
        }
        run {
            spokenCount = vm.messages.size
            val remainder = when {
                spoken.isEmpty() -> last.text
                last.text.startsWith(spoken) -> last.text.removePrefix(spoken)
                else -> ""
            }
            voice.enqueue(remainder)
            spoken = ""
            voice.endOfReply { if (voiceMode) startListening() }
        }
    }

    LaunchedEffect(vm.messages.size, vm.streamingText.length, vm.streaming, BuildRunner.timeline.size) {
        val totalItems = vm.messages.size + (if (vm.streaming) 1 else 0) + (if (vm.showInChatBuilder || BuildRunner.running) 1 else 0)
        if (totalItems > 0) {
            runCatching { listState.scrollToItem(totalItems - 1) }
        }
    }

    if (voiceMode) {
        val session = live
        val lastReply = vm.messages.lastOrNull { it.role == Role.ASSISTANT }?.text.orEmpty()
        fun exitVoice() {
            voiceMode = false
            live?.stop()
            live = null
            voice.stopListening()
            voice.stopSpeaking()
        }
        if (session != null) {
            VoiceScreen(
                state = session.state,
                level = session.level,
                liveLine = session.said.ifBlank { session.heard }.takeLast(220),
                error = session.error,
                modelLabel = "Natural voice · Gemini Live · ${GeminiLive.voices.firstOrNull { it.first == vm.liveVoice }?.let { "${it.first} (${it.second})" } ?: vm.liveVoice}",
                onTalk = {
                    when (session.state) {
                        VoiceState.SPEAKING -> session.interrupt()
                        VoiceState.IDLE -> session.start()
                        else -> Unit
                    }
                },
                onExit = { exitVoice() },
                settings = {
                    LiveSettings(vm, onChanged = {
                        session.stop()
                        live = if (vm.liveEnabled) newLive().also { it.start() } else null
                        if (live == null) startListening()
                    })
                },
            )
        } else {
            VoiceScreen(
                state = when {
                    voice.state == VoiceState.SPEAKING -> VoiceState.SPEAKING
                    voice.state == VoiceState.LISTENING -> VoiceState.LISTENING
                    vm.streaming || voice.state == VoiceState.THINKING -> VoiceState.THINKING
                    else -> VoiceState.IDLE
                },
                level = voice.level,
                liveLine = voice.partial.ifBlank {
                    if (vm.streaming) VoiceController.plain(vm.streamingText).takeLast(220) else VoiceController.plain(lastReply).take(220)
                },
                error = voice.error,
                modelLabel = "Phone voice · " + vm.voiceModel().let { id -> "${vm.provider.label} · ${vm.models.firstOrNull { it.id == id }?.name ?: id}" },
                onTalk = {
                    vm.stop()
                    startListening()
                },
                onExit = { exitVoice() },
                settings = {
                    LiveSettings(vm, onChanged = {
                        if (vm.liveEnabled && vm.geminiKey().isNotBlank()) {
                            voice.stopListening()
                            voice.stopSpeaking()
                            live = newLive().also { it.start() }
                        }
                    })
                    FilterChip(
                        selected = vm.voiceFastModel,
                        onClick = vm::toggleVoiceFastModel,
                        label = { Text("Fast model for voice (quicker replies)", color = androidx.compose.ui.graphics.Color.White) },
                    )
                    PhoneVoiceSettings(voice)
                },
            )
        }
        androidx.activity.compose.BackHandler { exitVoice() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AI Studio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (vm.unrestrictedMode) "🔓 Unrestricted · Claude Think · Source .zip & APK"
                            else "Claude Think · Source .zip & APK",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onOpenBuild, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Build") }
                    TextButton(onClick = onOpenRea, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("REA", fontWeight = FontWeight.Bold) }
                    TextButton(onClick = onOpenCodespace, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Codespace") }
                    TextButton(onClick = onOpenCreate, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Create") }
                    TextButton(onClick = onOpenHistory, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Chats") }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                vm = vm,
                text = input,
                onTextChange = { input = it },
                onSend = {
                    vm.send(input)
                    if (vm.streaming || input.isNotBlank()) input = ""
                },
                onBuildInChat = {
                    val promptToBuild = input.trim().ifBlank {
                        vm.messages.lastOrNull { it.role == Role.USER }?.text
                            ?: "Build a modern Android app"
                    }
                    vm.buildAppInChat(promptToBuild)
                    if (input.isNotBlank()) {
                        vm.send(input)
                        input = ""
                    }
                },
                onPackZipNow = {
                    vm.compileChatCodeToZip(announceInChat = true)
                },
                onAttach = { pickFiles.launch(arrayOf("*/*")) },
                onVoice = { micPermission.launch(android.Manifest.permission.RECORD_AUDIO) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            PickerRow(vm)
            OfflineReadyBanner(vm)
            vm.error?.let { ErrorBanner(it, onOpenSettings) }
            vm.exportZipStatus?.let { zipNote ->
                ZipSavedBanner(
                    message = zipNote,
                    onOpenBuild = onOpenBuild,
                    onDismiss = { vm.exportZipStatus = null },
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (vm.messages.isEmpty() && !vm.streaming && !vm.showInChatBuilder && !BuildRunner.running) {
                    item {
                        ClaudeWelcomeHero(
                            vm = vm,
                            onPromptPick = { chosen ->
                                input = chosen
                            },
                            onOpenRea = onOpenRea,
                            onAttachZip = { pickFiles.launch(arrayOf("application/zip", "*/*")) },
                        )
                    }
                }
                items(vm.messages) { msg ->
                    MessageBubble(
                        message = msg,
                        providerLabel = vm.provider.label,
                        unrestricted = vm.unrestrictedMode,
                        onBuildZipInChat = vm::buildZip,
                        onSendZipToBuildTab = vm::sendZipToBuildTab,
                        onDownloadZipToPhone = vm::saveZipAttachmentToPhone,
                        onShareZip = vm::shareZipAttachment,
                        onPackMessageToZip = { textToZip ->
                            vm.compileChatCodeToZip(specificText = textToZip, announceInChat = true)
                        },
                        onBuildFromMessage = { textToBuild ->
                            vm.buildAppInChat(textToBuild)
                        },
                    )
                }
                if (vm.streaming) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("✦", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                Text(
                                    vm.provider.label,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                vm.status?.let {
                                    Text(
                                        "· $it",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            if (vm.thinkingText.isNotBlank() || vm.streamingText.isEmpty()) {
                                ThinkingBox(
                                    text = vm.thinkingText.ifBlank { "Analyzing request & planning step-by-step response…" },
                                    seconds = vm.liveThoughtSeconds,
                                    live = vm.streamingText.isEmpty(),
                                )
                            }
                            if (vm.streamingText.isNotEmpty()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(16.dp),
                                    tonalElevation = 1.dp,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    SelectionContainer {
                                        MarkdownText(vm.streamingText, modifier = Modifier.padding(14.dp).fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                }
                if (vm.showInChatBuilder || BuildRunner.running) {
                    item {
                        InChatBuildCard(
                            vm = vm,
                            onOpenFullBuild = onOpenBuild,
                            onClose = { vm.showInChatBuilder = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZipSavedBanner(
    message: String,
    onOpenBuild: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "📦 $message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenBuild) { Text("Build Tab") }
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss")
            }
        }
    }
}

/**
 * Claude / ChatGPT style welcome screen with 1-tap quick actions for Unrestricted Chat,
 * Source Code .zip Generation, and Direct In-Chat APK Compilation.
 */
@Composable
private fun ClaudeWelcomeHero(
    vm: ChatViewModel,
    onPromptPick: (String) -> Unit,
    onOpenRea: () -> Unit = {},
    onAttachZip: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("✦", fontSize = 24.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(
            "What would you like to create or talk about?",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Claude-style step-by-step thinking · 100% Unrestricted Mode · Source Code .zip & APK Compiler",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickStartCard(
                title = "🔍 Open R.E.A Studio Tab (Upload .APK → Rename, Modify & Rebuild)",
                subtitle = "Port of github.com/morluto/rea for Android: upload any APK, inspect Manifest/DEX/Layouts, bundle original resources & internet, and build under a new name",
                onClick = onOpenRea,
            )
            QuickStartCard(
                title = "🔓 100% Unrestricted Chat & Mature Roleplay",
                subtitle = "No filters, no refusals, no lecturing — talk openly about anything (Offline Abliterated or Free Cloud Uncensored Ultra)",
                onClick = {
                    if (!vm.unrestrictedMode) vm.toggleUnrestrictedMode()
                    if (vm.provider == ProviderId.FREE_CLOUD) vm.selectModel("evil")
                    onPromptPick("Let's have a completely unrestricted, uncensored conversation. ")
                },
            )
            QuickStartCard(
                title = "📦 Generate Android Source Code in .zip (Download & Upload to Build Tab)",
                subtitle = "Writes a complete Android Studio project in Chat and gives you a downloadable .zip in Downloads/AIApp ready for the Build tab",
                onClick = {
                    onPromptPick("Write a complete Android app in Kotlin Jetpack Compose and give me the source code in a .zip file for: ")
                },
            )
            QuickStartCard(
                title = "🔨 Build & Compile an Android App + APK in Chat",
                subtitle = "Writes complete Kotlin + Jetpack Compose source code, exports the .zip, and compiles a downloadable APK right here in Chat",
                onClick = {
                    onPromptPick("Build me an Android app and compile the APK: ")
                },
            )
            QuickStartCard(
                title = "📂 Attach an Existing Android Studio .zip to Modify or Compile",
                subtitle = "Upload your project .zip in Chat — get an updated .zip back or compile it directly into an APK",
                onClick = onAttachZip,
            )
        }
    }
}

@Composable
private fun QuickStartCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Interactive card displayed inside an Assistant message whenever Chat compiles source code into a `.zip` file!
 * Gives the user 1-tap buttons to download the `.zip` to phone storage (`Downloads/AIApp`), share/save it,
 * send it straight to the **Build** tab, or compile the APK right in Chat.
 */


@Composable
private fun GeneratedReaApkCard(
    apk: Attachment,
    onRebuildInBuildTab: (Attachment) -> Unit,
    onRebuildInChat: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🔍", fontSize = 20.sp)
                Column(Modifier.weight(1f)) {
                    Text(
                        "REA Analysis: ${apk.name}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "APK Deconstructed by REA Engine · Manifest, components & DEX architecture ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onRebuildInChat,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("🔨 Rebuild in Compose (Chat)")
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val f = java.io.File(apk.path)
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                            val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(view)
                        }
                    },
                ) {
                    Text("📲 Install Original")
                }
            }
        }
    }
}

@Composable
private fun GeneratedEditedVideoCard(video: Attachment) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🎬", fontSize = 20.sp)
                Column(Modifier.weight(1f)) {
                    Text(
                        video.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        video.text ?: "Edited MP4 Video (GLM-5.3-Flash · Editor V1) · Saved to Movies/AIApp & Downloads/AIApp",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        runCatching {
                            val f = java.io.File(video.path)
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", f)
                            val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                .setDataAndType(uri, "video/mp4")
                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(view)
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("▶ Play Edited Video (.mp4)")
                }
                OutlinedButton(
                    onClick = {
                        com.aiapp.data.Downloads.shareAttachment(context, video)
                    },
                ) {
                    Text("📤 Share / Save")
                }
            }
        }
    }
}

@Composable
private fun GeneratedSourceZipCard(
    zip: Attachment,
    onDownloadToPhone: (Attachment) -> Unit,
    onShareZip: (Attachment) -> Unit,
    onSendToBuildTab: (Attachment) -> Unit,
    onCompileInChat: (Attachment) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("📦", fontSize = 20.sp)
                Column(Modifier.weight(1f)) {
                    Text(
                        zip.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        zip.text ?: "Complete Android Studio Gradle Project (.zip) · Saved to Downloads/AIApp",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onDownloadToPhone(zip) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("📥 Download .zip to Phone")
                }
                OutlinedButton(
                    onClick = { onShareZip(zip) },
                ) {
                    Text("Save As / Share")
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onSendToBuildTab(zip) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("🚀 Upload to Build Tab (Compile APK)")
                }
                OutlinedButton(
                    onClick = { onCompileInChat(zip) },
                ) {
                    Text("🔨 Build Here")
                }
            }
        }
    }
}

/**
 * Live In-Chat Android Studio Source Generator & APK Compiler Card.
 * Lets the user watch every build step, export the generated Android Studio source .zip,
 * and download/install the compiled APK without leaving the Chat tab!
 */
@Composable
private fun InChatBuildCard(
    vm: ChatViewModel,
    onOpenFullBuild: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val running = BuildRunner.running
    val project = BuildRunner.project
    val timeline = BuildRunner.timeline
    val lastResult = timeline.lastOrNull { it is TimelineItem.Result } as? TimelineItem.Result
    val recentEvents = timeline.filterIsInstance<TimelineItem.Event>().takeLast(6)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Column {
                        Text(
                            if (running) "Building APK in Chat: ${project?.appName ?: "Android App"}…"
                            else "In-Chat App Builder: ${project?.appName ?: "Ready"}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (running) "Generating source code & compiling APK on GitHub Actions"
                            else if (lastResult?.success == true) "✓ APK compiled & ready to install"
                            else "Source code & APK builder",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Row {
                    TextButton(onClick = onOpenFullBuild) { Text("Files / Logs") }
                    if (!running) {
                        IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Hide builder card")
                        }
                    }
                }
            }

            BuildRunner.progress?.let { p ->
                UploadBar(BuildRunner.progressText.ifBlank { "Working…" }, p)
            }

            if (recentEvents.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        recentEvents.forEach { item ->
                            val icon = when (item.event.kind) {
                                BuildEvent.Kind.THINKING -> "🧠"
                                BuildEvent.Kind.FILE -> "📝"
                                BuildEvent.Kind.BUILD -> "⚙️"
                                BuildEvent.Kind.DONE -> "✅"
                                BuildEvent.Kind.ERROR -> "⚠️"
                                else -> "•"
                            }
                            Text(
                                "$icon ${item.event.text}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            lastResult?.let { res ->
                Text(
                    res.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (res.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium,
                )
                res.apkUrl?.let { apkUrl ->
                    InChatApkDownload(url = apkUrl)
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (project != null && project.files.isNotEmpty()) {
                    OutlinedButton(
                        onClick = { vm.exportCurrentProjectZip() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("📦 Save Source .zip (${project.files.size} files)")
                    }
                }
                if (running) {
                    OutlinedButton(
                        onClick = { BuildService.stop(context) },
                    ) {
                        Text("Stop Build")
                    }
                }
            }
        }
    }
}

@Composable
private fun InChatApkDownload(url: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                Text(
                    "✓ Saved ${f.name} to Downloads/AIApp",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(onClick = { install(f) }, modifier = Modifier.fillMaxWidth()) {
                    Text("📲 Install Compiled APK")
                }
            }
            p != null -> UploadBar("Downloading compiled APK…", p)
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
                Text(
                    "📥 Download & Install APK" +
                        if (s != null) " (" + (if (s < 1048576) "${s / 1024} KB" else "%.1f MB".format(s / 1048576.0)) + ")" else "",
                )
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * One-tap "Download & Use" card shown right on the Chat screen when an Offline model is selected
 * and hasn't been downloaded yet — no API key, no Settings visit, and no website visit needed.
 */
@Composable
fun OfflineReadyBanner(vm: ChatViewModel) {
    val m = vm.selectedOfflineModel() ?: return
    if (vm.offlineDownloaded(m)) return
    val progress = vm.downloads[m.id]
    val err = vm.downloadErrors[m.id]

    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                m.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (m.description.isNotBlank()) {
                Text(
                    m.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Completely free · 100% Unrestricted on-device · ~${m.approxSizeGb} GB (${m.minRamGb} GB+ RAM)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Downloading… ${(progress * 100).toInt()}% (resumable)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { vm.cancelDownload(m) }) {
                        Text("Pause")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.downloadOffline(m) }) {
                        Text("Download & Use Now (${m.approxSizeGb} GB)")
                    }
                }
            }
            err?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PickerRow(vm: ChatViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dropdown(
            label = vm.provider.label,
            options = ProviderId.entries.toList(),
            display = { it.label },
            onSelect = vm::selectProvider,
        )
        val modelLabel = when {
            vm.loadingModels -> "Loading models…"
            vm.model.isBlank() -> "Pick model"
            else -> vm.models.firstOrNull { it.id == vm.model }?.name ?: vm.model
        }
        Dropdown(
            label = modelLabel,
            options = vm.models,
            display = { it.name },
            onSelect = { vm.selectModel(it.id) },
            onOpen = { if (vm.models.isEmpty()) vm.refreshModels() },
        )
        AssistChip(
            onClick = vm::newChat,
            label = { Text("➕ New Chat") },
        )
        AssistChip(
            onClick = { vm.compileChatCodeToZip(announceInChat = true) },
            label = { Text("📦 Pack Source .zip") },
        )
        AssistChip(
            onClick = vm::toggleUnrestrictedMode,
            label = { Text(if (vm.unrestrictedMode) "🔓 Unrestricted: ON" else "🔒 Unrestricted: OFF") },
        )
        AssistChip(
            onClick = vm::toggleThinkFirstMode,
            label = { Text(if (vm.thinkFirstMode) "🧠 Claude Think: ON" else "🧠 Think: OFF") },
        )
        AssistChip(
            onClick = vm::toggleWeb,
            label = { Text(if (vm.webEnabled) "🌐 Web: ON" else "🌐 Web: OFF") },
        )
        Dropdown(
            label = "Effort: ${vm.effort.label}",
            options = Effort.entries.toList(),
            display = { it.label },
            onSelect = vm::selectEffort,
        )
    }
}

@Composable
private fun <T> Dropdown(
    label: String,
    options: List<T>,
    display: (T) -> String,
    onSelect: (T) -> Unit,
    onOpen: () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = {
                onOpen()
                open = true
            },
            label = {
                Text(
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp),
                )
            },
        )
        DropdownMenu(expanded = open && options.isNotEmpty(), onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text("Settings") }
        }
    }
}

/**
 * Claude / ChatGPT style message layout:
 * - User messages appear in a sleek right-aligned pill bubble.
 * - Assistant messages appear in a full-width card with an avatar badge, collapsible Claude
 *   Extended Thinking block ("Thought for N seconds"), formatted Markdown/Code, any generated
 *   Android Studio `.zip` download cards, and an action bar ("📋 Copy", "📦 Pack Source .zip", "🔨 Compile to APK").
 */
@Composable
private fun MessageBubble(
    message: ChatMessage,
    providerLabel: String = "Assistant",
    unrestricted: Boolean = true,
    onBuildZipInChat: (Attachment) -> Unit = {},
    onSendZipToBuildTab: (Attachment) -> Unit = {},
    onDownloadZipToPhone: (Attachment) -> Unit = {},
    onShareZip: (Attachment) -> Unit = {},
    onPackMessageToZip: (String) -> Unit = {},
    onBuildFromMessage: (String) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    if (message.role == Role.USER) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            message.attachments.forEach { a ->
                AttachmentChip(a, onRemove = null)
                if (a.isZip) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                        Button(onClick = { onSendZipToBuildTab(a) }) {
                            Text("🚀 Compile in Build Tab")
                        }
                        OutlinedButton(onClick = { onBuildZipInChat(a) }) {
                            Text("🔨 Build Here")
                        }
                    }
                }
                if (a.isApk) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                        Button(onClick = { onBuildFromMessage("Use REA (Reverse Engineer Anything) to deconstruct and rebuild ${a.name} in clean modern Kotlin + Jetpack Compose:

" + (a.text ?: a.name)) }) {
                            Text("🔍 REA Deconstruct & Rebuild")
                        }
                    }
                }
            }
            if (message.text.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                    modifier = Modifier.widthIn(max = 330.dp),
                ) {
                    SelectionContainer {
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✦", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text(
                    providerLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (unrestricted) {
                    Text(
                        "· Unrestricted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (message.thinking.isNotBlank()) {
                ThinkingBox(message.thinking, message.thoughtSeconds, live = false)
            }
            if (message.media.isNotEmpty()) MediaRow(message.media)
            message.attachments.filter { it.isZip }.forEach { zipAtt ->
                GeneratedSourceZipCard(
                    zip = zipAtt,
                    onDownloadToPhone = onDownloadZipToPhone,
                    onShareZip = onShareZip,
                    onSendToBuildTab = onSendZipToBuildTab,
                    onCompileInChat = onBuildZipInChat,
                )
            }
            message.attachments.filter { it.isVideo }.forEach { vidAtt ->
                GeneratedEditedVideoCard(video = vidAtt)
            }
            message.attachments.filter { it.isApk }.forEach { apkAtt ->
                GeneratedReaApkCard(
                    apk = apkAtt,
                    onRebuildInBuildTab = onSendZipToBuildTab,
                    onRebuildInChat = { onBuildFromMessage("Use REA (Reverse Engineer Anything) to rebuild and recreate this app in clean Kotlin + Jetpack Compose based on this APK inspection:

" + (apkAtt.text ?: apkAtt.name)) },
                )
            }
            if (message.text.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        SelectionContainer {
                            MarkdownText(message.text, modifier = Modifier.fillMaxWidth())
                        }
                        HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 4.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = { clipboard.setText(AnnotatedString(message.text)) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("📋 Copy", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(
                                onClick = { onPackMessageToZip(message.text) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("📦 Pack Source .zip", style = MaterialTheme.typography.labelMedium)
                            }
                            TextButton(
                                onClick = { onBuildFromMessage(message.text.take(1500)) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text("🔨 Compile to APK", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentChip(a: Attachment, onRemove: (() -> Unit)?) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.padding(bottom = 4.dp).widthIn(max = 300.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp)) {
            if (a.isImage) {
                val bmp = remember(a.path) {
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(a.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
                    }.getOrNull()
                }
                bmp?.let {
                    androidx.compose.foundation.Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.padding(vertical = 6.dp).size(40.dp),
                    )
                }
            }
            Text(
                (if (a.isImage) "" else if (a.isPdf) "PDF · " else if (a.isZip) "Project · " else if (a.isApk) "🔍 REA APK · " else "File · ") + a.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp).weight(1f, fill = false),
            )
            if (onRemove != null) TextButton(onClick = onRemove) { Text("✕") }
        }
    }
}

@Composable
private fun Composer(
    vm: ChatViewModel,
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onBuildInChat: () -> Unit,
    onPackZipNow: () -> Unit,
    onAttach: () -> Unit,
    onVoice: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            vm.attachProgress.toList().forEach { (_, p) -> UploadBar("Attaching…", p) }
            if (vm.pendingAttachments.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.pendingAttachments.toList().forEach { a -> AttachmentChip(a, onRemove = { vm.removeAttachment(a) }) }
                }
                if (!vm.attaching) {
                    Text(
                        "✓ ${vm.pendingAttachments.size} file${if (vm.pendingAttachments.size == 1) "" else "s"} ready — send to chat or tap 🔨 Build APK",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onAttach, enabled = !vm.streaming) {
                    Icon(Icons.Filled.Add, contentDescription = "Attach files, photos, or .zip projects")
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = {
                        Text(
                            if (vm.unrestrictedMode) "Message (Unrestricted, Pack .zip & Build APK)…"
                            else "Message or ask for source code .zip…",
                        )
                    },
                    shape = RoundedCornerShape(22.dp),
                    maxLines = 6,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                if (!vm.streaming && text.isNotBlank()) {
                    IconButton(
                        onClick = onBuildInChat,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .size(40.dp),
                    ) {
                        Icon(
                            Icons.Filled.Build,
                            contentDescription = "Build & Compile Android APK in Chat",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                }
                when {
                    vm.streaming -> Button(onClick = vm::stop, shape = RoundedCornerShape(20.dp)) { Text("Stop") }
                    text.isBlank() && vm.pendingAttachments.isEmpty() -> OutlinedButton(
                        onClick = onVoice,
                        shape = RoundedCornerShape(20.dp),
                    ) { Text("🎙") }
                    else -> Button(onClick = onSend, shape = RoundedCornerShape(20.dp)) { Text("Send") }
                }
            }
        }
    }
}

/** Natural voice (Gemini Live) on/off and its voices. */
@Composable
private fun LiveSettings(vm: ChatViewModel, onChanged: () -> Unit) {
    val white = androidx.compose.ui.graphics.Color.White
    FilterChip(
        selected = vm.liveEnabled,
        onClick = {
            vm.toggleLive()
            onChanged()
        },
        label = { Text("Natural human-like voice (Gemini Live)", color = white) },
    )
    if (vm.geminiKey().isBlank()) {
        Text("Add a free Gemini key in Settings to use the natural voice.", color = white.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
    } else if (vm.liveEnabled) {
        GeminiLive.voices.forEach { (name, desc) ->
            FilterChip(
                selected = vm.liveVoice == name,
                onClick = {
                    vm.chooseLiveVoice(name)
                    onChanged()
                },
                label = { Text("$name · $desc", color = white) },
            )
        }
    }
}

/** A Claude-style progress bar: label, bar and percentage (or a moving bar when the size is unknown). */
@Composable
fun UploadBar(label: String, progress: Float, detail: String = "") {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress >= 0f) Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        }
        if (progress >= 0f) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        if (detail.isNotBlank()) {
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * Claude Extended Thinking Card:
 * Streams the model's step-by-step reasoning live while it thinks (with a live elapsed timer),
 * then folds cleanly into "🧠 Thought for N seconds ▾" (tap to expand/collapse).
 */
@Composable
fun ThinkingBox(text: String, seconds: Int, live: Boolean) {
    var open by remember(live) { mutableStateOf(live) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !live) { open = !open },
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
            )
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (live) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when {
                            live && seconds > 0 -> "🧠 Thinking · ${seconds}s…"
                            live -> "🧠 Thinking step by step…"
                            seconds > 0 -> "🧠 Thought for ${seconds}s  ${if (open) "▴" else "▾"}"
                            else -> "🧠 Thought process  ${if (open) "▴" else "▾"}"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (open) {
                    Text(
                        if (live) text.takeLast(1400) else text,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
