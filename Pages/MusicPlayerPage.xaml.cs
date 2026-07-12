using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using MEPlayer.Helpers;
using MEPlayer.Services;

namespace MEPlayer.Pages;

/// <summary>
/// 音乐播放页（对应 Flutter 端 music_player_page.dart）。
///
/// 结构：左侧封面区（300px）+ 中间歌词+控制区（弹性）+ 右侧播放队列（320px，可收起）。
/// 实际播放状态由 GlobalPlayerService 统一持有并贯穿整个 App 生命周期：
/// 离开本页不会停止播放，只有切到视频/IPTV 或退出程序才会停止。
/// 本页只是把 UI 需要的字段/方法转发到 GlobalPlayerService。
/// </summary>
public partial class MusicPlayerPage : UserControl
{
    private readonly GlobalPlayerService _player;
    private readonly List<Dictionary<string, string>> _initialPlaylist;
    private readonly int _initialIndex;

    // ── UI 元素引用 ──────────────────────────────────────────────────────
    private Border _coverBorder = null!;
    private TextBlock _titleText = null!;
    private TextBlock _artistText = null!;
    private ScrollViewer _lyricsScroll = null!;
    private StackPanel _lyricsPanel = null!;
    private TextBlock _emptyLyricsText = null!;
    private Slider _progressSlider = null!;
    private TextBlock _positionText = null!;
    private TextBlock _durationText = null!;
    private TextBlock _playPauseIcon = null!;
    private Button _playPauseBtn = null!;
    private Slider _volumeSlider = null!;
    private TextBlock _repeatIcon = null!;
    private Button _repeatBtn = null!;
    private TextBlock _speedText = null!;
    private Button _queueToggleBtn = null!;
    private ColumnDefinition _queueColumn = null!;
    private StackPanel _queuePanel = null!;
    private TextBlock _queueCountText = null!;

    private bool _showQueue;
    private bool _sliderDragging;

    // 歌词滚动参数（与 Flutter _LyricsView 一致）
    private const double LrcItemHeight = 44.0;
    private const double LrcTopPad = 120.0;

    public MusicPlayerPage(List<Dictionary<string, string>> playlist, int index)
    {
        InitializeComponent();
        _player = App.Current.Player;
        _initialPlaylist = playlist;
        _initialIndex = index;

        BuildShell();
        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  生命周期
    // ═══════════════════════════════════════════════════════════════════════
    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        // 若传入了新的播放列表且目标下标与当前正在播放的下标不同，才重新播放；
        // 若只是重新打开同一份播放列表且目标下标就是当前下标（例如通过底部播放栏回到本页），
        // 则不打断当前播放进度。
        var samePlaylist = IsSamePlaylist(_initialPlaylist, _player.MusicPlaylist);
        var sameIndex = samePlaylist && _initialIndex == _player.MusicCurrentIdx;
        if (_initialPlaylist.Count > 0 && !sameIndex)
        {
            _player.PlayMusicPlaylist(_initialPlaylist, _initialIndex);
        }
        else
        {
            _player.MiniBarKind = MiniBarKind.Music;
        }

        _player.PropertyChanged += OnPlayerPropertyChanged;
        RefreshAll();
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        _player.PropertyChanged -= OnPlayerPropertyChanged;
    }

    /// <summary>关闭播放页覆盖层，回到 Tab 主页（同窗口模式）。音乐继续在后台播放。</summary>
    private void GoBack()
    {
        if (App.Current.MainWindow is Views.MainWindow mw)
            mw.ClosePlayerPage();
    }

    private static bool IsSamePlaylist(List<Dictionary<string, string>> a, List<Dictionary<string, string>> b)
    {
        if (a.Count != b.Count) return false;
        for (int i = 0; i < a.Count; i++)
        {
            a[i].TryGetValue("path", out var pa);
            b[i].TryGetValue("path", out var pb);
            if (pa != pb) return false;
        }
        return true;
    }

    private void OnPlayerPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        Dispatcher.BeginInvoke(new Action(() =>
        {
            switch (e.PropertyName)
            {
                case nameof(GlobalPlayerService.MusicIsPlaying):
                    RefreshPlayPause();
                    break;
                case nameof(GlobalPlayerService.MusicPositionSec):
                case nameof(GlobalPlayerService.MusicDurationSec):
                    RefreshProgress();
                    DebugLogOnce($"[MusicPlayerPage] Refresh pos={_player.MusicPositionSec:F2} dur={_player.MusicDurationSec:F2}");
                    break;
                case nameof(GlobalPlayerService.MusicVolume):
                    RefreshVolume();
                    break;
                case nameof(GlobalPlayerService.MusicCurrentMeta):
                case nameof(GlobalPlayerService.MusicCurrentTitle):
                case nameof(GlobalPlayerService.MusicCurrentArtist):
                    RefreshCoverAndTitle();
                    RefreshLyrics();
                    break;
                case nameof(GlobalPlayerService.MusicCurrentLrcIdx):
                    RefreshLyricsHighlight();
                    break;
                case nameof(GlobalPlayerService.MusicRepeatMode):
                    RefreshRepeat();
                    break;
                case nameof(GlobalPlayerService.MusicPlaySpeed):
                    RefreshSpeed();
                    break;
                case nameof(GlobalPlayerService.MusicCurrentIdx):
                    RefreshQueue();
                    break;
            }
        }));
    }

    private void RefreshAll()
    {
        RefreshCoverAndTitle();
        RefreshLyrics();
        RefreshPlayPause();
        RefreshProgress();
        RefreshVolume();
        RefreshRepeat();
        RefreshSpeed();
        RefreshQueue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  外层骨架构建
    // ═══════════════════════════════════════════════════════════════════════
    private void BuildShell()
    {
        // 顶栏 + 主体
        RootGrid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(48, GridUnitType.Pixel) });
        RootGrid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        BuildTopBar();

        // 主体三列：封面(300) | 歌词+控制(弹性) | 队列(320/0)
        var body = new Grid();
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(300, GridUnitType.Pixel) });
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Pixel) });
        body.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _queueColumn = new ColumnDefinition { Width = new GridLength(0) };
        body.ColumnDefinitions.Add(_queueColumn);
        Grid.SetRow(body, 1);
        RootGrid.Children.Add(body);

        BuildCoverPanel(body);
        body.Children.Add(new Border { Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 60) });
        Grid.SetColumn(body.Children[body.Children.Count - 1], 1);
        BuildRightPanel(body);
        BuildQueuePanel(body);
    }

    private void BuildTopBar()
    {
        var bar = new Grid
        {
            Background = UiHelper.BrushOf("M3.Surface"),
        };
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 返回按钮
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); // 标题
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 播放列表按钮

        // 返回按钮
        var backBtn = new Button
        {
            Content = new TextBlock
            {
                Text = Mdl2.Back,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 16,
                Foreground = UiHelper.BrushOf("M3.OnSurface"),
            },
            Width = 36, Height = 36,
            Margin = new Thickness(4, 0, 0, 0),
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            ToolTip = "返回",
            Template = UiHelper.TransparentButtonTemplate(),
        };
        backBtn.Click += (_, _) => GoBack();
        bar.Children.Add(backBtn);

        var title = new TextBlock
        {
            Text = "正在播放",
            FontSize = 14,
            FontWeight = FontWeights.SemiBold,
            Foreground = UiHelper.BrushOf("M3.OnSurface"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(8, 0, 0, 0),
        };
        Grid.SetColumn(title, 1);
        bar.Children.Add(title);

        _queueToggleBtn = new Button
        {
            Content = new TextBlock
            {
                Text = Mdl2.QueueMusic,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 18,
                Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            },
            Width = 36, Height = 36,
            Margin = new Thickness(0, 0, 8, 0),
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            ToolTip = "播放列表",
            Template = UiHelper.TransparentButtonTemplate(),
        };
        _queueToggleBtn.Click += (_, _) => ToggleQueue();
        Grid.SetColumn(_queueToggleBtn, 2);
        bar.Children.Add(_queueToggleBtn);

        // 底部分隔线
        var barWithDivider = new Grid();
        barWithDivider.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        barWithDivider.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Pixel) });
        Grid.SetRow(bar, 0);
        barWithDivider.Children.Add(bar);
        var divider = new Border { Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 60) };
        Grid.SetRow(divider, 1);
        barWithDivider.Children.Add(divider);
        Grid.SetRow(barWithDivider, 0);
        RootGrid.Children.Add(barWithDivider);
    }

    // ── 左侧封面面板 ──────────────────────────────────────────────────────
    private void BuildCoverPanel(Grid parent)
    {
        var panel = new Grid
        {
            Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.SurfaceContainerHighest"), 40),
            Margin = new Thickness(28),
            VerticalAlignment = VerticalAlignment.Center,
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        panel.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 封面
        panel.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 标题
        panel.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 艺术家

        _coverBorder = new Border
        {
            Width = 220, Height = 220,
            CornerRadius = new CornerRadius(14),
            ClipToBounds = true,
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        Grid.SetRow(_coverBorder, 0);
        panel.Children.Add(_coverBorder);

        _titleText = new TextBlock
        {
            FontSize = 16,
            FontWeight = FontWeights.Bold,
            TextAlignment = TextAlignment.Center,
            TextWrapping = TextWrapping.Wrap,
            MaxHeight = 50,
            Margin = new Thickness(0, 24, 0, 0),
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        Grid.SetRow(_titleText, 1);
        panel.Children.Add(_titleText);

        _artistText = new TextBlock
        {
            FontSize = 13,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            TextAlignment = TextAlignment.Center,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Margin = new Thickness(0, 6, 0, 0),
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        Grid.SetRow(_artistText, 2);
        panel.Children.Add(_artistText);

        Grid.SetColumn(panel, 0);
        parent.Children.Add(panel);
    }

    // ── 中间面板（歌词 + 控制） ───────────────────────────────────────────
    private void BuildRightPanel(Grid parent)
    {
        var panel = new Grid();
        panel.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) }); // 歌词
        panel.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 控制栏
        Grid.SetColumn(panel, 2);
        parent.Children.Add(panel);

        BuildLyricsView(panel);
        BuildControlsBar(panel);
    }

    private void BuildLyricsView(Grid parent)
    {
        _lyricsPanel = new StackPanel();
        _emptyLyricsText = new TextBlock
        {
            Text = "暂无歌词",
            FontSize = 14,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        _lyricsScroll = new ScrollViewer
        {
            Content = _lyricsPanel,
            VerticalScrollBarVisibility = ScrollBarVisibility.Hidden,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Padding = new Thickness(32, LrcTopPad, 32, LrcTopPad),
        };
        Grid.SetRow(_lyricsScroll, 0);
        parent.Children.Add(_lyricsScroll);
    }

    private void BuildControlsBar(Grid parent)
    {
        var bar = new Grid
        {
            Background = UiHelper.BrushOf("M3.Surface"),
        };
        bar.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 进度条行
        bar.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 按钮行
        bar.Margin = new Thickness(24, 12, 24, 16);

        // ── 进度条行 ──
        var progressRow = new Grid();
        progressRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 当前时间
        progressRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); // 滑块
        progressRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 总时长

        _positionText = new TextBlock
        {
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 8, 0),
        };
        progressRow.Children.Add(_positionText);

        _progressSlider = new Slider
        {
            Minimum = 0,
            Maximum = 1,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 0, 4),
            TickFrequency = 0.001,
            IsSnapToTickEnabled = false,
            // 【BUG 修复】WPF Slider 默认 LargeChange=1；这里 Maximum-Minimum 正好也是 1
            // （因为用的是 0~1 的百分比值域），导致点击滑轨（而不是拖动滑块本身）时，
            // 默认行为会让 Value 直接跳变一整个 LargeChange（=1），也就是瞬间跳到
            // Maximum——表现为"点击进度条中间位置，进度条直接瞬间跑满"。
            // 显式设置一个很小的 LargeChange，避免这种整段跳变；
            // 同时开启 IsMoveToPointEnabled，让点击滑轨直接精确跳转到点击处对应的比例
            // （这也更符合进度条"点哪跳哪"的直觉交互，而不是步进式的 LargeChange 跳动）。
            LargeChange = 0.01,
            IsMoveToPointEnabled = true,
        };
        _progressSlider.ValueChanged += OnProgressSliderChanged;
        // 拖动时暂停自动更新，松开时 seek
        _progressSlider.PreviewMouseLeftButtonDown += (_, _) => _sliderDragging = true;
        _progressSlider.PreviewMouseLeftButtonUp += (_, _) =>
        {
            _sliderDragging = false;
            _player.MusicSeekTo(_progressSlider.Value * _player.MusicDurationSec);
        };
        Grid.SetColumn(_progressSlider, 1);
        progressRow.Children.Add(_progressSlider);

        _durationText = new TextBlock
        {
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(8, 0, 0, 0),
        };
        Grid.SetColumn(_durationText, 2);
        progressRow.Children.Add(_durationText);

        Grid.SetRow(progressRow, 0);
        bar.Children.Add(progressRow);

        // ── 按钮行 ──
        var btnRow = new Grid { Margin = new Thickness(0, 8, 0, 0) };
        btnRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(140, GridUnitType.Pixel) }); // 音量
        btnRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });   // 居中控制
        btnRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(140, GridUnitType.Pixel) }); // 右侧留白

        // 左侧音量
        var volRow = new Grid();
        volRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        volRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        volRow.Children.Add(new TextBlock
        {
            Text = Mdl2.VolumeUp,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 16,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 6, 0),
        });
        _volumeSlider = new Slider
        {
            Minimum = 0,
            Maximum = 100,
            VerticalAlignment = VerticalAlignment.Center,
        };
        _volumeSlider.ValueChanged += (_, e) =>
        {
            if (Math.Abs(e.NewValue - _player.MusicVolume) > 0.5)
                _player.MusicSetVolume(_volumeSlider.Value);
        };
        Grid.SetColumn(_volumeSlider, 1);
        volRow.Children.Add(_volumeSlider);
        Grid.SetColumn(volRow, 0);
        btnRow.Children.Add(volRow);

        // 居中播放控制
        var centerRow = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
        };

        _repeatBtn = MakeIconButton(Mdl2.Repeat, "列表循环", 20, (_, _) => _player.MusicCycleRepeat());
        _repeatIcon = (TextBlock)((StackPanel)_repeatBtn.Content).Children.Cast<TextBlock>().First();
        centerRow.Children.Add(_repeatBtn);

        centerRow.Children.Add(MakeIconButton(Mdl2.Prev, "上一首", 26, (_, _) => _player.MusicPrevious(), margin: 8));

        _playPauseBtn = MakeIconButton(Mdl2.PlayArrow, "播放", 28, (_, _) => _player.MusicTogglePlay(),
            fg: UiHelper.BrushOf("M3.Primary"), isPrimary: true);
        _playPauseIcon = (TextBlock)((StackPanel)_playPauseBtn.Content).Children.Cast<TextBlock>().First();
        centerRow.Children.Add(_playPauseBtn);

        centerRow.Children.Add(MakeIconButton(Mdl2.Next, "下一首", 26, (_, _) => _player.MusicNext(), margin: 4));

        // 播放速度
        var speedBtn = new Button
        {
            Content = new TextBlock
            {
                Text = "1x",
                FontSize = 12,
                Foreground = UiHelper.BrushOf("M3.OnSurface"),
            },
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            ToolTip = "播放速度",
            Template = UiHelper.TransparentButtonTemplate(),
            Margin = new Thickness(8, 0, 0, 0),
            VerticalAlignment = VerticalAlignment.Center,
        };
        _speedText = (TextBlock)speedBtn.Content;
        var speedMenu = new ContextMenu();
        foreach (var s in new[] { 0.5, 0.75, 1.0, 1.25, 1.5, 2.0 })
        {
            var item = new MenuItem { Header = $"{s}x", Tag = s };
            item.Click += (_, _) => _player.MusicSetSpeed(s);
            speedMenu.Items.Add(item);
        }
        speedBtn.ContextMenu = speedMenu;
        speedBtn.Click += (_, _) => speedMenu.IsOpen = true;
        centerRow.Children.Add(speedBtn);

        Grid.SetColumn(centerRow, 1);
        btnRow.Children.Add(centerRow);

        // 右侧留白（抵消左侧音量条宽度，让播放控制按钮真正居中）
        Grid.SetColumn(btnRow, 0);
        Grid.SetRow(btnRow, 1);
        bar.Children.Add(btnRow);

        // 顶部分隔线 + 控制栏（先组装好再一次性加到 parent，避免重复添加）
        var barWithDivider = new Grid();
        barWithDivider.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Pixel) });
        barWithDivider.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        var topDivider = new Border { Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 60) };
        Grid.SetRow(topDivider, 0);
        barWithDivider.Children.Add(topDivider);
        Grid.SetRow(bar, 1);
        barWithDivider.Children.Add(bar);
        Grid.SetRow(barWithDivider, 1);
        parent.Children.Add(barWithDivider);
    }

    // ── 右侧播放队列面板 ──────────────────────────────────────────────────
    private void BuildQueuePanel(Grid parent)
    {
        var queueBorder = new Border
        {
            Background = UiHelper.BrushOf("M3.Surface"),
            BorderBrush = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 60),
            BorderThickness = new Thickness(1, 0, 0, 0),
        };
        var queueGrid = new Grid();
        queueGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 标题
        queueGrid.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) }); // 列表

        // 标题栏
        var header = new Grid { Margin = new Thickness(16, 14, 8, 10) };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.Children.Add(new TextBlock
        {
            Text = "播放列表",
            FontSize = 14,
            FontWeight = FontWeights.SemiBold,
            VerticalAlignment = VerticalAlignment.Center,
        });
        _queueCountText = new TextBlock
        {
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(8, 0, 0, 0),
        };
        Grid.SetColumn(_queueCountText, 1);
        header.Children.Add(_queueCountText);
        var closeBtn = new Button
        {
            Content = new TextBlock
            {
                Text = Mdl2.Close,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 14,
                Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            },
            Width = 28, Height = 28,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            ToolTip = "关闭",
            Template = UiHelper.TransparentButtonTemplate(),
        };
        closeBtn.Click += (_, _) => ToggleQueue();
        Grid.SetColumn(closeBtn, 2);
        header.Children.Add(closeBtn);
        Grid.SetRow(header, 0);
        queueGrid.Children.Add(header);

        // 列表
        _queuePanel = new StackPanel { Margin = new Thickness(0, 4, 0, 0) };
        var queueScroll = new ScrollViewer
        {
            Content = _queuePanel,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        };
        Grid.SetRow(queueScroll, 1);
        queueGrid.Children.Add(queueScroll);

        queueBorder.Child = queueGrid;
        Grid.SetColumn(queueBorder, 3);
        parent.Children.Add(queueBorder);
    }

    // ── 图标按钮工厂 ──────────────────────────────────────────────────────
    private Button MakeIconButton(string glyph, string tooltip, double size,
        RoutedEventHandler onClick, Brush? fg = null, bool isPrimary = false, int margin = 4)
    {
        var sp = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        var icon = new TextBlock
        {
            Text = glyph,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = size,
            Foreground = fg ?? UiHelper.BrushOf("M3.OnSurface"),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        sp.Children.Add(icon);

        var btn = new Button
        {
            Content = sp,
            Width = isPrimary ? 48 : 36,
            Height = isPrimary ? 48 : 36,
            Margin = new Thickness(margin, 0, margin, 0),
            Background = isPrimary ? UiHelper.BrushOf("M3.Primary") : Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            ToolTip = tooltip,
            Template = isPrimary ? PrimaryButtonTemplate() : UiHelper.TransparentButtonTemplate(),
        };
        if (isPrimary)
        {
            icon.Foreground = UiHelper.BrushOf("M3.OnPrimary");
        }
        btn.Click += onClick;
        return btn;
    }

    private static ControlTemplate PrimaryButtonTemplate()
    {
        var xaml = @"
<ControlTemplate TargetType=""Button"" xmlns=""http://schemas.microsoft.com/winfx/2006/xaml/presentation"">
    <Border Background=""{TemplateBinding Background}"" CornerRadius=""24"">
        <ContentPresenter HorizontalAlignment=""Center"" VerticalAlignment=""Center""/>
    </Border>
</ControlTemplate>";
        return (ControlTemplate)System.Windows.Markup.XamlReader.Parse(xaml);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  UI 刷新
    // ═══════════════════════════════════════════════════════════════════════
    private void RefreshCoverAndTitle()
    {
        _titleText.Text = _player.MusicCurrentTitle;
        _artistText.Text = _player.MusicCurrentArtist;

        var coverBytes = _player.MusicCurrentMeta?.CoverBytes;
        _coverBorder.Child = null;
        if (coverBytes != null && coverBytes.Length > 0)
        {
            try
            {
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.StreamSource = new MemoryStream(coverBytes);
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.EndInit();
                _coverBorder.Child = new Image { Source = bmp, Stretch = Stretch.UniformToFill };
            }
            catch { }
        }
        if (_coverBorder.Child == null)
        {
            _coverBorder.Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.PrimaryContainer"), 120);
            _coverBorder.Child = new TextBlock
            {
                Text = Mdl2.MusicNote,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 64,
                Foreground = UiHelper.BrushOf("M3.Primary"),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            };
        }
        else
        {
            _coverBorder.Background = Brushes.Transparent;
        }
    }

    private void RefreshLyrics()
    {
        _lyricsPanel.Children.Clear();
        var meta = _player.MusicCurrentMeta;
        if (meta == null)
        {
            _lyricsPanel.Children.Add(new TextBlock
            {
                Text = "加载中...",
                FontSize = 12,
                Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 80, 0, 0),
            });
            return;
        }
        if (meta.LrcLines.Count == 0)
        {
            _lyricsPanel.Children.Add(new TextBlock
            {
                Text = "暂无歌词",
                FontSize = 14,
                Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 80, 0, 0),
            });
            return;
        }
        for (int i = 0; i < meta.LrcLines.Count; i++)
        {
            var line = meta.LrcLines[i];
            var tb = new TextBlock
            {
                Text = line.Text,
                FontSize = 15,
                TextAlignment = TextAlignment.Center,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Height = LrcItemHeight,
                Margin = new Thickness(0, 0, 0, 0),
                Tag = i,
            };
            _lyricsPanel.Children.Add(tb);
        }
        RefreshLyricsHighlight();
    }

    private void RefreshLyricsHighlight()
    {
        var meta = _player.MusicCurrentMeta;
        if (meta == null || meta.LrcLines.Count == 0) return;
        var activeIdx = _player.MusicCurrentLrcIdx;

        for (int i = 0; i < _lyricsPanel.Children.Count; i++)
        {
            if (_lyricsPanel.Children[i] is not TextBlock tb) continue;
            bool isActive = i == activeIdx;
            tb.Foreground = isActive ? UiHelper.BrushOf("M3.Primary") : UiHelper.WithAlpha(UiHelper.BrushOf("M3.OnSurface"), 140);
            tb.FontWeight = isActive ? FontWeights.Bold : FontWeights.Normal;
            tb.FontSize = isActive ? 17 : 15;
        }

        // 滚动到当前行（与 Flutter _onLrcIdxChanged 一致）
        if (activeIdx < 0) return;
        var offset = LrcTopPad + activeIdx * LrcItemHeight - 160;
        offset = Math.Max(0, Math.Min(offset, _lyricsScroll.ScrollableHeight));
        _lyricsScroll.ScrollToVerticalOffset(offset);
    }

    private void RefreshPlayPause()
    {
        if (_player.MusicIsPlaying)
        {
            _playPauseIcon.Text = Mdl2.Pause;
            _playPauseBtn.ToolTip = "暂停";
        }
        else
        {
            _playPauseIcon.Text = Mdl2.PlayArrow;
            _playPauseBtn.ToolTip = "播放";
        }
    }

    private void RefreshProgress()
    {
        var pos = _player.MusicPositionSec;
        var dur = _player.MusicDurationSec;
        _positionText.Text = FormatTime(pos);
        _durationText.Text = FormatTime(dur);
        if (!_sliderDragging && dur > 0)
        {
            var pct = Math.Clamp(pos / dur, 0, 1);
            _progressSlider.Value = pct;
        }
    }

    // ── 诊断：排查"进度条/歌词不动"问题用，节流写日志（每 2 秒最多一条）──
    // 【BUG 修复】和 MpvPlayer.WriteMpvLog / GlobalPlayerService.WriteGlobalLog 一样，
    // 这里也在写同一个 mpv.log 文件，此前不带来源标识、也没有和其它写入者共享锁，
    // 并发写入时可能互相抛 IOException 导致日志静默丢行。加上 [MusicPage] 前缀，
    // 并复用 MpvPlayer.LogFileLock 序列化写入。
    private DateTime _lastDebugLogAt = DateTime.MinValue;
    private void DebugLogOnce(string line)
    {
        var now = DateTime.Now;
        if ((now - _lastDebugLogAt).TotalSeconds < 2) return;
        _lastDebugLogAt = now;
        try
        {
            var dir = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "MEPlayer");
            var path = System.IO.Path.Combine(dir, "mpv.log");
            var text = $"{now:HH:mm:ss.fff} [MusicPage] {line}{Environment.NewLine}";
            lock (MEPlayer.Player.MpvPlayer.LogFileLock)
            {
                System.IO.Directory.CreateDirectory(dir);
                System.IO.File.AppendAllText(path, text);
            }
        }
        catch { }
    }

    private void RefreshVolume()
    {
        if (Math.Abs(_volumeSlider.Value - _player.MusicVolume) > 0.5)
            _volumeSlider.Value = _player.MusicVolume;
    }

    private void RefreshRepeat()
    {
        var mode = _player.MusicRepeatMode;
        _repeatIcon.Text = mode switch
        {
            GlobalRepeatMode.One => Mdl2.RepeatOne,
            GlobalRepeatMode.Shuffle => Mdl2.Shuffle,
            _ => Mdl2.Repeat,
        };
        _repeatBtn.ToolTip = mode switch
        {
            GlobalRepeatMode.List => "列表循环",
            GlobalRepeatMode.Shuffle => "随机播放",
            GlobalRepeatMode.One => "单曲循环",
            _ => "列表循环",
        };
        _repeatIcon.Foreground = mode == GlobalRepeatMode.Shuffle
            ? UiHelper.BrushOf("M3.Primary")
            : UiHelper.BrushOf("M3.OnSurface");
    }

    private void RefreshSpeed()
    {
        _speedText.Text = $"{_player.MusicPlaySpeed}x";
    }

    private void RefreshQueue()
    {
        _queuePanel.Children.Clear();
        var playlist = _player.MusicPlaylist;
        var activeIdx = _player.MusicCurrentIdx;
        _queueCountText.Text = $"{playlist.Count} 首";

        if (playlist.Count == 0)
        {
            _queuePanel.Children.Add(new TextBlock
            {
                Text = "播放列表为空",
                FontSize = 12,
                Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 24, 0, 0),
            });
            return;
        }

        for (int i = 0; i < playlist.Count; i++)
        {
            var track = playlist[i];
            var name = UiHelper.WithoutExtension(track.TryGetValue("name", out var n) ? n : "");
            var active = i == activeIdx;
            var tile = MakeQueueTile(i + 1, name, active);
            var captured = i;
            tile.MouseLeftButtonDown += (_, _) => _player.MusicPlayAt(captured);
            _queuePanel.Children.Add(tile);
        }
    }

    private Border MakeQueueTile(int index, string name, bool active)
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(28) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        FrameworkElement idxOrIcon;
        if (active)
        {
            idxOrIcon = new TextBlock
            {
                Text = Mdl2.Equalizer,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 14,
                Foreground = UiHelper.BrushOf("M3.Primary"),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            };
        }
        else
        {
            idxOrIcon = new TextBlock
            {
                Text = index.ToString(),
                FontSize = 12,
                Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            };
        }
        grid.Children.Add(idxOrIcon);

        var nameTb = new TextBlock
        {
            Text = name,
            FontSize = 13,
            TextTrimming = TextTrimming.CharacterEllipsis,
            FontWeight = active ? FontWeights.SemiBold : FontWeights.Normal,
            Foreground = active ? UiHelper.BrushOf("M3.Primary") : UiHelper.BrushOf("M3.OnSurface"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(10, 0, 0, 0),
        };
        Grid.SetColumn(nameTb, 1);
        grid.Children.Add(nameTb);

        return new Border
        {
            Child = grid,
            Padding = new Thickness(16, 8, 16, 8),
            Cursor = Cursors.Hand,
            Background = active ? UiHelper.WithAlpha(UiHelper.BrushOf("M3.PrimaryContainer"), 60) : Brushes.Transparent,
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  操作
    // ═══════════════════════════════════════════════════════════════════════
    private void ToggleQueue()
    {
        _showQueue = !_showQueue;
        var anim = new GridLengthAnimation
        {
            From = _queueColumn.Width,
            To = new GridLength(_showQueue ? 320 : 0),
            Duration = TimeSpan.FromMilliseconds(200),
        };
        _queueColumn.BeginAnimation(ColumnDefinition.WidthProperty, anim);

        // 队列图标高亮
        var icon = (TextBlock)_queueToggleBtn.Content;
        icon.Foreground = _showQueue
            ? UiHelper.BrushOf("M3.Primary")
            : UiHelper.BrushOf("M3.OnSurfaceVariant");
    }

    private void OnProgressSliderChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        // 拖动时只更新位置文字，松开时才 seek（在 PreviewMouseLeftButtonUp 中处理）
        if (_sliderDragging)
        {
            _positionText.Text = FormatTime(e.NewValue * _player.MusicDurationSec);
        }
    }

    private static string FormatTime(double sec)
    {
        if (sec < 0 || double.IsNaN(sec) || double.IsInfinity(sec)) sec = 0;
        var t = TimeSpan.FromSeconds(sec);
        if (t.TotalHours >= 1) return $"{(int)t.TotalHours}:{t.Minutes:D2}:{t.Seconds:D2}";
        return $"{t.Minutes}:{t.Seconds:D2}";
    }

    // ── GridLength 动画（ColumnDefinition.Width 原生不支持动画，需自定义） ──
    private sealed class GridLengthAnimation : AnimationTimeline
    {
        public static readonly DependencyProperty FromProperty =
            DependencyProperty.Register(nameof(From), typeof(GridLength), typeof(GridLengthAnimation));
        public static readonly DependencyProperty ToProperty =
            DependencyProperty.Register(nameof(To), typeof(GridLength), typeof(GridLengthAnimation));

        public GridLength From
        {
            get => (GridLength)GetValue(FromProperty);
            set => SetValue(FromProperty, value);
        }
        public GridLength To
        {
            get => (GridLength)GetValue(ToProperty);
            set => SetValue(ToProperty, value);
        }

        public override Type TargetPropertyType => typeof(GridLength);
        public override object GetCurrentValue(object defaultOriginValue, object defaultDestinationValue, AnimationClock animationClock)
        {
            if (animationClock == null) return From;
            var fromVal = From.Value;
            var toVal = To.Value;
            var t = animationClock.CurrentProgress ?? 0;
            // easeInOut
            var eased = t < 0.5 ? 2 * t * t : 1 - Math.Pow(-2 * t + 2, 2) / 2;
            return new GridLength(fromVal + (toVal - fromVal) * eased, To.IsStar ? GridUnitType.Star : GridUnitType.Pixel);
        }

        protected override Freezable CreateInstanceCore() => new GridLengthAnimation();
    }
}
