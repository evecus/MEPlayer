package com.tv.meplayer.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * UI 状态 + 触摸手势控制辅助，对应 Flutter 版 player_core.dart 中的 PlayerStateMixin。
 *
 * 子类（通常是 ViewModel）需提供 [backend] 与四个 UI 状态流；本类提供控制条
 * 自动隐藏、手势提示、亮度/音量/进度手势等通用逻辑。
 *
 * - 亮度：读取 [Settings.System.SCREEN_BRIGHTNESS]（0-255 归一化为 0-1），
 *   写入通过当前 Activity 窗口的 screenBrightness 实现即时生效。
 * - 音量：[AudioManager.setStreamVolume]（STREAM_MUSIC）。
 * - 进度：读取 [PlayerBackend.currentPosition] / [PlayerBackend.currentDuration]。
 */
abstract class PlayerStateHolder {

    abstract val backend: PlayerBackend
    abstract val showControls: MutableStateFlow<Boolean>
    abstract val isFullScreen: MutableStateFlow<Boolean>
    abstract val showGestureTip: MutableStateFlow<Boolean>
    abstract val gestureTipText: MutableStateFlow<String>

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var hideRunnable: Runnable? = null
    private var tipRunnable: Runnable? = null

    private var gestureStartBrightness: Float? = null
    private var gestureStartVolume: Float? = null
    private var gestureStartPositionSec: Float = 0f

    // ── 控制条 ─────────────────────────────────────────────
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

    /** 显示手势提示文字，800ms 后自动消失。 */
    fun showTip(text: String) {
        gestureTipText.value = text
        showGestureTip.value = true
        tipRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { showGestureTip.value = false }
        tipRunnable = r
        handler.postDelayed(r, TIP_DURATION_MS)
    }

    // ── 亮度手势 ───────────────────────────────────────────
    /** 记录手势起始时的系统亮度（归一化 0-1）。 */
    suspend fun onBrightnessGestureStart(context: Context) {
        gestureStartBrightness = readSystemBrightness(context)
    }

    /** 在起始亮度基础上叠加 [delta]（0-1），写入当前窗口亮度并提示。 */
    fun onBrightnessGestureUpdate(context: Context, delta: Float) {
        val start = gestureStartBrightness ?: 0.5f
        val value = (start + delta).coerceIn(0f, 1f)
        setWindowBrightness(context, value)
        showTip("亮度 ${(value * 100).roundToInt()}%")
    }

    // ── 音量手势 ───────────────────────────────────────────
    /** 记录手势起始时的媒体音量（归一化 0-1）。 */
    suspend fun onVolumeGestureStart(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            gestureStartVolume = if (max > 0) cur.toFloat() / max else 0f
        } else {
            gestureStartVolume = 0.5f
        }
    }

    /** 在起始音量基础上叠加 [delta]（0-1），设置 STREAM_MUSIC 音量并提示。 */
    fun onVolumeGestureUpdate(context: Context, delta: Float) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val start = gestureStartVolume ?: 0.5f
        val value = (start + delta).coerceIn(0f, 1f)
        val index = (value * max).roundToInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        showTip("音量 ${(value * 100).roundToInt()}%")
    }

    // ── 进度手势 ───────────────────────────────────────────
    /** 记录手势起始时的播放位置（秒）。 */
    fun onSeekGestureStart() {
        gestureStartPositionSec = backend.currentPosition / 1000f
    }

    /** 在起始位置基础上叠加 [deltaSeconds] 秒，显示目标时间提示（不实际跳转）。 */
    fun onSeekGestureUpdate(deltaSeconds: Float) {
        val durSec = backend.currentDuration / 1000f
        val target = (gestureStartPositionSec + deltaSeconds).coerceIn(0f, durSec)
        showTip(fmtSeconds(target))
    }

    /** 手势结束，按 [deltaSeconds] 计算最终目标并跳转。 */
    fun onSeekGestureEnd(deltaSeconds: Float) {
        val durSec = backend.currentDuration / 1000f
        val target = (gestureStartPositionSec + deltaSeconds).coerceIn(0f, durSec)
        scope.launch { backend.seek((target * 1000).toLong()) }
    }

    // ── 释放 ───────────────────────────────────────────────
    /** 释放 Handler 回调与协程作用域，防止泄漏。 */
    fun dispose() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        tipRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
        tipRunnable = null
        scope.cancel()
    }

    // ── helpers ────────────────────────────────────────────
    private fun readSystemBrightness(context: Context): Float {
        return try {
            val v = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            )
            v / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun setWindowBrightness(context: Context, value: Float) {
        val activity = context as? Activity ?: return
        val window = activity.window ?: return
        val attrs = window.attributes
        attrs.screenBrightness = value
        window.attributes = attrs
    }

    private fun fmtSeconds(s: Float): String {
        val total = s.toLong().coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", m, sec)
        }
    }

    companion object {
        private const val TIP_DURATION_MS = 800L
    }
}
