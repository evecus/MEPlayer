package com.mobile.meplayer.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 顶栏文字按钮，对应 Flutter 的 `_TextBarButton`。
 *
 * - 14sp 文字，颜色跟随当前 onSurface（禁用时降低透明度）。
 * - [icon] 可选，传入时显示在文字左侧（用于"搜索"等带图标的按钮）。
 * - padding 8dp，点击有水波纹反馈。
 */
@Composable
fun TextBarButton(
    label: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val color = if (enabled) {
        scheme.onSurface
    } else {
        scheme.onSurface.copy(alpha = 0.38f)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = label,
            color = color,
            fontSize = 14.sp
        )
    }
}
