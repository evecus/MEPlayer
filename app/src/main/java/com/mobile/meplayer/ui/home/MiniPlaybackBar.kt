package com.mobile.meplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mobile.meplayer.controller.MusicPlayerController
import com.mobile.meplayer.controller.PlaybackBarKind
import com.mobile.meplayer.controller.PlaybackBarController
import com.mobile.meplayer.ui.music.PlaylistTransfer
import com.mobile.meplayer.ui.navigation.AppNavigator

/**
 * 首页底部迷你播放栏，对应 Flutter 的 `MiniPlaybackBar`。
 *
 * 监听 [PlaybackBarController.kind]：
 * - MUSIC 且 [MusicPlayerController.hasContent]：显示音乐行（封面 + 标题/歌手 + 上一首/播放暂停/下一首）。
 * - VIDEO / IPTV：显示快照行（图标 + 标题/进度或分组名 + 播放 + 关闭）。
 * - NONE：不渲染。
 *
 * 音乐行点击进入音乐播放页；视频快照点击恢复播放（带 resumePosition）；IPTV 快照点击重连。
 */
@Composable
fun MiniPlaybackBar(navController: NavHostController) {
    val kind by PlaybackBarController.kind.collectAsState()
    val hasMusicContent by MusicPlayerController.hasContent.collectAsState()

    val showMusic = kind == PlaybackBarKind.MUSIC && hasMusicContent
    val showVideo = kind == PlaybackBarKind.VIDEO
    val showIptv = kind == PlaybackBarKind.IPTV

    if (!showMusic && !showVideo && !showIptv) return

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            HorizontalDivider()
            when {
                showMusic -> MusicRow(navController)
                showVideo -> VideoSnapshotRow(navController)
                showIptv -> IptvSnapshotRow(navController)
            }
        }
    }
}

// ── 音乐行 ──────────────────────────────────────────────────────────────

@Composable
private fun MusicRow(navController: NavHostController) {
    val title by MusicPlayerController.title.collectAsState()
    val artist by MusicPlayerController.artist.collectAsState()
    val coverBytes by MusicPlayerController.coverBytes.collectAsState()
    val isPlaying by MusicPlayerController.isPlaying.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面 + 标题/歌手：点击进入音乐播放页
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable {
                    // 用全局 holder 传递播放列表，避免 URL 超过 Intent 大小限制
                    PlaylistTransfer.playlist = MusicPlayerController.playlist
                    AppNavigator.toMusicPlayer(
                        navController = navController,
                        playlistJson = "",
                        index = MusicPlayerController.currentIdx.value
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverArt(coverBytes = coverBytes, size = 36)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = title.ifEmpty { "未知曲目" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = artist.ifEmpty { "未知歌手" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = { MusicPlayerController.prev() }) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "上一首")
        }
        IconButton(onClick = { MusicPlayerController.togglePlay() }) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "播放/暂停"
            )
        }
        IconButton(onClick = { MusicPlayerController.next() }) {
            Icon(Icons.Default.SkipNext, contentDescription = "下一首")
        }
    }
}

// ── 视频快照行 ──────────────────────────────────────────────────────────

@Composable
private fun VideoSnapshotRow(navController: NavHostController) {
    val title by PlaybackBarController.videoTitle.collectAsState()
    val position by PlaybackBarController.videoPosition.collectAsState()
    val duration by PlaybackBarController.videoDuration.collectAsState()

    val resume = {
        PlaylistTransfer.videoPlaylist = PlaybackBarController.videoPlaylist
        AppNavigator.toVideoPlayer(
            navController = navController,
            playlistJson = "",
            index = PlaybackBarController.videoIndex,
            resumePositionMs = position
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { resume() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            SnapshotIcon(
                imageVector = Icons.Default.VideoLibrary,
                size = 36
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = title.ifEmpty { "视频" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatTime(position)} / ${formatTime(duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        IconButton(onClick = { resume() }) {
            Icon(Icons.Default.PlayArrow, contentDescription = "继续播放")
        }
        IconButton(onClick = { PlaybackBarController.clear() }) {
            Icon(Icons.Default.Close, contentDescription = "关闭")
        }
    }
}

// ── IPTV 快照行 ─────────────────────────────────────────────────────────

@Composable
private fun IptvSnapshotRow(navController: NavHostController) {
    val channelName by PlaybackBarController.iptvChannelName.collectAsState()
    val groupName by PlaybackBarController.iptvGroupName.collectAsState()
    val url by PlaybackBarController.iptvUrl.collectAsState()

    val reconnect = {
        AppNavigator.toIptvPlayer(
            navController = navController,
            url = url,
            channelName = channelName,
            groupName = groupName,
            sourceIndex = PlaybackBarController.iptvSourceIndex
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { reconnect() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            SnapshotIcon(
                imageVector = Icons.Default.LiveTv,
                size = 36
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = channelName.ifEmpty { "频道" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = groupName.ifEmpty { "IPTV" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = { reconnect() }) {
            Icon(Icons.Default.PlayArrow, contentDescription = "重新连接")
        }
        IconButton(onClick = { PlaybackBarController.clear() }) {
            Icon(Icons.Default.Close, contentDescription = "关闭")
        }
    }
}

// ── 通用组件 ────────────────────────────────────────────────────────────

/**
 * 音乐封面：有 [coverBytes] 时用 Coil 加载，否则显示占位图标。
 */
@Composable
private fun CoverArt(coverBytes: ByteArray?, size: Int) {
    val modifier = Modifier
        .size(size.dp)
        .clip(RoundedCornerShape(6.dp))
    if (coverBytes != null) {
        AsyncImage(
            model = coverBytes,
            contentDescription = null,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size((size * 0.55f).dp)
            )
        }
    }
}

/**
 * 视频 / IPTV 快照行左侧的图标占位（圆形或圆角方块背景 + 居中图标）。
 */
@Composable
private fun SnapshotIcon(imageVector: androidx.compose.ui.graphics.vector.ImageVector, size: Int) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((size * 0.55f).dp)
        )
    }
}

/**
 * 时间格式化：小时以上用 `h:mm:ss`，否则 `mm:ss`。
 */
private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0L)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}
