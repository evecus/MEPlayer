package com.tv.meplayer.ui.music

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.tv.meplayer.data.MusicGroupEntry
import com.tv.meplayer.data.PermissionUtil
import com.tv.meplayer.data.SongEntry
import com.tv.meplayer.ui.navigation.AppNavigator
import com.tv.meplayer.ui.widget.SearchDialog
import com.tv.meplayer.ui.widget.TvFocusable
import com.tv.meplayer.ui.widget.TvPlaybackEntry
import com.tv.meplayer.ui.widget.showOptionDialog

/**
 * TV 端音乐库屏幕。
 *
 * - 顶栏：标题 + 搜索/排序/分类按钮
 * - 分类标签栏：歌曲/专辑/歌手/文件夹
 * - 5 列网格：歌曲卡片（封面+标题+歌手）或分组卡片（图标+名称+数量）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvMusicLibraryScreen(navController: NavHostController) {
    val vm: MusicLibraryViewModel = viewModel()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.init() }

    val hasPermission by vm.hasPermission.collectAsState()
    val permissionRequested by vm.permissionRequested.collectAsState()
    val isScanning by vm.isScanning.collectAsState()
    val category by vm.currentCategory.collectAsState()
    val sortSong by vm.currentSortSong.collectAsState()
    val sortGroup by vm.currentSortGroup.collectAsState()
    val keyword by vm.searchKeyword.collectAsState()
    val allSongs by vm.allSongs.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it } || PermissionUtil.hasMediaPermissions(context)
        vm.onPermissionResult(granted)
    }
    LaunchedEffect(hasPermission, permissionRequested) {
        if (!hasPermission && !permissionRequested) {
            permLauncher.launch(PermissionUtil.mediaPermissions())
        }
    }

    var showSearch by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showCategory by remember { mutableStateOf(false) }

    val songs = remember(category, sortSong, keyword, allSongs) { vm.sortedSongs() }
    val groups = remember(category, sortGroup, keyword, allSongs) { vm.sortedGroups() }

    val scheme = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音乐", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                actions = {
                    TvPlaybackEntry(navController = navController)
                    Spacer(Modifier.width(8.dp))
                    TvFocusable(onClick = { showSearch = true }, cornerRadius = 8.dp) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = scheme.onSurface)
                            Spacer(Modifier.width(6.dp))
                            Text("搜索", fontSize = 14.sp, color = scheme.onSurface)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TvFocusable(onClick = { showSort = true }, cornerRadius = 8.dp) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Sort, contentDescription = null, tint = scheme.onSurface)
                            Spacer(Modifier.width(6.dp))
                            Text("排序", fontSize = 14.sp, color = scheme.onSurface)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    TvFocusable(onClick = { showCategory = true }, cornerRadius = 8.dp) {
                        Text(
                            "分类",
                            fontSize = 14.sp,
                            color = scheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
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
                val tabs = listOf("歌曲" to MusicCategory.SONG, "专辑" to MusicCategory.ALBUM,
                    "歌手" to MusicCategory.ARTIST, "文件夹" to MusicCategory.FOLDER)
                tabs.forEach { (label, cat) ->
                    val selected = category == cat
                    TvFocusable(
                        onClick = { vm.setCategory(cat) },
                        cornerRadius = 8.dp
                    ) {
                        Text(
                            text = label,
                            fontSize = 16.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            // ── 内容区 ──
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    isScanning -> LoadingView("扫描中…")
                    !hasPermission && permissionRequested -> PermissionGuide {
                        permLauncher.launch(PermissionUtil.mediaPermissions())
                    }
                    !hasPermission -> LoadingView("准备中…")
                    category == MusicCategory.SONG -> {
                        if (songs.isEmpty()) {
                            EmptyHint("暂无音乐")
                        } else {
                            SongGrid(
                                songs = songs,
                                onItemClick = { index ->
                                    PlaylistTransfer.playlist = songs.map { it.toMap() }
                                    AppNavigator.toMusicPlayer(navController, "", index)
                                }
                            )
                        }
                    }
                    else -> {
                        if (groups.isEmpty()) {
                            EmptyHint("暂无分组")
                        } else {
                            GroupGrid(
                                groups = groups,
                                onItemClick = { e ->
                                    PlaylistTransfer.groupSongs = e.songs.map { it.toMap() }
                                    PlaylistTransfer.groupTitle = e.displayName
                                    PlaylistTransfer.groupSort = sortSong
                                    AppNavigator.toMusicGroup(navController, e.displayName, "", sortSong)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSearch) {
        SearchDialog(
            title = "搜索",
            hintText = "搜索歌曲 / 歌手 / 专辑",
            initialText = keyword,
            onChanged = { vm.setSearchKeyword(it) },
            onDismiss = { showSearch = false }
        )
    }
    if (showSort) {
        if (category == MusicCategory.SONG) {
            showOptionDialog(
                title = "歌曲排序",
                options = listOf("标题 A→Z", "标题 Z→A", "歌手 A→Z", "歌手 Z→A", "时间 旧→新", "时间 新→旧"),
                selected = sortSong,
                onSelected = { vm.setSortSong(it); showSort = false },
                onDismiss = { showSort = false }
            )
        } else {
            showOptionDialog(
                title = "分组排序",
                options = listOf("名称 A→Z", "名称 Z→A", "时间 旧→新", "时间 新→旧"),
                selected = sortGroup - MusicGroupSort.NAME_ASC,
                onSelected = { vm.setSortGroup(it + MusicGroupSort.NAME_ASC); showSort = false },
                onDismiss = { showSort = false }
            )
        }
    }
    if (showCategory) {
        showOptionDialog(
            title = "分类",
            options = listOf("歌曲", "专辑", "歌手", "文件夹"),
            selected = category,
            onSelected = { vm.setCategory(it); showCategory = false },
            onDismiss = { showCategory = false }
        )
    }
}

@Composable
private fun SongGrid(songs: List<SongEntry>, onItemClick: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(songs) { index, song ->
            TvFocusable(
                onClick = { onItemClick(index) },
                autoFocus = index == 0,
                cornerRadius = 12.dp
            ) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(scheme.surface),
                            contentAlignment = Alignment.Center
                        ) {
                            val cover = song.coverBytes
                            if (cover != null) {
                                val bmp = remember(cover) {
                                    BitmapFactory.decodeByteArray(cover, 0, cover.size)
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(Icons.Default.MusicNote, null, tint = scheme.primary,
                                        modifier = Modifier.size(48.dp))
                                }
                            } else {
                                Icon(Icons.Default.MusicNote, null, tint = scheme.primary,
                                    modifier = Modifier.size(48.dp))
                            }
                        }
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(
                                text = song.title.ifEmpty { song.name.substringBeforeLast('.', song.name) },
                                fontSize = 16.sp,
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = song.artist.ifEmpty { "未知歌手" },
                                fontSize = 14.sp,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupGrid(groups: List<MusicGroupEntry>, onItemClick: (MusicGroupEntry) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(groups) { index, g ->
            TvFocusable(
                onClick = { onItemClick(g) },
                autoFocus = index == 0,
                cornerRadius = 12.dp
            ) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Icon(
                            imageVector = Icons.Default.LibraryMusic,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = g.displayName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${g.songs.size} 首",
                            fontSize = 14.sp,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

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
private fun PermissionGuide(onGrant: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("需要存储权限才能扫描本地音乐", color = scheme.onSurface)
            Spacer(Modifier.height(16.dp))
            TvFocusable(onClick = onGrant, autoFocus = true, cornerRadius = 8.dp) {
                Text("授予权限", modifier = Modifier.padding(16.dp, 8.dp))
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = 18.sp, color = scheme.onSurfaceVariant)
    }
}
