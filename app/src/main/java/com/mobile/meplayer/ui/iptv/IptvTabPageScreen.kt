package com.mobile.meplayer.ui.iptv

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.mobile.meplayer.ui.navigation.AppNavigator

/**
 * IPTV 源管理页（底部导航第二个 Tab），对应 Flutter 的 `IptvTabPage`。
 *
 * - Scaffold body 显示源列表
 * - 空状态：居中 live_tv 图标 80dp + 提示 + 导入按钮
 * - 有源时：自适应列数的卡片网格（手机 1 列，平板 2~3 列），卡片宽高比 3.4
 * - 右下角 FAB.extended「导入」按钮，点击弹出编辑对话框（新建模式）
 */
@Composable
fun IptvTabPageScreen(
    navController: NavHostController,
    vm: IptvSourcesViewModel = viewModel()
) {
    val sources by vm.sources.collectAsState()
    val refreshing by vm.isRefreshing.collectAsState()

    var showEditDialog by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var editExisting by remember { mutableStateOf<Map<String, String>?>(null) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var deleteName by remember { mutableStateOf("") }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    editIndex = null
                    editExisting = null
                    showEditDialog = true
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("导入") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { padding ->
        if (sources.isEmpty()) {
            EmptyState(
                modifier = Modifier.padding(padding),
                onImport = {
                    editIndex = null
                    editExisting = null
                    showEditDialog = true
                }
            )
        } else {
            SourceGrid(
                sources = sources,
                isRefreshing = refreshing,
                modifier = Modifier.padding(padding),
                onTap = { i, src ->
                    AppNavigator.toIptvPlayer(
                        navController = navController,
                        url = "",
                        channelName = src["name"] ?: "IPTV",
                        groupName = "",
                        sourceIndex = i
                    )
                },
                onEdit = { i, src ->
                    editIndex = i
                    editExisting = src
                    showEditDialog = true
                },
                onDelete = { i, src ->
                    deleteIndex = i
                    deleteName = src["name"] ?: ""
                },
                onRefresh = { i -> vm.refreshSource(i) { _, _ -> } }
            )
        }
    }

    // 删除确认对话框
    deleteIndex?.let { idx ->
        DeleteConfirmDialog(
            name = deleteName,
            onConfirm = {
                vm.removeSource(idx)
                deleteIndex = null
                deleteName = ""
            },
            onDismiss = {
                deleteIndex = null
                deleteName = ""
            }
        )
    }

    // 编辑/导入对话框
    if (showEditDialog) {
        SourceEditDialog(
            existing = editExisting,
            onSave = { src ->
                val idx = editIndex
                if (idx != null) vm.updateSource(idx, src) else vm.addSource(src)
                showEditDialog = false
                editIndex = null
                editExisting = null
            },
            onDismiss = {
                showEditDialog = false
                editIndex = null
                editExisting = null
            }
        )
    }
}

// ── 空状态 ─────────────────────────────────────────────────────────────

@Composable
private fun EmptyState(modifier: Modifier = Modifier, onImport: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.LiveTv,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = scheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(text = "暂无 IPTV 源", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "点击右下角「导入」按钮添加源",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onImport) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("导入 IPTV 源")
        }
    }
}

// ── 源网格 ─────────────────────────────────────────────────────────────

@Composable
private fun SourceGrid(
    sources: List<Map<String, String>>,
    isRefreshing: Map<Int, Boolean>,
    modifier: Modifier = Modifier,
    onTap: (Int, Map<String, String>) -> Unit,
    onEdit: (Int, Map<String, String>) -> Unit,
    onDelete: (Int, Map<String, String>) -> Unit,
    onRefresh: (Int) -> Unit
) {
    val minCardWidth = 300.dp
    val spacing = 12.dp
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 手机 1 列，平板 2~3 列自适应
        val cols = ((maxWidth + spacing) / (minCardWidth + spacing))
            .toInt()
            .coerceIn(1, 3)
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            items(sources.size) { i ->
                val src = sources[i]
                SourceCard(
                    source = src,
                    isRefreshing = isRefreshing[i] == true,
                    onTap = { onTap(i, src) },
                    onEdit = { onEdit(i, src) },
                    onDelete = { onDelete(i, src) },
                    onRefresh = { onRefresh(i) }
                )
            }
        }
    }
}

// ── 源卡片 ─────────────────────────────────────────────────────────────

@Composable
private fun SourceCard(
    source: Map<String, String>,
    isRefreshing: Boolean,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val isNetwork = source["type"] != "file"
    val autoUpdate = source["autoUpdate"] == "true"
    val name = source["name"] ?: ""

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(3.4f)
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧 40dp 图标
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isNetwork) scheme.primaryContainer.copy(alpha = 0.47f)
                        else scheme.secondaryContainer.copy(alpha = 0.47f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isNetwork) Icons.Filled.WifiTethering else Icons.Filled.FolderOpen,
                    contentDescription = null,
                    tint = if (isNetwork) scheme.primary else scheme.secondary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            // 中间信息列
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(
                        label = if (isNetwork) "远程" else "文件",
                        color = if (isNetwork) scheme.primary else scheme.secondary
                    )
                    if (autoUpdate && isNetwork) {
                        Badge(label = "自动更新", color = scheme.tertiary)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            // 右侧操作按钮
            if (isNetwork) {
                if (isRefreshing) {
                    Box(
                        modifier = Modifier.size(36.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    }
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新源", modifier = Modifier.size(20.dp))
                    }
                }
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "编辑", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.DeleteOutline,
                    contentDescription = "删除",
                    tint = scheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun Badge(label: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(4.dp),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, color.copy(alpha = 0.31f))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = TextStyle(fontSize = 10.sp, color = color)
        )
    }
}

// ── 删除确认对话框 ─────────────────────────────────────────────────────

@Composable
private fun DeleteConfirmDialog(
    name: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除源") },
        text = { Text("确定要删除「$name」吗？") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.error,
                    contentColor = scheme.onError
                )
            ) { Text("删除") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ── 编辑/导入对话框 ────────────────────────────────────────────────────

@Composable
private fun SourceEditDialog(
    existing: Map<String, String>?,
    onSave: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val isEdit = existing != null
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current

    var name by remember { mutableStateOf(existing?.get("name") ?: "") }
    var importType by remember { mutableStateOf(existing?.get("type") ?: "network") }
    var url by remember { mutableStateOf(existing?.get("url") ?: "") }
    var filePath by remember { mutableStateOf(existing?.get("filePath") ?: "") }
    var fileName by remember { mutableStateOf(existing?.get("fileName") ?: "") }
    var autoUpdate by remember { mutableStateOf(existing?.get("autoUpdate") == "true") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    // 文件选择器
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // 某些情况下无法获取持久权限，仍可在本次会话读取
            }
            val displayName = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else ""
                }
            }.getOrNull().orEmpty()
            filePath = uri.toString()
            fileName = if (displayName.isNotEmpty()) displayName else (uri.lastPathSegment ?: "")
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = scheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .padding(20.dp)
            ) {
                Text(
                    text = if (isEdit) "编辑 IPTV 源" else "导入 IPTV 源",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(20.dp))

                // 名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = null },
                    label = { Text("名称") },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                // 导入方式切换
                Text("导入方式", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeToggle(
                        label = "远程导入",
                        icon = Icons.Filled.WifiTethering,
                        selected = importType == "network",
                        modifier = Modifier.weight(1f),
                        onTap = { importType = "network" }
                    )
                    TypeToggle(
                        label = "文件导入",
                        icon = Icons.Filled.FolderOpen,
                        selected = importType == "file",
                        modifier = Modifier.weight(1f),
                        onTap = { importType = "file" }
                    )
                }
                Spacer(Modifier.height(16.dp))

                // URL 输入 / 文件选择
                if (importType == "network") {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("M3U 链接") },
                        placeholder = { Text("http://example.com/playlist.m3u") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp))
                            .clickable { pickFile.launch(arrayOf("*/*")) }
                            .padding(horizontal = 12.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (fileName.isEmpty()) "点击选择本地 m3u/m3u8/txt 文件" else fileName,
                            color = if (fileName.isEmpty()) scheme.onSurfaceVariant else scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                // 自动更新（仅 network）
                if (importType == "network") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启动时自动更新", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = autoUpdate, onCheckedChange = { autoUpdate = it })
                    }
                }
                Spacer(Modifier.height(20.dp))

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val n = name.trim()
                            when {
                                n.isEmpty() -> { nameError = "名称不能为空"; return@Button }
                                importType == "network" && url.trim().isEmpty() -> {
                                    nameError = "请输入 M3U 链接"; return@Button
                                }
                                importType == "file" && filePath.isEmpty() -> {
                                    nameError = "请选择本地文件"; return@Button
                                }
                            }
                            saving = true
                            val src = mutableMapOf(
                                "name" to n,
                                "type" to importType,
                                "autoUpdate" to autoUpdate.toString()
                            )
                            if (importType == "network") {
                                src["url"] = url.trim()
                            } else {
                                src["filePath"] = filePath
                                if (fileName.isNotEmpty()) src["fileName"] = fileName
                            }
                            onSave(src)
                            saving = false
                        },
                        enabled = !saving
                    ) {
                        if (saving) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp), color = scheme.onPrimary)
                        } else {
                            Text(if (isEdit) "保存" else "导入")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeToggle(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val color = if (selected) scheme.primary else scheme.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onTap)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color))
    }
}
