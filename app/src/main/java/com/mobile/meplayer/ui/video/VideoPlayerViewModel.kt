package com.mobile.meplayer.ui.video

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobile.meplayer.controller.AppSettings
import com.mobile.meplayer.controller.MusicPlayerController
import com.mobile.meplayer.controller.PlaybackBarController
import com.mobile.meplayer.player.ExoPlayerBackend
import com.mobile.meplayer.player.PlayerBackend
import com.mobile.meplayer.player.PlayerStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.lang.ref.WeakReference

/**
 * 视频播放器 ViewModel，对应 Flutter 的 `VideoPlayerController`。
 *
 * 持有 [ExoPlayerBackend]，订阅其状态流并映射为 Compose 可订阅的 [MutableStateFlow]。
 *
 * 由于 [PlayerStateHolder] 是抽象类（含 Handler/协程作用域等可变状态），无法与
 * [AndroidViewModel] 同时作为父类继承，故通过组合持有 [holder] 实例并委托其
 * 控制条自动隐藏与亮度/音量/进度手势逻辑。
 *
 * 生命周期：
 * - [init] 由 Screen 在首次进入时调用一次：停止音乐播放、清空迷你播放栏、
 *   打开目标媒体并按需恢复播放进度。
 * - [onCleared] 保存播放快照到 [PlaybackBarController] 并释放后端、恢复屏幕方向。
 */
class VideoPlayerViewModel(app: Application) : AndroidViewModel(app) {

    val backend: ExoPlayerBackend = ExoPlayerBackend(app)

    // ── 播放状态 ─────────────────────────────────────────────
    var playlist: List<Map<String, String>> = emptyList()
    val currentIndex = MutableStateFlow(0)
    val isPlaying = MutableStateFlow(false)
    val isBuffering = MutableStateFlow(false)
    val position = MutableStateFlow(0L)   // ms
    val duration = MutableStateFlow(0L)   // ms
    val playSpeed = MutableStateFlow(1.0f)
    val isLocked = MutableStateFlow(false)
    val isCompleted = MutableStateFlow(false)

    // ── 控制条 / 手势状态（由 [holder] 复用） ───────────────
    val showControls = MutableStateFlow(true)
    val isFullScreen = MutableStateFlow(false)
    val showGestureTip = MutableStateFlow(false)
    val gestureTipText = MutableStateFlow("")

    /**
     * 复用 [PlayerStateHolder] 的控制条自动隐藏与手势逻辑；将其抽象成员绑定到本 VM
     * 的 [backend] 与状态流，从而在不继承的前提下获得等价能力。
     */
    private val holder: PlayerStateHolder = object : PlayerStateHolder() {
        override val backend: PlayerBackend get() = this@VideoPlayerViewModel.backend
        override val showControls: MutableStateFlow<Boolean> get() = this@VideoPlayerViewModel.showControls
        override val isFullScreen: MutableStateFlow<Boolean> get() = this@VideoPlayerViewModel.isFullScreen
        override val showGestureTip: MutableStateFlow<Boolean> get() = this@VideoPlayerViewModel.showGestureTip
        override val gestureTipText: MutableStateFlow<String> get() = this@VideoPlayerViewModel.gestureTipText
    }

    // ── 委托 [PlayerStateHolder] 的能力给 UI 层 ─────────────
    fun autoHideControls(seconds: Int = 4) = holder.autoHideControls(seconds)
    fun toggleControls() = holder.toggleControls()
    fun showTip(text: String) = holder.showTip(text)
    suspend fun onBrightnessGestureStart(context: Context) = holder.onBrightnessGestureStart(context)
    fun onBrightnessGestureUpdate(context: Context, delta: Float) = holder.onBrightnessGestureUpdate(context, delta)
    suspend fun onVolumeGestureStart(context: Context) = holder.onVolumeGestureStart(context)
    fun onVolumeGestureUpdate(context: Context, delta: Float) = holder.onVolumeGestureUpdate(context, delta)
    fun onSeekGestureStart() = holder.onSeekGestureStart()
    fun onSeekGestureUpdate(deltaSeconds: Float) = holder.onSeekGestureUpdate(deltaSeconds)
    fun onSeekGestureEnd(deltaSeconds: Float) = holder.onSeekGestureEnd(deltaSeconds)

    private var initialized = false
    private var subscribed = false
    private var activityRef: WeakReference<Activity>? = null

    /**
     * 初始化播放列表与起始位置。
     *
     * 媒体互斥：开始播放视频前，先停止可能正在播放的后台音乐
     * （[MusicPlayerController.stopForOtherMedia]），并清空迷你播放栏快照。
     */
    fun init(playlist: List<Map<String, String>>, index: Int, resumePositionMs: Long) {
        if (initialized) return
        initialized = true
        this.playlist = playlist
        currentIndex.value = index
        // 媒体互斥：停止后台音乐（与 IptvPlayerViewModel 一致，try/catch 兜底）
        try {
            MusicPlayerController.stopForOtherMedia()
        } catch (_: Throwable) {
            // MusicPlayerController 尚未初始化时忽略
        }
        PlaybackBarController.clear()
        ensureSubscribed()
        if (index !in playlist.indices) return
        val path = playlist[index]["path"] ?: return
        recordRecent(path)
        viewModelScope.launch {
            backend.open(path)
            if (resumePositionMs > 0) backend.seek(resumePositionMs)
        }
    }

    /** 播放指定索引项。 */
    fun playAt(index: Int) {
        if (index !in playlist.indices) return
        currentIndex.value = index
        isCompleted.value = false
        val path = playlist[index]["path"] ?: return
        recordRecent(path)
        viewModelScope.launch { backend.open(path) }
    }

    /** 下一首（不循环，到末尾则停留）。 */
    fun next() {
        val idx = currentIndex.value
        if (idx < playlist.size - 1) playAt(idx + 1)
    }

    /** 上一首。 */
    fun prev() {
        val idx = currentIndex.value
        if (idx > 0) playAt(idx - 1)
    }

    fun togglePlay() {
        viewModelScope.launch { backend.playOrPause() }
    }

    fun seekTo(ms: Long) {
        viewModelScope.launch { backend.seek(ms) }
    }

    fun setSpeed(speed: Float) {
        playSpeed.value = speed
        viewModelScope.launch { backend.setRate(speed) }
    }

    fun toggleLock() {
        isLocked.value = !isLocked.value
    }

    fun enterFullScreen(activity: Activity) {
        activityRef = WeakReference(activity)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        isFullScreen.value = true
    }

    fun exitFullScreen(activity: Activity) {
        activityRef = WeakReference(activity)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        runCatching {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        isFullScreen.value = false
    }

    fun toggleFullScreen(activity: Activity) {
        if (isFullScreen.value) exitFullScreen(activity) else enterFullScreen(activity)
    }

    override fun onCleared() {
        super.onCleared()
        // 保存播放快照供迷你播放栏展示
        val idx = currentIndex.value
        val item = playlist.getOrNull(idx)
        val title = item?.get("name") ?: ""
        val path = item?.get("path") ?: ""
        runCatching {
            PlaybackBarController.saveVideoSnapshot(
                title = title,
                path = path,
                positionMs = position.value,
                durationMs = duration.value,
                playlist = playlist,
                index = idx
            )
        }
        // 释放后端（内部为同步工作）
        runBlocking { runCatching { backend.release() } }
        // 恢复屏幕方向与系统栏
        activityRef?.get()?.let { act ->
            runCatching {
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                WindowCompat.getInsetsController(act.window, act.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
        holder.dispose()
    }

    // ── 内部 ───────────────────────────────────────────────

    private fun ensureSubscribed() {
        if (subscribed) return
        subscribed = true
        viewModelScope.launch { backend.playing.collect { isPlaying.value = it } }
        viewModelScope.launch { backend.buffering.collect { isBuffering.value = it } }
        viewModelScope.launch { backend.position.collect { position.value = it } }
        viewModelScope.launch { backend.duration.collect { duration.value = it } }
        viewModelScope.launch {
            backend.completed.collect { c ->
                isCompleted.value = c
                if (c && currentIndex.value < playlist.size - 1) {
                    next()
                }
            }
        }
    }

    private fun recordRecent(path: String) {
        val lower = path.lowercase()
        if (!lower.startsWith("http") && !lower.startsWith("rtsp") && !lower.startsWith("content")) {
            AppSettings.controller.addRecentFile(path)
        }
    }
}
