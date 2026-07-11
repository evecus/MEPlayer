package com.tv.meplayer.controller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 迷你播放栏展示的媒体类型，对应 Flutter 的 `PlaybackBarKind`。
 */
enum class PlaybackBarKind { NONE, VIDEO, IPTV, MUSIC }

/**
 * 全局单例：记录"退出播放页后"应在迷你播放栏中展示的媒体信息。
 *
 * 对应 Flutter 的 `PlaybackBarController`。
 *
 * - 视频 / IPTV 播放器随播放页面创建和销毁，退出时把信息快照保存在这里。
 * - 音乐播放器是全局常驻单例，信息直接从 MusicPlayerController 读取，
 *   这里只记录 [kind] 来切换展示。
 */
object PlaybackBarController {

    private val _kind = MutableStateFlow(PlaybackBarKind.NONE)
    val kind: StateFlow<PlaybackBarKind> = _kind.asStateFlow()

    // ── 视频快照 ─────────────────────────────────────────────────────────
    private val _videoTitle = MutableStateFlow("")
    val videoTitle: StateFlow<String> = _videoTitle.asStateFlow()

    private val _videoPath = MutableStateFlow("")
    val videoPath: StateFlow<String> = _videoPath.asStateFlow()

    private val _videoPosition = MutableStateFlow(0L)
    val videoPosition: StateFlow<Long> = _videoPosition.asStateFlow()

    private val _videoDuration = MutableStateFlow(0L)
    val videoDuration: StateFlow<Long> = _videoDuration.asStateFlow()

    var videoPlaylist: List<Map<String, String>> = emptyList()
        private set
    var videoIndex: Int = 0
        private set

    // ── IPTV 快照 ───────────────────────────────────────────────────────
    private val _iptvChannelName = MutableStateFlow("")
    val iptvChannelName: StateFlow<String> = _iptvChannelName.asStateFlow()

    private val _iptvGroupName = MutableStateFlow("")
    val iptvGroupName: StateFlow<String> = _iptvGroupName.asStateFlow()

    private val _iptvUrl = MutableStateFlow("")
    val iptvUrl: StateFlow<String> = _iptvUrl.asStateFlow()

    var iptvSourceIndex: Int = 0
        private set

    // ── 快照保存 ────────────────────────────────────────────────────────

    fun saveVideoSnapshot(
        title: String,
        path: String,
        positionMs: Long,
        durationMs: Long,
        playlist: List<Map<String, String>>,
        index: Int
    ) {
        _videoTitle.value = title
        _videoPath.value = path
        _videoPosition.value = positionMs
        _videoDuration.value = durationMs
        videoPlaylist = playlist
        videoIndex = index
        _kind.value = PlaybackBarKind.VIDEO
    }

    fun saveIptvSnapshot(
        channelName: String,
        groupName: String,
        url: String,
        sourceIndex: Int
    ) {
        _iptvChannelName.value = channelName
        _iptvGroupName.value = groupName
        _iptvUrl.value = url
        iptvSourceIndex = sourceIndex
        _kind.value = PlaybackBarKind.IPTV
    }

    fun markMusicActive() {
        _kind.value = PlaybackBarKind.MUSIC
    }

    fun clear() {
        _kind.value = PlaybackBarKind.NONE
    }

    val hasSnapshot: Boolean get() = _kind.value != PlaybackBarKind.NONE
}
