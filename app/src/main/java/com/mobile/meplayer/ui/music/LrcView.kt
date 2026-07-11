package com.mobile.meplayer.ui.music

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobile.meplayer.data.LrcLine

/**
 * LRC 歌词视图状态持有者，对应 Flutter 的 `lrc_view.dart` 中的 `_LrcViewState`。
 *
 * - [setText] 解析 LRC 文本为时间轴行（支持多时间戳行 `[00:01.00][00:30.00]lyric`），
 *   跳过 ID3 元数据标签行（`[ar:...]` `[ti:...]` 等），过滤制作信息行（词曲编混等）；
 *   无时间戳时作为纯文本展示。
 * - [updateProgress] 根据当前播放进度（ms）更新高亮行。
 */
class LrcViewState {

    var lines by mutableStateOf<List<LrcLine>>(emptyList())
        private set
    var plainLines by mutableStateOf<List<String>>(emptyList())
        private set
    var isPlain by mutableStateOf(false)
        private set
    var currentIndex by mutableStateOf(0)
        private set

    /** 由 Layout 测量阶段写入的目标垂直偏移，驱动 [animateFloatAsState] 平滑滚动。 */
    var targetOffset by mutableStateOf(0f)
        internal set

    fun setText(lrcText: String) {
        if (lrcText.isBlank()) {
            lines = emptyList()
            plainLines = emptyList()
            isPlain = false
            currentIndex = 0
            return
        }
        val timeRegex = Regex("""\[(\d{1,2}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        val tagRegex = Regex("""^\[(ar|ti|al|by|offset|length|re|ve|au|id):.*]""", RegexOption.IGNORE_CASE)
        val creditKeywords = listOf(
            "作词", "作曲", "编曲", "混音", "混缩", "录音", "母带", "制作",
            "和声", "后期", "吉他", "贝斯", "键盘", "弦乐", "lyrics", "music",
            "compose", "arrange", "mix", "mastering", "vocal", "guitar", "bass", "drum"
        )

        val timed = mutableListOf<LrcLine>()
        val plain = mutableListOf<String>()
        for (raw in lrcText.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (tagRegex.containsMatchIn(line)) continue
            val matches = timeRegex.findAll(line).toList()
            if (matches.isEmpty()) {
                val lower = line.lowercase()
                if (creditKeywords.any { lower.contains(it.lowercase()) }) continue
                plain.add(line)
                continue
            }
            val lastMatch = matches.last()
            val lyricText = line.substring(lastMatch.range.last + 1).trim()
            matches.forEach { m ->
                val min = m.groupValues[1].toIntOrNull() ?: 0
                val sec = m.groupValues[2].toIntOrNull() ?: 0
                val msStr = m.groupValues[3]
                val ms = when (msStr.length) {
                    0 -> 0
                    1 -> msStr.toInt() * 100
                    2 -> msStr.toInt() * 10
                    else -> msStr.substring(0, 3).toInt()
                }
                timed.add(LrcLine(min * 60_000L + sec * 1000L + ms, lyricText))
            }
        }

        if (timed.isEmpty()) {
            isPlain = true
            plainLines = plain
            lines = emptyList()
        } else {
            isPlain = false
            plainLines = emptyList()
            lines = timed.sortedBy { it.time }
        }
        currentIndex = 0
    }

    /** 根据播放进度更新当前高亮行。 */
    fun updateProgress(positionMs: Long) {
        if (isPlain || lines.isEmpty()) return
        var idx = 0
        for (i in lines.indices) {
            if (lines[i].time <= positionMs) idx = i else break
        }
        if (idx != currentIndex) currentIndex = idx
    }
}

@Composable
fun rememberLrcViewState(): LrcViewState = remember { LrcViewState() }

/**
 * LRC 歌词组件，对应 Flutter 的 `lrc_view.dart`。用 Compose [Layout] 自绘。
 *
 * - 17sp 普通行（半透明），20sp 加粗当前行。
 * - 当前行垂直居中，平滑滚动（[animateFloatAsState] 每帧逼近目标偏移）。
 * - 无时间戳时作为纯文本展示。
 *
 * @param state 外部持有的状态（用于调用 [LrcViewState.updateProgress]），
 *  为 null 时内部 [rememberLrcViewState] 创建。
 */
@Composable
fun LrcView(
    lrcText: String,
    emptyText: String = "暂无歌词",
    highlightColor: Color,
    normalColor: Color,
    lineSpacing: Float = 50f,
    modifier: Modifier = Modifier,
    state: LrcViewState? = null
) {
    val lrcState = state ?: rememberLrcViewState()
    LaunchedEffect(lrcText) { lrcState.setText(lrcText) }

    if (lrcState.isPlain) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (lrcState.plainLines.isEmpty()) {
                Text(emptyText, color = normalColor.copy(alpha = 0.5f), fontSize = 14.sp)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    lrcState.plainLines.forEach { line ->
                        Text(
                            text = line,
                            color = normalColor.copy(alpha = 0.6f),
                            fontSize = 17.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(lineSpacing.dp))
                    }
                }
            }
        }
        return
    }
    if (lrcState.lines.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(emptyText, color = normalColor.copy(alpha = 0.5f), fontSize = 14.sp)
        }
        return
    }

    val density = LocalDensity.current
    val currentIdx = lrcState.currentIndex
    val animated by animateFloatAsState(
        targetValue = lrcState.targetOffset,
        animationSpec = tween(durationMillis = 320),
        label = "lrcOffset"
    )

    Layout(
        content = {
            lrcState.lines.forEachIndexed { i, line ->
                val highlighted = i == currentIdx
                Text(
                    text = line.text.ifEmpty { "♪" },
                    color = if (highlighted) highlightColor else normalColor.copy(alpha = 0.55f),
                    fontSize = if (highlighted) 20.sp else 17.sp,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                )
            }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val spacingPx = with(density) { lineSpacing.dp.roundToPx() }
        val childConstraints = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(childConstraints) }
        val viewportW = constraints.maxWidth
        val viewportH = constraints.maxHeight
        val bounded = viewportH != Constraints.Infinity && viewportH > 0

        var y = 0
        var currentCenter = 0
        val ys = IntArray(placeables.size)
        placeables.forEachIndexed { i, p ->
            ys[i] = y
            if (i == currentIdx) currentCenter = y + p.height / 2
            y += p.height + spacingPx
        }
        val target = if (bounded) (viewportH / 2 - currentCenter).toFloat() else 0f
        if (target != lrcState.targetOffset) lrcState.targetOffset = target

        val layoutHeight = if (bounded) viewportH else y
        layout(viewportW, layoutHeight) {
            val off = animated.toInt()
            placeables.forEachIndexed { i, p ->
                p.placeRelative(0, ys[i] + off)
            }
        }
    }
}
