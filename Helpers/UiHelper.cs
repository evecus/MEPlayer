using System;
using System.Globalization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace MEPlayer.Helpers;

/// <summary>
/// UI 公共工具：图标、颜色、字符串格式化等。
/// 所有 Page 共用。
/// </summary>
public static class UiHelper
{
    /// <summary>使用 Segoe MDL2 Assets 图标字体的 TextBlock。</summary>
    public static TextBlock Icon(string glyph, double size = 16, Brush? fg = null)
    {
        return new TextBlock
        {
            Text = glyph,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = size,
            Foreground = fg ?? (Brush)Application.Current.Resources["M3.OnSurfaceVariant"],
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
    }

    /// <summary>时间格式化（HH:MM:SS 或 MM:SS）。</summary>
    public static string FormatTime(double sec)
    {
        if (sec < 0 || double.IsNaN(sec) || double.IsInfinity(sec)) sec = 0;
        var t = TimeSpan.FromSeconds(sec);
        if (t.TotalHours >= 1) return $"{(int)t.TotalHours}:{t.Minutes:D2}:{t.Seconds:D2}";
        return $"{t.Minutes}:{t.Seconds:D2}";
    }

    /// <summary>Brush + alpha 合成。</summary>
    public static SolidColorBrush WithAlpha(Brush b, byte alpha)
    {
        if (b is SolidColorBrush sc)
        {
            var c = sc.Color;
            return new SolidColorBrush(Color.FromArgb(alpha, c.R, c.G, c.B));
        }
        return new SolidColorBrush(Colors.Transparent);
    }

    /// <summary>Brush 取当前主题资源。</summary>
    public static Brush BrushOf(string key)
        => (Brush)Application.Current.Resources[key];

    /// <summary>文件名去扩展名（与 Dart p.withoutExtension 等价）。</summary>
    public static string WithoutExtension(string name)
        => System.IO.Path.GetFileNameWithoutExtension(name);

    /// <summary>取扩展名（含点）。</summary>
    public static string Extension(string name)
        => System.IO.Path.GetExtension(name);

    /// <summary>扩展名去点大写（如 .mp4 → MP4）。</summary>
    public static string ExtUpper(string name)
    {
        var e = Extension(name);
        return string.IsNullOrEmpty(e) ? "" : e.Substring(1).ToUpperInvariant();
    }

    /// <summary>路径末尾的目录名（与 Flutter _lastName 一致）。</summary>
    public static string LastPathSegment(string path)
    {
        var parts = path.Replace('\\', '/').Split('/', StringSplitOptions.RemoveEmptyEntries);
        return parts.Length == 0 ? path : parts[^1];
    }

    /// <summary>带圆角的透明 Button Template。</summary>
    public static System.Windows.Controls.ControlTemplate TransparentButtonTemplate()
    {
        var xaml = @"
<ControlTemplate TargetType=""Button"" xmlns=""http://schemas.microsoft.com/winfx/2006/xaml/presentation"">
    <Border Background=""{TemplateBinding Background}"" CornerRadius=""6"">
        <ContentPresenter HorizontalAlignment=""Stretch"" VerticalAlignment=""Center""/>
    </Border>
    <ControlTemplate.Triggers>
        <Trigger Property=""IsMouseOver"" Value=""True"">
            <Setter Property=""Background"" Value=""{DynamicResource M3.SurfaceContainerHighest}""/>
        </Trigger>
    </ControlTemplate.Triggers>
</ControlTemplate>";
        return (System.Windows.Controls.ControlTemplate)System.Windows.Markup.XamlReader.Parse(xaml);
    }
}

/// <summary>Segoe MDL2 Assets 图标码点常量（与 Flutter Icons 一一对应）。</summary>
public static class Mdl2
{
    public const string VideoLibrary = "\uE8B2";
    public const string VideoLibraryOutlined = "\uE8B2";
    public const string LiveTv = "\uE714";
    public const string LiveTvOutlined = "\uE714";
    public const string MusicNote = "\uE8D6";
    public const string MusicNoteOutlined = "\uE8D6";
    public const string Settings = "\uE713";
    public const string SettingsOutlined = "\uE713";

    public const string Add = "\uE710";
    public const string Refresh = "\uE72C";
    public const string Search = "\uE721";
    public const string Sort = "\uE8CB";
    public const string Folder = "\uE8B7";
    public const string FolderOutlined = "\uE8B7";
    public const string NewFolder = "\uE8DA";
    public const string Play = "\uE768";
    public const string Pause = "\uE769";
    public const string PlayArrow = "\uE768";
    public const string PlayCircle = "\uE768";
    public const string Next = "\uE893";
    public const string Prev = "\uE892";
    public const string Replay10 = "\uE8A1";
    public const string Forward10 = "\uE8A0";
    public const string VolumeOff = "\uE74F";
    public const string VolumeUp = "\uE767";
    public const string VolumeDown = "\uE766";
    public const string Fullscreen = "\uE740";
    public const string FullscreenExit = "\uE73F";
    public const string Back = "\uE72B";
    public const string Close = "\uE711";
    public const string Edit = "\uE70F";
    public const string Delete = "\uE74D";
    public const string Check = "\uE73E";
    public const string ChevronRight = "\uE76C";
    public const string ChevronLeft = "\uE76B";
    public const string More = "\uE712";
    public const string Info = "\uE946";
    public const string Cast = "\uE7B4"; // 网络/投播
    public const string Link = "\uE71B";
    public const string Upload = "\uE898";
    public const string Wifi = "\uE701";
    public const string Album = "\uE93C";
    public const string Person = "\uE77B";
    public const string Equalizer = "\uE799";
    public const string QueueMusic = "\uE8B0";
    public const string Repeat = "\uE8EE";
    public const string RepeatOne = "\uE8ED";
    public const string Shuffle = "\uE8B1";
    public const string LibraryMusic = "\uE8B9";
    public const string Movie = "\uE8B2";
    public const string Category = "\uE7AF";
    public const string Lyrics = "\uE7F4";
    public const string Warning = "\uE7BA";
    public const string Dot = "\uE915";
}
