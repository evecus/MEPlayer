package com.tv.meplayer.ui.video

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tv.meplayer.data.LocalScanner
import com.tv.meplayer.data.PermissionUtil
import com.tv.meplayer.data.ScannedVideo
import com.tv.meplayer.data.StorageService
import com.tv.meplayer.data.VideoFolderEntry
import com.tv.meplayer.data.parseScannedVideoList
import com.tv.meplayer.data.toJsonArrayString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 视频库分类常量，对应 Flutter 的 VideoCategory。
 */
object VideoCategory {
    const val VIDEO = 0
    const val FOLDER = 1
}

/**
 * 视频排序常量，对应 Flutter 的 VideoSort。
 */
object VideoSort {
    const val NAME_ASC = 0
    const val NAME_DESC = 1
    const val TIME_ASC = 2
    const val TIME_DESC = 3
}

/**
 * 视频文件夹排序常量，对应 Flutter 的 VideoFolderSort。
 */
object VideoFolderSort {
    const val NAME_ASC = 0
    const val NAME_DESC = 1
}

/**
 * 视频库 ViewModel，对应 Flutter 的 `VideoLibraryController`。
 *
 * 持有扫描结果、分类/排序状态，通过 [StorageService] 持久化缓存与偏好，
 * 通过 [MutableStateFlow] 暴露响应式状态供 Compose 层订阅。
 */
class VideoLibraryViewModel(app: Application) : AndroidViewModel(app) {

    val allVideos = MutableStateFlow<List<ScannedVideo>>(emptyList())
    val folderEntries = MutableStateFlow<List<VideoFolderEntry>>(emptyList())
    val isScanning = MutableStateFlow(false)
    val hasPermission = MutableStateFlow(false)
    val permissionRequested = MutableStateFlow(false)
    val currentCategory = MutableStateFlow(0)   // 0=视频 1=文件夹
    val currentSortVideo = MutableStateFlow(0)  // 0=nameAsc 1=nameDesc 2=timeAsc 3=timeDesc
    val currentSortFolder = MutableStateFlow(0) // 0=nameAsc 1=nameDesc
    val searchKeyword = MutableStateFlow("")

    /**
     * 检查权限、加载缓存或扫描。由 Screen 在进入时调用一次。
     */
    fun init() {
        val ctx = getApplication<Application>()
        val granted = PermissionUtil.hasAnyStorageAccess(ctx)
        hasPermission.value = granted
        if (granted) {
            permissionRequested.value = true
            loadPreferences()
            loadCacheOrScan()
        }
        // 未授权时保持 permissionRequested=false，交由 Screen 发起运行时权限请求
    }

    /**
     * 运行时权限请求结果回调。
     */
    fun onPermissionResult(granted: Boolean) {
        hasPermission.value = granted
        permissionRequested.value = true
        if (granted) {
            loadPreferences()
            loadCacheOrScan()
        }
    }

    /**
     * 重新扫描本地视频。[onFound] 每发现一个文件回调一次（后台线程）。
     */
    fun rescan(onFound: (String) -> Unit) {
        val ctx = getApplication<Application>()
        if (!PermissionUtil.hasAnyStorageAccess(ctx)) return
        viewModelScope.launch {
            isScanning.value = true
            val list = withContext(Dispatchers.IO) {
                LocalScanner.scanVideos(ctx) { name -> onFound(name) }
            }
            allVideos.value = list
            folderEntries.value = groupFolders(list)
            StorageService.setJsonArray(StorageService.kVideoLibraryCache, list.toJsonArrayString())
            isScanning.value = false
        }
    }

    /** 按当前搜索词与视频排序返回视频列表。 */
    fun sortedVideos(): List<ScannedVideo> {
        val kw = searchKeyword.value.trim().lowercase(Locale.getDefault())
        val filtered = if (kw.isEmpty()) {
            allVideos.value
        } else {
            allVideos.value.filter { it.name.lowercase(Locale.getDefault()).contains(kw) }
        }
        return when (currentSortVideo.value) {
            VideoSort.NAME_ASC -> filtered.sortedBy { it.name.lowercase(Locale.getDefault()) }
            VideoSort.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase(Locale.getDefault()) }
            VideoSort.TIME_ASC -> filtered.sortedBy { it.modified }
            VideoSort.TIME_DESC -> filtered.sortedByDescending { it.modified }
            else -> filtered
        }
    }

    /** 按当前搜索词与文件夹排序返回文件夹列表。 */
    fun sortedFolders(): List<VideoFolderEntry> {
        val kw = searchKeyword.value.trim().lowercase(Locale.getDefault())
        val filtered = if (kw.isEmpty()) {
            folderEntries.value
        } else {
            folderEntries.value.filter { it.name.lowercase(Locale.getDefault()).contains(kw) }
        }
        return when (currentSortFolder.value) {
            VideoFolderSort.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase(Locale.getDefault()) }
            else -> filtered.sortedBy { it.name.lowercase(Locale.getDefault()) }
        }
    }

    fun setCategory(c: Int) {
        currentCategory.value = c
        StorageService.setInt(StorageService.kVideoCategory, c)
    }

    fun setSortVideo(s: Int) {
        currentSortVideo.value = s
        StorageService.setInt(StorageService.kVideoSortAndroid, s)
    }

    fun setSortFolder(s: Int) {
        currentSortFolder.value = s
        StorageService.setInt(StorageService.kVideoFolderSort, s)
    }

    // ── 内部 ───────────────────────────────────────────────

    private fun loadPreferences() {
        currentCategory.value = StorageService.getInt(StorageService.kVideoCategory, VideoCategory.VIDEO)
        currentSortVideo.value = StorageService.getInt(StorageService.kVideoSortAndroid, VideoSort.NAME_ASC)
        currentSortFolder.value = StorageService.getInt(StorageService.kVideoFolderSort, VideoFolderSort.NAME_ASC)
    }

    private fun loadCacheOrScan() {
        val cached = StorageService.getJsonArray(StorageService.kVideoLibraryCache, "[]")
        val list = parseScannedVideoList(cached)
        allVideos.value = list
        folderEntries.value = groupFolders(list)
        if (list.isEmpty()) {
            rescan {}
        }
    }

    private fun groupFolders(videos: List<ScannedVideo>): List<VideoFolderEntry> {
        return videos
            .groupBy { it.folder }
            .map { (path, vids) -> VideoFolderEntry(path, File(path).name.ifEmpty { path }, vids) }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
    }
}

/** 格式化文件大小为人类可读字符串。 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024.0 && i < units.size - 1) {
        value /= 1024.0
        i++
    }
    return String.format(Locale.getDefault(), "%.1f %s", value, units[i])
}
