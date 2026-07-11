package com.mobile.meplayer.data

/**
 * M3U/M3U8 解析器，对应 Flutter 的 `m3u_parser.dart`。
 */
data class M3uChannel(
    val name: String,
    val url: String,
    val group: String = "未分组",
    val logo: String = "",
    val id: String = ""
)

object M3uParser {

    private val attrRegex = Regex("""(\w[\w-]*)="([^"]*)"""", RegexOption.IGNORE_CASE)

    fun parse(content: String): List<M3uChannel> {
        val lines = content.split(Regex("\r?\n"))
        val channels = mutableListOf<M3uChannel>()

        var name = ""
        var group = "未分组"
        var logo = ""
        var id = ""

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty() || line == "#EXTM3U") continue

            if (line.startsWith("#EXTINF:")) {
                name = attr(line, "tvg-name") ?: attr(line, "title") ?: commaName(line)
                group = attr(line, "group-title") ?: "未分组"
                logo = attr(line, "tvg-logo") ?: ""
                id = attr(line, "tvg-id") ?: ""
            } else if (!line.startsWith("#")) {
                channels.add(
                    M3uChannel(
                        name = if (name.isEmpty()) guessName(line) else name,
                        url = line,
                        group = group,
                        logo = logo,
                        id = id
                    )
                )
                name = ""
                group = "未分组"
                logo = ""
                id = ""
            }
        }
        return channels
    }

    private fun attr(line: String, key: String): String? {
        val pattern = Regex("""$key="([^"]*)"""", RegexOption.IGNORE_CASE)
        return pattern.find(line)?.groupValues?.get(1)?.trim()
    }

    private fun commaName(line: String): String {
        val idx = line.lastIndexOf(',')
        return if (idx < 0) "" else line.substring(idx + 1).trim()
    }

    private fun guessName(url: String): String {
        return runCatching {
            val uri = android.net.Uri.parse(url)
            val segments = uri.pathSegments
            val last = segments.lastOrNull() ?: return url
            last.substringBeforeLast('.', last)
        }.getOrDefault(url)
    }

    /** 按分组组织频道，保持插入顺序 */
    fun groupBy(channels: List<M3uChannel>): Map<String, List<M3uChannel>> {
        val map = LinkedHashMap<String, MutableList<M3uChannel>>()
        for (ch in channels) {
            map.getOrPut(ch.group) { mutableListOf() }.add(ch)
        }
        return map
    }
}
