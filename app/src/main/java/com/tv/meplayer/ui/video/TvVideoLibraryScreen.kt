package com.tv.meplayer.ui.video

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.tv.meplayer.data.PermissionUtil
import com.tv.meplayer.data.ScannedVideo
import com.tv.meplayer.data.VideoFolderEntry
import com.tv.meplayer.data.toJsonArrayString
import com.tv.meplayer.ui.navigation.AppNavigator
import com.tv.meplayer.ui.widget.GlideVideoThumbnail
import com.tv.meplayer.ui.widget.SearchDialog
import com.tv.meplayer.ui.widget.TvFocusable
import com.tv.meplayer.ui.widget.TvPlaybackEntry
import com.tv.meplayer.ui.widget.showOptionDialog

/**
 * TV 端视频库屏幕，对应 Flutter TV 端的 `VideoLibraryPage`。
 *
 * 布局：顶栏（标题 + 搜索/分类/排序）+ 分类标签栏（视频 / 文件夹）+ 5 列网格内容区。
 * - VIDEO 分类：每张卡片为 Glide 缩略图 + 文件名，点击以单条视频播放列表进入播放器。
 * - FOLDER 分类：每张卡片为文件夹图标 + 名称 + 视频数，点击进入文件夹详情页。
 *
 * 状态：扫描中显示 LoadingView；权限拒绝显示引导；所有可聚焦元素均用 [TvFocusable] 包裹。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvVideoLibraryScreen(navController: NavHostController) {
    val vm: VideoLibraryViewModel = viewModel()

    val allVideos by vm.allVideos.collectAsState()
    val folderEntries by vm.folderEntries.collectAsState()
    val isScanning by vm.isScanning.collectAsState()
    val hasPermission by vm.hasPermission.collectAsState()
    val permissionRequested by vm.permissionRequested.collectAsState()
    val currentCategory by vm.currentCategory.collectAsState()
    val currentSortVideo by vm.currentSortVideo.collectAsState()
    val currentSortFolder by vm.currentSortFolder.collectAsState()
    val searchKeyword by vm.searchKeyword.collectAsState()

    LaunchedEffect(Unit) { vm.init() }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.any { it }
        vm.onPermissionResult(granted)
    }

    LaunchedEffect(hasPermission, permissionRequested) {
        if (!hasPermission && !permissionRequested) {
            permLauncher.launch(PermissionUtil.mediaPermissions())
        }
    }

    var showSearchDialog by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var showSortDialog by remember { mutableStateOf(false) }

    val videos = remember(allVideos, currentSortVideo, searchKeyword) { vm.sortedVideos() }
    val folders = remember(folderEntries, currentSortFolder, searchKeyword) { vm.sortedFolders() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "视频",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    TvPlaybackEntry(navController = navController)
                    Spacer(Modifier.width(8.dp))
                    TopBarAction(Icons.Default.Search, "搜索") { showSearchDialog = true }
                    Spacer(Modifier.width(8.dp))
                    TopBarAction(Icons.Default.Category, "分类") { showCategoryDialog = true }
                    Spacer(Modifier.width(8.dp))
                    TopBarAction(Icons.Default.Sort, "排序") { showSortDialog = true }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── 分类标签栏 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CategoryTab(
                    label = "视频",
                    selected = currentCategory == VideoCategory.VIDEO
                ) { vm.setCategory(VideoCategory.VIDEO) }
                CategoryTab(
                    label = "文件夹",
                    selected = currentCategory == VideoCategory.FOLDER
                ) { vm.setCategory(VideoCategory.FOLDER) }
            }

            // ── 内容区 ──
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    !hasPermission -> PermissionGuide(
                        onRequest = { permLauncher.launch(PermissionUtil.mediaPermissions()) }
                    )
                    isScanning && allVideos.isEmpty() && folderEntries.isEmpty() -> LoadingView()
                    currentCategory == VideoCategory.VIDEO -> {
                        if (videos.isEmpty()) {
                            EmptyView("暂无视频")
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(5),
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(videos.size) { index ->
                                    val v = videos[index]
                                    VideoCard(
                                        video = v,
                                        autoFocus = index == 0
                                    ) {
                                        val playlistJson = listOf(
                                            mapOf("path" to v.path, "name" to v.name)
                                        ).toJsonArrayString()
                                        AppNavigator.toVideoPlayer(navController, playlistJson, 0)
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        if (folders.isEmpty()) {
                            EmptyView("暂无文件夹")
                        } else {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(5),
                                contentPadding = PaddingValues(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(folders.size) { index ->
                                    val f = folders[index]
                                    FolderCard(
                                        folder = f,
                                        autoFocus = index == 0
                                    ) {
                                        val videosJson = f.videos.map {
                                            mapOf("path" to it.path, "name" to it.name)
                                        }.toJsonArrayString()
                                        AppNavigator.toVideoFolder(
                                            navController,
                                            f.path,
                                            f.name,
                                            currentSortVideo,
                                            videosJson
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 对话框 ──
    if (showSearchDialog) {
        SearchDialog(
            title = "搜索视频",
            hintText = "输入文件名关键词",
            initialText = searchKeyword,
            onChanged = { vm.searchKeyword.value = it },
            onDismiss = { showSearchDialog = false }
        )
    }

    if (showCategoryDialog) {
        showOptionDialog(
            title = "分类",
            options = listOf("视频", "文件夹"),
            selected = currentCategory,
            onSelected = {
                vm.setCategory(if (it == 0) VideoCategory.VIDEO else VideoCategory.FOLDER)
                showCategoryDialog = false
            },
            onDismiss = { showCategoryDialog = false }
        )
    }

    if (showSortDialog) {
        if (currentCategory == VideoCategory.FOLDER) {
            showOptionDialog(
                title = "文件夹排序",
                options = listOf("名称 A-Z", "名称 Z-A"),
                selected = currentSortFolder,
                onSelected = {
                    vm.setSortFolder(it)
                    showSortDialog = false
                },
                onDismiss = { showSortDialog = false }
            )
        } else {
            showOptionDialog(
                title = "视频排序",
                options = listOf("名称 A-Z", "名称 Z-A", "时间升序", "时间降序"),
                selected = currentSortVideo,
                onSelected = {
                    vm.setSortVideo(it)
                    showSortDialog = false
                },
                onDismiss = { showSortDialog = false }
            )
        }
    }
}

/**
 * 顶栏操作按钮：图标 + 文字，用 [TvFocusable] 包裹以支持 D-pad 聚焦与确认。
 */
@Composable
private fun TopBarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    TvFocusable(onClick = onClick, cornerRadius = 8.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = scheme.onSurface,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = scheme.onSurface,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * 分类标签：选中态用 primary 色加粗。
 */
@Composable
private fun CategoryTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    TvFocusable(onClick = onClick, cornerRadius = 8.dp) {
        Text(
            text = label,
            fontSize = 16.sp,
            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/**
 * 视频卡片：Glide 缩略图（160dp）+ 文件名，整体用 [TvFocusable] 包裹。
 * 卡片高度约 200dp（缩略图 160dp + 文字区域）。
 */
@Composable
private fun VideoCard(video: ScannedVideo, autoFocus: Boolean, onClick: () -> Unit) {
    TvFocusable(
        onClick = onClick,
        autoFocus = autoFocus,
        modifier = Modifier.fillMaxWidth()
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                GlideVideoThumbnail(
                    videoPath = video.path,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
                Text(
                    text = video.name,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

/**
 * 文件夹卡片：文件夹图标 + 文件夹名 + 视频数量。
 */
@Composable
private fun FolderCard(folder: VideoFolderEntry, autoFocus: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    TvFocusable(
        onClick = onClick,
        autoFocus = autoFocus,
        modifier = Modifier.fillMaxWidth()
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = folder.name,
                    fontSize = 16.sp,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${folder.videos.size} 个视频",
                    fontSize = 14.sp,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 扫描中加载视图。 */
@Composable
private fun LoadingView() {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "扫描中…",
                color = scheme.onSurfaceVariant,
                fontSize = 16.sp
            )
        }
    }
}

/** 空状态视图。 */
@Composable
private fun EmptyView(message: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = scheme.onSurfaceVariant,
            fontSize = 16.sp
        )
    }
}

/** 权限拒绝引导视图：提示文字 + 授予权限按钮（自动获焦）。 */
@Composable
private fun PermissionGuide(onRequest: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "需要存储权限才能扫描本地视频",
                color = scheme.onSurface,
                fontSize = 18.sp
            )
            Spacer(Modifier.height(16.dp))
            TvFocusable(onClick = onRequest, autoFocus = true, cornerRadius = 8.dp) {
                Text(
                    text = "授予权限",
                    color = scheme.onPrimary,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }
    }
}
