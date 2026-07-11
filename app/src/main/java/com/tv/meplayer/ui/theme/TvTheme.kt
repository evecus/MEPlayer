package com.tv.meplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.tv.meplayer.data.StorageService

/**
 * 全局主题状态：支持亮/暗模式切换与主题色动态切换。
 *
 * 颜色种子通过 [StorageService] 持久化，下次启动恢复。
 */
object TvThemeState {
    /** 0 = 跟随系统，1 = 亮色，2 = 暗色 */
    var mode by mutableIntStateOf(0)
        private set

    /** 主题色种子的 ARGB 值 */
    var seedArgb by mutableIntStateOf(0xFF2ECC71.toInt())
        private set

    fun init() {
        mode = StorageService.getInt(StorageService.kThemeMode, 0)
        seedArgb = StorageService.getInt(StorageService.kSeedColor, 0xFF2ECC71.toInt())
    }

    fun updateMode(m: Int) {
        mode = m
        StorageService.setInt(StorageService.kThemeMode, m)
    }

    fun updateSeed(argb: Int) {
        seedArgb = argb
        StorageService.setInt(StorageService.kSeedColor, argb)
    }
}

/** 可选的主题色种子预设 */
val TvSeedPresets = listOf(
    0xFF2ECC71.toInt() to "翠绿",
    0xFF3498DB.toInt() to "天蓝",
    0xFF9B59B6.toInt() to "紫罗兰",
    0xFFE74C3C.toInt() to "朱红",
    0xFFF39C12.toInt() to "琥珀",
    0xFF1ABC9C.toInt() to "青松",
    0xFFFF6B9D.toInt() to "蜜桃粉",
    0xFFFAFAFA.toInt() to "雪白",
)

/**
 * TV 主题包装器：根据 [TvThemeState] 构造 Material3 配色，同时同步系统状态栏。
 * isDark 由 mode 决定（0 跟随系统，1/2 强制亮/暗）。
 */
@Composable
fun TvTheme(content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (TvThemeState.mode) {
        1 -> false
        2 -> true
        else -> systemDark
    }
    val seedColor = Color(TvThemeState.seedArgb)

    // 由种子色生成调色板（利用 Material3 亮度调节生成近似配色）
    val colorScheme = if (isDark) darkColorScheme(
        primary = seedColor,
        secondary = seedColor.copy(alpha = 0.8f),
        tertiary = seedColor.copy(alpha = 0.6f),
        background = Color(0xFF0F0F1A),
        surface = Color(0xFF1C1C2E),
        surfaceVariant = Color(0xFF252538),
        onPrimary = Color.Black,
        onBackground = Color.White,
        onSurface = Color.White,
    ) else lightColorScheme(
        primary = seedColor,
        secondary = seedColor.copy(alpha = 0.8f),
        tertiary = seedColor.copy(alpha = 0.6f),
        background = Color(0xFFF5F5F8),
        surface = Color.White,
        surfaceVariant = Color(0xFFEEEEEE),
        onPrimary = Color.White,
        onBackground = Color.Black,
        onSurface = Color.Black,
    )

    val sysUi = rememberSystemUiController()
    SideEffect {
        sysUi.statusBarDarkContentEnabled = !isDark
        sysUi.setSystemBarsColor(Color.Transparent)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
