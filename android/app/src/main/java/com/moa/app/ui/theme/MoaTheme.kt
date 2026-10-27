package com.moa.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4558B5),
    secondary = Color(0xFF526353),
    background = Color(0xFFF8F8FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF0FA),
)

@Composable
fun MoaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else LightColors,
        content = content,
    )
}
