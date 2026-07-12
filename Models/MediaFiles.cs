using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace MEPlayer.Models;

/// <summary>
/// 本地视频文件模型（与 Flutter 端 media_file.dart 中 VideoFile 等价）。
/// </summary>
public sealed class VideoFile
{
    public string Path { get; }
    public string Name { get; }
    public long Size { get; }

    public VideoFile(string path, string name, long size)
    {
        Path = path; Name = name; Size = size;
    }

    public string Extension => System.IO.Path.GetExtension(Name);

    public string NameWithoutExtension => System.IO.Path.GetFileNameWithoutExtension(Name);

    public Dictionary<string, string> ToPlayMap() => new() { ["path"] = Path, ["name"] = Name };
}

/// <summary>
/// 本地音乐文件基础模型（与 Flutter 端 MusicFile 等价）。
/// </summary>
public sealed class MusicFile
{
    public string Path { get; }
    public string Name { get; }
    public long Size { get; }

    public MusicFile(string path, string name, long size)
    {
        Path = path; Name = name; Size = size;
    }
}

/// <summary>
/// 扩展的本地音乐文件模型（与 Flutter 端 rich_music_file.dart 等价）。
/// 封装 MusicFile 并补充从 ID3 标签读取的 title/artist/album 以及文件修改时间，
/// 供音乐库分类/排序和播放器页面共同使用。
/// </summary>
public sealed class RichMusicFile : INotifyPropertyChanged
{
    public MusicFile Base { get; }

    public string Title { get; }
    public string Artist { get; }
    public string Album { get; }
    public long ModifiedMs { get; }

    /// <summary>
    /// 历史字段：早期版本会把封面写入磁盘文件、只存路径。现在统一改为内存态
    /// coverBytes，扫描逻辑不再写入此字段；保留只为兼容旧缓存格式。
    /// </summary>
    public string CoverPath { get; }

    private byte[]? _coverBytes;

    /// <summary>
    /// 封面图片的原始字节（仅内存，不会被序列化进缓存）。
    /// 按"当前展示列表"分批增量读取（默认每批 200 首），App 重启后丢失，
    /// 下次显示时按同样分批策略重新读取——这是预期行为。
    /// setter 会触发 CoverImage 属性变更通知，供 UI 绑定自动刷新。
    /// </summary>
    public byte[]? CoverBytes
    {
        get => _coverBytes;
        set
        {
            if (_coverBytes == value) return;
            _coverBytes = value;
            _coverImage = null;
            OnPropertyChanged();
            OnPropertyChanged(nameof(CoverImage));
            OnPropertyChanged(nameof(HasCover));
        }
    }

    private ImageSource? _coverImage;

    /// <summary>
    /// 封面 BitmapImage（供 UI 绑定）。第一次访问时从 CoverBytes 构建，
    /// 之后缓存。CoverBytes 变化时清空重建。
    /// </summary>
    public ImageSource? CoverImage
    {
        get
        {
            if (_coverImage != null) return _coverImage;
            if (_coverBytes == null || _coverBytes.Length == 0) return null;
            try
            {
                var img = new BitmapImage();
                img.BeginInit();
                img.StreamSource = new System.IO.MemoryStream(_coverBytes);
                img.CacheOption = BitmapCacheOption.OnLoad;
                img.DecodePixelWidth = 80; // 列表缩略图不需要全分辨率
                img.EndInit();
                img.Freeze();
                _coverImage = img;
            }
            catch { }
            return _coverImage;
        }
    }

    /// <summary>副标题："艺术家 - 专辑"，供 UI 绑定。</summary>
    public string Subtitle
    {
        get
        {
            var parts = new List<string>();
            if (!string.IsNullOrEmpty(Artist)) parts.Add(Artist);
            if (!string.IsNullOrEmpty(Album)) parts.Add(Album);
            return string.Join(" - ", parts);
        }
    }

    /// <summary>扩展名大写（不带点），供 UI 格式标签绑定。</summary>
    public string ExtUpper => System.IO.Path.GetExtension(Name).TrimStart('.').ToUpperInvariant();

    public RichMusicFile(MusicFile @base, string title, string artist, string album, long modifiedMs, string coverPath = "", byte[]? coverBytes = null)
    {
        Base = @base;
        Title = title;
        Artist = artist;
        Album = album;
        ModifiedMs = modifiedMs;
        CoverPath = coverPath;
        _coverBytes = coverBytes;
    }

    public string Path => Base.Path;
    public string Name => Base.Name;
    public long Size => Base.Size;
    public bool HasCover => _coverBytes != null && _coverBytes.Length > 0;

    public string NameWithoutExtension => System.IO.Path.GetFileNameWithoutExtension(Name);
    public string Extension => System.IO.Path.GetExtension(Name);

    public Dictionary<string, string> ToPlayMap() => new() { ["path"] = Path, ["name"] = Name };

    public static RichMusicFile FromMetadata(MusicFile @base, string? metaTitle, string? metaArtist, string? metaAlbum, long modifiedMs)
    {
        var nameNoExt = System.IO.Path.GetFileNameWithoutExtension(@base.Name);
        var title = !string.IsNullOrEmpty(metaTitle) ? metaTitle! : nameNoExt;
        var artist = !string.IsNullOrEmpty(metaArtist) ? metaArtist! : "";
        var album = !string.IsNullOrEmpty(metaAlbum) ? metaAlbum! : "";
        return new RichMusicFile(@base, title, artist, album, modifiedMs);
    }

    public event PropertyChangedEventHandler? PropertyChanged;
    private void OnPropertyChanged([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));

    /// <summary>序列化为缓存用的纯 Map（不含 coverBytes）。</summary>
    public Dictionary<string, object> ToJson() => new()
    {
        ["path"] = Path,
        ["name"] = Name,
        ["size"] = Size,
        ["title"] = Title,
        ["artist"] = Artist,
        ["album"] = Album,
        ["modifiedMs"] = ModifiedMs,
    };

    public static RichMusicFile FromJson(Dictionary<string, object> json)
    {
        string GetString(string k) => json.TryGetValue(k, out var v) && v != null ? v.ToString()! : "";
        long GetLong(string k)
        {
            if (!json.TryGetValue(k, out var v) || v == null) return 0;
            return v switch
            {
                long l => l,
                int i => i,
                double d => (long)d,
                JsonElement je => je.TryGetInt64(out var ll) ? ll : (long)je.GetDouble(),
                _ => 0,
            };
        }

        var path = GetString("path");
        var name = GetString("name");
        var size = GetLong("size");
        return new RichMusicFile(
            new MusicFile(path, name, size),
            GetString("title"),
            GetString("artist"),
            GetString("album"),
            GetLong("modifiedMs"),
            GetString("coverPath"));
    }
}
