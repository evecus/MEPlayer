package com.mobile.meplayer.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.mobile.meplayer.data.PermissionUtil
import com.mobile.meplayer.ui.iptv.IptvTabPageScreen
import com.mobile.meplayer.ui.music.MusicLibraryScreen
import com.mobile.meplayer.ui.settings.SettingsScreen
import com.mobile.meplayer.ui.video.VideoLibraryScreen

/**
 * 首页，对应 Flutter 的 `HomePage`。
 *
 * 含四个 Tab（视频 / IPTV / 音乐 / 设置）和底部 [MiniPlaybackBar]：
 * - 手机端（宽度 < 600dp）：顶部纯文字导航栏（4 个标签平分宽度，选中态加粗 +
 *   主题色 + 2.5dp 高 20dp 宽下划线）+ 内容区 + 迷你播放栏。
 * - 平板端（宽度 >= 600dp）：左侧 [NavigationRail]（图标 + 文字）+ 竖分隔线 +
 *   内容区 + 迷你播放栏。
 *
 * 首次进入时通过 [PermissionUtil] 请求媒体权限。
 */
@Composable
fun HomeScreen(navController: NavHostController) {
    // 用 rememberSaveable 持久化 Tab 选中状态：进入播放页再返回时，HomeScreen 会被
    // 重新组合，remember 会丢失状态导致回到默认的"视频"Tab；rememberSaveable 能跨
    // 导航返回保持用户上次所在的 Tab。
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    val configuration = LocalConfiguration.current
    val isWide = configuration.screenWidthDp >= 600

    // 首次进入请求媒体权限
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果忽略：缺失权限时各模块自行处理 */ }

    LaunchedEffect(Unit) {
        if (!PermissionUtil.hasMediaPermissions(context)) {
            permissionLauncher.launch(PermissionUtil.mediaPermissions())
        }
    }

    if (isWide) {
        WideLayout(navController, selectedIndex) { selectedIndex = it }
    } else {
        CompactLayout(navController, selectedIndex) { selectedIndex = it }
    }
}

// ── 手机端布局 ──────────────────────────────────────────────────────────

@Composable
private fun CompactLayout(
    navController: NavHostController,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    // 外层 Box 提供背景色（延伸到状态栏区域），Column 的 statusBarsPadding 消费
    // 状态栏 inset，使子节点（各 Tab 页的 Scaffold/TopAppBar）读到 0，避免双重 padding。
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            TextTabBar(selectedIndex = selectedIndex, onSelect = onSelect)
            Box(modifier = Modifier.weight(1f)) {
                TabContent(selectedIndex, navController)
            }
            MiniPlaybackBar(navController = navController)
        }
    }
}

// ── 平板端布局 ──────────────────────────────────────────────────────────

@Composable
private fun WideLayout(
    navController: NavHostController,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxHeight()
            ) {
                HomeTabs.forEachIndexed { index, tab ->
                    NavigationRailItem(
                        selected = selectedIndex == index,
                        onClick = { onSelect(index) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
            // 竖分隔线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Box(modifier = Modifier.weight(1f)) {
                    TabContent(selectedIndex, navController)
                }
                MiniPlaybackBar(navController = navController)
            }
        }
    }
}

// ── 顶部文字导航栏（手机端） ─────────────────────────────────────────────

@Composable
private fun TextTabBar(
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        HomeTabs.forEachIndexed { index, tab ->
            val selected = selectedIndex == index
            val color = if (selected) scheme.primary else scheme.onSurfaceVariant
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSelect(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = tab.label,
                    color = color,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .width(20.dp)
                        .height(2.5.dp)
                        .background(
                            color = if (selected) scheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(1.25.dp)
                        )
                )
            }
        }
    }
}

// ── 内容区 ──────────────────────────────────────────────────────────────

@Composable
private fun TabContent(selectedIndex: Int, navController: NavHostController) {
    when (selectedIndex) {
        0 -> VideoLibraryScreen(navController = navController)
        1 -> IptvTabPageScreen(navController = navController)
        2 -> MusicLibraryScreen(navController = navController)
        else -> SettingsScreen()
    }
}

// ── Tab 定义 ────────────────────────────────────────────────────────────

private data class HomeTab(val label: String, val icon: ImageVector)

private val HomeTabs = listOf(
    HomeTab("视频", Icons.Default.VideoLibrary),
    HomeTab("IPTV", Icons.Default.LiveTv),
    HomeTab("音乐", Icons.Default.MusicNote),
    HomeTab("设置", Icons.Default.Settings)
)
