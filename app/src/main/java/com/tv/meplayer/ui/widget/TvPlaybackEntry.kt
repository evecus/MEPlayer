package com.tv.meplayer.ui.widget

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.tv.meplayer.controller.MusicPlayerController
import com.tv.meplayer.controller.PlaybackBarController
import com.tv.meplayer.controller.PlaybackBarKind
import com.tv.meplayer.ui.music.PlaylistTransfer
import com.tv.meplayer.ui.navigation.AppNavigator

/**
 * TV 端顶部"播放入口"：图标 + 当前播放标题，用于快捷跳回播放页。
 *
 * 对应 Flutter TV 的 `TvPlaybackEntry`。只在有内容可跳转时显示，三者按
 * "音乐 > 视频/IPTV 快照"的优先级只展示一条：
 * - 音乐：直接从 [MusicPlayerController] 实时读取（常驻单例）。
 * - 视频 / IPTV：从 [PlaybackBarController] 读取退出播放页时保存的快照。
 *
 * 点击行为：
 * - 音乐 → 跳音乐播放页（不清空当前播放，仅展示）。
 * - 视频 → 跳视频播放页，从快照进度续播。
 * - IPTV → 跳 IPTV 播放页，重连同频道。
 */
@Composable
fun TvPlaybackEntry(navController: NavHostController) {
    val scheme = MaterialTheme.colorScheme

    val hasMusic by MusicPlayerController.hasContent.collectAsState()
    val musicTitle by MusicPlayerController.title.collectAsState()

    val kind by PlaybackBarController.kind.collectAsState()
    val videoTitle by PlaybackBarController.videoTitle.collectAsState()
    val videoPosition by PlaybackBarController.videoPosition.collectAsState()
    val iptvChannelName by PlaybackBarController.iptvChannelName.collectAsState()
    val iptvGroupName by PlaybackBarController.iptvGroupName.collectAsState()

    val showMusic = hasMusic
    val showSnapshot = !showMusic && kind != PlaybackBarKind.NONE
    if (!showMusic && !showSnapshot) return

    val icon: ImageVector
    val label: String
    val onClick: () -> Unit

    if (showMusic) {
        icon = Icons.Default.MusicNote
        label = musicTitle.ifEmpty { "音乐播放中" }
        onClick = {
            // 不重置播放列表，MusicPlayerController 已持有当前状态，
            // 播放页 LaunchedEffect 检测到 PlaylistTransfer.playlist 为 null 时不会重播。
            PlaylistTransfer.playlist = null
            AppNavigator.toMusicPlayer(navController, "[]", 0)
        }
    } else {
        val isVideo = kind == PlaybackBarKind.VIDEO
        if (isVideo) {
            icon = Icons.Default.Movie
            label = videoTitle
            onClick = {
                // 通过 PlaylistTransfer 传递完整播放列表（含 path/name），避免 URL 超限。
                PlaylistTransfer.videoPlaylist = PlaybackBarController.videoPlaylist
                AppNavigator.toVideoPlayer(
                    navController = navController,
                    playlistJson = "[]",
                    index = PlaybackBarController.videoIndex,
                    resumePositionMs = videoPosition
                )
            }
        } else {
            icon = Icons.Default.LiveTv
            label = iptvChannelName
            onClick = {
                AppNavigator.toIptvPlayer(
                    navController = navController,
                    url = "",
                    channelName = iptvChannelName,
                    groupName = iptvGroupName,
                    sourceIndex = PlaybackBarController.iptvSourceIndex
                )
            }
        }
    }

    TvFocusable(onClick = onClick, cornerRadius = 8.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                color = scheme.onSurface,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 260.dp)
            )
        }
    }
}
