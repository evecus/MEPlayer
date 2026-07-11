package com.mobile.meplayer.data

import org.json.JSONArray
import org.json.JSONObject

// ── 基础文件模型（对应 media_file.dart） ─────────────────────────────────

data class VideoFile(val path: String, val name: String, val size: Long)

data class MusicFile(val path: String, val name: String, val size: Long)

val videoExtensions = setOf(
    ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv",
    ".ts", ".m2ts", ".mpg", ".mpeg", ".webm", ".rmvb",
    ".rm", ".3gp", ".m4v"
)

val musicExtensions = setOf(
    ".mp3", ".flac", ".aac", ".ogg", ".opus",
    ".wav", ".m4a", ".wma", ".ape"
)

// ── 扫描结果模型（对应 local_scanner.dart 中的 ScannedVideo / ScannedAudio） ──

data class ScannedVideo(
    val path: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val folder: String,
    val thumbPath: String = ""
) {
    fun toBase() = VideoFile(path, name, size)

    fun copyWith(thumbPath: String) = ScannedVideo(path, name, size, modified, folder, thumbPath)

    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("name", name)
        put("size", size)
        put("modified", modified)
        put("folder", folder)
        put("thumbPath", thumbPath)
    }

    companion object {
        fun fromJson(json: JSONObject) = ScannedVideo(
            path = json.optString("path"),
            name = json.optString("name"),
            size = json.optLong("size"),
            modified = json.optLong("modified"),
            folder = json.optString("folder"),
            thumbPath = json.optString("thumbPath")
        )
    }
}

data class ScannedAudio(
    val path: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val folder: String
) {
    fun toBase() = MusicFile(path, name, size)
}

// ── 音乐库分组项 ───────────────────────────────────────────────────────

data class MusicGroupEntry(
    val key: String,
    val displayName: String,
    val songs: List<SongEntry>
)

// ── 视频文件夹分组项 ───────────────────────────────────────────────────

data class VideoFolderEntry(
    val path: String,
    val name: String,
    val videos: List<ScannedVideo>
)

// ── 歌曲项（对应 song_entry.dart） ─────────────────────────────────────
// 注意：title/artist/album/lyrics/coverBytes 都是可变的，扫描后异步加载元数据时回填。

class SongEntry(
    val path: String,
    val name: String,
    val folder: String,
    val size: Long,
    val modified: Long
) {
    var title: String = name.substringBeforeLast('.', name)
    var artist: String = ""
    var album: String = ""
    var metadataLoaded: Boolean = false
    var coverPath: String = ""
    var lyrics: String = ""
    var coverBytes: ByteArray? = null

    /** 转为播放器所需的 Map 格式 */
    fun toMap(): Map<String, String> = mapOf(
        "path" to path,
        "name" to name,
        "coverPath" to coverPath,
        "lyrics" to lyrics
    )

    /** 序列化为可存入 SharedPreferences 的 JSON 对象。不包含 coverBytes。 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("path", path)
        put("name", name)
        put("folder", folder)
        put("size", size)
        put("modified", modified)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("coverPath", coverPath)
        put("lyrics", lyrics)
    }

    companion object {
        fun fromJson(json: JSONObject) = SongEntry(
            path = json.optString("path"),
            name = json.optString("name"),
            folder = json.optString("folder"),
            size = json.optLong("size"),
            modified = json.optLong("modified")
        ).apply {
            title = json.optString("title", name.substringBeforeLast('.', name))
            artist = json.optString("artist")
            album = json.optString("album")
            coverPath = json.optString("coverPath")
            lyrics = json.optString("lyrics")
            metadataLoaded = true
        }
    }
}

// ── JSON 数组辅助 ──────────────────────────────────────────────────────

@JvmName("scannedVideosToJsonArrayString")
fun List<ScannedVideo>.toJsonArrayString(): String {
    val arr = JSONArray()
    forEach { arr.put(it.toJson()) }
    return arr.toString()
}

fun parseScannedVideoList(json: String): List<ScannedVideo> {
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { ScannedVideo.fromJson(it) }
    }.getOrDefault(emptyList())
}

@JvmName("songEntriesToJsonArrayString")
fun List<SongEntry>.toJsonArrayString(): String {
    val arr = JSONArray()
    forEach { arr.put(it.toJson()) }
    return arr.toString()
}

fun parseSongEntryList(json: String): List<SongEntry> {
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { SongEntry.fromJson(it) }
    }.getOrDefault(emptyList())
}

/** 将 List<Map<String,String>> 序列化为 JSON 字符串（用于导航传参） */
@JvmName("stringMapsToJsonArrayString")
fun List<Map<String, String>>.toJsonArrayString(): String {
    val arr = JSONArray()
    forEach { m ->
        val obj = JSONObject()
        m.forEach { (k, v) -> obj.put(k, v) }
        arr.put(obj)
    }
    return arr.toString()
}

fun parseStringMapList(json: String): List<Map<String, String>> {
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { idx ->
            val obj = arr.getJSONObject(idx)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { k -> map[k] = obj.getString(k) }
            map
        }
    }.getOrDefault(emptyList())
}
