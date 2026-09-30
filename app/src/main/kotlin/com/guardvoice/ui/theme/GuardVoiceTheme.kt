package com.guardvoice.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object GuardColors {
    val Background = Color(0xFFF4F8FC)
    val Surface = Color(0xFFFBFDFF)
    val SurfaceMuted = Color(0xFFEAF1F8)
    val Ink = Color(0xFF07192E)
    val InkMuted = Color(0xFF607084)
    val Line = Color(0xFFDCE5EF)
    val Forest = Color(0xFF087FA3)
    val ForestSoft = Color(0xFFDDF4F7)
    val Amber = Color(0xFF94661C)
    val AmberSoft = Color(0xFFF8EFD9)
    val Rose = Color(0xFF9B4549)
    val RoseSoft = Color(0xFFF8E5E6)
    val Navy = Color(0xFF052B57)
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

private val GuardTypography = androidx.compose.material3.Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.25).sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp
    )
)

@Composable
fun GuardVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GuardColorScheme,
        typography = GuardTypography,
        content = content
    )
}
