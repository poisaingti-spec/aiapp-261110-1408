package com.aiapp.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiapp.llm.ProviderId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(vm: CreateViewModel, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    var prompt by rememberSaveable {
        mutableStateOf("Edit this video with Editor V1: add dynamic captions, a hook title banner, punch-in zooms at key moments, and cinematic color grading.")
    }
    var modelMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val pickPhoto = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.attach(it) } }

    val pickVideo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.attachVideoForEdit(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Create & AI Video Editor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "🎬 GLM-5.3-Flash Free ($0 · Editor V1 Skill · No Subscription)",
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
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = vm.tabMode == CreateTabMode.FREE_VIDEO_EDITOR,
                    onClick = { vm.selectTabMode(CreateTabMode.FREE_VIDEO_EDITOR) },
                    label = { Text("🎬 AI Video Editor ($0 Free)") },
                )
                FilterChip(
                    selected = vm.tabMode == CreateTabMode.IMAGE,
                    onClick = { vm.selectTabMode(CreateTabMode.IMAGE) },
                    label = { Text("🖼 Image") },
                )
                FilterChip(
                    selected = vm.tabMode == CreateTabMode.VEO_VIDEO,
                    onClick = { vm.selectTabMode(CreateTabMode.VEO_VIDEO) },
                    label = { Text("🎥 Veo Video") },
                )
            }

            if (vm.tabMode == CreateTabMode.FREE_VIDEO_EDITOR) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "🎬 GLM-5.3-Flash (321B MIT) · Editor V1 Video Studio",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "100% Free ($0 · No API key or subscription needed). Pick a video from your phone, choose an Editor V1 preset or give timestamp notes (e.g., \"at second 5 zoom in and add a caption\"), and render the edited MP4 directly on your phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        // Model selector
                        Column {
                            OutlinedButton(onClick = { modelMenu = true }) {
                                Text(vm.models.firstOrNull { it.id == vm.model }?.name ?: "★ GLM-5.3-Flash Free 321B (Editor V1 · $0)")
                            }
                            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                vm.models.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m.name) },
                                        onClick = {
                                            vm.model = m.id
                                            modelMenu = false
                                        },
                                    )
                                }
                            }
                        }

                        // Video picker & preview
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Button(onClick = { pickVideo.launch(arrayOf("video/*")) }) {
                                Text(if (vm.sourceVideo == null) "📂 + Pick Video to Edit" else "📂 Change Video")
                            }
                            if (vm.sourceVideo != null) {
                                TextButton(onClick = { vm.clearSourceVideo() }) {
                                    Text("Remove")
                                }
                            }
                        }

                        vm.sourceVideo?.let { vid ->
                            val meta = vm.sourceVideoMeta
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    vm.sourceVideoThumb?.let { bmp ->
                                        Image(
                                            bmp.asImageBitmap(),
                                            contentDescription = "Video thumbnail",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(68.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                        )
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(vid.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                        if (meta != null) {
                                            Text(
                                                "Duration: ${"%.1f".format(meta.durationSec)}s · ${meta.displayWidth}×${meta.displayHeight}",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Text(
                                                "⏱ Estimated edit time: ~${meta.estimatedRenderSec} seconds",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Color grade & Aspect ratio chips
                        Text("Color Grade & Aspect Ratio:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(
                                "cinematic" to "🎨 Cinematic",
                                "vibrant" to "✨ Vibrant",
                                "warm" to "🌅 Warm Gold",
                                "cyberpunk" to "⚡ Cyberpunk",
                                "bw" to "🖤 B&W",
                                "none" to "Original Color",
                            ).forEach { (id, label) ->
                                FilterChip(
                                    selected = vm.selectedColorGrade == id,
                                    onClick = { vm.selectedColorGrade = id },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            listOf(
                                "original" to "📐 Keep Original Aspect",
                                "9:16" to "📱 9:16 Vertical (Shorts/Reels)",
                                "16:9" to "🖥 16:9 YouTube Landscape",
                            ).forEach { (id, label) ->
                                FilterChip(
                                    selected = vm.selectedTargetAspect == id,
                                    onClick = { vm.selectedTargetAspect = id },
                                    label = { Text(label) },
                                )
                            }
                        }

                        // 1-Tap Editor V1 Skill Quick Prompts
                        Text("1-Tap Editor V1 Skill Presets:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AssistChip(
                                onClick = {
                                    prompt = "Edit this video with Editor V1: add dynamic captions, a hook title banner, punch-in zooms, and cinematic color grading."
                                },
                                label = { Text("✨ Editor V1 Full Edit") },
                            )
                            AssistChip(
                                onClick = {
                                    prompt = "At second 2 and second 5 add a punch-in zoom (1.22x) with bold yellow & cyan captions highlighting the key message."
                                },
                                label = { Text("🔍 Timestamp Zoom (Sec 5)") },
                            )
                            AssistChip(
                                onClick = {
                                    vm.selectedTargetAspect = "9:16"
                                    prompt = "Convert to a 9:16 viral vertical Short with Editor V1 hook banner, fast timed captions, and bottom retention progress bar."
                                },
                                label = { Text("📱 Viral 9:16 Short") },
                            )
                            AssistChip(
                                onClick = {
                                    prompt = "Trim to the first 15 seconds, apply warm color grading, and add clean captions every 3 seconds."
                                },
                                label = { Text("✂️ Trim First 15s") },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Editor V1 instructions (use exact seconds like 'at second 5...')") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (vm.busy) {
                    LinearProgressIndicator(
                        progress = { vm.editorProgress.coerceIn(0.02f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(vm.status.orEmpty(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                    if (vm.editorThinking.isNotBlank()) {
                        ThinkingBox(text = vm.editorThinking, seconds = 0, live = true)
                    }
                } else {
                    Button(
                        onClick = { vm.runFreeVideoEdit(prompt) },
                        enabled = vm.sourceVideo != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("🎬 Edit Video with GLM-5.3-Flash ($0 Free)")
                    }
                    vm.status?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                // Standard Image / Veo Video modes
                if (!vm.video) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = vm.imageProvider == ProviderId.GEMINI, onClick = { vm.setProvider(ProviderId.GEMINI) }, label = { Text("Gemini") })
                        FilterChip(selected = vm.imageProvider == ProviderId.OPENAI, onClick = { vm.setProvider(ProviderId.OPENAI) }, label = { Text("ChatGPT") })
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = vm.aspect == "16:9", onClick = { vm.aspect = "16:9" }, label = { Text("Landscape 16:9") })
                        FilterChip(selected = vm.aspect == "9:16", onClick = { vm.aspect = "9:16" }, label = { Text("Portrait 9:16") })
                    }
                }
                Column {
                    OutlinedButton(onClick = { modelMenu = true }, enabled = vm.models.isNotEmpty()) {
                        Text(vm.models.firstOrNull { it.id == vm.model }?.name ?: if (vm.models.isEmpty()) "Loading models…" else "Pick a model")
                    }
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        vm.models.forEach { m ->
                            DropdownMenuItem(text = { Text(m.name) }, onClick = {
                                vm.model = m.id
                                modelMenu = false
                            })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickPhoto.launch(arrayOf("image/*")) }) {
                        Text(if (vm.reference == null) "+ Add a photo" else "Change photo")
                    }
                    vm.reference?.let { ref ->
                        val bmp = remember(ref.path) { BitmapFactory.decodeFile(ref.path, BitmapFactory.Options().apply { inSampleSize = 4 }) }
                        bmp?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp)) }
                        TextButton(onClick = { vm.reference = null }) { Text("Remove") }
                    }
                }
                if (vm.reference != null) {
                    Text(
                        if (vm.video) "The video will start from this photo." else "Describe how to change this photo.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(if (vm.video) "Describe the video (scene, camera, sound)" else "Describe the image") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (vm.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(vm.status.orEmpty(), modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                    }
                } else {
                    Button(onClick = { vm.generate(prompt) }, enabled = prompt.isNotBlank() && vm.model.isNotBlank()) {
                        Text(if (vm.video) "Create video" else "Create image")
                    }
                    vm.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            vm.error?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenSettings) { Text("Settings") }
                }
            }
            vm.resultBytes?.let { bytes ->
                val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                bmp?.let {
                    Image(it.asImageBitmap(), contentDescription = "Generated image", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                }
            }
            vm.resultUri?.let { uri ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (vm.resultIsVideo) "🎬 Edited MP4 Video Ready!" else "🖼 Image Ready!",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (vm.editorPlanSummary.isNotBlank()) {
                            Text(
                                vm.editorPlanSummary.take(600),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (vm.resultIsVideo) "video/mp4" else "image/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { context.startActivity(view) }
                            }) { Text(if (vm.resultIsVideo) "▶ Play Edited Video" else "Open") }
                            OutlinedButton(onClick = {
                                val share = Intent(Intent.ACTION_SEND).setType(if (vm.resultIsVideo) "video/mp4" else "image/*")
                                    .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                runCatching { context.startActivity(Intent.createChooser(share, "Share")) }
                            }) { Text("📤 Share / Save") }
                        }
                    }
                }
            }
        }
    }
}
