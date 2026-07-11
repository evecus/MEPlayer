package com.tv.meplayer.ui.music

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.tv.meplayer.data.AudioMetadataReader
import com.tv.meplayer.data.SongEntry
import com.tv.meplayer.data.parseStringMapList
import com.tv.meplayer.ui.navigation.AppNavigator
import com.tv.meplayer.ui.widget.TvFocusable
import com.tv.meplayer.ui.widget.showOptionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TV 端音乐分组详情页（某专辑/歌手/文件夹下的歌曲列表）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvMusicGroupScreen(
    navController: NavHostController,
    title: String,
    songsJson: String,
    sort: Int
) {
    val scheme = MaterialTheme.colorScheme

    var songs by remember(songsJson) {
        val fromHolder = PlaylistTransfer.groupSongs
        val list = if (fromHolder != null) {
            fromHolder.map { m ->
                SongEntry(
                    path = m["path"] ?: "",
                    name = m["name"] ?: "",
                    folder = "",
                    size = 0L,
                    modified = 0L
                ).apply {
                    lyrics = m["lyrics"] ?: ""
                    coverPath = m["coverPath"] ?: ""
                }
            }
        } else {
            parseStringMapList(songsJson).map { m ->
                SongEntry(
                    path = m["path"] ?: "",
                    name = m["name"] ?: "",
                    folder = "",
                    size = 0L,
                    modified = 0L
                ).apply {
                    lyrics = m["lyrics"] ?: ""
                    coverPath = m["coverPath"] ?: ""
                }
            }
        }
        mutableStateOf(list)
    }
    var sortMode by remember { mutableStateOf(sort) }
    var showSort by remember { mutableStateOf(false) }

    // 进入后立即消费 holder，防止返回时误用旧数据
    LaunchedEffect(Unit) { PlaylistTransfer.groupSongs = null }

    // 后台加载元数据
    LaunchedEffect(songsJson) {
        val list = songs
        withContext(Dispatchers.IO) {
            list.forEachIndexed { i, s ->
                val meta = AudioMetadataReader.readFile(s.path)
                s.title = meta.title?.takeIf { it.isNotEmpty() }
                    ?: s.name.substringBeforeLast('.', s.name)
                s.artist = meta.artist ?: ""
                s.album = meta.album ?: ""
                if (s.lyrics.isEmpty()) s.lyrics = meta.lyrics ?: ""
                s.coverBytes = meta.coverBytes
                s.metadataLoaded = true
                if ((i + 1) % 10 == 0 || i == list.lastIndex) {
                    withContext(Dispatchers.Main) { songs = list.toList() }
                }
            }
        }
    }

    val sorted = remember(songs, sortMode) { sortGroupSongs(songs, sortMode) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title.ifEmpty { "分组" }, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    TvFocusable(onClick = { navController.popBackStack() }, autoFocus = true, cornerRadius = 8.dp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回",
                            tint = scheme.onSurface, modifier = Modifier.padding(12.dp))
                    }
                },
                actions = {
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
                }
            )
        }
    ) { padding ->
        if (sorted.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("暂无歌曲", fontSize = 18.sp, color = scheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(sorted) { index, song ->
                    TvFocusable(
                        onClick = {
                            PlaylistTransfer.playlist = sorted.map { it.toMap() }
                            AppNavigator.toMusicPlayer(navController, "", index)
                        },
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
    }

    if (showSort) {
        showOptionDialog(
            title = "排序",
            options = listOf("标题 A→Z", "标题 Z→A", "歌手 A→Z", "歌手 Z→A", "时间 旧→新", "时间 新→旧"),
            selected = sortMode,
            onSelected = { sortMode = it; showSort = false },
            onDismiss = { showSort = false }
        )
    }
}

/** 对分组内歌曲按指定排序返回新列表。 */
private fun sortGroupSongs(items: List<SongEntry>, sort: Int): List<SongEntry> {
    return when (sort) {
        0 -> items.sortedBy { it.title.ifEmpty { it.name }.lowercase() }
        1 -> items.sortedByDescending { it.title.ifEmpty { it.name }.lowercase() }
        2 -> items.sortedBy { it.artist.lowercase() }
        3 -> items.sortedByDescending { it.artist.lowercase() }
        4 -> items.sortedBy { it.modified }
        5 -> items.sortedByDescending { it.modified }
        else -> items
    }
}
