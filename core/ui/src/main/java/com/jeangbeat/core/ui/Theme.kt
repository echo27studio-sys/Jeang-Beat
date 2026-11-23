package com.jeangbeat.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Studio palette: dark background so the colourful pads and notes stand out. */
object JbColors {
    val Background = Color(0xFF0F1115)
    val Surface = Color(0xFF181B22)
    val SurfaceRaised = Color(0xFF222632)
    val Outline = Color(0xFF2E3342)
    val GridLine = Color(0xFF262A36)
    val GridBeat = Color(0xFF353B4C)
    val StepOff = Color(0xFF2A2F3C)
    val StepOffAccent = Color(0xFF333A4A)
    val Text = Color(0xFFE8EAF0)
    val TextDim = Color(0xFF9AA1B2)
    /** Gold, inspired by Khmer temple ornament. */
    val Accent = Color(0xFFF2B33D)
    val Record = Color(0xFFE5484D)
    val Playhead = Color(0xFFFFFFFF)

    /** Channel colours; Channel.color indexes into this list. */
    val Channels = listOf(
        Color(0xFFF2B33D), // gold
        Color(0xFFE5484D), // red
        Color(0xFFFF8A3D), // orange
        Color(0xFF3DD6C6), // teal
        Color(0xFF5B8CFF), // blue
        Color(0xFFB57BFF), // purple
        Color(0xFF4CD964), // green
        Color(0xFFFF6FB1), // pink
    )

    fun channel(index: Int): Color = Channels[Math.floorMod(index, Channels.size)]
}

private val scheme = darkColorScheme(
    primary = JbColors.Accent,
    onPrimary = Color(0xFF1A1400),
    secondary = Color(0xFF3DD6C6),
    background = JbColors.Background,
    onBackground = JbColors.Text,
    surface = JbColors.Surface,
    onSurface = JbColors.Text,
    surfaceVariant = JbColors.SurfaceRaised,
    onSurfaceVariant = JbColors.TextDim,
    surfaceContainer = JbColors.Surface,
    surfaceContainerHigh = JbColors.SurfaceRaised,
    surfaceContainerHighest = JbColors.SurfaceRaised,
    outline = JbColors.Outline,
    error = JbColors.Record,
)

@Composable
fun JeangBeatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
