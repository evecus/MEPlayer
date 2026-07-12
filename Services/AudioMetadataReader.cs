using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace MEPlayer.Services;

/// <summary>
/// 纯 C# 音频元数据解析器，与 Flutter 端 audio_metadata.dart 完全一致。
/// 支持 MP3(ID3v2) / FLAC / OGG(Vorbis)。读取文件前 256KB 判断格式。
/// </summary>
public sealed class AudioMetadata
{
    public string? Title { get; set; }
    public string? Artist { get; set; }
    public string? Album { get; set; }
    public byte[]? CoverBytes { get; set; }
    public string? Lyrics { get; set; }

    public AudioMetadata CopyWith(
        string? title = null, string? artist = null, string? album = null,
        byte[]? coverBytes = null, string? lyrics = null) => new()
        {
            Title = title ?? Title,
            Artist = artist ?? Artist,
            Album = album ?? Album,
            CoverBytes = coverBytes ?? CoverBytes,
            Lyrics = lyrics ?? Lyrics,
        };
}

public static class AudioMetadataReader
{
    private const int FetchBytes = 256 * 1024;

    public static AudioMetadata ReadFile(string filePath)
    {
        try
        {
            if (!File.Exists(filePath)) return new AudioMetadata();
            var len = new FileInfo(filePath).Length;
            var readLen = (int)Math.Min(len, FetchBytes);
            var data = new byte[readLen];
            using (var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                fs.Read(data, 0, readLen);
            }

            AudioMetadata meta;
            if (IsId3(data)) meta = ParseId3(data);
            else if (IsFlac(data)) meta = ParseFlac(data);
            else if (IsOgg(data)) meta = ParseOgg(data);
            else meta = new AudioMetadata();

            if (string.IsNullOrEmpty(meta.Lyrics))
            {
                var lrc = ReadSidecarLrc(filePath);
                if (lrc != null) return meta.WithLyrics(lrc);
            }
            return meta;
        }
        catch
        {
            return new AudioMetadata();
        }
    }

    // 让 WithLyrics 可链式（CopyWith 别名）
    private static AudioMetadata WithLyrics(this AudioMetadata m, string lrc) =>
        m.CopyWith(lyrics: lrc);

    // ── 格式嗅探 ──────────────────────────────────────────────────────────
    private static bool IsId3(byte[] d) => d.Length >= 3 && d[0] == 0x49 && d[1] == 0x44 && d[2] == 0x33; // "ID3"
    private static bool IsFlac(byte[] d) => d.Length >= 4 && d[0] == 0x66 && d[1] == 0x4C && d[2] == 0x61 && d[3] == 0x43; // "fLaC"
    private static bool IsOgg(byte[] d) => d.Length >= 4 && d[0] == 0x4F && d[1] == 0x67 && d[2] == 0x67 && d[3] == 0x53; // "OggS"

    // ── ID3v2 解析 ────────────────────────────────────────────────────────
    private static AudioMetadata ParseId3(byte[] data)
    {
        if (data.Length < 10) return new();
        var version = data[3];
        var flags = data[5];
        var tagSize = SyncsafeInt(data, 6) + 10;
        var limit = Math.Min(tagSize, data.Length);
        int pos = 10;

        if ((flags & 0x40) != 0)
        {
            if (pos + 4 > limit) return new();
            var extSize = version == 4 ? SyncsafeInt(data, pos) : ReadInt(data, pos);
            pos += extSize;
        }

        string? title = null, artist = null, album = null, lyrics = null;
        byte[]? coverBytes = null;

        while (pos + 10 <= limit)
        {
            var b0 = data[pos];
            if (b0 < 0x41 || b0 > 0x5A) break;
            var frameId = Encoding.ASCII.GetString(data, pos, 4);
            var frameSize = version == 4 ? SyncsafeInt(data, pos + 4) : ReadInt(data, pos + 4);
            pos += 10;
            if (frameSize <= 0 || pos + frameSize > data.Length) break;

            var payload = new byte[frameSize];
            Array.Copy(data, pos, payload, 0, frameSize);
            pos += frameSize;

            switch (frameId)
            {
                case "TIT2": title = DecodeText(payload); break;
                case "TPE1": artist = DecodeText(payload); break;
                case "TALB": album = DecodeText(payload); break;
                case "APIC": coverBytes = DecodePicture(payload); break;
                case "USLT":
                    var lrc = DecodeLyrics(payload);
                    if (!string.IsNullOrEmpty(lrc)) lyrics = lrc;
                    break;
            }
        }

        return new AudioMetadata { Title = title, Artist = artist, Album = album, CoverBytes = coverBytes, Lyrics = lyrics };
    }

    private static string? DecodeText(byte[] payload)
    {
        if (payload.Length == 0) return null;
        var enc = payload[0];
        var raw = new byte[payload.Length - 1];
        Array.Copy(payload, 1, raw, 0, raw.Length);
        try
        {
            string s;
            switch (enc)
            {
                case 1: s = DecodeUtf16(raw); break;
                case 2: s = DecodeUtf16Be(raw); break;
                case 3: s = Encoding.UTF8.GetString(raw); break;
                default: s = Encoding.GetEncoding(28591).GetString(raw); break; // Latin-1
            }
            var nullIdx = s.IndexOf('\0');
            if (nullIdx >= 0) s = s.Substring(0, nullIdx);
            s = s.Trim();
            return string.IsNullOrEmpty(s) ? null : s;
        }
        catch { return null; }
    }

    private static byte[]? DecodePicture(byte[] payload)
    {
        if (payload.Length < 4) return null;
        var enc = payload[0];
        int pos = 1;
        while (pos < payload.Length && payload[pos] != 0) pos++;
        pos++;
        if (pos >= payload.Length) return null;
        pos++; // picture type
        if (pos >= payload.Length) return null;

        if (enc == 0 || enc == 3)
        {
            while (pos < payload.Length && payload[pos] != 0) pos++;
            pos++;
        }
        else
        {
            while (pos + 1 < payload.Length && !(payload[pos] == 0 && payload[pos + 1] == 0)) pos += 2;
            pos += 2;
        }
        if (pos >= payload.Length) return null;

        var result = new byte[payload.Length - pos];
        Array.Copy(payload, pos, result, 0, result.Length);
        return result;
    }

    private static string? DecodeLyrics(byte[] payload)
    {
        if (payload.Length < 5) return null;
        var enc = payload[0];
        int pos = 4;
        if (enc == 0 || enc == 3)
        {
            while (pos < payload.Length && payload[pos] != 0) pos++;
            pos++;
        }
        else
        {
            while (pos + 1 < payload.Length && !(payload[pos] == 0 && payload[pos + 1] == 0)) pos += 2;
            pos += 2;
        }
        if (pos >= payload.Length) return null;
        var raw = new byte[payload.Length - pos];
        Array.Copy(payload, pos, raw, 0, raw.Length);
        try
        {
            string s;
            switch (enc)
            {
                case 1: s = DecodeUtf16(raw); break;
                case 2: s = DecodeUtf16Be(raw); break;
                case 3: s = Encoding.UTF8.GetString(raw); break;
                default: s = Encoding.GetEncoding(28591).GetString(raw); break;
            }
            s = s.Replace("\0", "").Trim();
            return string.IsNullOrEmpty(s) ? null : s;
        }
        catch { return null; }
    }

    // ── FLAC 解析 ─────────────────────────────────────────────────────────
    private static AudioMetadata ParseFlac(byte[] data)
    {
        if (data.Length < 4) return new();
        Dictionary<string, string>? tags = null;
        byte[]? coverBytes = null;
        int pos = 4;

        while (pos + 4 <= data.Length)
        {
            var header = data[pos];
            var isLast = (header & 0x80) != 0;
            var blockType = header & 0x7F;
            var blockLen = (data[pos + 1] << 16) | (data[pos + 2] << 8) | data[pos + 3];
            pos += 4;
            if (pos + blockLen > data.Length) break;

            var block = new byte[blockLen];
            Array.Copy(data, pos, block, 0, blockLen);

            if (blockType == 4) tags = ParseVorbisComment(block, 0);
            else if (blockType == 6) coverBytes = ParseFlacPicture(block);

            pos += blockLen;
            if (isLast) break;
        }

        if (tags == null && coverBytes == null) return new();
        return new AudioMetadata
        {
            Title = tags?.GetValueOrDefault("TITLE"),
            Artist = tags?.GetValueOrDefault("ARTIST"),
            Album = tags?.GetValueOrDefault("ALBUM"),
            CoverBytes = coverBytes,
        };
    }

    private static byte[]? ParseFlacPicture(byte[] block)
    {
        int pos = 0;
        if (pos + 4 > block.Length) return null;
        pos += 4;
        if (pos + 4 > block.Length) return null;
        var mimeLen = ReadInt(block, pos);
        pos += 4 + mimeLen;
        if (pos + 4 > block.Length) return null;
        var descLen = ReadInt(block, pos);
        pos += 4 + descLen;
        pos += 16;
        if (pos + 4 > block.Length) return null;
        var picLen = ReadInt(block, pos);
        pos += 4;
        if (pos + picLen > block.Length || picLen <= 0) return null;
        var result = new byte[picLen];
        Array.Copy(block, pos, result, 0, picLen);
        return result;
    }

    // ── OGG (Vorbis) 解析 ─────────────────────────────────────────────────
    private static AudioMetadata ParseOgg(byte[] data)
    {
        int pos = 0;
        int pagesScanned = 0;
        const int maxPages = 8;

        while (pos + 27 <= data.Length && pagesScanned < maxPages)
        {
            if (!(data[pos] == 0x4F && data[pos + 1] == 0x67 && data[pos + 2] == 0x67 && data[pos + 3] == 0x53))
                break;
            pagesScanned++;

            var segCount = data[pos + 26];
            var segTableStart = pos + 27;
            if (segTableStart + segCount > data.Length) break;

            int payloadLen = 0;
            for (int i = 0; i < segCount; i++) payloadLen += data[segTableStart + i];
            var payloadStart = segTableStart + segCount;
            if (payloadStart + payloadLen > data.Length) break;

            var payload = new byte[payloadLen];
            Array.Copy(data, payloadStart, payload, 0, payloadLen);

            if (payload.Length > 7 &&
                payload[0] == 0x03 &&
                payload[1] == 0x76 && payload[2] == 0x6F && payload[3] == 0x72 &&
                payload[4] == 0x62 && payload[5] == 0x69 && payload[6] == 0x73)
            {
                var tags = ParseVorbisComment(payload, 7);
                if (tags != null)
                {
                    return new AudioMetadata
                    {
                        Title = tags.GetValueOrDefault("TITLE"),
                        Artist = tags.GetValueOrDefault("ARTIST"),
                        Album = tags.GetValueOrDefault("ALBUM"),
                    };
                }
            }

            pos = payloadStart + payloadLen;
        }
        return new();
    }

    // ── Vorbis Comment（FLAC/OGG 共用） ───────────────────────────────────
    private static Dictionary<string, string>? ParseVorbisComment(byte[] block, int offset)
    {
        int pos = offset;
        if (pos + 4 > block.Length) return null;
        var vendorLen = ReadIntLe(block, pos);
        pos += 4 + vendorLen;
        if (pos + 4 > block.Length) return null;
        var commentCount = ReadIntLe(block, pos);
        pos += 4;

        var tags = new Dictionary<string, string>();
        for (int i = 0; i < commentCount; i++)
        {
            if (pos + 4 > block.Length) break;
            var len = ReadIntLe(block, pos);
            pos += 4;
            if (len < 0 || pos + len > block.Length) break;

            string text;
            try { text = Encoding.UTF8.GetString(block, pos, len); }
            catch { pos += len; continue; }
            pos += len;

            var eqIdx = text.IndexOf('=');
            if (eqIdx <= 0) continue;
            var key = text.Substring(0, eqIdx).ToUpperInvariant();
            var value = text.Substring(eqIdx + 1);
            if (string.IsNullOrEmpty(value)) continue;
            if (!tags.ContainsKey(key)) tags[key] = value;
        }
        return tags;
    }

    // ── 外部 .lrc 文件 ────────────────────────────────────────────────────
    private static string? ReadSidecarLrc(string audioPath)
    {
        var dot = audioPath.LastIndexOf('.');
        var base_ = dot < 0 ? audioPath : audioPath.Substring(0, dot);
        foreach (var ext in new[] { ".lrc", ".LRC" })
        {
            var f = base_ + ext;
            if (File.Exists(f))
            {
                try { return File.ReadAllText(f, Encoding.UTF8); }
                catch
                {
                    try { return File.ReadAllText(f, Encoding.GetEncoding(28591)); }
                    catch { }
                }
            }
        }
        return null;
    }

    // ── 工具函数 ──────────────────────────────────────────────────────────
    private static int SyncsafeInt(byte[] d, int off) =>
        ((d[off] & 0x7f) << 21) | ((d[off + 1] & 0x7f) << 14) |
        ((d[off + 2] & 0x7f) << 7) | (d[off + 3] & 0x7f);

    private static int ReadInt(byte[] d, int off) =>
        ((d[off] & 0xff) << 24) | ((d[off + 1] & 0xff) << 16) |
        ((d[off + 2] & 0xff) << 8) | (d[off + 3] & 0xff);

    private static int ReadIntLe(byte[] d, int off) =>
        (d[off] & 0xff) | ((d[off + 1] & 0xff) << 8) |
        ((d[off + 2] & 0xff) << 16) | ((d[off + 3] & 0xff) << 24);

    private static string DecodeUtf16(byte[] raw)
    {
        if (raw.Length >= 2 && raw[0] == 0xFF && raw[1] == 0xFE)
            return Encoding.Unicode.GetString(raw, 2, raw.Length - 2);
        if (raw.Length >= 2 && raw[0] == 0xFE && raw[1] == 0xFF)
            return Encoding.BigEndianUnicode.GetString(raw, 2, raw.Length - 2);
        return Encoding.Unicode.GetString(raw);
    }

    private static string DecodeUtf16Be(byte[] raw) => Encoding.BigEndianUnicode.GetString(raw);
}
