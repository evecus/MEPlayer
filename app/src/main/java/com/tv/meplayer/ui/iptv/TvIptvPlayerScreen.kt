package com.tv.meplayer.ui.iptv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
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
import com.tv.meplayer.player.ExoPlayerView
import com.tv.meplayer.ui.widget.TvFocusable
import kotlinx.coroutines.delay

/**
 * TV 端 IPTV 全屏播放页。
 *
 * - 播放器全屏占满
 * - 频道列表默认隐藏，按 OK 键唤出左侧三列弹窗（分组/频道/线路）
 * - 弹窗 5 秒无操作自动隐藏
 * - 返回键：弹窗显示时先关弹窗，隐藏时退出页面
 */
@Composable
fun TvIptvPlayerScreen(
    navController: NavHostController,
    url: String = "",
    channelName: String = "",
    groupName: String = "",
    sourceIndex: Int = 0
) {
    val vm: IptvPlayerViewModel = viewModel()
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(Unit) { vm.init(url, channelName, groupName, sourceIndex) }

    val isBuffering by vm.isBuffering.collectAsState()
    val isLoading by vm.isLoading.collectAsState()
    val chName by vm.channelName.collectAsState()
    val browseGroup by vm.browseGroup.collectAsState()
    val playingGroup by vm.playingGroup.collectAsState()
    val streamUrls by vm.streamUrls.collectAsState()
    val streamIndex by vm.streamIndex.collectAsState()

    var showPanel by remember { mutableStateOf(false) }

    // 弹窗 5 秒自动隐藏
    LaunchedEffect(showPanel, browseGroup, chName, streamIndex) {
        if (showPanel) {
            delay(5000)
            showPanel = false
        }
    }

    // 返回键：弹窗显示时先关弹窗，隐藏时退出
    BackHandler {
        if (showPanel) showPanel = false
        else navController.popBackStack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        showPanel = !showPanel
                        true
                    }
                    else -> false
                }
            }
    ) {
        // 1. 全屏视频画面
        ExoPlayerView(
            backend = vm.backend,
            modifier = Modifier.fillMaxSize(),
            fill = Color.Black
        )

        // 2. 加载/缓冲指示器
        if (isLoading || isBuffering) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }

        // 3. 顶部信息栏（LIVE 徽章 + 频道名）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                    )
                )
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // LIVE 徽章
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFFE74C3C))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("LIVE", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = chName.ifEmpty { "正在加载..." },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 4. 底部提示栏
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                    )
                )
                .padding(16.dp)
        ) {
            Text(
                text = if (showPanel) "方向键切换  OK 确认  返回键关闭面板"
                       else "OK 唤出频道列表  返回键退出",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
        }

        // 5. 左侧三列频道弹窗（按需显示）
        if (showPanel) {
            ChannelPanel(
                vm = vm,
                scheme = scheme,
                browseGroup = browseGroup,
                playingGroup = playingGroup,
                chName = chName,
                streamUrls = streamUrls,
                streamIndex = streamIndex,
                onChannelSelected = { showPanel = false }
            )
        }
    }
}

/**
 * 左侧三列频道弹窗：分组 | 频道 | 线路。
 *
 * 居中偏左浮层，半透明背景 + 主色边框。
 */
@Composable
private fun ChannelPanel(
    vm: IptvPlayerViewModel,
    scheme: androidx.compose.material3.ColorScheme,
    browseGroup: String,
    playingGroup: String,
    chName: String,
    streamUrls: List<String>,
    streamIndex: Int,
    onChannelSelected: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 48.dp)
                .width(720.dp)
                .height(520.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(scheme.surface.copy(alpha = 0.95f))
                .border(2.dp, scheme.primary, RoundedCornerShape(12.dp))
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                // 分组列
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    PanelHeader("分组", scheme)
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(vm.groups.size) { idx ->
                            val g = vm.groups[idx]
                            val selected = g == browseGroup || g == playingGroup
                            PanelItem(
                                text = g,
                                selected = selected,
                                autoFocus = idx == 0,
                                onClick = { vm.browseGroup.value = g }
                            )
                        }
                    }
                }
                // 分隔线
                Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
                // 频道列
                Column(
                    modifier = Modifier
                        .weight(1.4f)
                        .fillMaxHeight()
                ) {
                    PanelHeader("频道", scheme)
                    val channels = vm.browsedChannels
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(channels.size) { idx ->
                            val ch = channels[idx]
                            val selected = ch.name == chName
                            PanelItem(
                                text = ch.name,
                                selected = selected,
                                autoFocus = idx == 0,
                                onClick = {
                                    vm.selectChannel(ch)
                                    onChannelSelected()
                                }
                            )
                        }
                    }
                }
                // 线路列（仅多线路时显示）
                if (streamUrls.size > 1) {
                    Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(scheme.outlineVariant))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        PanelHeader("线路", scheme)
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(streamUrls.size) { idx ->
                                PanelItem(
                                    text = "源${idx + 1}",
                                    selected = idx == streamIndex,
                                    autoFocus = idx == 0,
                                    onClick = { vm.selectStream(idx) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(text: String, scheme: androidx.compose.material3.ColorScheme) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surfaceVariant)
            .padding(12.dp)
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PanelItem(
    text: String,
    selected: Boolean,
    autoFocus: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    TvFocusable(
        onClick = onClick,
        autoFocus = autoFocus,
        cornerRadius = 6.dp,
        scaleOnFocus = 1.04f
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (selected) {
                Icon(Icons.Default.PlayArrow, contentDescription = null,
                    tint = scheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            } else {
                Spacer(Modifier.width(20.dp))
            }
            Text(
                text = text,
                fontSize = 14.sp,
                color = if (selected) scheme.primary else scheme.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


