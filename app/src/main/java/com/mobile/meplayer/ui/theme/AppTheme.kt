package com.mobile.meplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AppColors {
    val defaultSeed = Color(0xFF3498DB)
}

@Composable
fun AppTheme(
    seedColor: Color = AppColors.defaultSeed,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) darkColorScheme(primary = seedColor) else lightColorScheme(primary = seedColor)
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        content = content
    )
}
