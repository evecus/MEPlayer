package com.mobile.meplayer.ui.iptv

import android.app.Activity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mobile.meplayer.data.M3uChannel
import com.mobile.meplayer.player.ExoPlayerView

/**
 * IPTV 直播播放页，对应 Flutter 的 `IptvPlayerPage`。
 *
 * 布局：
 * - 手机：垂直 — 16:9 播放器 + 信息栏 48dp + 1dp 线 + 三列频道区
 * - 平板：水平分屏 — 播放器左 (flex 72) + 信息栏 + 三列右 (flex 28)
 * - 全屏：仅播放器 + 顶部栏（返回 + 频道名 + 设置 + 退出全屏）
 */
@Composable
fun IptvPlayerScreen(
    navController: NavHostController,
    url: String,
    channelName: String,
    groupName: String,
    sourceIndex: Int,
    vm: IptvPlayerViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isFullScreen by vm.isFullScreen.collectAsState()

    // 初始化一次
    LaunchedEffect(Unit) {
        vm.init(url, channelName, groupName, sourceIndex)
    }

    // 全屏时拦截返回键：先退出全屏，再按一次才真正返回
    BackHandler(enabled = isFullScreen) {
        activity?.let { vm.exitFullScreen(it) }
    }

    Scaffold(
        containerColor = if (isFullScreen) Color.Black else MaterialTheme.colorScheme.surface
    ) { padding ->
        if (isFullScreen) {
            FullScreenLayout(
                vm = vm,
                onBack = { activity?.let { vm.exitFullScreen(it) } },
                onExit = { navController.popBackStack() },
                onShowSettings = { /* 由全屏内部顶栏触发，设置弹窗在 PlayerArea 内统一处理 */ }
            )
        } else {
            val isWide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
            if (isWide) {
                TabletLayout(
                    vm = vm,
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() }
                )
            } else {
                PhoneLayout(
                    vm = vm,
                    modifier = Modifier.padding(padding),
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

// ── 手机布局：垂直 — 16:9 播放器 + 信息栏 + 三列 ──────────────────────

@Composable
private fun PhoneLayout(
    vm: IptvPlayerViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val widthDp = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val playerHeight = widthDp * 9f / 16f
    var showSettings by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // 16:9 播放器
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(playerHeight)
        ) {
            PlayerArea(
                vm = vm,
                isFullScreen = false,
                onBack = onBack,
                onShowSettings = { showSettings = true }
            )
        }
        InfoBar(vm = vm, onShowSettings = { showSettings = true })
        HorizontalDivider()
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            ChannelArea(vm = vm, onBack = onBack)
        }
    }

    if (showSettings) {
        SettingsDialog(vm = vm, onDismiss = { showSettings = false })
    }
}

// ── 平板布局：水平分屏 — 播放器左 + 信息栏+三列右 ────────────────────

@Composable
private fun TabletLayout(
    vm: IptvPlayerViewModel,
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    var showSettings by remember { mutableStateOf(false) }

    Row(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(72f).fillMaxHeight()) {
            PlayerArea(
                vm = vm,
                isFullScreen = false,
                onBack = onBack,
                onShowSettings = { showSettings = true }
            )
        }
        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant.copy(alpha = 0.47f)))
        Column(modifier = Modifier.weight(28f).fillMaxHeight()) {
            InfoBar(vm = vm, onShowSettings = { showSettings = true })
            HorizontalDivider()
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                ChannelArea(vm = vm, onBack = onBack)
            }
        }
    }

    if (showSettings) {
        SettingsDialog(vm = vm, onDismiss = { showSettings = false })
    }
}

// ── 全屏布局 ──────────────────────────────────────────────────────────

@Composable
private fun FullScreenLayout(
    vm: IptvPlayerViewModel,
    onBack: () -> Unit,
    onExit: () -> Unit,
    onShowSettings: () -> Unit
) {
    val showControls by vm.showControls.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        PlayerArea(
            vm = vm,
            isFullScreen = true,
            onBack = onExit,
            onShowSettings = { showSettings = true }
        )
        // 全屏顶部栏（自带避让）
        if (showControls) {
            FullScreenTopBar(
                vm = vm,
                onExit = onBack,
                onShowSettings = { showSettings = true }
            )
        }
    }

    if (showSettings) {
        SettingsDialog(vm = vm, onDismiss = { showSettings = false })
    }
}

// ── 播放器区域 ────────────────────────────────────────────────────────

@Composable
private fun PlayerArea(
    vm: IptvPlayerViewModel,
    isFullScreen: Boolean,
    onBack: () -> Unit,
    onShowSettings: () -> Unit
) {
    val isBuffering by vm.isBuffering.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val showControls by vm.showControls.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val chName by vm.channelName.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 视频画面
        ExoPlayerView(backend = vm.backend, modifier = Modifier.fillMaxSize(), fill = Color.Black)

        // 加载圈
        if (isBuffering || isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // 控制浮层（全屏时顶部栏改由 FullScreenTopBar 提供）
        if (showControls && !isFullScreen) {
            PlayerControls(
                vm = vm,
                isPlaying = isPlaying,
                onBack = onBack,
                onShowSettings = onShowSettings
            )
        } else if (showControls && isFullScreen) {
            // 全屏下仅显示中央播放/暂停 + 底部上下频道；点击空白处切换控制条
            Box(modifier = Modifier.fillMaxSize().clickable { vm.toggleControls() }) {
                CenterPlayPause(isPlaying = isPlaying, onToggle = { vm.togglePlay() })
                BottomChannelBar(vm = vm, modifier = Modifier.align(Alignment.BottomCenter))
            }
        } else {
            // 隐藏时点击切换显隐
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { vm.toggleControls() }
            )
        }

        // 右上角频道名标签（加载时）
        if ((isBuffering || isLoading) && chName.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 12.dp)
                    .background(Color.Black.copy(alpha = 0.54f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(text = chName, style = TextStyle(color = Color.White, fontSize = 12.sp))
            }
        }
    }
}

@Composable
private fun PlayerControls(
    vm: IptvPlayerViewModel,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onShowSettings: () -> Unit
) {
    val chName by vm.channelName.collectAsState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { vm.toggleControls() }
    ) {
        // 顶部栏：返回 + 频道名 + 设置 + 全屏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.68f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val activity = LocalContext.current as? Activity
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                Text(
                    text = if (chName.isEmpty()) "请选择频道" else chName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                )
                IconButton(onClick = onShowSettings) {
                    Icon(Icons.Filled.Tune, contentDescription = "播放设置", tint = Color.White)
                }
                IconButton(onClick = { activity?.let { vm.toggleFullScreen(it) } }) {
                    Icon(Icons.Filled.Fullscreen, contentDescription = "全屏", tint = Color.White)
                }
            }
        }

        // 中央播放/暂停
        CenterPlayPause(isPlaying = isPlaying, onToggle = { vm.togglePlay() })

        // 底部栏：上一频道 / 下一频道
        BottomChannelBar(vm = vm, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun CenterPlayPause(isPlaying: Boolean, onToggle: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        IconButton(onClick = onToggle, modifier = Modifier.size(56.dp)) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White.copy(alpha = 0.86f),
                modifier = Modifier.size(56.dp)
            )
        }
    }
}

@Composable
private fun BottomChannelBar(vm: IptvPlayerViewModel, modifier: Modifier = Modifier) {
    val browsed = vm.browsedChannels
    val chName by vm.channelName.collectAsState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.68f))
                )
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { prevChannel(vm, browsed, chName) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = null, tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text("上一频道", color = Color.White)
                }
            }
            Spacer(Modifier.width(24.dp))
            TextButton(onClick = { nextChannel(vm, browsed, chName) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("下一频道", color = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.SkipNext, contentDescription = null, tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun FullScreenTopBar(
    vm: IptvPlayerViewModel,
    onExit: () -> Unit,
    onShowSettings: () -> Unit
) {
    val chName by vm.channelName.collectAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.68f), Color.Transparent)
                )
            )
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "退出全屏", tint = Color.White)
            }
            Text(
                text = if (chName.isEmpty()) "请选择频道" else chName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            )
            IconButton(onClick = onShowSettings) {
                Icon(Icons.Filled.Tune, contentDescription = "播放设置", tint = Color.White)
            }
            val activity = LocalContext.current as? Activity
            IconButton(onClick = { activity?.let { vm.exitFullScreen(it) } }) {
                Icon(Icons.Filled.FullscreenExit, contentDescription = "退出全屏", tint = Color.White)
            }
        }
    }
}

// ── 信息栏 ────────────────────────────────────────────────────────────

@Composable
private fun InfoBar(vm: IptvPlayerViewModel, onShowSettings: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val chName by vm.channelName.collectAsState()
    val urls by vm.streamUrls.collectAsState()
    val idx by vm.streamIndex.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(scheme.surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.LiveTv, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (chName.isEmpty()) "请选择频道" else chName,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            style = TextStyle(color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        )
        if (urls.size > 1) {
            Text(
                text = "源${idx + 1}/${urls.size}",
                style = TextStyle(color = scheme.onSurfaceVariant, fontSize = 12.sp)
            )
        }
        IconButton(onClick = onShowSettings) {
            Icon(Icons.Filled.Tune, contentDescription = "播放设置", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun HorizontalDivider() {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(scheme.outlineVariant.copy(alpha = 0.47f))
    )
}

@Composable
private fun VerticalDivider() {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(scheme.outlineVariant.copy(alpha = 0.47f))
    )
}

// ── 三列频道区：分组 | 频道 | 线路 ───────────────────────────────────

@Composable
private fun ChannelArea(vm: IptvPlayerViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val isLoading by vm.isLoading.collectAsState()
    val allChannels by vm.allChannels.collectAsState()

    if (isLoading && allChannels.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = scheme.primary)
        }
        return
    }
    if (allChannels.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Outlined.LiveTv, contentDescription = null, modifier = Modifier.size(64.dp), tint = scheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text("未加载到频道", style = TextStyle(color = scheme.onSurfaceVariant))
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text("返回源管理") }
        }
        return
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { GroupColumn(vm = vm) }
        VerticalDivider()
        Box(modifier = Modifier.weight(2f).fillMaxHeight()) { ChannelColumn(vm = vm) }
        VerticalDivider()
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) { StreamColumn(vm = vm) }
    }
}

@Composable
private fun GroupColumn(vm: IptvPlayerViewModel) {
    val scheme = MaterialTheme.colorScheme
    val groups = vm.groups
    val browseGroup by vm.browseGroup.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        // 标题栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainerHighest.copy(alpha = 0.47f))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text("分组", style = TextStyle(color = scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(groups) { g ->
                val selected = browseGroup == g
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) scheme.primary.copy(alpha = 0.24f) else Color.Transparent)
                        .clickable { vm.browseGroup.value = g }
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = g,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelColumn(vm: IptvPlayerViewModel) {
    val scheme = MaterialTheme.colorScheme
    val list = vm.browsedChannels
    val chName by vm.channelName.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainerHighest.copy(alpha = 0.47f))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text("频道", style = TextStyle(color = scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        }
        if (list.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("请选择分组", style = TextStyle(color = scheme.onSurfaceVariant, fontSize = 12.sp))
            }
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(list) { ch ->
                val selected = chName == ch.name
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) scheme.primary.copy(alpha = 0.31f) else Color.Transparent)
                        .clickable { vm.selectChannel(ch) }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (ch.logo.isNotEmpty()) {
                        AsyncImage(
                            model = ch.logo,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp).padding(end = 8.dp)
                        )
                    } else {
                        Icon(
                            Icons.Filled.LiveTv,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp).padding(end = 8.dp)
                        )
                    }
                    Text(
                        text = ch.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        style = TextStyle(
                            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamColumn(vm: IptvPlayerViewModel) {
    val scheme = MaterialTheme.colorScheme
    val urls by vm.streamUrls.collectAsState()
    val idx by vm.streamIndex.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainerHighest.copy(alpha = 0.47f))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text("线路", style = TextStyle(color = scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        }
        if (urls.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无线路", style = TextStyle(color = scheme.onSurfaceVariant, fontSize = 12.sp))
            }
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(urls.size) { i ->
                val selected = idx == i
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected) scheme.primary.copy(alpha = 0.24f) else Color.Transparent)
                        .clickable { vm.selectStream(i) }
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "源${i + 1}",
                        style = TextStyle(
                            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    )
                }
            }
        }
    }
}

// ── 设置弹窗 ──────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SettingsDialog(vm: IptvPlayerViewModel, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val aspectMode by vm.aspectRatioMode.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放设置") },
        text = {
            Column {
                Text("画面比例", style = TextStyle(color = scheme.onSurfaceVariant, fontSize = 13.sp))
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IptvPlayerViewModel.aspectRatioLabels.forEachIndexed { i, label ->
                        val selected = aspectMode == i
                        androidx.compose.material3.FilterChip(
                            selected = selected,
                            onClick = { vm.setAspectRatio(i) },
                            label = {
                                Text(
                                    label,
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        color = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
                                    )
                                )
                            }
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("解码方式", style = TextStyle(color = scheme.onSurfaceVariant, fontSize = 13.sp))
                Spacer(Modifier.height(8.dp))
                // 解码方式已迁移到全局设置，这里仅展示当前后端类型提示
                androidx.compose.material3.FilterChip(
                    selected = true,
                    onClick = {},
                    label = {
                        Text(
                            "当前：ExoPlayer · 硬件解码（默认）",
                            style = TextStyle(fontSize = 12.sp, color = scheme.onSecondaryContainer)
                        )
                    }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "如需切换解码方式，请到「设置 → 播放」",
                    style = TextStyle(color = scheme.onSurfaceVariant, fontSize = 11.sp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

// ── 频道导航 ──────────────────────────────────────────────────────────

private fun prevChannel(vm: IptvPlayerViewModel, list: List<M3uChannel>, currentName: String) {
    if (list.isEmpty()) return
    val idx = list.indexOfFirst { it.name == currentName }
    if (idx <= 0) return
    vm.selectChannel(list[idx - 1])
}

private fun nextChannel(vm: IptvPlayerViewModel, list: List<M3uChannel>, currentName: String) {
    if (list.isEmpty()) return
    val idx = list.indexOfFirst { it.name == currentName }
    if (idx < 0 || idx >= list.size - 1) return
    vm.selectChannel(list[idx + 1])
}
