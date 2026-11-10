package com.aiapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aiapp.llm.OfflineModel
import com.aiapp.llm.ProviderId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOpenCodespace: (String) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FreeCloudSection(vm, onBack)
            OfflineSection(vm, onBack)
            GithubTokenField(vm, onOpenCodespace)
            HorizontalDivider()
            Text(
                "Paid Cloud API keys (optional — stored encrypted on this phone only).",
                style = MaterialTheme.typography.titleMedium,
            )
            ProviderId.entries.filter { it.needsKey }.forEach { KeyField(it, vm) }
        }
    }
}

private fun keyUrl(p: ProviderId): String = when (p) {
    ProviderId.CLAUDE -> "console.anthropic.com/settings/keys"
    ProviderId.GEMINI -> "aistudio.google.com/apikey"
    ProviderId.OPENAI -> "platform.openai.com/api-keys"
    ProviderId.OPEN -> "openrouter.ai/keys (one key for Qwen3 Coder, DeepSeek, Kimi, GLM, Dolphin; some are free)"
    ProviderId.FREE_CLOUD, ProviderId.OFFLINE -> ""
}

@Composable
private fun FreeCloudSection(vm: ChatViewModel, onBackToChat: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Free Cloud Models (No API Key · No Download · Instant)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Use top remote coding, reasoning, and uncensored models (Qwen 2.5 Coder 32B, DeepSeek R1 Full, " +
                    "Uncensored Pro, DeepSeek V3, OpenAI o3/o4 Reasoning, GPT-4.1/5 Large) in Chat and Build Mode " +
                    "immediately with zero API keys and zero browser sign-up.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    vm.selectProvider(ProviderId.FREE_CLOUD)
                    vm.selectModel("qwen-coder")
                    onBackToChat()
                }) { Text("Use Qwen 2.5 Coder 32B") }
                OutlinedButton(onClick = {
                    vm.selectProvider(ProviderId.FREE_CLOUD)
                    vm.selectModel("evil")
                    onBackToChat()
                }) { Text("Use Uncensored Pro") }
            }
        }
    }
}

@Composable
private fun KeyField(p: ProviderId, vm: ChatViewModel) {
    var text by remember(p) { mutableStateOf(vm.key(p)) }
    var note by remember(p) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                note = null
            },
            label = { Text("${p.label} API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Get one at ${keyUrl(p)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                vm.saveKey(p, text)
                note = "Saved"
            }) { Text("Save") }
            TextButton(onClick = {
                text = ""
                vm.saveKey(p, "")
                note = "Cleared"
            }) { Text("Clear") }
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun OfflineSection(vm: ChatViewModel, onBackToChat: () -> Unit) {
    val recommended = vm.recommendedOffline()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Free On-Phone Offline Models (Download & Use)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Run directly on this phone with no internet, no API key, and no website sign-up needed. " +
                "Your phone has about ${vm.deviceRamGb()} GB of RAM, so ${recommended.name} is recommended. " +
                "Interrupted downloads resume automatically where they stopped.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        vm.offlineModels().forEach { OfflineRow(it, vm, onBackToChat) }
        AddOfflineModel(vm)
    }
}

@Composable
private fun OfflineRow(m: OfflineModel, vm: ChatViewModel, onBackToChat: () -> Unit) {
    val progress = vm.downloads[m.id]
    val downloaded = vm.offlineDownloaded(m)
    val isSelected = vm.provider == ProviderId.OFFLINE && vm.model == m.id
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                m.name + if (isSelected) "  (Active)" else "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (m.description.isNotBlank()) {
                Text(
                    m.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                when {
                    downloaded -> "✓ Downloaded · Ready offline (No API key needed)"
                    progress != null -> "Downloading… ${(progress * 100).toInt()}%"
                    m.approxSizeGb == 0.0 -> "From huggingface.co/${m.repo}"
                    else -> "Free download · ~${m.approxSizeGb} GB · needs ${m.minRamGb} GB+ RAM"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    downloaded -> {
                        Button(onClick = {
                            vm.useOffline(m)
                            onBackToChat()
                        }) { Text(if (isSelected) "Use Now" else "Select & Use") }
                        OutlinedButton(onClick = { vm.deleteOffline(m) }) { Text("Delete") }
                    }
                    progress != null -> OutlinedButton(onClick = { vm.cancelDownload(m) }) { Text("Pause") }
                    else -> {
                        Button(onClick = { vm.useOffline(m) }) { Text("Download & Use") }
                        if (m.id.startsWith("hf-")) OutlinedButton(onClick = { vm.deleteOffline(m) }) { Text("Remove") }
                    }
                }
            }
            vm.downloadErrors[m.id]?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun GithubTokenField(vm: ChatViewModel, onOpenCodespace: (String) -> Unit) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showAdvanced by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf(vm.githubClientId()) }
    var token by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("GitHub & Codespace / ChatGPT Codex", style = MaterialTheme.typography.titleMedium)
        val code = vm.githubCode
        when {
            vm.githubToken().isNotBlank() -> {
                Text(
                    "Signed in as ${vm.githubUser.ifBlank { "your GitHub account" }}. " +
                        "New apps get their own repo and can open in GitHub Codespaces automatically.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onOpenCodespace(CodespaceSessionManager.URL_GITHUB_CODESPACES) }) {
                        Text("Open Codespaces")
                    }
                    OutlinedButton(onClick = { onOpenCodespace(CodespaceSessionManager.URL_CHATGPT_CODEX) }) {
                        Text("ChatGPT Codex")
                    }
                    OutlinedButton(onClick = vm::signOutGithub) { Text("Sign out") }
                }
            }
            code != null -> {
                Text("Code copied to clipboard! Approve this code on GitHub:")
                Text(code.userCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(code.userCode))
                        onOpenCodespace(code.verificationUriComplete ?: code.verificationUri)
                    }) { Text("Approve in App") }
                    OutlinedButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(code.userCode))
                        uri.openUri(code.verificationUriComplete ?: code.verificationUri)
                    }) { Text("Open Browser") }
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(code.userCode))
                    }) { Text("Copy code") }
                }
                Text("Waiting for you to approve… (stays active even if you switch apps)", style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        vm.signInGithub { link -> onOpenCodespace(link) }
                    }) { Text("Sign in with GitHub (In-App)") }
                    OutlinedButton(onClick = {
                        vm.signInGithub { link -> uri.openUri(link) }
                    }) { Text("Sign in via Browser") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpenCodespace(CodespaceSessionManager.URL_GITHUB_CODESPACES) }) {
                        Text("GitHub Codespaces")
                    }
                    OutlinedButton(onClick = { onOpenCodespace(CodespaceSessionManager.URL_CHATGPT_CODEX) }) {
                        Text("ChatGPT Codex")
                    }
                }
                Text(
                    "Sign in once inside the app or browser; the app captures your token to create repos, build APKs, and launch Codespaces.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        vm.githubLoginError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Hide advanced" else "Advanced") }
        if (showAdvanced) {
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("GitHub OAuth Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "From github.com/settings/developers → OAuth Apps (tick \"Enable Device Flow\").",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { vm.saveGithubClientId(clientId.trim()) }) { Text("Save Client ID") }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Or paste a personal access token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Classic token with repo, workflow, and codespace scopes, from github.com/settings/tokens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                vm.saveGithubToken(token)
                token = ""
            }, enabled = token.isNotBlank()) { Text("Save token") }
        }
    }
}

@Composable
private fun AddOfflineModel(vm: ChatViewModel) {
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf(vm.hfToken()) }
    var tokenNote by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add any custom Hugging Face model", style = MaterialTheme.typography.titleSmall)
        Text(
            "Paste a Hugging Face link to any LiteRT-LM model (.litertlm), for example from huggingface.co/litert-community.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = link,
            onValueChange = {
                link = it
                error = null
            },
            label = { Text("Hugging Face link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            error = vm.addOfflineModel(link)
            if (error == null) link = ""
        }, enabled = link.isNotBlank()) { Text("Add and download") }
        error?.let { Text(it, style = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                tokenNote = null
            },
            label = { Text("Hugging Face token (optional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Not needed for any of the built-in ★ Free Unrestricted & Coder models above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                vm.saveHfToken(token)
                tokenNote = "Saved"
            }) { Text("Save token") }
            tokenNote?.let { Text("  $it", style = MaterialTheme.typography.labelMedium) }
        }
    }
}
