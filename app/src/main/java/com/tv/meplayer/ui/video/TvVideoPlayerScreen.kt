package com.tv.meplayer.ui.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.tv.meplayer.data.parseStringMapList
import com.tv.meplayer.player.ExoPlayerView
import com.tv.meplayer.ui.music.PlaylistTransfer
import com.tv.meplayer.ui.widget.TvFocusable
import java.util.Locale

/**
 * TV 端视频播放器屏幕。
 *
 * 与手机端不同，TV 端不使用手势，改为遥控器按键：
 * - 方向左/右键：seek ±10s
 * - OK/Enter：播放/暂停
 * - 任意其它键：控制条隐藏时显示
 *
 * TV 本就是横屏全屏，故无全屏切换逻辑。
 */
@Composable
fun TvVideoPlayerScreen(
    navController: NavHostController,
    playlistJson: String,
    index: Int,
    resumePositionMs: Long,
) {
    val vm: VideoPlayerViewModel = viewModel()

    // 播放列表优先从 PlaylistTransfer.videoPlaylist 读取，否则解析 JSON，读后置 null
    val playlist = remember(playlistJson) {
        PlaylistTransfer.videoPlaylist?.also { PlaylistTransfer.videoPlaylist = null }
            ?: parseStringMapList(playlistJson)
    }

    LaunchedEffect(playlistJson, index, resumePositionMs) {
        vm.init(playlist, index, resumePositionMs)
    }
    LaunchedEffect(Unit) { vm.autoHideControls() }

    BackHandler { navController.popBackStack() }

    val isPlaying by vm.isPlaying.collectAsState()
    val isBuffering by vm.isBuffering.collectAsState()
    val position by vm.position.collectAsState()
    val duration by vm.duration.collectAsState()
    val showControls by vm.showControls.collectAsState()
    val currentIndex by vm.currentIndex.collectAsState()

    val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
    val hasMultiple = playlist.size > 1
    val currentName = playlist.getOrNull(currentIndex)?.get("name") ?: ""

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        vm.seekTo((position - 10_000).coerceAtLeast(0))
                        vm.autoHideControls()
                        true
                    }
                    Key.DirectionRight -> {
                        vm.seekTo((position + 10_000).coerceAtMost(duration))
                        vm.autoHideControls()
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        vm.togglePlay()
                        vm.autoHideControls()
                        true
                    }
                    else -> {
                        if (!showControls) {
                            vm.toggleControls()
                            true
                        } else {
                            false
                        }
                    }
                }
            }
    ) {
        ExoPlayerView(
            backend = vm.backend,
            modifier = Modifier.fillMaxSize(),
            fill = Color.Black,
        )

        if (isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        if (showControls) {
            // ── 顶部栏：返回 + 视频名 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                        )
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = currentName,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // ── 底部栏：进度条 + 时间 + 按钮 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                        )
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = formatTime(position),
                            color = Color.White,
                            fontSize = 14.sp,
                        )
                        Slider(
                            value = progress,
                            onValueChange = { vm.seekTo((it * duration).toLong()) },
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        Text(
                            text = formatTime(duration),
                            color = Color.White,
                            fontSize = 14.sp,
                        )
                    }
                    Spacer(Modifier.size(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (hasMultiple) {
                            TvFocusable(
                                onClick = {
                                    vm.prev()
                                    vm.autoHideControls()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "上一个",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .size(36.dp),
                                )
                            }
                            Spacer(Modifier.width(24.dp))
                        }
                        TvFocusable(
                            onClick = {
                                vm.togglePlay()
                                vm.autoHideControls()
                            },
                            autoFocus = true,
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "播放/暂停",
                                tint = Color.White,
                                modifier = Modifier
                                    .padding(8.dp)
                                    .size(44.dp),
                            )
                        }
                        if (hasMultiple) {
                            Spacer(Modifier.width(24.dp))
                            TvFocusable(
                                onClick = {
                                    vm.next()
                                    vm.autoHideControls()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "下一个",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .size(36.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 将毫秒格式化为 mm:ss 或 HH:mm:ss。 */
private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}
