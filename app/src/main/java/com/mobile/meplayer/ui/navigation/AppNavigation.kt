package com.mobile.meplayer.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mobile.meplayer.ui.home.HomeScreen
import com.mobile.meplayer.ui.iptv.IptvPlayerScreen
import com.mobile.meplayer.ui.music.MusicGroupScreen
import com.mobile.meplayer.ui.music.MusicPlayerScreen
import com.mobile.meplayer.ui.video.VideoFolderScreen
import com.mobile.meplayer.ui.video.VideoPlayerScreen

/**
 * 路由常量，对应 Flutter 的 `routes.dart` 中的 [AppRoutes]。
 */
object AppRoutes {
    const val HOME = "home"
    const val VIDEO_PLAYER = "video/player"
    const val VIDEO_FOLDER = "video/folder"
    const val IPTV_PLAYER = "iptv/player"
    const val MUSIC_PLAYER = "music/player"
    const val MUSIC_GROUP = "music/group"
}

/**
 * 导航辅助方法，对应 Flutter 的 [AppNavigator]。
 *
 * 复杂数据（playlist / videos / songs）序列化为 JSON 字符串后经 [Uri.encode]
 * 传递，目标页解码使用。
 */
object AppNavigator {

    fun toVideoPlayer(
        navController: NavHostController,
        playlistJson: String,
        index: Int,
        resumePositionMs: Long = 0L
    ) {
        navController.navigate(
            "${AppRoutes.VIDEO_PLAYER}" +
                "?playlist=${Uri.encode(playlistJson)}" +
                "&index=$index" +
                "&resumePositionMs=$resumePositionMs"
        )
    }

    fun toVideoFolder(
        navController: NavHostController,
        folderPath: String,
        folderName: String,
        sort: Int,
        videosJson: String
    ) {
        navController.navigate(
            "${AppRoutes.VIDEO_FOLDER}" +
                "?folderPath=${Uri.encode(folderPath)}" +
                "&folderName=${Uri.encode(folderName)}" +
                "&sort=$sort" +
                "&videos=${Uri.encode(videosJson)}"
        )
    }

    fun toIptvPlayer(
        navController: NavHostController,
        url: String,
        channelName: String,
        groupName: String = "",
        sourceIndex: Int = 0
    ) {
        navController.navigate(
            "${AppRoutes.IPTV_PLAYER}" +
                "?url=${Uri.encode(url)}" +
                "&channelName=${Uri.encode(channelName)}" +
                "&groupName=${Uri.encode(groupName)}" +
                "&sourceIndex=$sourceIndex"
        )
    }

    fun toMusicPlayer(
        navController: NavHostController,
        playlistJson: String,
        index: Int
    ) {
        navController.navigate(
            "${AppRoutes.MUSIC_PLAYER}" +
                "?playlist=${Uri.encode(playlistJson)}" +
                "&index=$index"
        )
    }

    fun toMusicGroup(
        navController: NavHostController,
        title: String,
        songsJson: String,
        sort: Int = 0
    ) {
        navController.navigate(
            "${AppRoutes.MUSIC_GROUP}" +
                "?title=${Uri.encode(title)}" +
                "&songs=${Uri.encode(songsJson)}" +
                "&sort=$sort"
        )
    }
}

/**
 * 应用导航容器，对应 Flutter 的 `GetMaterialApp` + 路由表。
 *
 * 使用 Jetpack Navigation Compose，首页作为起始路由。各路由通过 NavArgument
 * 接收参数。
 */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = AppRoutes.HOME) {

        composable(AppRoutes.HOME) {
            HomeScreen(navController = navController)
        }

        composable(
            route = "${AppRoutes.VIDEO_PLAYER}" +
                "?playlist={playlist}&index={index}&resumePositionMs={resumePositionMs}",
            arguments = listOf(
                navArgument("playlist") {
                    type = NavType.StringType
                    defaultValue = "[]"
                },
                navArgument("index") {
                    type = NavType.IntType
                    defaultValue = 0
                },
                navArgument("resumePositionMs") {
                    type = NavType.LongType
                    defaultValue = 0L
                }
            )
        ) { entry ->
            VideoPlayerScreen(
                navController = navController,
                playlistJson = entry.arguments?.getString("playlist") ?: "[]",
                index = entry.arguments?.getInt("index") ?: 0,
                resumePositionMs = entry.arguments?.getLong("resumePositionMs") ?: 0L
            )
        }

        composable(
            route = "${AppRoutes.VIDEO_FOLDER}" +
                "?folderPath={folderPath}&folderName={folderName}&sort={sort}&videos={videos}",
            arguments = listOf(
                navArgument("folderPath") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("folderName") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("sort") {
                    type = NavType.IntType
                    defaultValue = 0
                },
                navArgument("videos") {
                    type = NavType.StringType
                    defaultValue = "[]"
                }
            )
        ) { entry ->
            VideoFolderScreen(
                navController = navController,
                folderPath = entry.arguments?.getString("folderPath") ?: "",
                folderName = entry.arguments?.getString("folderName") ?: "",
                sort = entry.arguments?.getInt("sort") ?: 0,
                videosJson = entry.arguments?.getString("videos") ?: "[]"
            )
        }

        composable(
            route = "${AppRoutes.IPTV_PLAYER}" +
                "?url={url}&channelName={channelName}&groupName={groupName}&sourceIndex={sourceIndex}",
            arguments = listOf(
                navArgument("url") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("channelName") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("groupName") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("sourceIndex") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            )
        ) { entry ->
            IptvPlayerScreen(
                navController = navController,
                url = entry.arguments?.getString("url") ?: "",
                channelName = entry.arguments?.getString("channelName") ?: "",
                groupName = entry.arguments?.getString("groupName") ?: "",
                sourceIndex = entry.arguments?.getInt("sourceIndex") ?: 0
            )
        }

        composable(
            route = "${AppRoutes.MUSIC_PLAYER}?playlist={playlist}&index={index}",
            arguments = listOf(
                navArgument("playlist") {
                    type = NavType.StringType
                    defaultValue = "[]"
                },
                navArgument("index") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            )
        ) { entry ->
            MusicPlayerScreen(
                navController = navController,
                playlistJson = entry.arguments?.getString("playlist") ?: "[]",
                index = entry.arguments?.getInt("index") ?: 0
            )
        }

        composable(
            route = "${AppRoutes.MUSIC_GROUP}?title={title}&songs={songs}&sort={sort}",
            arguments = listOf(
                navArgument("title") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("songs") {
                    type = NavType.StringType
                    defaultValue = "[]"
                },
                navArgument("sort") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            )
        ) { entry ->
            MusicGroupScreen(
                navController = navController,
                title = entry.arguments?.getString("title") ?: "",
                songsJson = entry.arguments?.getString("songs") ?: "[]",
                sort = entry.arguments?.getInt("sort") ?: 0
            )
        }
    }
}

// ── 占位页面 ────────────────────────────────────────────────────────────
// 首页 [HomeScreen] 由 `ui.home` 包提供；视频播放页/文件夹页由 `ui.video` 包提供；
// IPTV 播放页由 `ui.iptv` 包提供；音乐播放页/分组页由 `ui.music` 包提供。
// 各路由均已接入真实实现，无需占位。
