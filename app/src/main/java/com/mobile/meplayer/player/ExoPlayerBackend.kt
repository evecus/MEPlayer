package com.mobile.meplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * 基于 Media3 ExoPlayer 的播放器后端实现，对应 Flutter 版的 exo_backend.dart。
 *
 * 与 Flutter video_player 不同，Media3 的 ExoPlayer 是一个可复用的单例播放器：
 * 每次 [open] 通过 setMediaItem + prepare + play 切换媒体，无需销毁重建。
 *
 * 状态通过 [MutableStateFlow] 暴露；position 流由主线程 Handler 每 200ms 轮询
 * player.currentPosition 得到，同时在该轮询中完成 duration 同步与完成检测。
 *
 * ExoPlayer 默认硬件解码，无需额外硬/软解开关；[setAspectRatio] 为 no-op，
 * 画面比例由 UI 层的 PlayerView resizeMode 控制。
 */
class ExoPlayerBackend(context: Context) : PlayerBackend {

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context).build()

    /** 供同模块的 [ExoPlayerView] 取用，对外保持 private 语义。 */
    internal val player: ExoPlayer get() = exoPlayer

    override val type: PlayerBackendType = PlayerBackendType.EXO

    // ── 状态流 ──────────────────────────────────────────────
    private val _playingFlow = MutableStateFlow(false)
    private val _bufferingFlow = MutableStateFlow(false)
    private val _positionFlow = MutableStateFlow(0L)
    private val _durationFlow = MutableStateFlow(0L)
    private val _completedFlow = MutableStateFlow(false)

    override val playing: Flow<Boolean> get() = _playingFlow
    override val buffering: Flow<Boolean> get() = _bufferingFlow
    override val position: Flow<Long> get() = _positionFlow
    override val duration: Flow<Long> get() = _durationFlow
    override val completed: Flow<Boolean> get() = _completedFlow

    // ── 同步状态读取 ────────────────────────────────────────
    override val currentPosition: Long
        get() = exoPlayer.currentPosition.coerceAtLeast(0L)

    override val currentDuration: Long
        get() {
            val d = exoPlayer.duration
            return if (d == C.TIME_UNSET || d < 0) 0L else d
        }

    override val isPlaying: Boolean
        get() = exoPlayer.isPlaying

    override val isBuffering: Boolean
        get() = exoPlayer.playbackState == Player.STATE_BUFFERING

    // ── 轮询 / 缓存 ────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private var _released = false
    private var _lastPosition = 0L
    private var _lastDuration = 0L
    private var _lastCompleted = false

    /**
     * 主线程定时任务：每 200ms 读取 currentPosition/duration 并更新对应 StateFlow，
     * 同时按 "position 接近 duration 且非播放中" 判定完成状态。
     */
    private val positionUpdateTask = object : Runnable {
        override fun run() {
            if (_released) return
            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
            val rawDur = exoPlayer.duration
            val dur = if (rawDur == C.TIME_UNSET || rawDur < 0) 0L else rawDur

            if (pos != _lastPosition) {
                _lastPosition = pos
                _positionFlow.value = pos
            }
            if (dur != _lastDuration) {
                _lastDuration = dur
                _durationFlow.value = dur
            }
            val finished = dur > 0 && pos >= dur - COMPLETE_THRESHOLD_MS && !exoPlayer.isPlaying
            if (finished != _lastCompleted) {
                _lastCompleted = finished
                _completedFlow.value = finished
            }
            handler.postDelayed(this, POSITION_POLL_MS)
        }
    }

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playingFlow.value = isPlaying
                // 重新开始播放时，完成状态复位
                if (isPlaying && _lastCompleted) {
                    _lastCompleted = false
                    _completedFlow.value = false
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> _bufferingFlow.value = true
                    Player.STATE_READY -> {
                        _bufferingFlow.value = false
                        val d = exoPlayer.duration
                        val dur = if (d == C.TIME_UNSET || d < 0) 0L else d
                        if (dur != _lastDuration) {
                            _lastDuration = dur
                            _durationFlow.value = dur
                        }
                    }
                    Player.STATE_ENDED -> {
                        _bufferingFlow.value = false
                        if (!_lastCompleted) {
                            _lastCompleted = true
                            _completedFlow.value = true
                        }
                    }
                    Player.STATE_IDLE -> _bufferingFlow.value = false
                }
            }

            override fun onPlayerErrorChanged(error: PlaybackException?) {
                if (error != null) _bufferingFlow.value = false
            }
        })
        handler.post(positionUpdateTask)
    }

    // ── 控制 ───────────────────────────────────────────────
    override suspend fun open(pathOrUrl: String) {
        if (_released) return
        val mediaItem = buildMediaItem(pathOrUrl) ?: return
        // 切换媒体前复位完成状态与缓存
        _lastCompleted = false
        _completedFlow.value = false
        _lastPosition = 0L
        _lastDuration = 0L
        _positionFlow.value = 0L
        _durationFlow.value = 0L
        _bufferingFlow.value = true
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    override suspend fun playOrPause() {
        if (_released) return
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    override suspend fun seek(positionMs: Long) {
        if (_released) return
        exoPlayer.seekTo(positionMs)
        _lastPosition = positionMs
        _positionFlow.value = positionMs
        if (_lastCompleted) {
            _lastCompleted = false
            _completedFlow.value = false
        }
    }

    override suspend fun setRate(rate: Float) {
        if (_released) return
        exoPlayer.setPlaybackSpeed(rate)
    }

    override suspend fun setAspectRatio(mode: Int) {
        // no-op：画面比例由 UI 层的 PlayerView resizeMode 控制
    }

    override suspend fun release() {
        if (_released) return
        _released = true
        handler.removeCallbacks(positionUpdateTask)
        exoPlayer.release()
        _playingFlow.value = false
        _bufferingFlow.value = false
        _completedFlow.value = false
    }

    /**
     * 根据路径或 URL 构造 [MediaItem]：
     * - http/https/file/content：直接用解析出的 Uri
     * - 其它（无 scheme）：当作本地文件路径，转 file:// Uri
     */
    private fun buildMediaItem(pathOrUrl: String): MediaItem? {
        val trimmed = pathOrUrl.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val uri = Uri.parse(trimmed)
            when (uri.scheme?.lowercase()) {
                "http", "https", "file", "content" -> MediaItem.fromUri(uri)
                else -> MediaItem.fromUri(Uri.fromFile(File(trimmed)))
            }
        } catch (e: Exception) {
            // 解析失败时兜底按本地文件路径处理
            try {
                MediaItem.fromUri(Uri.fromFile(File(trimmed)))
            } catch (e2: Exception) {
                null
            }
        }
    }

    companion object {
        private const val POSITION_POLL_MS = 200L
        private const val COMPLETE_THRESHOLD_MS = 250L
    }
}
