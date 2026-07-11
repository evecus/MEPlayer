package com.mobile.meplayer.ui.settings

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobile.meplayer.controller.AppSettings
import com.mobile.meplayer.controller.SettingsController
import kotlinx.coroutines.flow.StateFlow

/**
 * 设置 ViewModel，桥接全局 [SettingsController] 给 Compose 层使用。
 *
 * 主题相关状态（seedColor / themeMode）由 [SettingsController] 持有并持久化，
 * 这里仅做转发，保证 MainActivity 主题切换能响应式生效。
 *
 * Kotlin 版统一使用 ExoPlayer（固定硬件解码，无 MPV 后端），原 MPV 相关的
 * hardwareDecode / mpvProfile / compatMode / playerBackend 字段已移除。
 */
class SettingsViewModel : ViewModel() {

    private val ctrl: SettingsController = AppSettings.controller

    val seedColor: StateFlow<Int> = ctrl.seedColor
    val themeMode: StateFlow<Int> = ctrl.themeMode

    fun setThemeMode(mode: Int) = ctrl.setThemeMode(mode)
    fun setSeedColor(color: Int) = ctrl.setSeedColor(color)
}

/** 通过 lifecycle-viewmodel-compose 获取 [SettingsViewModel] 实例。 */
@Composable
fun settingsViewModel(): SettingsViewModel = viewModel()
