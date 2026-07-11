package com.tv.meplayer.player

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.Flow

/**
 * 播放器后端类型。统一只用 ExoPlayer，保留枚举便于上层做差异化逻辑。
 */
enum class PlayerBackendType { EXO }

/**
 * 统一的播放器后端抽象，对应 Flutter 版的 player_backend.dart。
 *
 * 将原先 media_kit (MPV) 与 video_player (ExoPlayer) 两种后端统一为单一
 * Media3 ExoPlayer 后端。上层业务控制器与播放页 UI 通过本接口访问播放器，
 * 无需关心底层实现。
 *
 * 暴露以下能力：
 * - 5 个状态流：playing / buffering / position / duration / completed
 * - 同步状态读取：currentPosition / currentDuration / isPlaying / isBuffering
 * - 基本控制：open / playOrPause / seek / setRate / setAspectRatio / release
 */
interface PlayerBackend {
    val type: PlayerBackendType

    // ── 状态流 ──────────────────────────────────────────────
    val playing: Flow<Boolean>
    val buffering: Flow<Boolean>
    val position: Flow<Long> // ms
    val duration: Flow<Long> // ms
    val completed: Flow<Boolean>

    // ── 同步状态读取 ────────────────────────────────────────
    val currentPosition: Long // ms
    val currentDuration: Long // ms
    val isPlaying: Boolean
    val isBuffering: Boolean

    // ── 控制 ───────────────────────────────────────────────
    suspend fun open(pathOrUrl: String)
    suspend fun playOrPause()
    suspend fun seek(positionMs: Long)
    suspend fun setRate(rate: Float)

    /**
     * 设置画面比例。ExoPlayer 后端为 no-op，画面比例由 UI 层的
     * PlayerView resizeMode 控制。值含义：
     * 0=auto 1=16:9 2=4:3 3=填充 4=原始 5=裁剪
     */
    suspend fun setAspectRatio(mode: Int)

    /** 释放底层资源。调用后不可再用。 */
    suspend fun release()
}
