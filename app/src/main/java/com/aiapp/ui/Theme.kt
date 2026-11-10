package com.aiapp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFFC96442),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF0E6DC),
    onPrimaryContainer = Color(0xFF3D3D3A),
    background = Color(0xFFFAF9F5),
    surface = Color(0xFFFAF9F5),
    surfaceVariant = Color(0xFFF0EEE6),
    onBackground = Color(0xFF1F1E1D),
    onSurface = Color(0xFF1F1E1D),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFD97757),
    onPrimary = Color(0xFF1F1E1D),
    primaryContainer = Color(0xFF3A3532),
    onPrimaryContainer = Color(0xFFF0EEE6),
    background = Color(0xFF262624),
    surface = Color(0xFF262624),
    surfaceVariant = Color(0xFF30302E),
    onBackground = Color(0xFFF0EEE6),
    onSurface = Color(0xFFF0EEE6),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
