package com.tv.meplayer.ui.iptv

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.tv.meplayer.ui.navigation.AppNavigator
import com.tv.meplayer.ui.widget.TvFocusable
import com.tv.meplayer.ui.widget.TvPlaybackEntry

/**
 * TV 端 IPTV 源管理页：卡片网格 + 导入源按钮。
 *
 * - 空源时显示居中引导
 * - 源卡片：整卡点击=播放 + 刷新/编辑/删除按钮
 * - 导入/编辑共用一个对话框，支持远程/文件导入 + autoUpdate 勾选
 */
@Composable
fun TvIptvLibraryScreen(navController: NavHostController) {
    val vm: IptvSourcesViewModel = viewModel()
    val scheme = MaterialTheme.colorScheme
    val sources by vm.sources.collectAsState()
    val isRefreshingMap by vm.isRefreshing.collectAsState()

    // 编辑对话框状态：editIndex=null=新建，非null=编辑
    var showEditDialog by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var editExisting by remember { mutableStateOf<Map<String, String>?>(null) }

    // 删除确认
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var deleteName by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(scheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶栏 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("IPTV", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
                Spacer(Modifier.weight(1f))
                TvPlaybackEntry(navController = navController)
                Spacer(Modifier.width(12.dp))
                TvFocusable(
                    onClick = {
                        editIndex = null
                        editExisting = null
                        showEditDialog = true
                    },
                    autoFocus = sources.isEmpty(),
                    cornerRadius = 8.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = scheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("导入源", fontSize = 16.sp, color = scheme.primary)
                    }
                }
            }

            // ── 内容区 ──
            if (sources.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.LiveTv,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(80.dp)
                        )
                        Spacer(Modifier.height(24.dp))
                        Text("暂无IPTV源", fontSize = 24.sp, color = scheme.onSurface)
                        Spacer(Modifier.height(8.dp))
                        Text("请点击导入", fontSize = 18.sp, color = scheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
                ) {
                    items(sources.size) { idx ->
                        val src = sources[idx]
                        SourceCard(
                            source = src,
                            isRefreshing = isRefreshingMap[idx] == true,
                            onTap = {
                                AppNavigator.toIptvPlayer(
                                    navController = navController,
                                    url = "",
                                    channelName = "",
                                    groupName = "",
                                    sourceIndex = idx
                                )
                            },
                            onEdit = {
                                editIndex = idx
                                editExisting = src
                                showEditDialog = true
                            },
                            onDelete = {
                                deleteIndex = idx
                                deleteName = src["name"] ?: ""
                            },
                            onRefresh = { vm.refreshSource(idx) { _, _ -> } },
                            autoFocus = idx == 0
                        )
                    }
                }
            }
        }
    }

    // 编辑/导入对话框
    if (showEditDialog) {
        SourceEditDialog(
            existing = editExisting,
            onDismiss = {
                showEditDialog = false
                editIndex = null
                editExisting = null
            },
            onSave = { src ->
                val idx = editIndex
                if (idx != null) vm.updateSource(idx, src) else vm.addSource(src)
                showEditDialog = false
                editIndex = null
                editExisting = null
            }
        )
    }

    // 删除确认
    if (deleteIndex != null) {
        AlertDialog(
            onDismissRequest = { deleteIndex = null },
            title = { Text("删除源") },
            text = { Text("确定要删除「$deleteName」吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        deleteIndex?.let { vm.removeSource(it) }
                        deleteIndex = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.error,
                        contentColor = scheme.onError
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteIndex = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 源卡片：整卡可聚焦点击=播放，右侧刷新/编辑/删除按钮。
 */
@Composable
private fun SourceCard(
    source: Map<String, String>,
    isRefreshing: Boolean,
    onTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
    autoFocus: Boolean
) {
    val scheme = MaterialTheme.colorScheme
    val isNetwork = source["type"] != "file"
    val autoUpdate = source["autoUpdate"] == "true"
    val name = source["name"] ?: ""
    val url = source["url"] ?: source["filePath"] ?: ""

    TvFocusable(onClick = onTap, autoFocus = autoFocus, cornerRadius = 12.dp) {
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp)
            ) {
                // 左侧图标
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Badge(label = if (isNetwork) "远程" else "文件",
                            color = if (isNetwork) scheme.primary else scheme.secondary)
                        if (autoUpdate && isNetwork) {
                            Badge(label = "自动更新", color = scheme.tertiary)
                        }
                    }
                    if (url.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = url,
                            fontSize = 12.sp,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 右侧操作按钮
                if (isNetwork) {
                    if (isRefreshing) {
                        Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        TvFocusable(onClick = onRefresh, cornerRadius = 8.dp) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新源",
                                tint = scheme.primary, modifier = Modifier.padding(8.dp).size(20.dp))
                        }
                    }
                }
                TvFocusable(onClick = onEdit, cornerRadius = 8.dp) {
                    Icon(Icons.Outlined.Edit, contentDescription = "编辑",
                        tint = scheme.onSurface, modifier = Modifier.padding(8.dp).size(20.dp))
                }
                TvFocusable(onClick = onDelete, cornerRadius = 8.dp) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "删除",
                        tint = scheme.error, modifier = Modifier.padding(8.dp).size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun Badge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text = label, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

/**
 * 导入/编辑共用对话框：支持远程导入/文件导入切换 + autoUpdate 勾选。
 */
@Composable
private fun SourceEditDialog(
    existing: Map<String, String>?,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val isEdit = existing != null

    var name by remember { mutableStateOf(existing?.get("name") ?: "") }
    var importType by remember { mutableStateOf(existing?.get("type") ?: "network") }
    var url by remember { mutableStateOf(existing?.get("url") ?: "") }
    var filePath by remember { mutableStateOf(existing?.get("filePath") ?: "") }
    var fileName by remember { mutableStateOf(existing?.get("fileName") ?: "") }
    var autoUpdate by remember { mutableStateOf(existing?.get("autoUpdate") == "true") }
    var nameError by remember { mutableStateOf<String?>(null) }

    // 文件选择 launcher
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑 IPTV 源" else "导入 IPTV 源") },
        text = {
            Column {
                // 名称
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; nameError = null },
                    label = { Text("名称") },
                    isError = nameError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (nameError != null) {
                    Text(nameError!!, fontSize = 12.sp, color = scheme.error)
                }
                Spacer(Modifier.height(12.dp))

                // 导入方式切换
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TypeToggle(
                        label = "远程导入",
                        icon = Icons.Filled.WifiTethering,
                        selected = importType == "network",
                        onClick = { importType = "network" }
                    )
                    TypeToggle(
                        label = "文件导入",
                        icon = Icons.Filled.FolderOpen,
                        selected = importType == "file",
                        onClick = { importType = "file" }
                    )
                }
                Spacer(Modifier.height(12.dp))

                // URL 输入 或 文件选择
                if (importType == "network") {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("M3U 链接") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = scheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { pickFile.launch(arrayOf("*/*")) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Icon(Icons.Default.AttachFile, contentDescription = null, tint = scheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (fileName.isNotEmpty()) fileName else "点击选择本地 m3u/m3u8/txt 文件",
                                fontSize = 14.sp,
                                color = if (fileName.isNotEmpty()) scheme.onSurface else scheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 自动更新勾选框（仅网络源）
                if (importType == "network") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("启动时自动更新", modifier = Modifier.weight(1f), fontSize = 16.sp)
                        Switch(checked = autoUpdate, onCheckedChange = { autoUpdate = it })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
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
            }) { Text(if (isEdit) "保存" else "导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun TypeToggle(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    TvFocusable(onClick = onClick, cornerRadius = 8.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (selected) scheme.primaryContainer
                    else scheme.surfaceVariant
                )
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Icon(icon, contentDescription = null,
                tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 14.sp,
                color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
        }
    }
}
