package com.tv.meplayer.ui.widget

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * TV 端可聚焦组件的统一包装器。
 *
 * 负责：
 * 1. D-pad 方向键拦截 —— 方向键交给 Compose 默认几何寻路（返回 false 让事件继续传播）；
 *    Enter/OK/Space 触发 [onClick]；Back 由系统处理。
 * 2. 焦点视觉 —— 聚焦时缩放 1.08 + 主题色边框 2dp + 主题色光晕阴影。
 * 3. 触摸兜底 —— 支持点击（手机/平板也能用）。
 *
 * 用法：
 * ```
 * TvFocusable(onClick = { /* 确认操作 */ }) {
 *     // 卡片内容
 * }
 * ```
 */
@Composable
fun TvFocusable(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = false,
    scaleOnFocus: Float = 1.08f,
    cornerRadius: Dp = 12.dp,
    content: @Composable () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val scheme = MaterialTheme.colorScheme
    var isFocused by remember { mutableStateOf(false) }

    val animatedScale by animateFloatAsState(
        targetValue = if (isFocused) scaleOnFocus else 1.0f,
        animationSpec = tween(durationMillis = 180),
        label = "tv_focus_scale"
    )

    // 自动获取焦点
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            runCatching { focusRequester.requestFocus() }
        }
    }

    Box(
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { state -> isFocused = state.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onClick?.invoke()
                        true
                    }
                    // 方向键返回 false，交给 Compose 默认方向寻路（基于几何位置找最近邻）
                    Key.DirectionUp, Key.DirectionDown,
                    Key.DirectionLeft, Key.DirectionRight -> false
                    else -> false
                }
            }
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            }
            .shadow(
                elevation = if (isFocused) 12.dp else 0.dp,
                shape = RoundedCornerShape(cornerRadius),
                ambientColor = scheme.primary.copy(alpha = 0.6f),
                spotColor = scheme.primary.copy(alpha = 0.8f)
            )
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) scheme.primary else Color.Transparent,
                shape = RoundedCornerShape(cornerRadius)
            )
            .background(
                color = if (isFocused) scheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                shape = RoundedCornerShape(cornerRadius)
            )
    ) {
        content()
    }
}
