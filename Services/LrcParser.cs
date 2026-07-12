using System;
using System.Collections.Generic;
using System.Text.RegularExpressions;

namespace MEPlayer.Services;

/// <summary>一行 LRC 歌词。</summary>
public sealed class LrcLine
{
    public TimeSpan Time { get; }
    public string Text { get; }
    public LrcLine(TimeSpan time, string text) { Time = time; Text = text; }
}

/// <summary>LRC 文本解析，与 Flutter 端 parseLrc 一致。</summary>
public static class LrcParser
{
    private static readonly Regex Re = new(@"\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)", RegexOptions.Compiled);

    public static List<LrcLine> Parse(string lrcText)
    {
        var lines = new List<LrcLine>();
        foreach (var rawLine in lrcText.Split('\n'))
        {
            var line = rawLine.Trim();
            foreach (Match m in Re.Matches(line))
            {
                var min = int.Parse(m.Groups[1].Value);
                var sec = int.Parse(m.Groups[2].Value);
                var msRaw = m.Groups[3].Value;
                var ms = msRaw.Length == 2 ? int.Parse(msRaw) * 10 : int.Parse(msRaw);
                var text = m.Groups[4].Value.Trim();
                lines.Add(new LrcLine(TimeSpan.FromMinutes(min) + TimeSpan.FromSeconds(sec) + TimeSpan.FromMilliseconds(ms), text));
            }
        }
        lines.Sort((a, b) => a.Time.CompareTo(b.Time));
        return lines;
    }
}
