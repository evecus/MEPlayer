package com.tv.meplayer.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tv.meplayer.ui.home.TvHomeScreen
import com.tv.meplayer.ui.iptv.TvIptvPlayerScreen
import com.tv.meplayer.ui.music.TvMusicGroupScreen
import com.tv.meplayer.ui.music.TvMusicPlayerScreen
import com.tv.meplayer.ui.video.TvVideoFolderScreen
import com.tv.meplayer.ui.video.TvVideoLibraryScreen
import com.tv.meplayer.ui.video.TvVideoPlayerScreen
import com.tv.meplayer.ui.settings.TvSettingsScreen

/**
 * 路由常量。
 */
object AppRoutes {
    const val HOME = "home"
    const val VIDEO = "video"
    const val IPTV = "iptv"
    const val MUSIC = "music"
    const val SETTINGS = "settings"
    const val VIDEO_PLAYER = "video/player"
    const val VIDEO_FOLDER = "video/folder"
    const val IPTV_PLAYER = "iptv/player"
    const val MUSIC_PLAYER = "music/player"
    const val MUSIC_GROUP = "music/group"
}

/**
 * 导航辅助方法。复杂数据通过 [PlaylistTransfer] 单例内存传递，避免 URL 超限。
 */
object AppNavigator {

    fun toVideo(navController: NavHostController) = navController.navigate(AppRoutes.VIDEO)
    fun toIptv(navController: NavHostController) = navController.navigate(AppRoutes.IPTV)
    fun toMusic(navController: NavHostController) = navController.navigate(AppRoutes.MUSIC)
    fun toSettings(navController: NavHostController) = navController.navigate(AppRoutes.SETTINGS)

    fun toVideoPlayer(
        navController: NavHostController,
        playlistJson: String,
        index: Int,
        resumePositionMs: Long = 0L
    ) {
        navController.navigate(
            "${AppRoutes.VIDEO_PLAYER}?playlist=${Uri.encode(playlistJson)}" +
                "&index=$index&resumePositionMs=$resumePositionMs"
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
            "${AppRoutes.VIDEO_FOLDER}?folderPath=${Uri.encode(folderPath)}" +
                "&folderName=${Uri.encode(folderName)}&sort=$sort&videos=${Uri.encode(videosJson)}"
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
            "${AppRoutes.IPTV_PLAYER}?url=${Uri.encode(url)}" +
                "&channelName=${Uri.encode(channelName)}" +
                "&groupName=${Uri.encode(groupName)}&sourceIndex=$sourceIndex"
        )
    }

    fun toMusicPlayer(navController: NavHostController, playlistJson: String, index: Int) {
        navController.navigate(
            "${AppRoutes.MUSIC_PLAYER}?playlist=${Uri.encode(playlistJson)}&index=$index"
        )
    }

    fun toMusicGroup(
        navController: NavHostController,
        title: String,
        songsJson: String,
        sort: Int = 0
    ) {
        navController.navigate(
            "${AppRoutes.MUSIC_GROUP}?title=${Uri.encode(title)}" +
                "&songs=${Uri.encode(songsJson)}&sort=$sort"
        )
    }
}

/**
 * 应用导航容器。首页作为起始路由。
 */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = AppRoutes.HOME) {

        composable(AppRoutes.HOME) {
            TvHomeScreen(navController = navController)
        }

        composable(AppRoutes.VIDEO) {
            TvVideoLibraryScreen(navController = navController)
        }

        composable(AppRoutes.IPTV) {
            com.tv.meplayer.ui.iptv.TvIptvLibraryScreen(navController = navController)
        }

        composable(AppRoutes.MUSIC) {
            com.tv.meplayer.ui.music.TvMusicLibraryScreen(navController = navController)
        }

        composable(AppRoutes.SETTINGS) {
            TvSettingsScreen(navController = navController)
        }

        composable(
            route = "${AppRoutes.VIDEO_PLAYER}?playlist={playlist}&index={index}&resumePositionMs={resumePositionMs}",
            arguments = listOf(
                navArgument("playlist") { type = NavType.StringType; defaultValue = "[]" },
                navArgument("index") { type = NavType.IntType; defaultValue = 0 },
                navArgument("resumePositionMs") { type = NavType.LongType; defaultValue = 0L }
            )
        ) { entry ->
            TvVideoPlayerScreen(
                navController = navController,
                playlistJson = entry.arguments?.getString("playlist") ?: "[]",
                index = entry.arguments?.getInt("index") ?: 0,
                resumePositionMs = entry.arguments?.getLong("resumePositionMs") ?: 0L
            )
        }

        composable(
            route = "${AppRoutes.VIDEO_FOLDER}?folderPath={folderPath}&folderName={folderName}&sort={sort}&videos={videos}",
            arguments = listOf(
                navArgument("folderPath") { type = NavType.StringType; defaultValue = "" },
                navArgument("folderName") { type = NavType.StringType; defaultValue = "" },
                navArgument("sort") { type = NavType.IntType; defaultValue = 0 },
                navArgument("videos") { type = NavType.StringType; defaultValue = "[]" }
            )
        ) { entry ->
            TvVideoFolderScreen(
                navController = navController,
                folderPath = entry.arguments?.getString("folderPath") ?: "",
                folderName = entry.arguments?.getString("folderName") ?: "",
                sort = entry.arguments?.getInt("sort") ?: 0,
                videosJson = entry.arguments?.getString("videos") ?: "[]"
            )
        }

        composable(
            route = "${AppRoutes.IPTV_PLAYER}?url={url}&channelName={channelName}&groupName={groupName}&sourceIndex={sourceIndex}",
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                navArgument("channelName") { type = NavType.StringType; defaultValue = "" },
                navArgument("groupName") { type = NavType.StringType; defaultValue = "" },
                navArgument("sourceIndex") { type = NavType.IntType; defaultValue = 0 }
            )
        ) { entry ->
            TvIptvPlayerScreen(
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
                navArgument("playlist") { type = NavType.StringType; defaultValue = "[]" },
                navArgument("index") { type = NavType.IntType; defaultValue = 0 }
            )
        ) { entry ->
            TvMusicPlayerScreen(
                navController = navController,
                playlistJson = entry.arguments?.getString("playlist") ?: "[]",
                index = entry.arguments?.getInt("index") ?: 0
            )
        }

        composable(
            route = "${AppRoutes.MUSIC_GROUP}?title={title}&songs={songs}&sort={sort}",
            arguments = listOf(
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("songs") { type = NavType.StringType; defaultValue = "[]" },
                navArgument("sort") { type = NavType.IntType; defaultValue = 0 }
            )
        ) { entry ->
            TvMusicGroupScreen(
                navController = navController,
                title = entry.arguments?.getString("title") ?: "",
                songsJson = entry.arguments?.getString("songs") ?: "[]",
                sort = entry.arguments?.getInt("sort") ?: 0
            )
        }
    }
}
