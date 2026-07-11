package com.mobile.meplayer.ui.video

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.mobile.meplayer.data.parseStringMapList
import com.mobile.meplayer.player.ExoPlayerView
import com.mobile.meplayer.ui.music.PlaylistTransfer
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/** 控件层半透明背景色，对应 Flutter 版的 #44000000。 */
private val ControlBg = Color(0x44000000)

/** 当前播放列表项左侧指示器颜色，对应 Flutter 版的 #FF1ABC9C（青绿色）。 */
private val TealIndicator = Color(0xFF1ABC9C)

/**
 * 视频播放器页面，对应 Flutter 的 `VideoPlayerPage`。
 *
 * 布局随屏幕宽度与全屏状态切换：
 * - 手机（maxWidth < 600dp）：垂直 —— 16:9 播放器 + 标题 + 1dp 分隔线 + 播放列表
 * - 平板（maxWidth ≥ 600dp）：水平 74:26 分屏 —— 左播放器 + 右(56dp 标题栏 + 1dp 线 + 列表)
 * - 全屏：仅播放器 + 控件层
 *
 * 播放器区域为 `Box(ExoPlayerView + 控件层)`；控件层半透明 [#44000000]，含
 * 返回 / 锁 / 中央播放暂停（56dp 手机、64dp 平板）/ 底部 seekbar+全屏 / loading
 * （40dp 手机、48dp 平板）。手势：拖拽控制亮度（左半屏）/ 音量（右半屏）/ 进度（水平）。
 */
@Composable
fun VideoPlayerScreen(
    navController: NavHostController,
    playlistJson: String,
    index: Int,
    resumePositionMs: Long
) {
    val vm: VideoPlayerViewModel = viewModel()
    val context = LocalContext.current
    val activity = context as? Activity

    // 播放列表优先从全局 PlaylistTransfer 读取（避免 URL 超限），否则回退到 URL 解析
    val playlist = remember(playlistJson) {
        PlaylistTransfer.videoPlaylist ?: parseStringMapList(playlistJson)
    }
    LaunchedEffect(Unit) {
        // 进入后立即消费 holder，防止返回时误用旧数据
        PlaylistTransfer.videoPlaylist = null
    }

    LaunchedEffect(playlistJson, index, resumePositionMs) {
        vm.init(playlist, index, resumePositionMs)
    }
    LaunchedEffect(Unit) { vm.autoHideControls() }

    val isFullScreen by vm.isFullScreen.collectAsState()

    // 全屏时系统返回键先退出全屏，而非直接返回上一页
    BackHandler(enabled = isFullScreen) {
        activity?.let { vm.exitFullScreen(it) }
    }

    if (isFullScreen) {
        FullScreenPlayer(vm = vm, navController = navController, activity = activity)
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            TabletLayout(vm = vm, navController = navController, activity = activity, playlist = playlist)
        } else {
            PhoneLayout(vm = vm, navController = navController, activity = activity, playlist = playlist)
        }
    }
}

// ── 手机布局 ───────────────────────────────────────────────────────────

@Composable
private fun PhoneLayout(
    vm: VideoPlayerViewModel,
    navController: NavHostController,
    activity: Activity?,
    playlist: List<Map<String, String>>
) {
    val scheme = MaterialTheme.colorScheme
    val currentIndex by vm.currentIndex.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // 16:9 播放器（黑底）
        PlayerArea(
            vm = vm,
            navController = navController,
            activity = activity,
            isTablet = false,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        )
        // 标题（12dp padding，15sp bold）
        val name = playlist.getOrNull(currentIndex)?.get("name").orEmpty()
        Text(
            text = name.substringBeforeLast('.', name),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        )
        // 1dp 分隔线
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outlineVariant)
        )
        // 播放列表
        Playlist(
            vm = vm,
            playlist = playlist,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ── 平板布局 ───────────────────────────────────────────────────────────

@Composable
private fun TabletLayout(
    vm: VideoPlayerViewModel,
    navController: NavHostController,
    activity: Activity?,
    playlist: List<Map<String, String>>
) {
    val scheme = MaterialTheme.colorScheme
    val currentIndex by vm.currentIndex.collectAsState()

    Row(modifier = Modifier.fillMaxSize()) {
        // 左：播放器（74%）
        PlayerArea(
            vm = vm,
            navController = navController,
            activity = activity,
            isTablet = true,
            modifier = Modifier
                .weight(0.74f)
                .fillMaxHeight()
        )
        // 右：标题栏 + 列表（26%）
        Column(
            modifier = Modifier
                .weight(0.26f)
                .fillMaxHeight()
        ) {
            // 56dp 标题栏：返回 + 标题
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
            ) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "返回",
                        tint = scheme.onSurface
                    )
                }
                val name = playlist.getOrNull(currentIndex)?.get("name").orEmpty()
                Text(
                    text = name.substringBeforeLast('.', name),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                )
            }
            // 1dp 分隔线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(scheme.outlineVariant)
            )
            // 播放列表
            Playlist(
                vm = vm,
                playlist = playlist,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ── 全屏布局 ───────────────────────────────────────────────────────────

@Composable
private fun FullScreenPlayer(
    vm: VideoPlayerViewModel,
    navController: NavHostController,
    activity: Activity?
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ExoPlayerView(
            backend = vm.backend,
            modifier = Modifier.fillMaxSize(),
            fill = Color.Black
        )
        ControlLayer(
            vm = vm,
            onBack = { activity?.let { vm.exitFullScreen(it) } },
            onFullScreenToggle = { activity?.let { vm.toggleFullScreen(it) } },
            isTablet = true, // 全屏统一使用平板尺寸（64dp 按钮）
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ── 播放器区域 ─────────────────────────────────────────────────────────

@Composable
private fun PlayerArea(
    vm: VideoPlayerViewModel,
    navController: NavHostController,
    activity: Activity?,
    isTablet: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.background(Color.Black)) {
        ExoPlayerView(
            backend = vm.backend,
            modifier = Modifier.fillMaxSize(),
            fill = Color.Black
        )
        ControlLayer(
            vm = vm,
            onBack = { navController.popBackStack() },
            onFullScreenToggle = { activity?.let { vm.toggleFullScreen(it) } },
            isTablet = isTablet,
            modifier = Modifier.fillMaxSize()
        )
    }
}

// ── 控件层 ─────────────────────────────────────────────────────────────

@Composable
private fun ControlLayer(
    vm: VideoPlayerViewModel,
    onBack: () -> Unit,
    onFullScreenToggle: () -> Unit,
    isTablet: Boolean,
    modifier: Modifier = Modifier
) {
    val showControls by vm.showControls.collectAsState()
    val isLocked by vm.isLocked.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val isBuffering by vm.isBuffering.collectAsState()
    val position by vm.position.collectAsState()
    val duration by vm.duration.collectAsState()
    val isFullScreen by vm.isFullScreen.collectAsState()
    val showTip by vm.showGestureTip.collectAsState()
    val tipText by vm.gestureTipText.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val playBtnSize = if (isTablet) 64.dp else 56.dp
    val loadingSize = if (isTablet) 48.dp else 40.dp

    Box(modifier = modifier) {
        if (isLocked) {
            // 锁定状态：仅响应点击切换锁按钮显隐，控件可见时显示解锁按钮
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { vm.toggleControls() })
                    },
                contentAlignment = Alignment.Center
            ) {
                if (showControls) {
                    IconButton(
                        onClick = { vm.toggleLock() },
                        modifier = Modifier.size(playBtnSize)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "解锁",
                            tint = Color.White,
                            modifier = Modifier.size(playBtnSize)
                        )
                    }
                }
            }
        } else {
            // 手势层（透明，置于控件层下方；空白的控件层区域会透传到此处）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { vm.toggleControls() })
                    }
                    .pointerInput(Unit) {
                        detectPlayerDragGestures(vm, context, scope)
                    }
            )

            // 控件层（半透明 #44000000）
            if (showControls) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(ControlBg)
                ) {
                    // 顶部：返回 + 锁
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(top = 2.dp)
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { vm.toggleLock() }) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "锁定",
                                tint = Color.White
                            )
                        }
                    }

                    // 中央：播放/暂停 或 loading
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBuffering) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 3.dp,
                                modifier = Modifier.size(loadingSize)
                            )
                        } else {
                            IconButton(
                                onClick = { vm.togglePlay() },
                                modifier = Modifier.size(playBtnSize)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "暂停" else "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(playBtnSize)
                                )
                            }
                        }
                    }

                    // 底部：seekbar + 时间 + 全屏
                    BottomSeekBar(
                        position = position,
                        duration = duration,
                        isFullScreen = isFullScreen,
                        onSeek = vm::seekTo,
                        onFullScreenToggle = onFullScreenToggle,
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }

            // 手势提示
            if (showTip) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tipText,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

// ── 底部进度条 ─────────────────────────────────────────────────────────

@Composable
private fun BottomSeekBar(
    position: Long,
    duration: Long,
    isFullScreen: Boolean,
    onSeek: (Long) -> Unit,
    onFullScreenToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val safeDuration = if (duration > 0) duration else 1L
    val progress = (position.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Slider(
            value = progress,
            onValueChange = { v -> onSeek((v * duration).toLong()) },
            colors = SliderDefaults.colors(
                thumbColor = TealIndicator,
                activeTrackColor = TealIndicator,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Text(
                text = formatTime(position),
                color = Color.White,
                fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatTime(duration),
                color = Color.White,
                fontSize = 12.sp
            )
            IconButton(onClick = onFullScreenToggle) {
                Icon(
                    imageVector = if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = if (isFullScreen) "退出全屏" else "全屏",
                    tint = Color.White
                )
            }
        }
    }
}

// ── 播放列表 ───────────────────────────────────────────────────────────

@Composable
private fun Playlist(
    vm: VideoPlayerViewModel,
    playlist: List<Map<String, String>>,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val currentIndex by vm.currentIndex.collectAsState()

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        itemsIndexed(playlist) { index, item ->
            val isCurrent = index == currentIndex
            Card(
                shape = RoundedCornerShape(6.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isCurrent) scheme.surfaceVariant else scheme.surface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clickable { vm.playAt(index) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(10.dp)
                ) {
                    // 左 18dp 青绿色指示器（仅当前项）
                    Box(
                        modifier = Modifier
                            .size(width = 3.dp, height = 18.dp)
                            .background(
                                if (isCurrent) TealIndicator else Color.Transparent,
                                RoundedCornerShape(2.dp)
                            )
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = item["name"].orEmpty(),
                        fontSize = 13.sp,
                        color = if (isCurrent) TealIndicator else scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

// ── 手势 ───────────────────────────────────────────────────────────────

/**
 * 在播放器区域内识别拖拽手势：
 * - 水平拖拽 → 进度（[PlayerStateHolder.onSeekGestureStart] 等系列）
 * - 左半屏垂直拖拽 → 亮度
 * - 右半屏垂直拖拽 → 音量
 *
 * 方向由首次超过阈值的位移决定，确定后该次拖拽只作用于一种手势。
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectPlayerDragGestures(
    vm: VideoPlayerViewModel,
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var startX = 0f
    var totalDx = 0f
    var totalDy = 0f
    var mode = 0 // 0=未确定 1=亮度 2=音量 3=进度
    detectDragGestures(
        onDragStart = { offset ->
            startX = offset.x
            totalDx = 0f
            totalDy = 0f
            mode = 0
            // 三类手势的起始量都预先读取（开销小），方向确定后只更新对应手势
            scope.launch { vm.onBrightnessGestureStart(context) }
            scope.launch { vm.onVolumeGestureStart(context) }
            vm.onSeekGestureStart()
        },
        onDrag = { _, delta ->
            totalDx += delta.x
            totalDy += delta.y
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            if (mode == 0) {
                if (abs(totalDx) > abs(totalDy) && abs(totalDx) > 8f) {
                    mode = 3
                } else if (abs(totalDy) > abs(totalDx) && abs(totalDy) > 8f) {
                    mode = if (startX < w / 2f) 1 else 2
                }
            }
            when (mode) {
                1 -> vm.onBrightnessGestureUpdate(context, -totalDy / h)
                2 -> vm.onVolumeGestureUpdate(context, -totalDy / h)
                3 -> {
                    val durSec = vm.backend.currentDuration / 1000f
                    vm.onSeekGestureUpdate(totalDx / w * durSec)
                }
            }
        },
        onDragEnd = {
            if (mode == 3) {
                val w = size.width.toFloat()
                val durSec = vm.backend.currentDuration / 1000f
                vm.onSeekGestureEnd(totalDx / w * durSec)
            }
            mode = 0
        },
        onDragCancel = { mode = 0 }
    )
}

/** 将毫秒格式化为 `mm:ss` 或 `h:mm:ss`。 */
private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}
