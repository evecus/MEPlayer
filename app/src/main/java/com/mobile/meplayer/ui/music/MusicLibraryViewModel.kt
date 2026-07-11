package com.mobile.meplayer.ui.music

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobile.meplayer.data.ArtistSplitter
import com.mobile.meplayer.data.AudioMetadataReader
import com.mobile.meplayer.data.LocalScanner
import com.mobile.meplayer.data.MusicGroupEntry
import com.mobile.meplayer.data.PermissionUtil
import com.mobile.meplayer.data.SongEntry
import com.mobile.meplayer.data.StorageService
import com.mobile.meplayer.data.parseSongEntryList
import com.mobile.meplayer.data.toJsonArrayString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 音乐库分类常量。 */
object MusicCategory {
    const val SONG = 0
    const val ALBUM = 1
    const val ARTIST = 2
    const val FOLDER = 3
}

/** 歌曲排序常量。 */
object MusicSongSort {
    const val TITLE_ASC = 0
    const val TITLE_DESC = 1
    const val ARTIST_ASC = 2
    const val ARTIST_DESC = 3
    const val TIME_ASC = 4
    const val TIME_DESC = 5
}

/** 分组排序常量。 */
object MusicGroupSort {
    const val NAME_ASC = 10
    const val NAME_DESC = 11
    const val TIME_ASC = 12
    const val TIME_DESC = 13
}

/**
 * 音乐库 ViewModel，对应 Flutter 的 `MusicLibraryController`。
 *
 * 负责：权限检查、本地音频扫描、元数据异步加载与缓存、分类分组、排序、搜索。
 */
class MusicLibraryViewModel(app: Application) : AndroidViewModel(app) {

    val allSongs = MutableStateFlow<List<SongEntry>>(emptyList())
    val isScanning = MutableStateFlow(false)
    val hasPermission = MutableStateFlow(false)
    val permissionRequested = MutableStateFlow(false)
    val currentCategory = MutableStateFlow(0)
    val currentSortSong = MutableStateFlow(0)
    val currentSortGroup = MutableStateFlow(10)
    val searchKeyword = MutableStateFlow("")
    val metaLoadCount = MutableStateFlow(0)

    /** 封面版本号：每次封面更新时自增，UI 据此触发刷新。 */
    val coverVersion = MutableStateFlow(0)

    private val coverRequestedPaths = mutableSetOf<String>()
    private var coverLoadGeneration = 0

    private var initialized = false

    /** 初始化：恢复持久化的分类/排序，恢复缓存，按需触发封面后台加载。 */
    fun init() {
        if (initialized) return
        initialized = true
        currentCategory.value = StorageService.getInt(StorageService.kMusicCategory, MusicCategory.SONG)
        currentSortSong.value = StorageService.getInt(StorageService.kMusicSongSort, MusicSongSort.TITLE_ASC)
        currentSortGroup.value = StorageService.getInt(StorageService.kMusicGroupSort, MusicGroupSort.NAME_ASC)
        hasPermission.value = PermissionUtil.hasMediaPermissions(getApplication())
        if (hasPermission.value) {
            permissionRequested.value = true
        }

        val cacheJson = StorageService.getJsonArray(StorageService.kMusicLibraryCacheAndroid, "[]")
        val cached = parseSongEntryList(cacheJson)
        if (cached.isNotEmpty()) {
            allSongs.value = cached
            metaLoadCount.value = cached.size
            // 从缓存恢复时：coverBytes 不落盘，由 UI 分页按需触发封面加载
        } else if (hasPermission.value) {
            // 无缓存且有权限：自动扫描
            rescan {}
        }
    }

    /** 运行时权限请求结果回调。 */
    fun onPermissionResult(granted: Boolean) {
        hasPermission.value = granted
        permissionRequested.value = true
        if (granted && allSongs.value.isEmpty()) {
            rescan {}
        }
    }

    /** 重新扫描本地音频并异步加载元数据。[onFound] 每发现一个文件回调一次。 */
    fun rescan(onFound: (String) -> Unit) {
        if (isScanning.value) return
        isScanning.value = true
        metaLoadCount.value = 0
        resetCovers()
        viewModelScope.launch {
            val scanned = withContext(Dispatchers.IO) {
                LocalScanner.scanAudios(getApplication()) { onFound(it) }
            }
            val songs = scanned.map {
                SongEntry(it.path, it.name, it.folder, it.size, it.modified)
            }
            // 后台异步加载元数据，每处理 20 首落盘缓存
            withContext(Dispatchers.IO) {
                var processed = 0
                songs.forEach { song ->
                    val meta = AudioMetadataReader.readFile(song.path)
                    song.title = meta.title?.takeIf { it.isNotEmpty() }
                        ?: song.name.substringBeforeLast('.', song.name)
                    song.artist = meta.artist ?: ""
                    song.album = meta.album ?: ""
                    song.lyrics = meta.lyrics ?: ""
                    // 封面不随扫描全量加载，由 UI 分页按需加载以节省内存
                    song.metadataLoaded = true
                    processed++
                    if (processed % 20 == 0) {
                        StorageService.setJsonArray(
                            StorageService.kMusicLibraryCacheAndroid,
                            songs.toJsonArrayString()
                        )
                        val snapshot = processed
                        withContext(Dispatchers.Main) { metaLoadCount.value = snapshot }
                    }
                }
                StorageService.setJsonArray(
                    StorageService.kMusicLibraryCacheAndroid,
                    songs.toJsonArrayString()
                )
                withContext(Dispatchers.Main) { metaLoadCount.value = songs.size }
            }
            allSongs.value = songs
            isScanning.value = false
        }
    }

    /** 清空所有内存封面并重置加载状态（分类/排序变化或重新扫描时调用）。 */
    fun resetCovers() {
        coverLoadGeneration++
        coverRequestedPaths.clear()
        allSongs.value.forEach { it.coverBytes = null }
        coverVersion.value++
    }

    /**
     * 请求加载指定歌曲的封面（自动去重，每 20 首刷新一次 UI）。
     * 用于分页加载：UI 传入当前需要封面的一批歌曲（如前 200 首）。
     */
    fun requestCovers(songs: List<SongEntry>) {
        val toLoad = songs.filter { it.path !in coverRequestedPaths }
        if (toLoad.isEmpty()) return
        toLoad.forEach { coverRequestedPaths.add(it.path) }
        val gen = coverLoadGeneration
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                var processed = 0
                for (song in toLoad) {
                    if (coverLoadGeneration != gen) return@withContext
                    val meta = AudioMetadataReader.readFile(song.path)
                    song.coverBytes = meta.coverBytes
                    processed++
                    if (processed % 20 == 0) {
                        withContext(Dispatchers.Main) { coverVersion.value++ }
                    }
                }
                withContext(Dispatchers.Main) { coverVersion.value++ }
            }
        }
    }

    /** 按当前排序与搜索词过滤后的歌曲列表。 */
    fun sortedSongs(): List<SongEntry> {
        val kw = searchKeyword.value.trim().lowercase()
        val filtered = if (kw.isEmpty()) allSongs.value else allSongs.value.filter {
            it.title.lowercase().contains(kw) ||
                it.artist.lowercase().contains(kw) ||
                it.album.lowercase().contains(kw)
        }
        return when (currentSortSong.value) {
            MusicSongSort.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
            MusicSongSort.TITLE_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            MusicSongSort.ARTIST_ASC -> filtered.sortedWith(
                compareBy({ it.artist.lowercase() }, { it.title.lowercase() })
            )
            MusicSongSort.ARTIST_DESC -> filtered.sortedWith(
                compareByDescending<SongEntry> { it.artist.lowercase() }.thenByDescending { it.title.lowercase() }
            )
            MusicSongSort.TIME_ASC -> filtered.sortedBy { it.modified }
            MusicSongSort.TIME_DESC -> filtered.sortedByDescending { it.modified }
            else -> filtered
        }
    }

    /** 按当前分类构建分组列表（仅 ALBUM/ARTIST/FOLDER 有分组）。 */
    fun buildGroups(): List<MusicGroupEntry> {
        val songs = allSongs.value
        return when (currentCategory.value) {
            MusicCategory.ALBUM -> songs
                .groupBy { it.album.ifEmpty { "未知专辑" } }
                .map { (k, v) -> MusicGroupEntry(k, k, v) }
            MusicCategory.ARTIST -> {
                val map = linkedMapOf<String, MutableList<SongEntry>>()
                songs.forEach { s ->
                    ArtistSplitter.split(s.artist).forEach { a ->
                        map.getOrPut(a) { mutableListOf() }.add(s)
                    }
                }
                map.map { (k, v) -> MusicGroupEntry(k, k, v) }
            }
            MusicCategory.FOLDER -> songs
                .groupBy { it.folder }
                .map { (k, v) ->
                    val name = k.substringAfterLast('/').ifEmpty { k }
                    MusicGroupEntry(k, name, v)
                }
            else -> emptyList()
        }
    }

    /** 按当前分组排序与搜索词过滤后的分组列表。 */
    fun sortedGroups(): List<MusicGroupEntry> {
        val kw = searchKeyword.value.trim().lowercase()
        val groups = buildGroups()
        val filtered = if (kw.isEmpty()) groups else groups.filter {
            it.displayName.lowercase().contains(kw)
        }
        return when (currentSortGroup.value) {
            MusicGroupSort.NAME_ASC -> filtered.sortedBy { it.displayName.lowercase() }
            MusicGroupSort.NAME_DESC -> filtered.sortedByDescending { it.displayName.lowercase() }
            MusicGroupSort.TIME_ASC -> filtered.sortedBy { it.songs.maxOf { s -> s.modified } }
            MusicGroupSort.TIME_DESC -> filtered.sortedByDescending { it.songs.maxOf { s -> s.modified } }
            else -> filtered
        }
    }

    fun setCategory(c: Int) {
        currentCategory.value = c
        StorageService.setInt(StorageService.kMusicCategory, c)
        resetCovers()
    }

    fun setSortSong(s: Int) {
        currentSortSong.value = s
        StorageService.setInt(StorageService.kMusicSongSort, s)
        resetCovers()
    }

    fun setSortGroup(s: Int) {
        currentSortGroup.value = s
        StorageService.setInt(StorageService.kMusicGroupSort, s)
    }

    fun setSearchKeyword(kw: String) {
        searchKeyword.value = kw
    }
}
