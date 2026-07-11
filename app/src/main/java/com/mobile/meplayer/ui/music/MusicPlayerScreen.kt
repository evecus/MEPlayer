package com.mobile.meplayer.ui.music

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.mobile.meplayer.controller.MusicPlayerController
import com.mobile.meplayer.controller.PlayMode
import java.util.Locale

/**
 * 音乐播放页，对应 Flutter 的 `MusicPlayerPage`。
 *
 * 用 [MusicPlayerController]（object 单例）驱动状态。若 [playlistJson] 非空则
 * 调用 `playPlaylist` 开始播放。手机/平板自适应布局。
 */
@Composable
fun MusicPlayerScreen(
    navController: NavHostController,
    playlistJson: String,
    index: Int
) {
    // 播放列表通过全局 PlaylistTransfer 传递（避免 URL 超限），URL 仅为占位。
    LaunchedEffect(Unit) {
        val list = PlaylistTransfer.playlist
        if (list != null) {
            PlaylistTransfer.playlist = null
            MusicPlayerController.playPlaylist(list, index)
        }
    }

    val title by MusicPlayerController.title.collectAsState()
    val artist by MusicPlayerController.artist.collectAsState()
    val isPlaying by MusicPlayerController.isPlaying.collectAsState()
    val position by MusicPlayerController.position.collectAsState()
    val duration by MusicPlayerController.duration.collectAsState()
    val coverBytes by MusicPlayerController.coverBytes.collectAsState()
    val lyrics by MusicPlayerController.lyrics.collectAsState()
    val showQueue by MusicPlayerController.showQueue.collectAsState()
    val playMode by MusicPlayerController.playMode.collectAsState()
    val currentIdx by MusicPlayerController.currentIdx.collectAsState()

    val lrcState = rememberLrcViewState()
    LaunchedEffect(position) { lrcState.updateProgress(position) }

    val isTablet = LocalConfiguration.current.screenWidthDp >= 600

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (isTablet) {
            TabletPlayer(
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                coverBytes = coverBytes,
                lyrics = lyrics,
                playMode = playMode,
                currentIdx = currentIdx,
                lrcState = lrcState,
                onBack = { navController.popBackStack() }
            )
        } else {
            PhonePlayer(
                title = title,
                artist = artist,
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                coverBytes = coverBytes,
                lyrics = lyrics,
                playMode = playMode,
                currentIdx = currentIdx,
                lrcState = lrcState,
                onBack = { navController.popBackStack() }
            )
        }

        // 队列面板覆盖层
        QueuePanel(
            isTablet = isTablet,
            visible = showQueue,
            currentIdx = currentIdx,
            playMode = playMode,
            onClose = { MusicPlayerController.setShowQueue(false) },
            onPlayAt = { MusicPlayerController.playAt(it) }
        )
    }
}

// ── 手机布局 ───────────────────────────────────────────────

@Composable
private fun PhonePlayer(
    title: String,
    artist: String,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    coverBytes: ByteArray?,
    lyrics: String,
    playMode: PlayMode,
    currentIdx: Int,
    lrcState: LrcViewState,
    onBack: () -> Unit
) {
    val density = LocalDensity.current
    val openThresholdPx = with(density) { 80.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 顶部：返回40dp + 歌名20sp bold居中 + 歌手15sp + 右占位40dp
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "返回",
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onBack)
                    .padding(8.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = artist,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.size(40.dp))
        }

        // 中间：封面/歌词（alpha 切换），左右划切换，上划开队列
        val pagerState = rememberPagerState(pageCount = { 2 })
        LaunchedEffect(pagerState.currentPage) {
            MusicPlayerController.setShowLyrics(pagerState.currentPage == 1)
        }

        var totalDrag by remember { mutableStateOf(0f) }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { totalDrag = 0f },
                        onDragEnd = {
                            if (totalDrag < -openThresholdPx) {
                                MusicPlayerController.setShowQueue(true)
                            }
                            totalDrag = 0f
                        },
                        onDragCancel = { totalDrag = 0f }
                    ) { _, dragAmount ->
                        totalDrag += dragAmount
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(state = pagerState) { page ->
                val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                val a = (1f - kotlin.math.abs(offset)).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier.fillMaxSize().alpha(a),
                    contentAlignment = Alignment.Center
                ) {
                    if (page == 0) {
                        CoverDisplay(coverBytes = coverBytes, size = 260.dp)
                    } else {
                        LrcView(
                            lrcText = lyrics,
                            highlightColor = MaterialTheme.colorScheme.primary,
                            normalColor = MaterialTheme.colorScheme.onBackground,
                            lineSpacing = 50f,
                            state = lrcState,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // 底部：进度条 + 控制按钮
        ProgressBar(
            position = position,
            duration = duration,
            onSeek = { MusicPlayerController.seekTo(it) }
        )
        ControlBar(
            isPlaying = isPlaying,
            playMode = playMode,
            onTogglePlay = { MusicPlayerController.togglePlay() },
            onPrev = { MusicPlayerController.prev() },
            onNext = { MusicPlayerController.next() },
            onCycleMode = { MusicPlayerController.cyclePlayMode() },
            onQueue = { MusicPlayerController.setShowQueue(true) }
        )
        Spacer(Modifier.size(8.dp))
    }
}

// ── 平板布局 ───────────────────────────────────────────────

@Composable
private fun TabletPlayer(
    title: String,
    artist: String,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    coverBytes: ByteArray?,
    lyrics: String,
    playMode: PlayMode,
    currentIdx: Int,
    lrcState: LrcViewState,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 返回48dp 左上
        Icon(
            imageVector = Icons.Default.ArrowBack,
            contentDescription = "返回",
            tint = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onBack)
                .padding(10.dp)
                .align(Alignment.TopStart)
        )

        // 水平 1:1 分屏
        Row(modifier = Modifier.fillMaxSize()) {
            // 左：封面区（可被队列覆盖）
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                CoverDisplay(coverBytes = coverBytes, size = 300.dp)
            }
            // 右：信息区（歌名26sp + 歌手17sp + 歌词常驻 + 进度条 + 控制）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 24.dp, top = 60.dp, bottom = 24.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = artist,
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.size(12.dp))
                LrcView(
                    lrcText = lyrics,
                    highlightColor = MaterialTheme.colorScheme.primary,
                    normalColor = MaterialTheme.colorScheme.onBackground,
                    lineSpacing = 50f,
                    state = lrcState,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.size(8.dp))
                ProgressBar(
                    position = position,
                    duration = duration,
                    onSeek = { MusicPlayerController.seekTo(it) }
                )
                ControlBar(
                    isPlaying = isPlaying,
                    playMode = playMode,
                    onTogglePlay = { MusicPlayerController.togglePlay() },
                    onPrev = { MusicPlayerController.prev() },
                    onNext = { MusicPlayerController.next() },
                    onCycleMode = { MusicPlayerController.cyclePlayMode() },
                    onQueue = { MusicPlayerController.setShowQueue(true) }
                )
            }
        }
    }
}

// ── 封面展示 ───────────────────────────────────────────────

@Composable
private fun CoverDisplay(coverBytes: ByteArray?, size: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    if (coverBytes != null) {
        val bmp = remember(coverBytes) {
            runCatching {
                BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
            }.getOrNull()
        }
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .size(size)
                    .clip(RoundedCornerShape(12.dp))
            )
            return
        }
    }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = scheme.primary.copy(alpha = 0.6f),
            modifier = Modifier.size((size.value * 0.38f).dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "暂无封面",
            fontSize = 14.sp,
            color = scheme.onSurfaceVariant
        )
    }
}

// ── 进度条 ─────────────────────────────────────────────────

@Composable
private fun ProgressBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
    ) {
        Text(
            text = formatTime(position),
            fontSize = 12.sp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Slider(
            value = fraction,
            onValueChange = { v ->
                if (duration > 0) onSeek((v * duration).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = scheme.primary,
                activeTrackColor = scheme.primary,
                inactiveTrackColor = scheme.onSurfaceVariant.copy(alpha = 0.3f)
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        )
        Text(
            text = formatTime(duration),
            fontSize = 12.sp,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

// ── 控制按钮 ───────────────────────────────────────────────

@Composable
private fun ControlBar(
    isPlaying: Boolean,
    playMode: PlayMode,
    onTogglePlay: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCycleMode: () -> Unit,
    onQueue: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = MusicPlayerController.playModeIcon(),
                contentDescription = MusicPlayerController.playModeLabel(),
                tint = if (playMode != PlayMode.LIST) scheme.primary else scheme.onBackground,
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onCycleMode)
                    .padding(10.dp)
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "上一首",
                tint = scheme.onBackground,
                modifier = Modifier
                    .size(52.dp)
                    .clickable(onClick = onPrev)
                    .padding(8.dp)
            )
        }
        Box(Modifier.weight(1.4f), contentAlignment = Alignment.Center) {
            Surface(
                shape = CircleShape,
                color = scheme.primary,
                modifier = Modifier
                    .size(64.dp)
                    .clickable(onClick = onTogglePlay)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "下一首",
                tint = scheme.onBackground,
                modifier = Modifier
                    .size(52.dp)
                    .clickable(onClick = onNext)
                    .padding(8.dp)
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.QueueMusic,
                contentDescription = "播放队列",
                tint = scheme.onBackground,
                modifier = Modifier
                    .size(44.dp)
                    .clickable(onClick = onQueue)
                    .padding(10.dp)
            )
        }
    }
}

// ── 队列面板 ───────────────────────────────────────────────

@Composable
private fun QueuePanel(
    isTablet: Boolean,
    visible: Boolean,
    currentIdx: Int,
    playMode: PlayMode,
    onClose: () -> Unit,
    onPlayAt: (Int) -> Unit
) {
    val density = LocalDensity.current
    val closeThresholdPx = with(density) { 80.dp.toPx() }
    val scheme = MaterialTheme.colorScheme
    val playlist = MusicPlayerController.playlist
    val currentTitle = MusicPlayerController.title.collectAsState().value
    val currentArtist = MusicPlayerController.artist.collectAsState().value

    val enter = if (isTablet) {
        slideInVertically(initialOffsetY = { -it }) + fadeIn()
    } else {
        slideInVertically(initialOffsetY = { it }) + fadeIn()
    }
    val exit = if (isTablet) {
        slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    } else {
        slideOutVertically(targetOffsetY = { it }) + fadeOut()
    }

    AnimatedVisibility(visible = visible, enter = enter, exit = exit) {
        var totalDrag by remember { mutableStateOf(0f) }
        val closeModifier = Modifier.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { totalDrag = 0f },
                onDragEnd = {
                    val triggered = if (isTablet) totalDrag < -closeThresholdPx
                    else totalDrag > closeThresholdPx
                    if (triggered) onClose()
                    totalDrag = 0f
                },
                onDragCancel = { totalDrag = 0f }
            ) { _, dragAmount -> totalDrag += dragAmount }
        }

        Box(
            modifier = if (isTablet) {
                Modifier.fillMaxHeight().background(scheme.surface)
            } else {
                Modifier.fillMaxSize().background(scheme.surface)
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(closeModifier)
                    .padding(16.dp)
            ) {
                Text(
                    text = if (isTablet) "此处向上轻扫以返回" else "此处向下轻扫以返回",
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = currentTitle,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = currentArtist.ifEmpty { "未知歌手" },
                    fontSize = 13.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "共 ${playlist.size} 首",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "播放队列",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface
                )
                Spacer(Modifier.size(4.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(playlist) { i, item ->
                        val isCurrent = i == currentIdx
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlayAt(i) }
                                .padding(vertical = 8.dp)
                        ) {
                            Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                if (isCurrent) {
                                    Icon(
                                        imageVector = Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = scheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = item["title"]?.takeIf { it.isNotEmpty() }
                                    ?: item["name"]?.substringBeforeLast('.', item["name"] ?: "")
                                    ?: "",
                                fontSize = 15.sp,
                                color = if (isCurrent) scheme.primary else scheme.onSurface,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // 底部播放模式按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { MusicPlayerController.cyclePlayMode() }
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = MusicPlayerController.playModeIcon(),
                        contentDescription = null,
                        tint = if (playMode != PlayMode.LIST) scheme.primary else scheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = MusicPlayerController.playModeLabel(),
                        fontSize = 14.sp,
                        color = if (playMode != PlayMode.LIST) scheme.primary else scheme.onSurface
                    )
                }
            }
        }
    }
}

// ── 工具 ───────────────────────────────────────────────────

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    else String.format(Locale.getDefault(), "%02d:%02d", m, s)
}
