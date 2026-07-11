package com.tv.meplayer.ui.video

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.navigation.NavHostController
import com.tv.meplayer.data.parseStringMapList
import com.tv.meplayer.ui.navigation.AppNavigator
import com.tv.meplayer.ui.widget.GlideVideoThumbnail
import com.tv.meplayer.ui.widget.TvFocusable
import com.tv.meplayer.ui.widget.showOptionDialog

/**
 * TV 端文件夹详情页，对应 Flutter TV 端的 `VideoFolderPage`。
 *
 * 展示某文件夹下的视频列表（5 列网格）。从导航参数接收 [videosJson]（整个文件夹的视频），
 * 解析后按当前排序展示。点击某视频时，以整个文件夹的 [videosJson] 作为播放列表进入播放器，
 * index 定位到该视频在原始列表中的位置。
 *
 * 排序为页面本地状态（初始值来自导航参数 [sort]），切换通过 [showOptionDialog] 完成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvVideoFolderScreen(
    navController: NavHostController,
    folderPath: String,
    folderName: String,
    sort: Int,
    videosJson: String
) {
    val scheme = MaterialTheme.colorScheme

    var currentSort by remember { mutableStateOf(sort) }
    var showSortDialog by remember { mutableStateOf(false) }

    val parsed = remember(videosJson) { parseStringMapList(videosJson) }
    val sortedVideos = remember(parsed, currentSort) { sortFolderVideos(parsed, currentSort) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = folderName.ifEmpty { "文件夹" },
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    TvFocusable(
                        onClick = { navController.popBackStack() },
                        cornerRadius = 8.dp
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = scheme.onSurface,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(20.dp)
                        )
                    }
                },
                actions = {
                    TopBarAction(Icons.Default.Sort, "排序") { showSortDialog = true }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (sortedVideos.isEmpty()) {
                EmptyView("文件夹为空")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(sortedVideos.size) { index ->
                        val item = sortedVideos[index]
                        val path = item["path"].orEmpty()
                        val name = item["name"].orEmpty()
                        FolderVideoCard(
                            path = path,
                            name = name,
                            autoFocus = index == 0
                        ) {
                            // 以整个文件夹的 videosJson 作为播放列表；index 定位到该视频
                            // 在原始（未排序）列表中的位置，保证播放器选中正确条目。
                            val originalIndex = parsed.indexOfFirst { it["path"] == path }
                                .coerceAtLeast(0)
                            AppNavigator.toVideoPlayer(navController, videosJson, originalIndex)
                        }
                    }
                }
            }
        }
    }

    if (showSortDialog) {
        showOptionDialog(
            title = "排序",
            options = listOf("名称 A-Z", "名称 Z-A", "时间升序", "时间降序"),
            selected = currentSort,
            onSelected = {
                currentSort = it
                showSortDialog = false
            },
            onDismiss = { showSortDialog = false }
        )
    }
}

/**
 * 对文件夹内的视频列表排序，参考手机端 `VideoFolderScreen` 的逻辑。
 *
 * [VideoSort] 常量：0=名称升序 1=名称降序 2=时间升序 3=时间降序。
 * 列表项为 [parseStringMapList] 解析出的 Map，"modified" 字段为字符串形式的时间戳。
 */
private fun sortFolderVideos(
    list: List<Map<String, String>>,
    sort: Int
): List<Map<String, String>> {
    return when (sort) {
        VideoSort.NAME_ASC -> list.sortedBy { it["name"]?.lowercase().orEmpty() }
        VideoSort.NAME_DESC -> list.sortedByDescending { it["name"]?.lowercase().orEmpty() }
        VideoSort.TIME_ASC -> list.sortedBy { it["modified"]?.toLongOrNull() ?: 0L }
        VideoSort.TIME_DESC -> list.sortedByDescending { it["modified"]?.toLongOrNull() ?: 0L }
        else -> list
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
 * 文件夹详情页视频卡片：Glide 缩略图（160dp）+ 文件名，整体用 [TvFocusable] 包裹。
 * 卡片高度约 200dp（缩略图 160dp + 文字区域）。
 */
@Composable
private fun FolderVideoCard(
    path: String,
    name: String,
    autoFocus: Boolean,
    onClick: () -> Unit
) {
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
                    videoPath = path,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                )
                Text(
                    text = name,
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
