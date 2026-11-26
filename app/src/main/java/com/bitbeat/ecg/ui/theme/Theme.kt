package com.bitbeat.ecg.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** BitBeat ECG palette "B2 · Clinical blue". */
object BitBeat {
    val Background = Color(0xFFF4F6FB)
    val Surface = Color(0xFFFFFFFF)
    val Cobalt = Color(0xFF2445C8)
    val SoftBlue = Color(0xFFDCE5FB)
    val LightBlue = Color(0xFF6F8CE6)
    val Alert = Color(0xFFB8460B)
    val SoftAlert = Color(0xFFFBE9DF)
    val Ink = Color(0xFF0E1B3D)
    val SecondaryText = Color(0xFF55607A)
    val Border = Color(0xFFC9D3E6)
}

private val BitBeatColors = lightColorScheme(
    primary = BitBeat.Cobalt,
    onPrimary = Color.White,
    primaryContainer = BitBeat.SoftBlue,
    onPrimaryContainer = BitBeat.Ink,
    secondary = BitBeat.LightBlue,
    onSecondary = Color.White,
    background = BitBeat.Background,
    onBackground = BitBeat.Ink,
    surface = BitBeat.Surface,
    onSurface = BitBeat.Ink,
    surfaceVariant = BitBeat.SoftBlue,
    onSurfaceVariant = BitBeat.SecondaryText,
    outline = BitBeat.Border,
    error = BitBeat.Alert,
    onError = Color.White
)

/**
 * Always light mode and no Android 12+ "dynamic colors",
 * so the app looks the same on every phone.
 */
@Composable
fun BitBeatECGTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = BitBeatColors,
        typography = Typography,
        content = content
    )
}