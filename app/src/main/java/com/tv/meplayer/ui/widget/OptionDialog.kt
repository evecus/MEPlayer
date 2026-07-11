package com.tv.meplayer.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 通用单选对话框，对应 Flutter 的 [option_dialog.dart]。
 *
 * 展示一个标题和一组选项，选中态用 primary 色的 RadioButton 图标。
 * 用户点击任意选项后立即通过 [onSelected] 回调所选索引并关闭对话框。
 */
@Composable
fun showOptionDialog(
    title: String,
    options: List<String>,
    selected: Int = 0,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(
                modifier = Modifier.padding(
                    start = 20.dp,
                    end = 20.dp,
                    top = 20.dp,
                    bottom = 12.dp
                )
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                options.forEachIndexed { i, option ->
                    val isSelected = i == selected
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(i) }
                            .padding(horizontal = 12.dp, vertical = 14.dp)
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelected(i) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = scheme.primary,
                                unselectedColor = scheme.onSurfaceVariant
                            )
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) scheme.primary else scheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
