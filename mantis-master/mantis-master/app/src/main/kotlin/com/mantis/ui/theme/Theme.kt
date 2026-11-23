package com.mantis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MantisGreen        = Color(0xFF3DBB5E)
private val MantisBackground   = Color(0xFFFFFFFF)
private val MantisSurface      = Color(0xFFFFFFFF)
private val MantisOnBackground = Color(0xFF1A1A1A)
private val MantisSubtext      = Color(0xFF8A8A8A)

private val LightColorScheme = lightColorScheme(
    primary          = MantisGreen,
    onPrimary        = Color.White,
    background       = MantisBackground,
    onBackground     = MantisOnBackground,
    surface          = MantisSurface,
    onSurface        = MantisOnBackground,
    onSurfaceVariant = MantisSubtext,
    error            = Color(0xFFE53935),
    onError          = Color.White,
)

@Composable
fun MantisTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography  = MantisTypography,
        content     = content,
    )
}
