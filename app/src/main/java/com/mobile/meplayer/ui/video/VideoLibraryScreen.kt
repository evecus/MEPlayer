package com.mobile.meplayer.ui.video

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.mobile.meplayer.data.PermissionUtil
import com.mobile.meplayer.data.ScannedVideo
import com.mobile.meplayer.data.VideoFolderEntry
import com.mobile.meplayer.data.toJsonArrayString
import com.mobile.meplayer.ui.navigation.AppNavigator
import com.mobile.meplayer.ui.widget.GlideVideoThumbnail
import com.mobile.meplayer.ui.widget.SearchDialog
import com.mobile.meplayer.ui.widget.TextBarButton
import com.mobile.meplayer.ui.widget.showOptionDialog
import java.io.File

/**
 * 视频库页面，对应 Flutter 的 `VideoLibraryPage`。
 *
 * 顶栏提供搜索 / 分类 / 排序 / 链接播放；正文依据权限与扫描状态显示加载圈、
 * 权限拒绝引导或内容区（视频列表 / 文件夹列表）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoLibraryScreen(navController: NavHostController) {
    val vm: VideoLibraryViewModel = viewModel()
    val context = LocalContext.current

    val hasPermission by vm.hasPermission.collectAsState()
    val permissionRequested by vm.permissionRequested.collectAsState()
    val isScanning by vm.isScanning.collectAsState()
    val category by vm.currentCategory.collectAsState()
    val sortVideo by vm.currentSortVideo.collectAsState()
    val sortFolder by vm.currentSortFolder.collectAsState()
    val allVideos by vm.allVideos.collectAsState()
    val folders by vm.folderEntries.collectAsState()
    val keyword by vm.searchKeyword.collectAsState()

    var showSearch by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it } || PermissionUtil.hasAnyStorageAccess(context)
        vm.onPermissionResult(granted)
    }

    LaunchedEffect(Unit) { vm.init() }
    LaunchedEffect(hasPermission, permissionRequested) {
        if (!hasPermission && !permissionRequested) {
            permLauncher.launch(PermissionUtil.mediaPermissions())
        }
    }

    val videos = remember(allVideos, keyword, sortVideo) { vm.sortedVideos() }
    val folderList = remember(folders, keyword, sortFolder) { vm.sortedFolders() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("视频") },
                actions = {
                    TextBarButton(label = "搜索", icon = Icons.Default.Search) { showSearch = true }
                    TextBarButton(label = "分类") { showCategoryDialog = true }
                    TextBarButton(label = "排序") { showSortDialog = true }
                    IconButton(onClick = { showLinkDialog = true }) {
                        Icon(Icons.Default.Link, contentDescription = "链接播放")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isScanning -> LoadingView("扫描中…")
                !hasPermission && permissionRequested -> PermissionDeniedView {
                    permLauncher.launch(PermissionUtil.mediaPermissions())
                }
                !hasPermission -> LoadingView("准备中…")
                else -> when (category) {
                    VideoCategory.FOLDER -> FolderListContent(
                        entries = folderList,
                        onItemClick = { e ->
                            AppNavigator.toVideoFolder(
                                navController = navController,
                                folderPath = e.path,
                                folderName = e.name,
                                sort = sortVideo,
                                videosJson = e.videos.toJsonArrayString()
                            )
                        }
                    )
                    else -> VideoListContent(
                        videos = videos,
                        onItemClick = { v ->
                            AppNavigator.toVideoPlayer(
                                navController = navController,
                                playlistJson = listOf(
                                    mapOf("path" to v.path, "name" to v.name)
                                ).toJsonArrayString(),
                                index = 0
                            )
                        }
                    )
                }
            }
        }
    }

    if (showSearch) {
        SearchDialog(
            title = "搜索视频",
            hintText = "输入文件名",
            initialText = keyword,
            onChanged = { vm.searchKeyword.value = it },
            onDismiss = { showSearch = false }
        )
    }

    if (showCategoryDialog) {
        showOptionDialog(
            title = "分类",
            options = listOf("视频", "文件夹"),
            selected = category,
            onSelected = { vm.setCategory(it); showCategoryDialog = false },
            onDismiss = { showCategoryDialog = false }
        )
    }

    if (showSortDialog) {
        if (category == VideoCategory.FOLDER) {
            showOptionDialog(
                title = "排序",
                options = listOf("名称 A-Z", "名称 Z-A"),
                selected = sortFolder,
                onSelected = { vm.setSortFolder(it); showSortDialog = false },
                onDismiss = { showSortDialog = false }
            )
        } else {
            showOptionDialog(
                title = "排序",
                options = listOf("名称 A-Z", "名称 Z-A", "时间升序", "时间降序"),
                selected = sortVideo,
                onSelected = { vm.setSortVideo(it); showSortDialog = false },
                onDismiss = { showSortDialog = false }
            )
        }
    }

    if (showLinkDialog) {
        LinkPlayDialog(
            onConfirm = { url ->
                showLinkDialog = false
                if (url.isNotBlank()) {
                    AppNavigator.toVideoPlayer(
                        navController = navController,
                        playlistJson = listOf(
                            mapOf("path" to url, "name" to url)
                        ).toJsonArrayString(),
                        index = 0
                    )
                }
            },
            onDismiss = { showLinkDialog = false }
        )
    }
}

// ── 内容区 ───────────────────────────────────────────────

@Composable
private fun VideoListContent(
    videos: List<ScannedVideo>,
    onItemClick: (ScannedVideo) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 600.dp) 2 else 1
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(videos) { v ->
                VideoCard(video = v, onClick = { onItemClick(v) })
            }
        }
    }
}

@Composable
private fun VideoCard(video: ScannedVideo, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp)
        ) {
            // 左 36dp 缩略图：用 Glide 加载视频帧，自动磁盘缓存
            GlideVideoThumbnail(
                videoPath = video.path,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.name,
                    fontSize = 14.sp,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatFileSize(video.size),
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = "播放",
                tint = scheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun FolderListContent(
    entries: List<VideoFolderEntry>,
    onItemClick: (VideoFolderEntry) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 600.dp) 2 else 1
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(entries) { e ->
                FolderCard(entry = e, onClick = { onItemClick(e) })
            }
        }
    }
}

@Composable
private fun FolderCard(entry: VideoFolderEntry, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(8.dp)
        ) {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entry.videos.size} 个视频",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "进入",
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── 状态视图 ─────────────────────────────────────────────

@Composable
private fun LoadingView(text: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text, color = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionDeniedView(onGrant: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "需要存储权限才能扫描本地视频",
                color = scheme.onSurface
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onGrant) { Text("授予权限") }
        }
    }
}

@Composable
private fun LinkPlayDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("链接播放") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = { Text("输入视频 URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(url) }) { Text("播放") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
