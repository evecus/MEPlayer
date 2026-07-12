using System;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using MEPlayer.Services;
using MEPlayer.Views;

namespace MEPlayer.Controls;

/// <summary>
/// 底部播放栏（对应 Flutter 端 mini_player_bar.dart）。
///
/// 仅在四个主页面（视频/IPTV/音乐/设置）显示，播放页不显示。
/// 展示当前"活跃"的媒体（video/iptv/music 三选一），点击后进入对应播放页：
/// - 本地视频：按保存的进度续播
/// - IPTV：回到之前的频道继续播放
/// - 音乐：音乐本身在后台一直播放，点击只是重新打开播放页 UI
/// </summary>
public partial class MiniPlayerBar : UserControl
{
    private readonly GlobalPlayerService _player;
    private readonly AppSettingsService _settings;

    public MiniPlayerBar()
    {
        InitializeComponent();
        _player = App.Current.Player;
        _settings = App.Current.Settings;

        _player.PropertyChanged += OnPlayerPropertyChanged;
        UpdateContent();
    }

    private void OnPlayerPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        // 任何相关属性变化都重建内容（简化处理，性能足够）
        if (e.PropertyName == nameof(GlobalPlayerService.MiniBarKind) ||
            e.PropertyName == nameof(GlobalPlayerService.VideoTitle) ||
            e.PropertyName == nameof(GlobalPlayerService.VideoPositionSec) ||
            e.PropertyName == nameof(GlobalPlayerService.VideoDurationSec) ||
            e.PropertyName == nameof(GlobalPlayerService.IptvChannelName) ||
            e.PropertyName == nameof(GlobalPlayerService.IptvGroupName) ||
            e.PropertyName == nameof(GlobalPlayerService.MusicCurrentTitle) ||
            e.PropertyName == nameof(GlobalPlayerService.MusicCurrentArtist) ||
            e.PropertyName == nameof(GlobalPlayerService.MusicIsPlaying) ||
            e.PropertyName == nameof(GlobalPlayerService.MusicCurrentMeta))
        {
            Dispatcher.BeginInvoke(UpdateContent);
        }
    }

    private void UpdateContent()
    {
        ContentHost.Children.Clear();
        switch (_player.MiniBarKind)
        {
            case MiniBarKind.Video:
                ContentHost.Children.Add(BuildVideoContent());
                break;
            case MiniBarKind.Iptv:
                ContentHost.Children.Add(BuildIptvContent());
                break;
            case MiniBarKind.Music:
                ContentHost.Children.Add(BuildMusicContent());
                break;
        }
    }

    // ── 视频内容 ──────────────────────────────────────────────────────────
    private FrameworkElement BuildVideoContent()
    {
        var grid = new Grid { Margin = new Thickness(16, 0, 16, 0) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        // 左侧图标
        var icon = new Border
        {
            Width = 40, Height = 40,
            CornerRadius = new CornerRadius(8),
            Background = WithAlpha((Brush)Resources["M3.PrimaryContainer"], 140),
            Child = new TextBlock
            {
                Text = "\uE8B2", // VideoLibrary
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 20,
                Foreground = (Brush)Resources["M3.Primary"],
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            },
        };
        grid.Children.Add(icon);

        // 中间标题 + 进度
        var center = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 0, 0) };
        center.Children.Add(new TextBlock
        {
            Text = _player.VideoTitle,
            FontWeight = FontWeights.SemiBold,
            FontSize = 13,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Foreground = (Brush)Resources["M3.OnSurface"],
        });
        var progressRow = new Grid { Margin = new Thickness(0, 4, 0, 0) };
        progressRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        progressRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        progressRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var posText = new TextBlock
        {
            Text = FormatTime(_player.VideoPositionSec),
            FontSize = 10,
            Foreground = (Brush)Resources["M3.OnSurfaceVariant"],
        };
        Grid.SetColumn(posText, 0);
        progressRow.Children.Add(posText);

        var pct = _player.VideoDurationSec > 0 ? Math.Clamp(_player.VideoPositionSec / _player.VideoDurationSec, 0, 1) : 0;
        var progress = new ProgressBar
        {
            Value = pct * 100,
            Minimum = 0, Maximum = 100,
            Height = 3,
            Margin = new Thickness(6, 0, 6, 0),
            Background = WithAlpha((Brush)Resources["M3.OutlineVariant"], 80),
            Foreground = (Brush)Resources["M3.Primary"],
        };
        Grid.SetColumn(progress, 1);
        progressRow.Children.Add(progress);

        var durText = new TextBlock
        {
            Text = FormatTime(_player.VideoDurationSec),
            FontSize = 10,
            Foreground = (Brush)Resources["M3.OnSurfaceVariant"],
        };
        Grid.SetColumn(durText, 2);
        progressRow.Children.Add(durText);
        center.Children.Add(progressRow);

        Grid.SetColumn(center, 1);
        grid.Children.Add(center);

        // 右侧播放图标
        var playIcon = new TextBlock
        {
            Text = "\uE768", // PlayCircle
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 26,
            Foreground = (Brush)Resources["M3.Primary"],
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(8, 0, 0, 0),
        };
        Grid.SetColumn(playIcon, 2);
        grid.Children.Add(playIcon);

        var btn = new Button
        {
            Content = grid,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            Template = CreateTransparentButtonTemplate(),
        };
        btn.Click += (_, _) => ResumeVideo();
        return btn;
    }

    // ── IPTV 内容 ─────────────────────────────────────────────────────────
    private FrameworkElement BuildIptvContent()
    {
        var grid = new Grid { Margin = new Thickness(16, 0, 16, 0) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var icon = new Border
        {
            Width = 40, Height = 40,
            CornerRadius = new CornerRadius(8),
            Background = WithAlpha((Brush)Resources["M3.PrimaryContainer"], 140),
            Child = new TextBlock
            {
                Text = "\uE714", // LiveTv
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 20,
                Foreground = (Brush)Resources["M3.Primary"],
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            },
        };
        grid.Children.Add(icon);

        var center = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 8, 0) };
        center.Children.Add(new TextBlock
        {
            Text = _player.IptvChannelName,
            FontWeight = FontWeights.SemiBold,
            FontSize = 13,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Foreground = (Brush)Resources["M3.OnSurface"],
        });
        center.Children.Add(new TextBlock
        {
            Text = string.IsNullOrEmpty(_player.IptvGroupName) ? "IPTV" : _player.IptvGroupName,
            FontSize = 11,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Foreground = (Brush)Resources["M3.OnSurfaceVariant"],
            Margin = new Thickness(0, 2, 0, 0),
        });
        Grid.SetColumn(center, 1);
        grid.Children.Add(center);

        var paused = new Border
        {
            Background = Brushes.Red,
            CornerRadius = new CornerRadius(3),
            Padding = new Thickness(6, 2, 6, 2),
            Margin = new Thickness(8, 0, 0, 0),
            Child = new TextBlock
            {
                Text = "已暂停",
                Foreground = Brushes.White,
                FontSize = 10,
                FontWeight = FontWeights.Bold,
            },
        };
        paused.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(paused, 2);
        grid.Children.Add(paused);

        var playIcon = new TextBlock
        {
            Text = "\uE768",
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 26,
            Foreground = (Brush)Resources["M3.Primary"],
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(8, 0, 0, 0),
        };
        Grid.SetColumn(playIcon, 3);
        grid.Children.Add(playIcon);

        var btn = new Button
        {
            Content = grid,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            Template = CreateTransparentButtonTemplate(),
        };
        btn.Click += (_, _) => ResumeIptv();
        return btn;
    }

    // ── 音乐内容 ──────────────────────────────────────────────────────────
    private FrameworkElement BuildMusicContent()
    {
        var grid = new Grid { Margin = new Thickness(16, 0, 16, 0) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        // 左侧封面 + 标题（可点击打开播放页）
        var leftGrid = new Grid();
        leftGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        leftGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var cover = new Border
        {
            Width = 40, Height = 40,
            CornerRadius = new CornerRadius(8),
            Background = WithAlpha((Brush)Resources["M3.PrimaryContainer"], 140),
        };
        var coverBytes = _player.MusicCurrentMeta?.CoverBytes;
        if (coverBytes != null && coverBytes.Length > 0)
        {
            try
            {
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.StreamSource = new System.IO.MemoryStream(coverBytes);
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.EndInit();
                cover.Child = new Image { Source = bmp, Stretch = Stretch.UniformToFill };
            }
            catch { }
        }
        else
        {
            cover.Child = new TextBlock
            {
                Text = "\uE8D6", // MusicNote
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 20,
                Foreground = (Brush)Resources["M3.Primary"],
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            };
        }
        leftGrid.Children.Add(cover);

        var info = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 0, 0) };
        info.Children.Add(new TextBlock
        {
            Text = _player.MusicCurrentTitle,
            FontWeight = FontWeights.SemiBold,
            FontSize = 13,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Foreground = (Brush)Resources["M3.OnSurface"],
        });
        var artist = _player.MusicCurrentArtist;
        info.Children.Add(new TextBlock
        {
            Text = string.IsNullOrEmpty(artist) ? "音乐" : artist,
            FontSize = 11,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Foreground = (Brush)Resources["M3.OnSurfaceVariant"],
            Margin = new Thickness(0, 2, 0, 0),
        });
        Grid.SetColumn(info, 1);
        leftGrid.Children.Add(info);

        var openBtn = new Button
        {
            Content = leftGrid,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            Template = CreateTransparentButtonTemplate(),
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
        };
        openBtn.Click += (_, _) => OpenMusicPlayer();
        Grid.SetColumn(openBtn, 0);
        grid.Children.Add(openBtn);

        // 上一首 / 播放暂停 / 下一首
        var prevBtn = MakeIconButton("\uE892", "上一首", _player.MusicPrevious);
        Grid.SetColumn(prevBtn, 1);
        grid.Children.Add(prevBtn);

        var playBtn = MakeIconButton(
            _player.MusicIsPlaying ? "\uE769" : "\uE768",
            _player.MusicIsPlaying ? "暂停" : "播放",
            _player.MusicTogglePlay,
            30,
            (Brush)Resources["M3.Primary"]);
        Grid.SetColumn(playBtn, 2);
        grid.Children.Add(playBtn);

        var nextBtn = MakeIconButton("\uE893", "下一首", _player.MusicNext);
        Grid.SetColumn(nextBtn, 3);
        grid.Children.Add(nextBtn);

        return grid;
    }

    private Button MakeIconButton(string glyph, string tooltip, Action onClick, double size = 20, Brush? fg = null)
    {
        var btn = new Button
        {
            Content = new TextBlock
            {
                Text = glyph,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = size,
                Foreground = fg ?? (Brush)Resources["M3.OnSurfaceVariant"],
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            },
            Width = 36, Height = 36,
            Margin = new Thickness(4, 0, 4, 0),
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            ToolTip = tooltip,
            Template = CreateIconButtonTemplate(),
        };
        btn.Click += (_, _) => onClick();
        return btn;
    }

    private void ResumeVideo()
    {
        var args = _player.VideoResume;
        if (args == null) return;
        var page = new Pages.VideoPlayerPage(args);
        if (App.Current.MainWindow is Views.MainWindow mw)
            mw.OpenPlayerPage(page);
    }

    private void ResumeIptv()
    {
        var args = _player.IptvResume;
        if (args == null) return;
        var page = new Pages.IptvPlayerPage(args);
        if (App.Current.MainWindow is Views.MainWindow mw)
            mw.OpenPlayerPage(page);
    }

    private void OpenMusicPlayer()
    {
        if (_player.MusicPlaylist.Count == 0) return;
        var page = new Pages.MusicPlayerPage(_player.MusicPlaylist, _player.MusicCurrentIdx);
        if (App.Current.MainWindow is Views.MainWindow mw)
            mw.OpenPlayerPage(page);
    }

    private static string FormatTime(double sec)
    {
        if (sec < 0) sec = 0;
        var t = TimeSpan.FromSeconds(sec);
        if (t.TotalHours >= 1) return $"{(int)t.TotalHours}:{t.Minutes:D2}:{t.Seconds:D2}";
        return $"{t.Minutes}:{t.Seconds:D2}";
    }

    private static SolidColorBrush WithAlpha(Brush b, byte alpha)
    {
        if (b is SolidColorBrush sc)
        {
            var c = sc.Color;
            return new SolidColorBrush(Color.FromArgb(alpha, c.R, c.G, c.B));
        }
        return Brushes.Transparent;
    }

    private static ControlTemplate CreateTransparentButtonTemplate()
    {
        var xaml = @"
<ControlTemplate TargetType=""Button"" xmlns=""http://schemas.microsoft.com/winfx/2006/xaml/presentation"">
    <Border Background=""{TemplateBinding Background}"">
        <ContentPresenter HorizontalAlignment=""Stretch"" VerticalAlignment=""Center""/>
    </Border>
</ControlTemplate>";
        return (ControlTemplate)System.Windows.Markup.XamlReader.Parse(xaml);
    }

    private static ControlTemplate CreateIconButtonTemplate()
    {
        var xaml = @"
<ControlTemplate TargetType=""Button"" xmlns=""http://schemas.microsoft.com/winfx/2006/xaml/presentation"">
    <Border Background=""{TemplateBinding Background}"" CornerRadius=""18"">
        <ContentPresenter HorizontalAlignment=""Center"" VerticalAlignment=""Center""/>
    </Border>
    <ControlTemplate.Triggers>
        <Trigger Property=""IsMouseOver"" Value=""True"">
            <Setter Property=""Background"" Value=""{DynamicResource M3.SurfaceContainerHighest}""/>
        </Trigger>
    </ControlTemplate.Triggers>
</ControlTemplate>";
        return (ControlTemplate)System.Windows.Markup.XamlReader.Parse(xaml);
    }
}
