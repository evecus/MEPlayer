package com.tv.meplayer.data

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * 本地媒体扫描工具，对应 Flutter 的 `local_scanner.dart`。
 *
 * 主扫描方式为 MediaStore 查询（由系统 MediaProvider 维护，自动索引所有存储卷，
 * 包括内部存储、SD 卡、U 盘等外置设备），辅以 File 递归遍历补充 MediaStore
 * 尚未索引的文件，结果按 canonicalPath 去重。
 */
object LocalScanner {

    private const val MAX_DEPTH = 8

    /**
     * 扫描本地视频文件。[onFound] 每发现一个文件就回调一次。
     */
    fun scanVideos(context: Context, onFound: (String) -> Unit = {}): List<ScannedVideo> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ScannedVideo>()
        // 1) MediaStore 查询：覆盖所有存储卷（含 SD 卡 / U 盘）
        scanVideosViaMediaStore(context, seen, out, onFound)
        // 2) File 遍历补充：扫到 MediaStore 尚未索引的文件
        for (root in discoverRoots(context)) {
            val dir = File(root)
            if (!dir.exists()) continue
            runCatching {
                scanDir(dir, seen, videos = out, audios = null, depth = MAX_DEPTH, onFound = onFound)
            }
        }
        return out
    }

    /**
     * 扫描本地音频文件。
     */
    fun scanAudios(context: Context, onFound: (String) -> Unit = {}): List<ScannedAudio> {
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ScannedAudio>()
        // 1) MediaStore 查询：覆盖所有存储卷（含 SD 卡 / U 盘）
        scanAudiosViaMediaStore(context, seen, out, onFound)
        // 2) File 遍历补充：扫到 MediaStore 尚未索引的文件
        for (root in discoverRoots(context)) {
            val dir = File(root)
            if (!dir.exists()) continue
            runCatching {
                scanDir(dir, seen, videos = null, audios = out, depth = MAX_DEPTH, onFound = onFound)
            }
        }
        return out
    }

    // ── MediaStore 查询 ─────────────────────────────────────────

    /** 通过 MediaStore 查询所有存储卷上的视频文件。 */
    private fun scanVideosViaMediaStore(
        context: Context,
        seen: MutableSet<String>,
        out: MutableList<ScannedVideo>,
        onFound: (String) -> Unit
    ) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_MODIFIED
        )
        runCatching {
            context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val dataIdx = c.getColumnIndex(MediaStore.Video.Media.DATA)
                val nameIdx = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                val modIdx = c.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                while (c.moveToNext()) {
                    if (dataIdx < 0) continue
                    val path = c.getString(dataIdx) ?: continue
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: File(path).name else File(path).name
                    // 用扩展名集合二次过滤，保持与 File 遍历一致的结果
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext.isNotEmpty() && !videoExtensions.contains(".$ext")) continue
                    val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    // MediaStore DATE_MODIFIED 为秒级，统一转毫秒与 File.lastModified() 一致
                    val modified = if (modIdx >= 0) c.getLong(modIdx) * 1000L else 0L
                    val folder = File(path).parent ?: "/"
                    val dedupeKey = runCatching { File(path).canonicalPath }.getOrDefault(path)
                    if (!seen.add(dedupeKey)) continue
                    out.add(ScannedVideo(path, name, size, modified, folder))
                    onFound(name)
                }
            }
        }
    }

    /** 通过 MediaStore 查询所有存储卷上的音频文件。 */
    private fun scanAudiosViaMediaStore(
        context: Context,
        seen: MutableSet<String>,
        out: MutableList<ScannedAudio>,
        onFound: (String) -> Unit
    ) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_MODIFIED
        )
        runCatching {
            context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val dataIdx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val nameIdx = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val modIdx = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                while (c.moveToNext()) {
                    if (dataIdx < 0) continue
                    val path = c.getString(dataIdx) ?: continue
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: File(path).name else File(path).name
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext.isNotEmpty() && !musicExtensions.contains(".$ext")) continue
                    val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    val modified = if (modIdx >= 0) c.getLong(modIdx) * 1000L else 0L
                    val folder = File(path).parent ?: "/"
                    val dedupeKey = runCatching { File(path).canonicalPath }.getOrDefault(path)
                    if (!seen.add(dedupeKey)) continue
                    out.add(ScannedAudio(path, name, size, modified, folder))
                    onFound(name)
                }
            }
        }
    }

    // ── File 遍历补充 ──────────────────────────────────────────

    /**
     * 枚举当前设备上所有可扫描的存储卷根目录（内部存储 + SD 卡 + U 盘等）。
     */
    private fun discoverRoots(context: Context): List<String> {
        val roots = mutableSetOf<String>()

        // 1) getExternalFilesDirs(null) 为每个挂载的外部存储卷返回一个本应用专属目录
        runCatching {
            context.getExternalFilesDirs(null).forEach { dir ->
                if (dir != null) {
                    volumeRootFromAppDir(dir.absolutePath)?.let { roots.add(it) }
                }
            }
        }

        // 2) 枚举 /storage 下的条目作为补充
        runCatching {
            val storageDir = File("/storage")
            if (storageDir.exists()) {
                storageDir.listFiles()?.forEach { f ->
                    val name = f.name
                    if (name != "self" && name != "emulated" && f.isDirectory) {
                        roots.add(f.absolutePath)
                    }
                }
            }
        }

        // 3) 兜底经典内部存储路径
        roots.add("/storage/emulated/0")
        roots.add("/sdcard")

        // 用 canonicalPath 去重（解析符号链接，如 /sdcard -> /storage/emulated/0）
        val resolved = mutableMapOf<String, String>()
        for (root in roots) {
            runCatching {
                val dir = File(root)
                if (dir.exists()) {
                    val real = dir.canonicalPath
                    resolved.putIfAbsent(real, root)
                }
            }
        }
        return resolved.values.toList()
    }

    /**
     * 从形如 `/storage/XXXX-XXXX/Android/data/<pkg>/files` 反推卷根目录。
     */
    private fun volumeRootFromAppDir(appDirPath: String): String? {
        val normalized = appDirPath.replace('\\', '/')
        val idx = normalized.indexOf("/Android/")
        if (idx <= 0) return null
        return normalized.substring(0, idx)
    }

    private fun scanDir(
        dir: File,
        seen: MutableSet<String>,
        videos: MutableList<ScannedVideo>?,
        audios: MutableList<ScannedAudio>?,
        depth: Int,
        onFound: (String) -> Unit
    ) {
        if (depth < 0) return
        val entities = runCatching { dir.listFiles() }.getOrNull() ?: return

        for (entity in entities) {
            val name = entity.name
            if (entity.isDirectory) {
                if (name.startsWith('.') || name == "Android") continue
                runCatching {
                    scanDir(entity, seen, videos, audios, depth - 1, onFound)
                }
            } else if (entity.isFile) {
                val ext = entity.extension.let { if (it.isNotEmpty()) ".$it" else "" }.lowercase()
                val isVideo = videoExtensions.contains(ext)
                val isAudio = musicExtensions.contains(ext)
                if (!isVideo && !isAudio) continue

                // 去重键用 canonicalPath
                val dedupeKey = runCatching { entity.canonicalPath }.getOrDefault(entity.absolutePath)
                if (!seen.add(dedupeKey)) continue

                runCatching {
                    val stat = entity
                    val size = stat.length()
                    val modified = stat.lastModified()
                    val folder = entity.parent ?: "/"
                    val added = if (isVideo && videos != null) {
                        videos.add(ScannedVideo(entity.absolutePath, name, size, modified, folder))
                        true
                    } else if (isAudio && audios != null) {
                        audios.add(ScannedAudio(entity.absolutePath, name, size, modified, folder))
                        true
                    } else {
                        false
                    }
                    if (added) onFound(name)
                }
            }
        }
    }
}
