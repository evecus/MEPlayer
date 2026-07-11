package com.mobile.meplayer.ui.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.mobile.meplayer.data.AudioMetadataReader
import com.mobile.meplayer.data.SongEntry
import com.mobile.meplayer.data.parseStringMapList
import com.mobile.meplayer.ui.navigation.AppNavigator
import com.mobile.meplayer.ui.widget.TextBarButton
import com.mobile.meplayer.ui.widget.showOptionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 音乐分组详情页，对应 Flutter 的 `MusicGroupPage`。
 *
 * - 解析 [songsJson] 为歌曲列表并后台加载元数据。
 * - 单列列表，每项 Card + 左封面/音符 + 标题 + 副标题(歌手) + 右播放图标。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicGroupScreen(
    navController: NavHostController,
    title: String,
    songsJson: String,
    sort: Int
) {
    // 歌曲列表通过全局 PlaylistTransfer 传递（避免 URL 超限）；holder 为空时回退到 URL。
    var songs by remember {
        val fromHolder = PlaylistTransfer.groupSongs
        if (fromHolder != null) {
            PlaylistTransfer.groupSongs = null
            mutableStateOf(
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
            )
        } else {
            mutableStateOf(
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
            )
        }
    }
    var sortMode by remember { mutableStateOf(sort) }
    var showSort by remember { mutableStateOf(false) }

    // 后台加载元数据（标题/歌手/专辑/歌词/封面）
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

    val sorted = remember(songs, sortMode) { sortSongs(songs, sortMode) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    TextBarButton(label = "排序", icon = Icons.Default.Sort, onClick = { showSort = true })
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(12.dp)
        ) {
            itemsIndexed(sorted) { index, song ->
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            PlaylistTransfer.playlist = sorted.map { it.toMap() }
                            AppNavigator.toMusicPlayer(navController, "", index)
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(12.dp)
                    ) {
                        CoverOrNote(coverBytes = song.coverBytes, size = 44.dp, iconSize = 26.dp)
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
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "播放",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }

    if (showSort) {
        val options = listOf(
            "标题 A→Z", "标题 Z→A", "歌手 A→Z", "歌手 Z→A", "时间 旧→新", "时间 新→旧"
        )
        showOptionDialog(
            title = "歌曲排序",
            options = options,
            selected = sortMode,
            onSelected = { sortMode = it },
            onDismiss = { showSort = false }
        )
    }
}

private fun sortSongs(songs: List<SongEntry>, mode: Int): List<SongEntry> = when (mode) {
    MusicSongSort.TITLE_ASC -> songs.sortedBy { it.title.lowercase() }
    MusicSongSort.TITLE_DESC -> songs.sortedByDescending { it.title.lowercase() }
    MusicSongSort.ARTIST_ASC -> songs.sortedWith(
        compareBy({ it.artist.lowercase() }, { it.title.lowercase() })
    )
    MusicSongSort.ARTIST_DESC -> songs.sortedWith(
        compareByDescending<SongEntry> { it.artist.lowercase() }.thenByDescending { it.title.lowercase() }
    )
    MusicSongSort.TIME_ASC -> songs.sortedBy { it.modified }
    MusicSongSort.TIME_DESC -> songs.sortedByDescending { it.modified }
    else -> songs
}
