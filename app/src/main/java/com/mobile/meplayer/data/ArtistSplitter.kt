package com.mobile.meplayer.data

/**
 * 艺术家字符串切分工具，对应 Flutter 的 `artist_splitter.dart`。
 */
object ArtistSplitter {
    fun split(artist: String): List<String> {
        if (artist.isEmpty()) return listOf("未知艺术家")
        var s = artist
        listOf("feat.", "ft.", "vs.", "Feat.", "Ft.", "Vs.").forEach {
            s = s.replace(it, "|")
        }
        listOf("/", ",", "&", "×", "·", "、", ";", "；").forEach {
            s = s.replace(it, "|")
        }
        val parts = s.split("|")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
            .toList()
        return if (parts.isEmpty()) listOf("未知艺术家") else parts
    }
}
