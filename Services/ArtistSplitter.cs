using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;

namespace MEPlayer.Services;

/// <summary>
/// 艺术家字符串切分工具（与 Flutter 端 artist_splitter.dart 一致）。
/// 一首歌可能写多个艺术家（如 "周杰伦 / 阿信"、"A feat. B"），
/// 切分成多个独立分组 key，让同一首歌出现在每个艺术家的分组下。
/// </summary>
public static class ArtistSplitter
{
    private static readonly Regex[] FeatPatterns =
    {
        new(@"\bfeat\.?\s*", RegexOptions.IgnoreCase),
        new(@"\bft\.?\s*", RegexOptions.IgnoreCase),
        new(@"\bvs\.?\s*", RegexOptions.IgnoreCase),
    };

    private static readonly char[] Separators = { '/', ',', '&', '×', '·', '、', ';', '；' };

    public static List<string> Split(string artist)
    {
        if (string.IsNullOrEmpty(artist)) return new() { "未知艺术家" };

        var s = artist;
        foreach (var re in FeatPatterns)
            s = re.Replace(s, "|");
        foreach (var sep in Separators)
            s = s.Replace(sep, '|');

        var seen = new HashSet<string>();
        var parts = new List<string>();
        foreach (var raw in s.Split('|'))
        {
            var t = raw.Trim();
            if (string.IsNullOrEmpty(t)) continue;
            if (seen.Add(t)) parts.Add(t);
        }
        return parts.Count == 0 ? new() { "未知艺术家" } : parts;
    }
}
