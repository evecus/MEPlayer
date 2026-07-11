package com.tv.meplayer.controller

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tv.meplayer.data.StorageService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 播放器后端选择（对应 Flutter 的 PlayerBackendChoice）。
 * Kotlin 版统一只用 ExoPlayer，但保留枚举以兼容设置项。
 */
enum class PlayerBackendChoice { AUTO, EXO, MPV }

/**
 * 全局设置控制器（单例），对应 Flutter 的 `BaseSettingsController` +
 * `AppSettingsController`。
 *
 * 通过 [StorageService] 持久化，通过 [StateFlow] 暴露响应式状态。
 */
class SettingsController : ViewModel() {

    // ── 外观 ─────────────────────────────────────────────────────────────
    private val _themeMode = MutableStateFlow(StorageService.getInt(StorageService.kThemeMode, 0))
    val themeMode: StateFlow<Int> = _themeMode.asStateFlow()

    private val _seedColor = MutableStateFlow(StorageService.getInt(StorageService.kSeedColor, 0xFF3498DB.toInt()))
    val seedColor: StateFlow<Int> = _seedColor.asStateFlow()

    // ── 播放 ─────────────────────────────────────────────────────────────
    private val _hardwareDecode = MutableStateFlow(StorageService.getBoolean(StorageService.kHardwareDecode, true))
    val hardwareDecode: StateFlow<Boolean> = _hardwareDecode.asStateFlow()

    private val _mpvProfile = MutableStateFlow(StorageService.getString(StorageService.kMpvProfile, "balanced"))
    val mpvProfile: StateFlow<String> = _mpvProfile.asStateFlow()

    private val _compatMode = MutableStateFlow(StorageService.getBoolean(StorageService.kCompatMode, false))
    val compatMode: StateFlow<Boolean> = _compatMode.asStateFlow()

    private val _playerBackend = MutableStateFlow(parseBackendChoice(StorageService.getString(StorageService.kPlayerBackend, "auto")))
    val playerBackend: StateFlow<PlayerBackendChoice> = _playerBackend.asStateFlow()

    // ── 媒体扫描路径 ─────────────────────────────────────────────────────
    private val _videoScanPaths = MutableStateFlow(StorageService.getStringList(StorageService.kVideoScanPaths))
    val videoScanPaths: StateFlow<List<String>> = _videoScanPaths.asStateFlow()

    private val _musicScanPaths = MutableStateFlow(StorageService.getStringList(StorageService.kMusicScanPaths))
    val musicScanPaths: StateFlow<List<String>> = _musicScanPaths.asStateFlow()

    // ── IPTV 源 ─────────────────────────────────────────────────────────
    private val _iptvSources = MutableStateFlow(StorageService.getMapList(StorageService.kIptvSources))
    val iptvSources: StateFlow<List<Map<String, String>>> = _iptvSources.asStateFlow()

    // ── 最近文件 ────────────────────────────────────────────────────────
    private val _recentFiles = MutableStateFlow(StorageService.getStringList(StorageService.kRecentFiles))
    val recentFiles: StateFlow<List<String>> = _recentFiles.asStateFlow()

    // ── 外观设置 ────────────────────────────────────────────────────────

    fun setThemeMode(mode: Int) {
        _themeMode.value = mode
        StorageService.setInt(StorageService.kThemeMode, mode)
    }

    fun setSeedColor(color: Int) {
        _seedColor.value = color
        StorageService.setInt(StorageService.kSeedColor, color)
    }

    // ── 播放设置 ────────────────────────────────────────────────────────

    fun setHardwareDecode(v: Boolean) {
        _hardwareDecode.value = v
        StorageService.setBoolean(StorageService.kHardwareDecode, v)
    }

    fun setMpvProfile(v: String) {
        _mpvProfile.value = v
        StorageService.setString(StorageService.kMpvProfile, v)
    }

    fun setCompatMode(v: Boolean) {
        _compatMode.value = v
        StorageService.setBoolean(StorageService.kCompatMode, v)
    }

    fun setPlayerBackend(v: PlayerBackendChoice) {
        _playerBackend.value = v
        StorageService.setString(StorageService.kPlayerBackend, v.name.lowercase())
    }

    /**
     * Kotlin 版统一用 ExoPlayer，此方法始终返回 true。
     * 保留接口以兼容原 Flutter 的 shouldUseExo 逻辑。
     */
    fun shouldUseExo(isIptv: Boolean): Boolean = true

    // ── 扫描路径 ────────────────────────────────────────────────────────

    fun addVideoScanPath(path: String) {
        val list = _videoScanPaths.value.toMutableList()
        if (!list.contains(path)) {
            list.add(path)
            _videoScanPaths.value = list
            StorageService.setStringList(StorageService.kVideoScanPaths, list)
        }
    }

    fun removeVideoScanPath(path: String) {
        val list = _videoScanPaths.value.toMutableList()
        list.remove(path)
        _videoScanPaths.value = list
        StorageService.setStringList(StorageService.kVideoScanPaths, list)
    }

    fun addMusicScanPath(path: String) {
        val list = _musicScanPaths.value.toMutableList()
        if (!list.contains(path)) {
            list.add(path)
            _musicScanPaths.value = list
            StorageService.setStringList(StorageService.kMusicScanPaths, list)
        }
    }

    fun removeMusicScanPath(path: String) {
        val list = _musicScanPaths.value.toMutableList()
        list.remove(path)
        _musicScanPaths.value = list
        StorageService.setStringList(StorageService.kMusicScanPaths, list)
    }

    // ── IPTV 源 ─────────────────────────────────────────────────────────

    fun addIptvSource(name: String, url: String, type: String = "network") {
        val list = _iptvSources.value.toMutableList()
        list.add(mapOf("name" to name, "url" to url, "type" to type))
        _iptvSources.value = list
        StorageService.setMapList(StorageService.kIptvSources, list)
    }

    fun addIptvSource(source: Map<String, String>) {
        val list = _iptvSources.value.toMutableList()
        list.add(source)
        _iptvSources.value = list
        StorageService.setMapList(StorageService.kIptvSources, list)
    }

    fun updateIptvSource(index: Int, source: Map<String, String>) {
        val list = _iptvSources.value.toMutableList()
        if (index in list.indices) {
            list[index] = source
            _iptvSources.value = list
            StorageService.setMapList(StorageService.kIptvSources, list)
        }
    }

    fun removeIptvSource(index: Int) {
        val list = _iptvSources.value.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            _iptvSources.value = list
            StorageService.setMapList(StorageService.kIptvSources, list)
        }
    }

    // ── 最近文件 ────────────────────────────────────────────────────────

    fun addRecentFile(path: String) {
        val list = _recentFiles.value.toMutableList()
        list.remove(path)
        list.add(0, path)
        while (list.size > 50) list.removeAt(list.size - 1)
        _recentFiles.value = list
        StorageService.setStringList(StorageService.kRecentFiles, list)
    }

    private fun parseBackendChoice(s: String): PlayerBackendChoice = when (s) {
        "exo" -> PlayerBackendChoice.EXO
        "mpv" -> PlayerBackendChoice.MPV
        else -> PlayerBackendChoice.AUTO
    }
}

/**
 * 全局单例的 SettingsController，在 App 生命周期内复用。
 */
object AppSettings {
    val controller = SettingsController()
}
