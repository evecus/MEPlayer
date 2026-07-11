package com.mobile.meplayer.ui.music

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.mobile.meplayer.data.MusicGroupEntry
import com.mobile.meplayer.data.PermissionUtil
import com.mobile.meplayer.data.SongEntry
import com.mobile.meplayer.ui.navigation.AppNavigator
import com.mobile.meplayer.ui.widget.SearchDialog
import com.mobile.meplayer.ui.widget.TextBarButton
import com.mobile.meplayer.ui.widget.showOptionDialog
import kotlin.math.min

/**
 * 音乐库页面，对应 Flutter 的 `MusicLibraryPage`。
 *
 * - TopAppBar actions：搜索（图标+文字）、排序（文字）、分类（文字）。
 * - 歌曲分类时显示歌曲列表（单列）；分组分类时显示分组列表（单列）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicLibraryScreen(navController: NavHostController) {
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
    val coverVersion by vm.coverVersion.collectAsState()
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

    val songs = remember(category, sortSong, keyword, allSongs, coverVersion) {
        vm.sortedSongs()
    }
    val groups = remember(category, sortGroup, keyword, allSongs, coverVersion) {
        vm.sortedGroups()
    }

    // 封面分页加载：每页 200 首，滚动到未加载区域时增量加载
    val songListState = rememberLazyListState()
    val lastVisibleIndex by remember {
        derivedStateOf { songListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
    }
    // 分类/排序变化时重置为 0（与 ViewModel.resetCovers 呼应）
    var coverRequestedCount by remember(category, sortSong) { mutableIntStateOf(0) }
    LaunchedEffect(category, songs.isNotEmpty(), lastVisibleIndex, coverRequestedCount) {
        if (category != MusicCategory.SONG || songs.isEmpty()) return@LaunchedEffect
        if (coverRequestedCount >= songs.size) return@LaunchedEffect
        // 仍在已加载封面的缓冲区（提前 50 首预判）内则不加载
        if (coverRequestedCount > 0 && lastVisibleIndex >= 0 &&
            lastVisibleIndex < coverRequestedCount - 50
        ) return@LaunchedEffect
        val end = min(coverRequestedCount + 200, songs.size)
        vm.requestCovers(songs.subList(coverRequestedCount, end))
        coverRequestedCount = end
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("音乐") },
                actions = {
                    TextBarButton(label = "搜索", icon = Icons.Default.Search, onClick = { showSearch = true })
                    TextBarButton(label = "排序", icon = Icons.Default.Sort, onClick = { showSort = true })
                    TextBarButton(label = "分类", onClick = { showCategory = true })
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
                category == MusicCategory.SONG -> SongList(
                    songs = songs,
                    listState = songListState,
                    onClick = { index ->
                        // 用全局 holder 传递播放列表，避免 URL 超过 Intent 大小限制
                        PlaylistTransfer.playlist = songs.map { it.toMap() }
                        AppNavigator.toMusicPlayer(navController, "", index)
                    }
                )
                else -> GroupList(
                    groups = groups,
                    onClick = { e ->
                        PlaylistTransfer.groupSongs = e.songs.map { it.toMap() }
                        PlaylistTransfer.groupTitle = e.displayName
                        PlaylistTransfer.groupSort = sortSong
                        AppNavigator.toMusicGroup(navController, e.displayName, "", sortSong)
                    }
                )
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
            val options = listOf(
                "标题 A→Z", "标题 Z→A", "歌手 A→Z", "歌手 Z→A", "时间 旧→新", "时间 新→旧"
            )
            showOptionDialog(
                title = "歌曲排序",
                options = options,
                selected = sortSong,
                onSelected = { vm.setSortSong(it) },
                onDismiss = { showSort = false }
            )
        } else {
            val options = listOf("名称 A→Z", "名称 Z→A", "时间 旧→新", "时间 新→旧")
            showOptionDialog(
                title = "分组排序",
                options = options,
                selected = sortGroup - MusicGroupSort.NAME_ASC,
                onSelected = { vm.setSortGroup(it + MusicGroupSort.NAME_ASC) },
                onDismiss = { showSort = false }
            )
        }
    }
    if (showCategory) {
        val options = listOf("歌曲", "专辑", "歌手", "文件夹")
        showOptionDialog(
            title = "分类",
            options = options,
            selected = category,
            onSelected = { vm.setCategory(it) },
            onDismiss = { showCategory = false }
        )
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
private fun PermissionDeniedView(onGrant: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "需要存储权限才能扫描本地音乐",
                color = scheme.onSurface
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onGrant) { Text("授予权限") }
        }
    }
}

@Composable
private fun SongList(
    songs: List<SongEntry>,
    listState: LazyListState,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        itemsIndexed(songs) { index, song ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(index) }
                    .padding(12.dp)
            ) {
                CoverOrNote(coverBytes = song.coverBytes, size = 32.dp, iconSize = 20.dp)
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = song.artist.ifEmpty { "未知歌手" },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupList(
    groups: List<MusicGroupEntry>,
    onClick: (MusicGroupEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(12.dp)
    ) {
        items(groups) { e ->
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(e) }
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.size(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = e.displayName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.size(2.dp))
                        Text(
                            text = "${e.songs.size} 首",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(-90f)
                    )
                }
            }
        }
    }
}

/**
 * 封面/音符占位组件：有封面字节则显示封面，否则显示 [MusicNote] 图标。
 * 在音乐库、分组详情、播放器中共用。
 */
@Composable
fun CoverOrNote(
    coverBytes: ByteArray?,
    size: Dp,
    iconSize: Dp,
    modifier: Modifier = Modifier
) {
    val tint = MaterialTheme.colorScheme.primary
    if (coverBytes != null) {
        val bmp = remember(coverBytes) {
            runCatching {
                BitmapFactory.decodeByteArray(coverBytes, 0, coverBytes.size)
            }.getOrNull()
        }
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier
                    .size(size)
                    .clip(RoundedCornerShape(4.dp))
            )
            return
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Default.MusicNote,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(iconSize)
        )
    }
}
