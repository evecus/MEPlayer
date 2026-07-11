package com.mobile.meplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.mobile.meplayer.ui.navigation.AppNavigation
import com.mobile.meplayer.ui.settings.settingsViewModel
import com.mobile.meplayer.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsViewModel = settingsViewModel()
            val seedColor by settingsViewModel.seedColor.collectAsState()
            val themeMode by settingsViewModel.themeMode.collectAsState()
            val darkTheme = when (themeMode) {
                0 -> isSystemInDarkTheme()
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            AppTheme(seedColor = Color(seedColor), darkTheme = darkTheme) {
                AppNavigation()
            }
        }
    }
}
