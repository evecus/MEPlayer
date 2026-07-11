package com.tv.meplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 存储服务，对应 Flutter 的 `storage_service.dart`。
 *
 * 基于 SharedPreferences + JSON 序列化，提供跨 App 全局持久化的 key-value 存储。
 */
object StorageService {

    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context, boxName: String = "flutter_player_android") {
        prefs = context.getSharedPreferences(boxName, Context.MODE_PRIVATE)
    }

    // ── 基础读写 ─────────────────────────────────────────────────────────

    fun getString(key: String, default: String = ""): String =
        prefs.getString(key, default) ?: default

    fun setString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getInt(key: String, default: Int = 0): Int =
        prefs.getInt(key, default)

    fun setInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean =
        prefs.getBoolean(key, default)

    fun setBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getLong(key: String, default: Long = 0L): Long =
        prefs.getLong(key, default)

    fun setLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    // ── List<String> ────────────────────────────────────────────────────

    fun getStringList(key: String, default: List<String> = emptyList()): List<String> {
        val raw = prefs.getString(key, null) ?: return default
        return runCatching {
            JSONArray(raw).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
        }.getOrDefault(default)
    }

    fun setStringList(key: String, value: List<String>) {
        val arr = JSONArray()
        value.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    // ── List<Map<String,String>> ────────────────────────────────────────

    fun getMapList(key: String, default: List<Map<String, String>> = emptyList()): List<Map<String, String>> {
        val raw = prefs.getString(key, null) ?: return default
        return runCatching {
            JSONArray(raw).let { arr ->
                (0 until arr.length()).map { idx ->
                    val obj = arr.getJSONObject(idx)
                    val map = mutableMapOf<String, String>()
                    obj.keys().forEach { k -> map[k] = obj.getString(k) }
                    map
                }
            }
        }.getOrDefault(default)
    }

    fun setMapList(key: String, value: List<Map<String, String>>) {
        val arr = JSONArray()
        value.forEach { m ->
            val obj = JSONObject()
            m.forEach { (k, v) -> obj.put(k, v) }
            arr.put(obj)
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    // ── JSON 对象（用于扫描结果缓存） ────────────────────────────────────

    fun getJsonArray(key: String, default: String = "[]"): String =
        prefs.getString(key, default) ?: default

    fun setJsonArray(key: String, json: String) {
        prefs.edit().putString(key, json).apply()
    }

    fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    // ── Key 常量（与 Flutter 端完全一致） ───────────────────────────────

    const val kHardwareDecode = "hw_decode"
    const val kMpvProfile = "mpv_profile"
    const val kCompatMode = "compat_mode"
    const val kIptvSources = "iptv_sources"
    const val kVideoScanPaths = "video_scan_paths"
    const val kMusicScanPaths = "music_scan_paths"
    const val kRecentFiles = "recent_files"
    const val kPlayerVolume = "player_volume"
    const val kPlayerBackend = "player_backend"

    const val kThemeMode = "theme_mode"
    const val kSeedColor = "seed_color"

    const val kVideoSortMode = "video_sort_mode"
    const val kVideoThumbCache = "video_thumb_cache"

    const val kMusicCategory = "music_category"
    const val kMusicSongSort = "music_song_sort"
    const val kMusicGroupSort = "music_group_sort"
    const val kMusicLibraryCache = "music_library_cache"

    const val kVideoCategory = "video_category"
    const val kVideoSortAndroid = "video_sort_android"
    const val kVideoFolderSort = "video_folder_sort"
    const val kVideoLibraryCache = "video_library_cache_android"
    const val kMusicLibraryCacheAndroid = "music_library_cache_android"
}
