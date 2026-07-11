package com.mobile.meplayer.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 纯 Kotlin ID3v2 元数据解析器，对应 Flutter 的 `audio_metadata.dart`。
 */
data class AudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val coverBytes: ByteArray? = null,
    val lyrics: String? = null
)

object AudioMetadataReader {

    private const val FETCH_BYTES = 256 * 1024 // 256 KB

    fun readFile(filePath: String): AudioMetadata {
        val file = File(filePath)
        if (!file.exists()) return AudioMetadata()

        val fileLen = file.length()
        val readLen = if (fileLen < FETCH_BYTES) fileLen.toInt() else FETCH_BYTES

        val data = ByteArray(readLen)
        runCatching {
            file.inputStream().use { input ->
                if (input.read(data) != readLen) return AudioMetadata()
            }
        }.onFailure { return AudioMetadata() }

        val meta = parseId3(data)

        // 无内嵌歌词时查找外部 .lrc 文件
        if (meta.lyrics.isNullOrEmpty()) {
            val lrc = readSidecarLrc(filePath)
            if (lrc != null) {
                return meta.copy(lyrics = lrc)
            }
        }
        return meta
    }

    // ── ID3v2 解析 ─────────────────────────────────────────────────────

    private fun parseId3(data: ByteArray): AudioMetadata {
        if (data.size < 10) return AudioMetadata()
        // 魔数 "ID3"
        if (data[0] != 0x49.toByte() || data[1] != 0x44.toByte() || data[2] != 0x33.toByte()) {
            return AudioMetadata()
        }

        val version = data[3].toInt() and 0xFF // 3 = v2.3, 4 = v2.4
        val flags = data[5].toInt() and 0xFF

        val tagSize = syncsafeInt(data, 6) + 10
        val limit = if (tagSize < data.size) tagSize else data.size

        var pos = 10

        // 跳过扩展头
        if ((flags and 0x40) != 0) {
            if (pos + 4 > limit) return AudioMetadata()
            val extSize = if (version == 4) syncsafeInt(data, pos) else readInt(data, pos)
            pos += extSize
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var coverBytes: ByteArray? = null
        var lyrics: String? = null

        while (pos + 10 <= limit) {
            val b0 = data[pos].toInt() and 0xFF
            if (b0 < 0x41 || b0 > 0x5A) break // padding

            val frameId = String(data, pos, 4, Charsets.ISO_8859_1)
            val frameSize = if (version == 4) syncsafeInt(data, pos + 4) else readInt(data, pos + 4)
            pos += 10

            if (frameSize <= 0 || pos + frameSize > data.size) break

            val payload = data.copyOfRange(pos, pos + frameSize)
            pos += frameSize

            when (frameId) {
                "TIT2" -> title = decodeText(payload)
                "TPE1" -> artist = decodeText(payload)
                "TALB" -> album = decodeText(payload)
                "APIC" -> coverBytes = decodePicture(payload)
                "USLT" -> {
                    val lrc = decodeLyrics(payload)
                    if (!lrc.isNullOrEmpty()) lyrics = lrc
                }
            }
        }

        return AudioMetadata(title, artist, album, coverBytes, lyrics)
    }

    private fun decodeText(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val enc = payload[0].toInt() and 0xFF
        val raw = payload.copyOfRange(1, payload.size)
        val s = when (enc) {
            1 -> decodeUtf16(raw)
            2 -> decodeUtf16Be(raw)
            3 -> decodeUtf8(raw)
            else -> String(raw, Charsets.ISO_8859_1)
        }
        val nullIdx = s.indexOf('\u0000')
        val result = (if (nullIdx >= 0) s.substring(0, nullIdx) else s).trim()
        return if (result.isEmpty()) null else result
    }

    private fun decodePicture(payload: ByteArray): ByteArray? {
        if (payload.size < 4) return null
        val enc = payload[0].toInt() and 0xFF
        var pos = 1

        // MIME type（null 结尾）
        while (pos < payload.size && payload[pos].toInt() != 0) pos++
        pos++
        if (pos >= payload.size) return null

        // Picture type (1 byte)
        pos++
        if (pos >= payload.size) return null

        // Description
        if (enc == 0 || enc == 3) {
            while (pos < payload.size && payload[pos].toInt() != 0) pos++
            pos++
        } else {
            while (pos + 1 < payload.size && !(payload[pos].toInt() == 0 && payload[pos + 1].toInt() == 0)) {
                pos += 2
            }
            pos += 2
        }
        if (pos >= payload.size) return null
        return payload.copyOfRange(pos, payload.size)
    }

    private fun decodeLyrics(payload: ByteArray): String? {
        if (payload.size < 5) return null
        val enc = payload[0].toInt() and 0xFF
        var pos = 4 // skip lang [1..3]

        if (enc == 0 || enc == 3) {
            while (pos < payload.size && payload[pos].toInt() != 0) pos++
            pos++
        } else {
            while (pos + 1 < payload.size && !(payload[pos].toInt() == 0 && payload[pos + 1].toInt() == 0)) {
                pos += 2
            }
            pos += 2
        }
        if (pos >= payload.size) return null

        val raw = payload.copyOfRange(pos, payload.size)
        val s = when (enc) {
            1 -> decodeUtf16(raw)
            2 -> decodeUtf16Be(raw)
            3 -> decodeUtf8(raw)
            else -> String(raw, Charsets.ISO_8859_1)
        }
        val result = s.replace("\u0000", "").trim()
        return if (result.isEmpty()) null else result
    }

    private fun readSidecarLrc(audioPath: String): String? {
        val base = audioPath.substringBeforeLast('.', audioPath)
        for (ext in listOf(".lrc", ".LRC")) {
            val f = File("$base$ext")
            if (f.exists()) {
                return runCatching { f.readText(Charsets.UTF_8) }
                    .getOrElse { f.readText(Charsets.ISO_8859_1) }
            }
        }
        return null
    }

    // ── 工具函数 ───────────────────────────────────────────────────────

    private fun syncsafeInt(d: ByteArray, off: Int): Int =
        ((d[off].toInt() and 0x7f) shl 21) or
            ((d[off + 1].toInt() and 0x7f) shl 14) or
            ((d[off + 2].toInt() and 0x7f) shl 7) or
            (d[off + 3].toInt() and 0x7f)

    private fun readInt(d: ByteArray, off: Int): Int =
        ((d[off].toInt() and 0xff) shl 24) or
            ((d[off + 1].toInt() and 0xff) shl 16) or
            ((d[off + 2].toInt() and 0xff) shl 8) or
            (d[off + 3].toInt() and 0xff)

    private fun decodeUtf8(raw: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return decoder.decode(ByteBuffer.wrap(raw)).toString()
    }

    private fun decodeUtf16(raw: ByteArray): String {
        if (raw.size >= 2) {
            if (raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) {
                return String(raw, 2, raw.size - 2, Charsets.UTF_16LE)
            } else if (raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
                return String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
            }
        }
        return String(raw, Charsets.UTF_16LE)
    }

    private fun decodeUtf16Be(raw: ByteArray): String =
        String(raw, Charsets.UTF_16BE)
}

// ── LRC 解析 ───────────────────────────────────────────────────────────

data class LrcLine(val time: Long, val text: String)

fun parseLrc(lrcText: String): List<LrcLine> {
    val lines = mutableListOf<LrcLine>()
    val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""")

    for (rawLine in lrcText.split('\n')) {
        val line = rawLine.trim()
        for (match in regex.findAll(line)) {
            val min = match.groupValues[1].toInt()
            val sec = match.groupValues[2].toInt()
            val msStr = match.groupValues[3]
            val ms = when (msStr.length) {
                2 -> msStr.toInt() * 10
                3 -> msStr.toInt()
                else -> 0
            }
            val text = match.groupValues[4].trim()
            lines.add(LrcLine(min * 60_000L + sec * 1000L + ms, text))
        }
    }
    lines.sortBy { it.time }
    return lines
}
