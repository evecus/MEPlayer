using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;

namespace MEPlayer.Services;

/// <summary>简单 M3U/M3U8 解析器，与 Flutter 端 m3u_parser.dart 完全一致。</summary>
public sealed class M3uChannel
{
    public string Name { get; set; } = "";
    public string Url { get; set; } = "";
    public string Group { get; set; } = "未分组";
    public string Logo { get; set; } = "";
    public string Id { get; set; } = "";
}

public static class M3uParser
{
    private static readonly Regex AttrRe = new(@"""([^""]*)""", RegexOptions.Compiled);

    public static List<M3uChannel> Parse(string content)
    {
        var channels = new List<M3uChannel>();
        var lines = content.Split(new[] { "\r\n", "\n" }, StringSplitOptions.None);

        string name = "", group = "未分组", logo = "", id = "";

        foreach (var raw in lines)
        {
            var line = raw.Trim();
            if (string.IsNullOrEmpty(line) || line == "#EXTM3U") continue;

            if (line.StartsWith("#EXTINF:"))
            {
                name = Attr(line, "tvg-name") ?? Attr(line, "title") ?? CommaName(line);
                group = Attr(line, "group-title") ?? "未分组";
                logo = Attr(line, "tvg-logo") ?? "";
                id = Attr(line, "tvg-id") ?? "";
            }
            else if (!line.StartsWith("#"))
            {
                channels.Add(new M3uChannel
                {
                    Name = string.IsNullOrEmpty(name) ? GuessName(line) : name,
                    Url = line,
                    Group = group,
                    Logo = logo,
                    Id = id,
                });
                name = ""; group = "未分组"; logo = ""; id = "";
            }
        }
        return channels;
    }

    private static string? Attr(string line, string key)
    {
        var idx = line.IndexOf(key + "=\"", StringComparison.OrdinalIgnoreCase);
        if (idx < 0) return null;
        idx += key.Length + 2;
        var end = line.IndexOf('"', idx);
        if (end < 0) return null;
        return line.Substring(idx, end - idx).Trim();
    }

    private static string CommaName(string line)
    {
        var idx = line.LastIndexOf(',');
        return idx < 0 ? "" : line.Substring(idx + 1).Trim();
    }

    private static string GuessName(string url)
    {
        try
        {
            var uri = new Uri(url);
            var seg = uri.Segments.Length > 0 ? uri.Segments[^1] : url;
            return seg.Split('.')[0];
        }
        catch { return url; }
    }

    /// <summary>按 group-title 分组，保留插入顺序。</summary>
    public static Dictionary<string, List<M3uChannel>> GroupBy(List<M3uChannel> channels)
    {
        var map = new Dictionary<string, List<M3uChannel>>();
        foreach (var ch in channels)
        {
            if (!map.TryGetValue(ch.Group, out var list))
            {
                list = new List<M3uChannel>();
                map[ch.Group] = list;
            }
            list.Add(ch);
        }
        return map;
    }
}
