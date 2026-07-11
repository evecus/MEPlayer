package com.tv.meplayer.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * 渲染 ExoPlayer 画面的 Composable，对应 Flutter 版 ExoBackend.buildView。
 *
 * 使用 [PlayerView]（useController=false，禁用内置控制条），通过 [AndroidView]
 * 嵌入 Compose。画面铺满整个区域并以 [fill] 填充背景色，resizeMode 固定为
 * [AspectRatioFrameLayout.RESIZE_MODE_FIT]。
 *
 * [fit] 参数保留用于与 Flutter 版 buildView 的 fit 形参对齐，实际缩放由
 * PlayerView 的 resizeMode 控制（FIT）。
 */
@OptIn(UnstableApi::class)
@Composable
fun ExoPlayerView(
    backend: ExoPlayerBackend,
    modifier: Modifier = Modifier,
    fill: Color = Color.Black,
    fit: ContentScale = ContentScale.Fit,
) {
    AndroidView(
        modifier = modifier.fillMaxSize().background(fill),
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                player = backend.player
            }
        },
        update = { view ->
            if (view.player !== backend.player) {
                view.player = backend.player
            }
        },
    )
}
