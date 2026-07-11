package com.mobile.meplayer.ui.widget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 扫描过程日志的驱动器，对应 Flutter 的 [scan_progress_dialog.dart]。
 *
 * 调用方在扫描开始前创建一个实例传给 [ScanProgressDialog]，扫描过程中不断调用
 * [appendLine] 追加一行日志，扫描结束后调用 [complete] 让弹窗把"确定"按钮从
 * 禁用变为可点击。
 */
class ScanProgressController {
    val lines = mutableStateListOf<String>()

    var finished by mutableStateOf(false)
        private set

    /** 追加一行日志，仅保留最近 500 行用于展示，不影响扫描本身。 */
    fun appendLine(line: String) {
        lines.add(line)
        if (lines.size > 500) {
            lines.removeAt(0)
        }
    }

    /** 标记扫描完成，使"确定"按钮变为可点击。 */
    fun complete() {
        finished = true
    }
}

/**
 * 扫描进度弹窗（视频/音乐扫描通用），对应 Flutter 的 [scan_progress_dialog.dart]。
 *
 * - 未完成时显示 CircularProgressIndicator + 确定按钮禁用。
 * - 完成后显示 check_circle 图标 + 按钮可点击，点击后关闭弹窗。
 * - 日志列表用 reverse ListView，最新内容自动可见。
 * - 弹窗在未完成时不允许通过返回键/点击遮罩关闭。
 */
@Composable
fun ScanProgressDialog(
    title: String,
    controller: ScanProgressController,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    Dialog(onDismissRequest = { if (controller.finished) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.widthIn(min = 280.dp, max = 420.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // 标题行：未完成显示进度圈，完成显示 check_circle
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!controller.finished) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (controller.finished) "${title}完成" else title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // 日志区域
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = scheme.surfaceContainerHighest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 280.dp)
                ) {
                    if (controller.lines.isEmpty()) {
                        Text(
                            text = "准备扫描…",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    } else {
                        // reverse: 最新日志在底部，自动滚动可见
                        LazyColumn(
                            reverseLayout = true,
                            modifier = Modifier.padding(12.dp)
                        ) {
                            items(controller.lines.size) { i ->
                                val line = controller.lines[controller.lines.size - 1 - i]
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))

                // 确定按钮：未完成时禁用
                Button(
                    onClick = onDismiss,
                    enabled = controller.finished,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("确定")
                }
            }
        }
    }
}
