package com.tv.meplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.tv.meplayer.ui.navigation.AppNavigator
import com.tv.meplayer.ui.widget.TvFocusable
import com.tv.meplayer.ui.widget.TvPlaybackEntry

/**
 * TV 端首页：Header + 横向 3 大模块卡片（视频 / IPTV / 音乐）+ 右上设置入口。
 *
 * 焦点：第一张卡（视频）自动获焦；方向键在卡片间切换靠 Compose 默认几何寻路。
 */
@Composable
fun TvHomeScreen(navController: NavHostController) {
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .padding(48.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.VideoLibrary,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "MEPlayer",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onBackground
                )
                Spacer(Modifier.weight(1f))
                TvPlaybackEntry(navController = navController)
                Spacer(Modifier.width(12.dp))
                TvFocusable(
                    onClick = { AppNavigator.toSettings(navController) },
                    cornerRadius = 8.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = scheme.onSurface)
                        Spacer(Modifier.width(8.dp))
                        Text("设置", fontSize = 18.sp, color = scheme.onSurface)
                    }
                }
            }

            Spacer(Modifier.height(48.dp))

            // ── 3 大模块卡片 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                ModuleCard(
                    autoFocus = true,
                    icon = Icons.Default.VideoLibrary,
                    label = "视频",
                    subtitle = "本地媒体库 · 网络链接",
                    gradientColors = listOf(Color(0xFF1A6BA0), Color(0xFF0D3F61)),
                    modifier = Modifier.weight(1f)
                ) {
                    AppNavigator.toVideo(navController)
                }
                ModuleCard(
                    icon = Icons.Default.LiveTv,
                    label = "IPTV",
                    subtitle = "网络直播源 · m3u 文件",
                    gradientColors = listOf(Color(0xFF8E2020), Color(0xFF4A1010)),
                    modifier = Modifier.weight(1f)
                ) {
                    AppNavigator.toIptv(navController)
                }
                ModuleCard(
                    icon = Icons.Default.LibraryMusic,
                    label = "音乐",
                    subtitle = "本地音乐库",
                    gradientColors = listOf(Color(0xFF1A7A4A), Color(0xFF0A4025)),
                    modifier = Modifier.weight(1f)
                ) {
                    AppNavigator.toMusic(navController)
                }
            }
        }
    }
}

/**
 * 模块大卡片：渐变背景 + 大图标 + 标题 + 副标题。
 * 聚焦时由 TvFocusable 提供缩放 + 边框 + 光晕。
 */
@Composable
private fun ModuleCard(
    icon: ImageVector,
    label: String,
    subtitle: String,
    gradientColors: List<Color>,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    onClick: () -> Unit
) {
    TvFocusable(
        onClick = onClick,
        autoFocus = autoFocus,
        scaleOnFocus = 1.06f,
        cornerRadius = 16.dp,
        modifier = modifier.height(280.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(gradientColors))
                .padding(32.dp)
        ) {
            Column(modifier = Modifier.align(Alignment.BottomStart)) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = label,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = subtitle,
                    fontSize = 18.sp,
                    color = Color.White.copy(alpha = 0.8f)
                )
            }
        }
    }
}
