package com.guardvoice.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object GuardColors {
    val Background = Color(0xFFF7F5EF)
    val Surface = Color(0xFFFDFBF5)
    val SurfaceMuted = Color(0xFFEDE8DA)
    val Ink = Color(0xFF1D1A16)
    val InkMuted = Color(0xFF6C675B)
    val Line = Color(0xFFE2DED2)
    val Forest = Color(0xFF186A3B)
    val ForestSoft = Color(0xFFDCEBDF)
    val Amber = Color(0xFF8A5A18)
    val AmberSoft = Color(0xFFF2E3C4)
    val Rose = Color(0xFF94352E)
    val RoseSoft = Color(0xFFF1D7D2)
}

private val GuardColorScheme = lightColorScheme(
    background = GuardColors.Background,
    surface = GuardColors.Surface,
    primary = GuardColors.Forest,
    onPrimary = Color.White,
    onBackground = GuardColors.Ink,
    onSurface = GuardColors.Ink,
    secondary = GuardColors.Amber,
    outline = GuardColors.Line
)

@Composable
fun GuardVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GuardColorScheme,
        content = content
    )
}
