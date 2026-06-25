package com.dangeedums.acmeter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F6E2A),
    onPrimary = Color.White,
    secondary = Color(0xFFC97A1A),
    background = Color(0xFFFAF9F6),
    surface = Color.White,
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8BD08F),
    secondary = Color(0xFFE8A45A),
    background = Color(0xFF101412),
    surface = Color(0xFF181C1A),
)

@Composable
fun AcEnergyMeterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
