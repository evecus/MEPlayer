package com.tv.meplayer.ui.settings

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import com.tv.meplayer.data.AudioMetadataReader
import com.tv.meplayer.data.LocalScanner
import com.tv.meplayer.data.PermissionUtil
import com.tv.meplayer.data.SongEntry
import com.tv.meplayer.data.StorageService
import com.tv.meplayer.data.toJsonArrayString
import com.tv.meplayer.ui.theme.TvSeedPresets
import com.tv.meplayer.ui.theme.TvThemeState
import com.tv.meplayer.ui.widget.ScanProgressDialog
import com.tv.meplayer.ui.widget.ScanProgressController
import com.tv.meplayer.ui.widget.TvFocusable
import com.tv.meplayer.ui.widget.showOptionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TV 端设置页：左侧栏 + 右侧设置项列表。
 *
 * 设置项：外观（主题模式/主题色）、存储权限、媒体扫描（扫描视频/扫描音乐）、关于。
 */
@Composable
fun TvSettingsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    var showThemeModeDialog by remember { mutableStateOf(false) }

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

    // ── 扫描状态 ──
    var progress by remember { mutableStateOf(ScanProgressController()) }
    var showScanDialog by remember { mutableStateOf(false) }
    var scanType by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(scheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 顶栏 ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text("设置", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = scheme.onBackground)
            }

            // ── 左侧栏 + 右侧内容 ──
            Row(modifier = Modifier.fillMaxSize()) {
                // 左侧栏
                Box(
                    modifier = Modifier
                        .width(240.dp)
                        .fillMaxHeight()
                        .background(scheme.surface)
                        .padding(24.dp)
                ) {
                    Column {
                        Icon(Icons.Default.Tv, contentDescription = null, tint = scheme.primary,
                            modifier = Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("MEPlayer", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                        Text("TV 版", fontSize = 14.sp, color = scheme.onSurfaceVariant)
                    }
                }

                // 右侧设置项
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ── 外观 ──
                    item { SettingsSectionTitle("外观") }
                    item {
                        SettingsRow(
                            icon = Icons.Default.Palette,
                            title = "主题模式",
                            subtitle = when (TvThemeState.mode) {
                                1 -> "亮色"; 2 -> "暗色"; else -> "跟随系统"
                            },
                            onClick = { showThemeModeDialog = true }
                        )
                    }
                    item { ThemeColorRow() }

                    // ── 存储权限 ──
                    item { SettingsSectionTitle("存储权限") }
                    item {
                        val permSubtitle = when (hasAllFilesAccess) {
                            null -> "检测中"
                            true -> "已开启"
                            false -> "如果设备插了SD卡，需要开启此项以扫描外置存储"
                        }
                        SettingsRow(
                            icon = Icons.Default.SdStorage,
                            title = "外部存储权限",
                            subtitle = permSubtitle,
                            trailing = {
                                if (hasAllFilesAccess == true) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null,
                                        tint = Color(0xFF4CAF50), modifier = Modifier.size(28.dp))
                                } else {
                                    Icon(Icons.Default.ChevronRight, contentDescription = null,
                                        tint = scheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                                }
                            },
                            onClick = { PermissionUtil.requestAllFilesAccess(context) }
                        )
                    }

                    // ── 媒体扫描 ──
                    item { SettingsSectionTitle("媒体扫描") }
                    item {
                        SettingsRow(
                            icon = Icons.Default.VideoLibrary,
                            title = "扫描视频",
                            subtitle = "重新扫描本地视频文件",
                            onClick = {
                                progress = ScanProgressController()
                                scanType = "video"
                                showScanDialog = true
                            }
                        )
                    }
                    item {
                        SettingsRow(
                            icon = Icons.Default.MusicNote,
                            title = "扫描音乐",
                            subtitle = "重新扫描本地音乐文件",
                            onClick = {
                                progress = ScanProgressController()
                                scanType = "music"
                                showScanDialog = true
                            }
                        )
                    }

                    // ── 关于 ──
                    item { SettingsSectionTitle("关于") }
                    item {
                        SettingsRow(
                            icon = Icons.Default.Info,
                            title = "应用信息",
                            subtitle = "MEPlayer 1.0.0"
                        )
                    }
                }
            }
        }
    }

    if (showThemeModeDialog) {
        showOptionDialog(
            title = "主题模式",
            options = listOf("跟随系统", "亮色", "暗色"),
            selected = TvThemeState.mode,
            onSelected = { TvThemeState.updateMode(it); showThemeModeDialog = false },
            onDismiss = { showThemeModeDialog = false }
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

    // ── 扫描执行（逻辑与手机端完全一致）──
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

@Composable
private fun SettingsSectionTitle(text: String) {
    val scheme = MaterialTheme.colorScheme
    Text(
        text = text,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = scheme.primary
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(scheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, color = scheme.onSurface, fontWeight = FontWeight.Medium)
                Text(subtitle, fontSize = 14.sp, color = scheme.onSurfaceVariant)
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
    if (onClick != null) {
        TvFocusable(onClick = onClick, cornerRadius = 12.dp) { content() }
    } else {
        content()
    }
}

@Composable
private fun ThemeColorRow() {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(20.dp)
    ) {
        Text("主题色", fontSize = 18.sp, color = scheme.onSurface, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvSeedPresets.forEach { (argb, label) ->
                val selected = TvThemeState.seedArgb == argb
                TvFocusable(
                    onClick = { TvThemeState.updateSeed(argb) },
                    cornerRadius = 24.dp,
                    scaleOnFocus = 1.15f
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(argb))
                            .border(
                                width = if (selected) 3.dp else 0.dp,
                                color = if (selected) scheme.onSurface else Color.Transparent,
                                shape = CircleShape
                            )
                    )
                }
            }
        }
    }
}
