package com.aiapp.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Full-screen live conversation with an animated orb. */
@Composable
fun VoiceScreen(
    state: VoiceState,
    level: Float,
    liveLine: String,
    error: String?,
    modelLabel: String,
    onTalk: () -> Unit,
    onExit: () -> Unit,
    settings: @Composable () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    Surface(color = Color(0xFF05070D), modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(modelLabel, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(260.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTalk() },
                contentAlignment = Alignment.Center,
            ) { Orb(state, level) }
            Spacer(Modifier.padding(12.dp))
            Text(
                when (state) {
                    VoiceState.LISTENING -> "Listening…"
                    VoiceState.THINKING -> "Thinking…"
                    VoiceState.SPEAKING -> "Speaking — just talk, or tap the orb, to interrupt"
                    VoiceState.IDLE -> "Tap the orb and speak"
                },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (liveLine.isNotBlank()) {
                Text(
                    liveLine,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            error?.let {
                Text(it, color = Color(0xFFFF8A80), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.weight(1f))
            if (showSettings) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) { settings() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showSettings = !showSettings }) { Text("Voice", color = Color.White) }
                Button(onClick = onTalk, enabled = state != VoiceState.LISTENING) {
                    Text(if (state == VoiceState.SPEAKING) "Interrupt" else "🎙 Talk")
                }
                OutlinedButton(onClick = onExit) { Text("✕ End", color = Color.White) }
            }
        }
    }
}

/** Settings for the phone's own voice. */
@Composable
fun PhoneVoiceSettings(voice: VoiceController) {
    VoiceSettings(voice)
}

@Composable
private fun VoiceSettings(voice: VoiceController) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Deeper" to 0.8f, "Normal" to 1.0f, "Higher" to 1.2f).forEach { (n, p) ->
                FilterChip(selected = voice.pitch == p, onClick = { voice.choosePitch(p) }, label = { Text(n, color = Color.White) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Slow" to 0.8f, "Normal" to 1.0f, "Fast" to 1.25f).forEach { (n, r) ->
                FilterChip(selected = voice.rate == r, onClick = { voice.chooseRate(r) }, label = { Text(n, color = Color.White) })
            }
        }
        Text("Voices on this phone (tap to hear):", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
        voice.voices.forEach { v ->
            TextButton(onClick = { voice.selectVoice(v) }) {
                Text(
                    (if (voice.voiceName == v.name) "● " else "") + "${v.locale.displayName} · ${v.name}" +
                        if (v.isNetworkConnectionRequired) " (online)" else "",
                    color = Color.White,
                )
            }
        }
    }
}

/** A glowing, slowly turning sphere that breathes with your voice and ripples while speaking. */
@Composable
private fun Orb(state: VoiceState, micLevel: Float) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin")
    val wave by t.animateFloat(0f, 2f * PI.toFloat(), infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "wave")
    val breathe by t.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breathe")

    val energyTarget = when (state) {
        VoiceState.LISTENING -> 0.15f + micLevel * 0.85f
        VoiceState.SPEAKING -> 0.55f + 0.25f * sin(wave * 2f)
        VoiceState.THINKING -> 0.35f
        VoiceState.IDLE -> 0.1f
    }
    val energy by animateFloatAsState(energyTarget, tween(180), label = "energy")
    val speed = if (state == VoiceState.THINKING) 3f else 1f

    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2, size.height / 2)
        val base = size.minDimension * 0.30f * breathe * (1f + energy * 0.18f)

        // Outer glow.
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFF4FC3F7).copy(alpha = 0.35f + energy * 0.25f), Color.Transparent),
                center = c,
                radius = base * 1.9f,
            ),
            radius = base * 1.9f,
            center = c,
        )
        // Core sphere.
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFE3F6FF), Color(0xFF64B5F6), Color(0xFF1E3A8A)),
                center = Offset(c.x - base * 0.3f, c.y - base * 0.35f),
                radius = base * 1.4f,
            ),
            radius = base,
            center = c,
        )
        // Swirling bands, like weather over a globe.
        for (i in 0 until 3) {
            val a = spin * speed + i * 2.1f
            val r = base * (0.55f + 0.12f * i) * (1f + energy * 0.15f * sin(wave + i))
            blob(c + Offset(cos(a) * base * 0.35f, sin(a * 1.3f) * base * 0.25f), r, listOf(Color(0xFF81D4FA), Color(0xFF7C4DFF))[i % 2], 0.35f)
        }
        // Ripples while speaking.
        if (state == VoiceState.SPEAKING) {
            for (i in 0 until 3) {
                val p = ((wave / (2f * PI.toFloat())) + i / 3f) % 1f
                drawCircle(
                    Color(0xFF81D4FA).copy(alpha = (1f - p) * 0.35f),
                    radius = base * (1f + p * 0.7f),
                    center = c,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
                )
            }
        }
        // Specular highlight.
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), center = Offset(c.x - base * 0.35f, c.y - base * 0.4f), radius = base * 0.45f),
            radius = base * 0.45f,
            center = Offset(c.x - base * 0.35f, c.y - base * 0.4f),
        )
    }
}

private fun DrawScope.blob(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = center, radius = radius),
        radius = radius,
        center = center,
    )
}
