package com.aiapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiapp.builder.BuildEvent
import com.aiapp.builder.BuildRunner
import com.aiapp.builder.BuildService
import com.aiapp.builder.ReaEngine
import com.aiapp.builder.TimelineItem
import com.aiapp.data.Downloads

/**
 * Dedicated **R.E.A (Reverse Engineer Anything)** Studio Screen (`github.com/morluto/rea` Android Port).
 *
 * Lets the user:
 * 1. Upload any `.apk` file (or specify a website/app to reverse engineer).
 * 2. Inspect the 4 `morluto/rea` static analysis evidence families (`Overview & Signature`, `Manifest XML`,
 *    `DEX Classes & Methods`, `Decoded Layouts, Strings & Assets`).
 * 3. Give the app a **New App Name** & **New Package ID**, toggle **Bundle Original APK Resources**
 *    and **Use Live Internet Research**, and specify any **Custom Modifications**.
 * 4. Rebuild & compile the modified `.apk` directly on GitHub Actions OR export the complete
 *    Android Studio source `.zip` to `Downloads/AIApp/`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaScreen(
    chatVm: ChatViewModel,
    reaVm: ReaViewModel,
    onBack: () -> Unit,
    onOpenBuild: () -> Unit,
    onOpenCodespace: (String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val pickApk = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { reaVm.importApk(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("🔍 R.E.A Studio (Android)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Reverse Engineer Anything (morluto/rea port) · APK → Modified App",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onOpenBuild) { Text("Build") }
                    TextButton(onClick = { onOpenCodespace(null) }) { Text("Codespace") }
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Card 1: Upload APK or Target Website
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "1. Select Target APK or App to Reverse Engineer",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Upload any Android `.apk` file (or enter a website/app below). REA statically deconstructs its binary AndroidManifest.xml, APK Signing Block (v1/v2/v3), Multi-DEX classes/methods/fields, XML layouts, and extracts its reusable drawables, fonts, sounds & assets.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                pickApk.launch(
                                    arrayOf(
                                        "application/vnd.android.package-archive",
                                        "application/octet-stream",
                                        "*/*",
                                    ),
                                )
                            },
                            enabled = !reaVm.inspecting && !reaVm.generatingZip,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (reaVm.uploadedApk == null) "📂 Upload .APK File" else "📂 Change .APK (${reaVm.uploadedApk?.name})")
                        }
                        if (reaVm.uploadedApk != null) {
                            OutlinedButton(onClick = { reaVm.clearApk() }) {
                                Text("Clear")
                            }
                        }
                    }

                    if (reaVm.inspecting) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    OutlinedTextField(
                        value = reaVm.webTargetUrlOrDesc,
                        onValueChange = { reaVm.webTargetUrlOrDesc = it },
                        label = { Text("Or target website URL / app reference (optional)") },
                        placeholder = { Text("e.g. https://example.com or describe an existing app/tool") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Card 2: Interactive REA Evidence Inspector (shown when an APK is loaded)
            reaVm.apkSummary?.let { summary ->
                ReaEvidenceInspectorCard(
                    summary = summary,
                    extractedCount = reaVm.extractedBundle?.files?.size ?: 0,
                    activeTab = reaVm.activeEvidenceTab,
                    onSelectTab = { reaVm.activeEvidenceTab = it },
                    onCopySpec = { clipboard.setText(AnnotatedString(summary.fullMarkdownSpec)) },
                )
            }

            // Card 3: Rename App, Resource & Internet Toggles, and Custom Modifications
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "2. Rename App, Resources & Custom Modifications",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = reaVm.newAppName,
                            onValueChange = { reaVm.updateNewAppName(it) },
                            label = { Text("New App Name") },
                            placeholder = { Text("My Custom App") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = reaVm.newPackageName,
                            onValueChange = { reaVm.newPackageName = it },
                            label = { Text("New Package ID") },
                            placeholder = { Text("com.reabuilt.myapp") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    // Resource & Internet switches
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("📦 Bundle Original APK Resources", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Copies extracted icons, drawables, fonts, raw audio & assets from the uploaded APK into the new app",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = reaVm.bundleApkResources, onCheckedChange = { reaVm.bundleApkResources = it })
                    }

                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("🌐 Use Live Internet Research & Downloads", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Allows the REA Agent to search the web, read API docs, and fetch external assets while modifying",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = reaVm.useInternetResources, onCheckedChange = { reaVm.useInternetResources = it })
                    }

                    Text("1-Tap Modification Presets:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        AssistChip(
                            onClick = {
                                reaVm.modificationsPrompt =
                                    "Recreate this app so every screen, feature, and workflow works identically to the original app under the new name, using all bundled resources."
                            },
                            label = { Text("✨ Exact Clone (New Name)") },
                        )
                        AssistChip(
                            onClick = {
                                reaVm.modificationsPrompt =
                                    "Recreate all features of the original app, but make it work 100% offline with local storage and no login/subscription gate."
                            },
                            label = { Text("🔓 Make 100% Offline") },
                        )
                        AssistChip(
                            onClick = {
                                reaVm.modificationsPrompt =
                                    "Recreate the original app with an OLED Dark Mode theme, modern Material 3 cards, and remove all ads or analytics trackers."
                            },
                            label = { Text("🌙 OLED Dark + No Ads") },
                        )
                        AssistChip(
                            onClick = {
                                reaVm.modificationsPrompt =
                                    "Recreate the original app and add an Export / Backup to phone storage button on the main screen."
                            },
                            label = { Text("📥 Add Export Feature") },
                        )
                    }

                    OutlinedTextField(
                        value = reaVm.modificationsPrompt,
                        onValueChange = { reaVm.modificationsPrompt = it },
                        label = { Text("Describe how the rebuilt app should work & any modifications") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Status / Error Banner
            reaVm.status?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            reaVm.error?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        if (chatVm.githubToken().isBlank()) {
                            TextButton(onClick = {
                                chatVm.signInGithub { link -> onOpenCodespace(link) }
                            }) { Text("Sign in GitHub") }
                        }
                        TextButton(onClick = onOpenSettings) { Text("Settings") }
                    }
                }
            }

            // Card 4: Execute REA Reconstruction (Build APK or Export Source .zip)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "3. Build Reconstructed APK or Download Source .zip",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                reaVm.startReaApkBuild(chatVm) {
                                    chatVm.signInGithub { link -> onOpenCodespace(link) }
                                }
                            },
                            enabled = !BuildRunner.running && !reaVm.generatingZip,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("🚀 Rebuild & Compile APK")
                        }
                        OutlinedButton(
                            onClick = { reaVm.generateReaSourceZip(chatVm) },
                            enabled = !reaVm.generatingZip && !BuildRunner.running,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("📦 Generate Source .zip")
                        }
                    }
                    if (reaVm.generatingZip || BuildRunner.running) {
                        OutlinedButton(
                            onClick = { reaVm.cancelGeneration() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Stop Current Operation")
                        }
                    }
                }
            }

            // Live Thinking & Code Preview during .zip generation
            if (reaVm.generatingZip) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (reaVm.liveThinking.isNotBlank()) {
                    ThinkingBox(text = reaVm.liveThinking, seconds = 0, live = true)
                }
                if (reaVm.liveCodePreview.isNotBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            reaVm.liveCodePreview,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }

            // Generated Android Studio Source .zip Card
            reaVm.generatedZipAttachment?.let { zipAtt ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "📦 Reconstructed Android Studio Project (.zip) Ready!",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${zipAtt.name} · ${reaVm.generatedZipSavedNote ?: "Saved to Downloads/AIApp"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { chatVm.saveZipAttachmentToPhone(zipAtt) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("📥 Save to Downloads")
                            }
                            OutlinedButton(
                                onClick = { Downloads.shareAttachment(context, zipAtt) },
                            ) {
                                Text("📤 Share .zip")
                            }
                        }
                        Button(
                            onClick = {
                                chatVm.sendZipToBuildTab(zipAtt)
                                onOpenBuild()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("🚀 Compile This .zip to APK in Build Tab")
                        }
                    }
                }
            }

            // Live APK Build Progress Card (when a build is running or finished)
            if (BuildRunner.running || BuildRunner.timeline.isNotEmpty()) {
                ReaLiveBuildCard(onOpenFullBuild = onOpenBuild)
            }
        }
    }
}

@Composable
private fun ReaEvidenceInspectorCard(
    summary: ReaEngine.ReaApkSummary,
    extractedCount: Int,
    activeTab: ReaEvidenceTab,
    onSelectTab: (ReaEvidenceTab) -> Unit,
    onCopySpec: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "🔍 REA Evidence: ${summary.appLabel} (${summary.fileName})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Pkg: ${summary.packageName} · v${summary.versionName} · ${summary.classesCount} classes · $extractedCount resources extracted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TextButton(onClick = onCopySpec) {
                    Text("📋 Copy Report")
                }
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ReaEvidenceTab.entries.forEach { tab ->
                    FilterChip(
                        selected = activeTab == tab,
                        onClick = { onSelectTab(tab) },
                        label = { Text(tab.label) },
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp),
            ) {
                SelectionContainer {
                    Column(
                        Modifier
                            .padding(10.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        when (activeTab) {
                            ReaEvidenceTab.OVERVIEW -> {
                                Text("Package: ${summary.packageName}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                Text("Version: ${summary.versionName} (code ${summary.versionCode}) · minSdk ${summary.minSdk} / targetSdk ${summary.targetSdk}", style = MaterialTheme.typography.bodySmall)
                                Text("Signing Schemes: ${summary.signatureReport.schemesSummary}", style = MaterialTheme.typography.bodySmall)
                                if (summary.signatureReport.signerSubject.isNotBlank()) {
                                    Text("Signer: ${summary.signatureReport.signerSubject}", style = MaterialTheme.typography.bodySmall)
                                }
                                Text("SHA-256: ${summary.signatureReport.sha256Digest}", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                Text("Activities (${summary.activities.size}):", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                summary.activities.take(15).forEach { act ->
                                    Text("• $act", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                                if (summary.permissions.isNotEmpty()) {
                                    Spacer(Modifier.size(4.dp))
                                    Text("Permissions (${summary.permissions.size}):", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                                    summary.permissions.take(15).forEach { p ->
                                        Text("• $p", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    }
                                }
                            }
                            ReaEvidenceTab.MANIFEST -> {
                                Text(
                                    summary.decodedManifestXml.ifBlank { "No binary AndroidManifest.xml found." },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                )
                            }
                            ReaEvidenceTab.DEX -> {
                                Text("Domain Classes (${summary.appClasses.size} of ${summary.classesCount}):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                summary.appClasses.take(35).forEach { c ->
                                    Text("• $c", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                Text("Decompiled Method Signatures (${summary.methodSignatures.size} sampled):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                summary.methodSignatures.take(40).forEach { m ->
                                    Text("• $m", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                                if (summary.fieldSignatures.isNotEmpty()) {
                                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                    Text("Decompiled Fields (${summary.fieldSignatures.size} sampled):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                    summary.fieldSignatures.take(25).forEach { f ->
                                        Text("• $f", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    }
                                }
                            }
                            ReaEvidenceTab.RESOURCES -> {
                                Text("Extracted Reusable Resources ($extractedCount bundled):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                summary.reusableResourcePaths.take(25).forEach { r ->
                                    Text("• $r", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }
                                if (summary.decodedLayouts.isNotEmpty()) {
                                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                    Text("Decoded XML Layouts (${summary.decodedLayouts.size}):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                    summary.decodedLayouts.entries.take(4).forEach { (name, xml) ->
                                        Text("[$name]", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall)
                                        Text(xml.take(600), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                                    }
                                }
                                if (summary.networkUrls.isNotEmpty()) {
                                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                    Text("Discovered URLs / Endpoints (${summary.networkUrls.size}):", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                    summary.networkUrls.take(20).forEach { u ->
                                        Text("• $u", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaLiveBuildCard(onOpenFullBuild: () -> Unit) {
    val running = BuildRunner.running
    val project = BuildRunner.project
    val timeline = BuildRunner.timeline
    val lastResult = timeline.lastOrNull { it is TimelineItem.Result } as? TimelineItem.Result
    val recentEvents = timeline.filterIsInstance<TimelineItem.Event>().takeLast(6)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Text(
                        if (running) "Compiling Rebuilt APK: ${project?.appName ?: "REA App"}…"
                        else "REA Build Status: ${project?.appName ?: "Ready"}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                TextButton(onClick = onOpenFullBuild) {
                    Text("Open Build Tab")
                }
            }

            BuildRunner.progress?.let { p ->
                UploadBar(BuildRunner.progressText.ifBlank { "Working…" }, p)
            }

            recentEvents.forEach { item ->
                Text(
                    "• ${item.event.text}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            lastResult?.let { res ->
                Text(
                    res.message,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (res.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
