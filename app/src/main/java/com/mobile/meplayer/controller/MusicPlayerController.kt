package com.mobile.meplayer.controller

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.ui.graphics.vector.ImageVector
import com.mobile.meplayer.data.AudioMetadataReader
import com.mobile.meplayer.player.ExoPlayerBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 音乐播放模式，对应 Flutter 的 `PlayMode`。
 */
enum class PlayMode { LIST, SHUFFLE, REPEAT_ONE }

/**
 * 全局常驻的音乐播放控制器（object 单例），对应 Flutter 的 `MusicPlayerController`。
 *
 * - 基于 [ExoPlayerBackend]（Media3）实现真实播放。
 * - 通过 [StateFlow] 暴露播放状态给 Compose 层。
 * - 元数据（标题/歌手/专辑/歌词/封面）在切歌时异步回填。
 *
 * 由于是 object 单例、需要 Context 构造 ExoPlayer，需在 [App.onCreate] 中调用
 * [init] 完成初始化（与 `StorageService.init` 同样的模式）。
 */
object MusicPlayerController {

    private lateinit var appContext: Context
    private var backend: ExoPlayerBackend? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _playlist = MutableStateFlow<List<Map<String, String>>>(emptyList())

    /** 当前播放列表（非响应式快照，UI 用 [currentIdx] 等流驱动刷新）。 */
    val playlist: List<Map<String, String>> get() = _playlist.value

    private val _currentIdx = MutableStateFlow(0)
    val currentIdx: StateFlow<Int> = _currentIdx.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _playMode = MutableStateFlow(PlayMode.LIST)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    private val _showQueue = MutableStateFlow(false)
    val showQueue: StateFlow<Boolean> = _showQueue.asStateFlow()

    private val _showLyrics = MutableStateFlow(false)
    val showLyrics: StateFlow<Boolean> = _showLyrics.asStateFlow()

    private val _coverBytes = MutableStateFlow<ByteArray?>(null)
    val coverBytes: StateFlow<ByteArray?> = _coverBytes.asStateFlow()

    private val _lyrics = MutableStateFlow("")
    val lyrics: StateFlow<String> = _lyrics.asStateFlow()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _artist = MutableStateFlow("")
    val artist: StateFlow<String> = _artist.asStateFlow()

    private val _album = MutableStateFlow("")
    val album: StateFlow<String> = _album.asStateFlow()

    private val _hasContent = MutableStateFlow(false)
    val hasContent: StateFlow<Boolean> = _hasContent.asStateFlow()

    /** 在 [App.onCreate] 中调用，创建底层 ExoPlayer 并桥接状态流。 */
    fun init(context: Context) {
        if (backend != null) return
        appContext = context.applicationContext
        val b = ExoPlayerBackend(appContext)
        backend = b
        scope.launch { b.playing.collect { _isPlaying.value = it } }
        scope.launch { b.buffering.collect { _isBuffering.value = it } }
        scope.launch { b.position.collect { _position.value = it } }
        scope.launch { b.duration.collect { _duration.value = it } }
        scope.launch {
            b.completed.collect { c ->
                if (c) onCompleted()
            }
        }
    }

    // ── 播放控制 ─────────────────────────────────────────────

    /** 载入播放列表并从 [index] 开始播放。 */
    fun playPlaylist(playlist: List<Map<String, String>>, index: Int) {
        if (playlist.isEmpty()) return
        _playlist.value = playlist
        _hasContent.value = true
        playAt(index.coerceIn(0, playlist.lastIndex))
        PlaybackBarController.markMusicActive()
    }

    /** 播放指定索引的歌曲。 */
    fun playAt(i: Int) {
        val list = _playlist.value
        if (i !in list.indices) return
        _currentIdx.value = i
        val item = list[i]
        _title.value = item["title"]?.takeIf { it.isNotEmpty() }
            ?: item["name"]?.substringBeforeLast('.', item["name"] ?: "") ?: ""
        _artist.value = item["artist"] ?: ""
        _album.value = item["album"] ?: ""
        _lyrics.value = item["lyrics"] ?: ""
        _coverBytes.value = null
        val path = item["path"] ?: return
        scope.launch { backend?.open(path) }
        loadMetadata(item, path)
    }

    private fun loadMetadata(item: Map<String, String>, path: String) {
        ioScope.launch {
            val meta = AudioMetadataReader.readFile(path)
            if (_lyrics.value.isEmpty() && !meta.lyrics.isNullOrEmpty()) {
                _lyrics.value = meta.lyrics
            }
            if (_title.value.isEmpty() && !meta.title.isNullOrEmpty()) {
                _title.value = meta.title
            }
            if (_artist.value.isEmpty() && !meta.artist.isNullOrEmpty()) {
                _artist.value = meta.artist
            }
            if (_album.value.isEmpty() && !meta.album.isNullOrEmpty()) {
                _album.value = meta.album
            }
            _coverBytes.value = meta.coverBytes
        }
    }

    fun togglePlay() {
        scope.launch { backend?.playOrPause() }
    }

    /**
     * 停止音乐播放（暂停），但不销毁播放器后端本身 —— 因为本 controller 是
     * App 级常驻单例。用于播放视频/IPTV 前实现互斥：三种媒体同一时刻只能
     * 有一个在播放。调用后迷你播放栏不再展示音乐信息，直到下次播放音乐。
     */
    fun stopForOtherMedia() {
        val b = backend ?: return
        if (b.isPlaying) {
            scope.launch { b.playOrPause() }
        }
        _hasContent.value = false
    }

    fun next() {
        val list = _playlist.value
        if (list.isEmpty()) return
        val idx = when (_playMode.value) {
            PlayMode.SHUFFLE -> {
                if (list.size <= 1) 0
                else (0 until list.size).filter { it != _currentIdx.value }.random()
            }
            else -> (_currentIdx.value + 1) % list.size
        }
        playAt(idx)
    }

    fun prev() {
        val list = _playlist.value
        if (list.isEmpty()) return
        // 播放超过 3 秒则回到本曲开头
        if (_position.value > 3000L) {
            seekTo(0L)
            return
        }
        val idx = if (_currentIdx.value - 1 < 0) list.lastIndex else _currentIdx.value - 1
        playAt(idx)
    }

    fun seekTo(ms: Long) {
        scope.launch { backend?.seek(ms.coerceAtLeast(0L)) }
    }

    fun cyclePlayMode() {
        _playMode.value = when (_playMode.value) {
            PlayMode.LIST -> PlayMode.SHUFFLE
            PlayMode.SHUFFLE -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.LIST
        }
    }

    fun playModeLabel(): String = when (_playMode.value) {
        PlayMode.LIST -> "顺序播放"
        PlayMode.SHUFFLE -> "随机播放"
        PlayMode.REPEAT_ONE -> "单曲循环"
    }

    fun playModeIcon(): ImageVector = when (_playMode.value) {
        PlayMode.LIST -> Icons.Default.Repeat
        PlayMode.SHUFFLE -> Icons.Default.Shuffle
        PlayMode.REPEAT_ONE -> Icons.Default.RepeatOne
    }

    fun setShowQueue(v: Boolean) { _showQueue.value = v }
    fun toggleQueue() { _showQueue.value = !_showQueue.value }
    fun setShowLyrics(v: Boolean) { _showLyrics.value = v }
    fun toggleLyrics() { _showLyrics.value = !_showLyrics.value }

    private fun onCompleted() {
        when (_playMode.value) {
            PlayMode.REPEAT_ONE -> playAt(_currentIdx.value)
            else -> next()
        }
    }
}
