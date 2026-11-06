package com.example.fluffydisco.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val FluffyColors = darkColorScheme(
    primary = AppPrimary,
    onPrimary = AppBackground,
    primaryContainer = Color(0xFF352B50),
    onPrimaryContainer = AppText,

    secondary = AppSecondary,
    background = AppBackground,
    onBackground = AppText,

    surface = AppSurface,
    onSurface = AppText,
    onSurfaceVariant = AppMuted,

    surfaceVariant = AppOutline,
    outline = AppOutline
)

@Composable
fun FluffyDiscoTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = FluffyColors,
        typography = Typography(),
        shapes = Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
        ),
        content = content
    )
}