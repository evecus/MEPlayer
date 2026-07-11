package com.mobile.meplayer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mobile.meplayer.controller.AppSettings
import com.mobile.meplayer.data.AudioMetadataReader
import com.mobile.meplayer.data.LocalScanner
import com.mobile.meplayer.data.PermissionUtil
import com.mobile.meplayer.data.SongEntry
import com.mobile.meplayer.data.StorageService
import com.mobile.meplayer.data.toJsonArrayString
import com.mobile.meplayer.ui.widget.ScanProgressDialog
import com.mobile.meplayer.ui.widget.ScanProgressController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置页，对应 Flutter 的 `settings_page.dart`。
 *
 * 作为首页第四个 Tab 展示，不需要 navController。布局使用 Scaffold + LazyColumn，
 * 分为：外观 / 播放 / 存储权限 / 媒体扫描 / 关于 五个分区。
 *
 * 由于 Kotlin 版统一使用 ExoPlayer（固定硬件解码，无 MPV 后端），原 Flutter 版
 * 的播放器后端切换、MPV 解码方式、兼容模式、画质预设等 MPV 相关选项已移除。
 *
 * 状态直接来自全局单例 [AppSettings.controller]，主题色/模式切换会通过
 * MainActivity 的主题包裹响应式生效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val ctrl = AppSettings.controller
    val scheme = MaterialTheme.colorScheme

    val themeMode by ctrl.themeMode.collectAsState()
    val seedColor by ctrl.seedColor.collectAsState()

    // ── 外部存储权限状态：null=检测中 / true=已开启 / false=未开启 ──
    var hasAllFilesAccess by remember { mutableStateOf<Boolean?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAllFilesAccess = PermissionUtil.hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { hasAllFilesAccess = PermissionUtil.hasAllFilesAccess() }

    // ── BottomSheet 显隐 ──
    var showColorSheet by remember { mutableStateOf(false) }

    // ── 扫描状态 ──
    var progress by remember { mutableStateOf(ScanProgressController()) }
    var showScanDialog by remember { mutableStateOf(false) }
    var scanType by remember { mutableStateOf("") }

    // ── 版本号 ──
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── 外观 ──
            item { sectionHeader("外观") }
            item {
                SegmentedSettingItem(
                    icon = Icons.Filled.Brightness6,
                    title = "主题模式",
                    options = listOf("跟随系统", "浅色", "深色"),
                    selected = themeMode,
                    onSelect = { ctrl.setThemeMode(it) }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Filled.Palette,
                    title = "主题色",
                    trailing = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(seedColor))
                        )
                    },
                    onClick = { showColorSheet = true }
                )
            }

            // ── 播放 ──
            item { sectionHeader("播放") }
            item {
                SettingsItem(
                    icon = Icons.Filled.Memory,
                    title = "解码方式",
                    subtitle = "ExoPlayer 默认硬件解码",
                    trailing = {
                        Text("硬解", color = scheme.onSurfaceVariant)
                    }
                )
            }

            // ── 存储权限 ──
            item { sectionHeader("存储权限") }
            item {
                val permSubtitle = when (hasAllFilesAccess) {
                    null -> "检测中"
                    true -> "已开启"
                    false -> "如果设备插了SD卡，需要开启此项以扫描外置存储"
                }
                SettingsItem(
                    icon = Icons.Filled.SdStorage,
                    title = "外部存储权限",
                    subtitle = permSubtitle,
                    trailing = {
                        if (hasAllFilesAccess == true) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50)
                            )
                        } else {
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = scheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = { PermissionUtil.requestAllFilesAccess(context) }
                )
            }
            item {
                Text(
                    text = "仅使用内部存储无需开启此项",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 2.dp, bottom = 6.dp)
                )
            }

            // ── 媒体扫描 ──
            item { sectionHeader("媒体扫描") }
            item {
                SettingsItem(
                    icon = Icons.Filled.VideoLibrary,
                    title = "扫描视频",
                    subtitle = "重新扫描本地视频文件",
                    trailing = {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant
                        )
                    },
                    onClick = {
                        progress = ScanProgressController()
                        scanType = "video"
                        showScanDialog = true
                    }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Filled.MusicNote,
                    title = "扫描音乐",
                    subtitle = "重新扫描本地音乐文件",
                    trailing = {
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant
                        )
                    },
                    onClick = {
                        progress = ScanProgressController()
                        scanType = "music"
                        showScanDialog = true
                    }
                )
            }

            // ── 关于 ──
            item { sectionHeader("关于") }
            item {
                SettingsItem(
                    icon = Icons.Filled.Info,
                    title = "FlutterPlayer 版本号",
                    trailing = {
                        Text("v${versionName}", color = scheme.onSurfaceVariant)
                    }
                )
            }
            item {
                SettingsItem(
                    icon = Icons.Filled.Code,
                    title = "基于 ExoPlayer",
                    subtitle = "支持几乎所有音视频格式"
                )
            }
        }
    }

    // ── 主题色选择 ──
    if (showColorSheet) {
        ColorPickerSheet(
            current = seedColor,
            onPick = { ctrl.setSeedColor(it) },
            onDismiss = { showColorSheet = false }
        )
    }

    // ── 扫描进度弹窗 ──
    if (showScanDialog) {
        ScanProgressDialog(
            title = if (scanType == "video") "扫描视频" else "扫描音乐",
            controller = progress,
            onDismiss = { showScanDialog = false }
        )
    }

    // ── 扫描执行 ──
    // key 包含 progress 实例本身：点击扫描时 progress 被替换为新实例，
    // LaunchedEffect 会以新的闭包重启，确保 progressCtrl 指向最新的 controller。
    LaunchedEffect(showScanDialog, scanType, progress) {
        if (!showScanDialog) return@LaunchedEffect
        val progressCtrl = progress
        try {
            if (scanType == "video") {
                val videos = withContext(Dispatchers.IO) {
                    LocalScanner.scanVideos(context) { progressCtrl.appendLine(it) }
                }
                StorageService.setJsonArray(
                    StorageService.kVideoLibraryCache,
                    videos.toJsonArrayString()
                )
                progressCtrl.appendLine("共扫描到 ${videos.size} 个视频")
            } else {
                val songs = withContext(Dispatchers.IO) {
                    LocalScanner.scanAudios(context) { progressCtrl.appendLine(it) }
                        .map { a ->
                            SongEntry(a.path, a.name, a.folder, a.size, a.modified).apply {
                                try {
                                    val meta = AudioMetadataReader.readFile(a.path)
                                    meta.title?.let { if (it.isNotEmpty()) title = it }
                                    meta.artist?.let { if (it.isNotEmpty()) artist = it }
                                    meta.album?.let { if (it.isNotEmpty()) album = it }
                                    lyrics = meta.lyrics.orEmpty()
                                    metadataLoaded = true
                                } catch (_: Throwable) {
                                    // 单个文件元数据读取失败不影响整体扫描
                                }
                            }
                        }
                }
                StorageService.setJsonArray(
                    StorageService.kMusicLibraryCacheAndroid,
                    songs.toJsonArrayString()
                )
                progressCtrl.appendLine("共扫描到 ${songs.size} 首音乐")
            }
        } catch (t: Throwable) {
            progressCtrl.appendLine("扫描出错: ${t.message ?: "未知错误"}")
        }
        progressCtrl.complete()
    }
}

// ── 辅助 Composable ─────────────────────────────────────────────────────

/** 分区标题：primary 色、加粗、带上下间距。 */
@Composable
private fun sectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp)
    )
}

/**
 * 通用设置项（对应 Flutter 的 ListTile）。
 *
 * 左侧图标 + 标题/副标题，右侧可选 trailing，整体可点击。底部带细分隔线。
 */
@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            color = scheme.outlineVariant
        )
    }
}

/**
 * 分段选择设置项：图标 + 标题在上一行，SingleChoiceSegmentedButtonRow 在下一行，
 * 可选 footer 说明文字。底部带细分隔线。
 */
@Composable
private fun SegmentedSettingItem(
    icon: ImageVector,
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    footer: String? = null
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = index == selected,
                        onClick = { onSelect(index) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size)
                    ) {
                        Text(label)
                    }
                }
            }
            if (footer != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp),
            color = scheme.outlineVariant
        )
    }
}

/**
 * 主题色选择 BottomSheet：8 种预设色，选中态显示对勾。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorPickerSheet(
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = remember {
        listOf(
            0xFF3498DB.toInt(), 0xFF2ECC71.toInt(), 0xFFE74C3C.toInt(), 0xFF9B59B6.toInt(),
            0xFFF39C12.toInt(), 0xFF1ABC9C.toInt(), 0xFFE91E63.toInt(), 0xFF607D8B.toInt()
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = "选择主题色",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
        )
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            colors.chunked(4).forEach { rowColors ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    rowColors.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .clickable {
                                    onPick(c)
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (c == current) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
