package com.tv.meplayer.ui.music

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.tv.meplayer.controller.MusicPlayerController
import com.tv.meplayer.ui.widget.TvFocusable
import java.util.Locale

/**
 * TV 端音乐播放器屏幕。
 *
 * 横屏左右分栏：左侧封面，右侧歌曲信息 + 歌词 + 进度条 + 控制按钮。
 * 遥控器按键：
 * - 方向左/右键：上一首 / 下一首
 * - OK/Enter：播放/暂停
 *
 * 队列面板打开时，按键交由队列列表处理焦点导航与确认。
 */
@Composable
fun TvMusicPlayerScreen(
    navController: NavHostController,
    playlistJson: String,
    index: Int,
) {
    LaunchedEffect(Unit) {
        val list = PlaylistTransfer.playlist
        if (list != null) {
            PlaylistTransfer.playlist = null
            MusicPlayerController.playPlaylist(list, index)
        }
    }

    BackHandler { navController.popBackStack() }

    val title by MusicPlayerController.title.collectAsState()
    val artist by MusicPlayerController.artist.collectAsState()
    val isPlaying by MusicPlayerController.isPlaying.collectAsState()
    val position by MusicPlayerController.position.collectAsState()
    val duration by MusicPlayerController.duration.collectAsState()
    val coverBytes by MusicPlayerController.coverBytes.collectAsState()
    val lyrics by MusicPlayerController.lyrics.collectAsState()
    val playMode by MusicPlayerController.playMode.collectAsState()
    val currentIdx by MusicPlayerController.currentIdx.collectAsState()
    val showQueue by MusicPlayerController.showQueue.collectAsState()

    val lrcState = rememberLrcViewState()
    LaunchedEffect(position) { lrcState.updateProgress(position) }

    val scheme = MaterialTheme.colorScheme
    val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // 队列打开时交由列表处理焦点导航与确认
                if (showQueue) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        MusicPlayerController.prev()
                        true
                    }
                    Key.DirectionRight -> {
                        MusicPlayerController.next()
                        true
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        MusicPlayerController.togglePlay()
                        true
                    }
                    else -> false
                }
            }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // ── 左侧：封面 ──
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                val cover = coverBytes
                if (cover != null) {
                    val bitmap = remember(cover) {
                        BitmapFactory.decodeByteArray(cover, 0, cover.size)
                    }
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "封面",
                            modifier = Modifier.size(300.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(120.dp),
                        )
                    }
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(120.dp),
                    )
                }
            }

            // ── 右侧：信息 + 歌词 + 进度 + 控制 ──
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(24.dp)
            ) {
                Text(
                    text = title.ifEmpty { "未知歌曲" },
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artist.isNotEmpty()) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = artist,
                        fontSize = 18.sp,
                        color = scheme.onBackground.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(16.dp))

                LrcView(
                    lrcText = lyrics,
                    highlightColor = scheme.primary,
                    normalColor = scheme.onBackground,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    state = lrcState,
                )

                Spacer(Modifier.size(16.dp))

                // 进度条
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formatTime(position),
                        color = scheme.onBackground,
                        fontSize = 14.sp,
                    )
                    Slider(
                        value = progress,
                        onValueChange = { MusicPlayerController.seekTo((it * duration).toLong()) },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    Text(
                        text = formatTime(duration),
                        color = scheme.onBackground,
                        fontSize = 14.sp,
                    )
                }

                Spacer(Modifier.size(16.dp))

                // 控制按钮栏
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TvFocusable(
                        onClick = { MusicPlayerController.cyclePlayMode() }
                    ) {
                        Icon(
                            imageVector = MusicPlayerController.playModeIcon(),
                            contentDescription = MusicPlayerController.playModeLabel(),
                            tint = scheme.onBackground,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(32.dp),
                        )
                    }
                    Spacer(Modifier.width(24.dp))
                    TvFocusable(
                        onClick = { MusicPlayerController.prev() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "上一首",
                            tint = scheme.onBackground,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(40.dp),
                        )
                    }
                    Spacer(Modifier.width(24.dp))
                    TvFocusable(
                        onClick = { MusicPlayerController.togglePlay() },
                        autoFocus = true,
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "播放/暂停",
                            tint = scheme.onBackground,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(48.dp),
                        )
                    }
                    Spacer(Modifier.width(24.dp))
                    TvFocusable(
                        onClick = { MusicPlayerController.next() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "下一首",
                            tint = scheme.onBackground,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(40.dp),
                        )
                    }
                    Spacer(Modifier.width(24.dp))
                    TvFocusable(
                        onClick = { MusicPlayerController.toggleQueue() }
                    ) {
                        Icon(
                            imageVector = Icons.Default.QueueMusic,
                            contentDescription = "队列",
                            tint = scheme.onBackground,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(32.dp),
                        )
                    }
                }
            }
        }

        // 队列面板
        if (showQueue) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(360.dp)
                    .background(scheme.surface.copy(alpha = 0.95f))
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "播放队列",
                        color = scheme.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.size(12.dp))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        itemsIndexed(MusicPlayerController.playlist) { i, item ->
                            val songTitle = item["title"]?.takeIf { it.isNotEmpty() }
                                ?: item["name"] ?: ""
                            TvFocusable(
                                onClick = { MusicPlayerController.playAt(i) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                autoFocus = i == currentIdx,
                            ) {
                                Text(
                                    text = songTitle,
                                    color = if (i == currentIdx) scheme.primary else scheme.onSurface,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(8.dp),
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
