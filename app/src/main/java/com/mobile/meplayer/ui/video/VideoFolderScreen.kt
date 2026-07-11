package com.mobile.meplayer.ui.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.mobile.meplayer.data.parseStringMapList
import com.mobile.meplayer.ui.navigation.AppNavigator
import com.mobile.meplayer.ui.widget.GlideVideoThumbnail
import com.mobile.meplayer.ui.widget.TextBarButton
import com.mobile.meplayer.ui.widget.showOptionDialog

/**
 * 视频文件夹页面，对应 Flutter 的 `VideoFolderPage`。
 *
 * 展示某个文件夹下的视频列表，支持按名称/时间排序，点击进入播放器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoFolderScreen(
    navController: NavHostController,
    folderPath: String,
    folderName: String,
    sort: Int,
    videosJson: String
) {
    val scheme = MaterialTheme.colorScheme

    val parsed = remember(videosJson) { parseStringMapList(videosJson) }
    var currentSort by remember(sort) { mutableStateOf(sort) }
    var showSortDialog by remember { mutableStateOf(false) }

    val videos = remember(parsed, currentSort) { sortFolderVideos(parsed, currentSort) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(folderName.ifEmpty { "文件夹" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    TextBarButton(label = "排序") { showSortDialog = true }
                }
            )
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val columns = if (maxWidth >= 600.dp) 2 else 1
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(videos) { index, item ->
                    FolderVideoCard(
                        item = item,
                        onClick = {
                            AppNavigator.toVideoPlayer(
                                navController = navController,
                                playlistJson = videosJson,
                                index = index
                            )
                        }
                    )
                }
            }
        }
    }

    if (showSortDialog) {
        showOptionDialog(
            title = "排序",
            options = listOf("名称 A-Z", "名称 Z-A", "时间升序", "时间降序"),
            selected = currentSort,
            onSelected = { currentSort = it; showSortDialog = false },
            onDismiss = { showSortDialog = false }
        )
    }
}

@Composable
private fun FolderVideoCard(
    item: Map<String, String>,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val name = item["name"] ?: ""
    val path = item["path"] ?: ""
    val title = name.substringBeforeLast('.', name)

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
            // 左 44dp 缩略图：用 Glide 加载视频帧，自动磁盘缓存
            GlideVideoThumbnail(
                videoPath = path,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = name,
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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

/** 对文件夹内视频项按指定排序返回新列表。 */
private fun sortFolderVideos(
    items: List<Map<String, String>>,
    sort: Int
): List<Map<String, String>> {
    return when (sort) {
        VideoSort.NAME_ASC -> items.sortedBy { (it["name"] ?: "").lowercase() }
        VideoSort.NAME_DESC -> items.sortedByDescending { (it["name"] ?: "").lowercase() }
        VideoSort.TIME_ASC -> items.sortedBy { (it["modified"] ?: "0").toLongOrNull() ?: 0L }
        VideoSort.TIME_DESC -> items.sortedByDescending { (it["modified"] ?: "0").toLongOrNull() ?: 0L }
        else -> items
    }
}
