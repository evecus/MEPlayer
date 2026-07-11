package com.mobile.meplayer.ui.iptv

import android.app.Activity
import android.app.Application
import android.content.ContentResolver
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobile.meplayer.controller.AppSettings
import com.mobile.meplayer.controller.MusicPlayerController
import com.mobile.meplayer.controller.PlaybackBarController
import com.mobile.meplayer.data.M3uChannel
import com.mobile.meplayer.data.M3uParser
import com.mobile.meplayer.player.ExoPlayerBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * IPTV 直播播放 ViewModel，对应 Flutter 的 `IptvPlayerController`。
 *
 * 基于 [AndroidViewModel] + [ExoPlayerBackend]：
 * - 加载 M3U 源 → 按分组组织频道 → 三列浏览（分组 | 频道 | 线路）
 * - 同名频道合并为多线路（源1 / 源2 / …）
 * - 切换频道 / 切换线路 / 全屏 / 画面比例
 *
 * 退出时（[onCleared]）把频道信息快照存入 [PlaybackBarController]，供迷你播放栏恢复。
 */
class IptvPlayerViewModel(app: Application) : AndroidViewModel(app) {

    private val client = OkHttpClient()

    val backend: ExoPlayerBackend = ExoPlayerBackend(app)

    // ── 频道数据 ──────────────────────────────────────────
    val allChannels = MutableStateFlow<List<M3uChannel>>(emptyList())
    val grouped = MutableStateFlow<Map<String, List<M3uChannel>>>(emptyMap())

    /** 当前浏览的分组（频道列高亮）。 */
    val browseGroup = MutableStateFlow("")

    /** 当前播放频道所在分组（持久高亮）。 */
    val playingGroup = MutableStateFlow("")

    /** 当前播放的频道名。 */
    val channelName = MutableStateFlow("")

    /** 当前频道的所有线路 URL（源1 / 源2 / …）。 */
    val streamUrls = MutableStateFlow<List<String>>(emptyList())
    val streamIndex = MutableStateFlow(0)

    // ── 源（播放列表）数据 ───────────────────────────────
    val sources = MutableStateFlow<List<Map<String, String>>>(emptyList())
    val sourceIndex = MutableStateFlow(0)

    // ── 播放器状态 ────────────────────────────────────────
    val isBuffering = MutableStateFlow(false)
    val isPlaying = MutableStateFlow(false)
    val isLoading = MutableStateFlow(false)

    /** 画面比例：0=默认 1=16:9 2=4:3 3=填充 4=原始 5=裁剪。 */
    val aspectRatioMode = MutableStateFlow(0)

    // ── PlayerStateHolder 相关 ────────────────────────────
    val showControls = MutableStateFlow(true)
    val isFullScreen = MutableStateFlow(false)

    // ── 入参缓存 ──────────────────────────────────────────
    private var initialUrl: String = ""
    private var initialChannelName: String = ""
    private var initialGroupName: String = ""
    private var initialSourceIdx: Int = 0
    private var initialized = false

    /** 分组列表（保持插入顺序）。 */
    val groups: List<String> get() = grouped.value.keys.toList()

    /** 当前浏览分组下的频道（按名称去重，保留首个）。 */
    val browsedChannels: List<M3uChannel>
        get() {
            val g = browseGroup.value
            if (g.isEmpty()) return emptyList()
            val seen = mutableSetOf<String>()
            return (grouped.value[g] ?: emptyList()).filter { seen.add(it.name) }
        }

    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    init {
        // 订阅 backend 状态流，桥接到本 VM 的 StateFlow
        viewModelScope.launch {
            backend.buffering.collect { isBuffering.value = it }
        }
        viewModelScope.launch {
            backend.playing.collect { isPlaying.value = it }
        }
    }

    /**
     * 初始化：接收导航参数，停止音乐 / 清空迷你栏快照，加载源。
     * 幂等，多次调用只有首次生效。
     */
    fun init(url: String, channelName: String, groupName: String, sourceIdx: Int) {
        if (initialized) return
        initialized = true

        initialUrl = url
        initialChannelName = channelName
        initialGroupName = groupName
        initialSourceIdx = sourceIdx

        this.channelName.value = channelName
        this.playingGroup.value = groupName

        // 媒体互斥：开始播放 IPTV 前，先停止可能正在播放的音乐。
        try {
            MusicPlayerController.stopForOtherMedia()
        } catch (_: Throwable) {
            // MusicPlayerController 尚未注册时忽略
        }
        // IPTV 开始播放意味着不再需要展示"上次退出的视频/IPTV"快照。
        PlaybackBarController.clear()

        sources.value = AppSettings.controller.iptvSources.value

        if (sources.value.isNotEmpty()) {
            val idx = sourceIdx.coerceIn(0, sources.value.size - 1)
            loadSource(idx)
        } else if (url.isNotEmpty()) {
            streamUrls.value = listOf(url)
            streamIndex.value = 0
            viewModelScope.launch { backend.open(url) }
        }
    }

    // ── 加载 M3U 源 ───────────────────────────────────────

    fun loadSource(idx: Int) {
        val list = sources.value
        if (idx !in list.indices) return
        val src = list[idx]
        sourceIndex.value = idx
        isLoading.value = true
        viewModelScope.launch {
            try {
                val content = withContext(Dispatchers.IO) { readSourceContent(src) }
                if (content.isNotEmpty()) {
                    val parsed = M3uParser.parse(content)
                    allChannels.value = parsed
                    grouped.value = M3uParser.groupBy(parsed)

                    val grps = groups
                    if (grps.isEmpty()) return@launch

                    var targetGroup = grps.first()
                    var targetCh: M3uChannel? = null

                    if (initialGroupName.isNotEmpty() && grps.contains(initialGroupName)) {
                        targetGroup = initialGroupName
                    }
                    if (initialChannelName.isNotEmpty()) {
                        for (c in grouped.value[targetGroup] ?: emptyList()) {
                            if (c.name == initialChannelName) {
                                targetCh = c
                                break
                            }
                        }
                    }
                    if (targetCh == null) {
                        targetCh = (grouped.value[targetGroup] ?: emptyList()).firstOrNull()
                    }

                    browseGroup.value = targetGroup
                    playingGroup.value = targetGroup

                    if (targetCh != null) {
                        selectChannelInternal(targetCh, autoPlay = initialUrl.isEmpty())
                    } else if (initialUrl.isNotEmpty()) {
                        streamUrls.value = listOf(initialUrl)
                        streamIndex.value = 0
                        backend.open(initialUrl)
                    }
                }
            } catch (_: Throwable) {
                // silent
            } finally {
                isLoading.value = false
            }
        }
    }

    private fun readSourceContent(src: Map<String, String>): String {
        val type = src["type"] ?: "network"
        return if (type == "file") {
            val path = src["filePath"] ?: src["url"] ?: ""
            if (path.isEmpty()) "" else readFilePath(path)
        } else {
            val url = src["url"] ?: ""
            if (url.isEmpty()) "" else fetchUrl(url)
        }
    }

    private fun fetchUrl(url: String): String {
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) "" else resp.body?.string() ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun readFilePath(path: String): String {
        return try {
            if (path.startsWith("content://")) {
                val resolver: ContentResolver = getApplication<Application>().contentResolver
                resolver.openInputStream(Uri.parse(path))?.use { input ->
                    input.bufferedReader().use { it.readText() }
                } ?: ""
            } else {
                File(path).readText()
            }
        } catch (_: Throwable) {
            ""
        }
    }

    // ── 频道选择 ──────────────────────────────────────────

    fun selectChannel(ch: M3uChannel) {
        selectChannelInternal(ch, autoPlay = true)
    }

    private fun selectChannelInternal(ch: M3uChannel, autoPlay: Boolean) {
        channelName.value = ch.name
        playingGroup.value = ch.group

        // 收集所有同名频道的 URL 作为多线路（源1 / 源2 / …）
        val urls = allChannels.value
            .filter { it.name == ch.name }
            .map { it.url }
            .toSet()
            .toList()
        streamUrls.value = if (urls.isNotEmpty()) urls else listOf(ch.url)
        streamIndex.value = 0

        if (autoPlay) {
            viewModelScope.launch { _playStream(streamUrls.value.first()) }
        }
    }

    fun selectStream(idx: Int) {
        val urls = streamUrls.value
        if (idx !in urls.indices) return
        streamIndex.value = idx
        viewModelScope.launch { _playStream(urls[idx]) }
    }

    private suspend fun _playStream(url: String) {
        backend.open(url)
        autoHideControls()
    }

    fun togglePlay() {
        viewModelScope.launch { backend.playOrPause() }
    }

    // ── 画面比例 ──────────────────────────────────────────

    fun setAspectRatio(mode: Int) {
        aspectRatioMode.value = mode
        viewModelScope.launch { backend.setAspectRatio(mode) }
    }

    // ── 全屏 ──────────────────────────────────────────────

    fun enterFullScreen(activity: Activity) {
        if (isFullScreen.value) return
        isFullScreen.value = true
        val window = activity.window ?: return
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        @Suppress("DEPRECATION")
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        autoHideControls(seconds = 3)
    }

    fun exitFullScreen(activity: Activity) {
        if (!isFullScreen.value) return
        isFullScreen.value = false
        val window = activity.window ?: return
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.systemBars())
        }
        @Suppress("DEPRECATION")
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        showControls.value = true
    }

    fun toggleFullScreen(activity: Activity) {
        if (isFullScreen.value) exitFullScreen(activity) else enterFullScreen(activity)
    }

    // ── 控制条自动隐藏 ────────────────────────────────────

    /** 显示控制条并在 [seconds] 秒后自动隐藏。 */
    fun autoHideControls(seconds: Int = 4) {
        hideRunnable?.let { handler.removeCallbacks(it) }
        showControls.value = true
        val r = Runnable { showControls.value = false }
        hideRunnable = r
        handler.postDelayed(r, seconds * 1000L)
    }

    /** 切换控制条显隐：可见则立即隐藏，隐藏则显示并启动自动隐藏计时。 */
    fun toggleControls() {
        if (showControls.value) {
            hideRunnable?.let { handler.removeCallbacks(it) }
            showControls.value = false
        } else {
            autoHideControls()
        }
    }

    // ── 释放 ───────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        // 退出播放页即停止直播拉流，但把频道信息存进全局迷你播放栏，
        // 以便用户在首页点击播放栏时重新连接回同一频道（直播无法续播进度）。
        if (channelName.value.isNotEmpty()) {
            PlaybackBarController.saveIptvSnapshot(
                channelName = channelName.value,
                groupName = playingGroup.value,
                url = if (streamUrls.value.isNotEmpty()) streamUrls.value[streamIndex.value] else "",
                sourceIndex = sourceIndex.value,
            )
        }
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
        // 同步释放后端：onCleared 时 viewModelScope 已取消，launch 不会执行，
        // 必须用 runBlocking 同步释放，否则 ExoPlayer 音频会继续在后台播放。
        runBlocking { runCatching { backend.release() } }
    }

    companion object {
        val aspectRatioLabels = listOf("默认", "16:9", "4:3", "填充", "原始", "裁剪")
    }
}
