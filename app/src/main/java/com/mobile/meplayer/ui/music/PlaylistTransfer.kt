package com.mobile.meplayer.ui.music

/**
 * 跨页面播放列表传递中介（object 单例）。
 *
 * 用于在音乐库 → 分组详情 / 播放器之间传递歌曲列表，避免将包含完整歌词
 * 等大字段的 JSON 序列化进导航 URL 导致 TransactionTooLargeException 崩溃。
 * 同时也用于视频播放列表的传递。
 *
 * 使用模式：源页写入后立即导航，目标页读取并置空。
 */
object PlaylistTransfer {

    /** 传给音乐播放器的播放列表。 */
    @Volatile
    var playlist: List<Map<String, String>>? = null

    /** 传给分组详情页的歌曲列表。 */
    @Volatile
    var groupSongs: List<Map<String, String>>? = null

    /** 传给分组详情页的标题。 */
    @Volatile
    var groupTitle: String = ""

    /** 传给分组详情页的排序模式。 */
    @Volatile
    var groupSort: Int = 0

    /** 传给视频播放器的播放列表。 */
    @Volatile
    var videoPlaylist: List<Map<String, String>>? = null
}
