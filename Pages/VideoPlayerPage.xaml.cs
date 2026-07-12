using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using MEPlayer.Helpers;
using MEPlayer.Models;
using MEPlayer.Player;
using MEPlayer.Services;
using FormsScreen = System.Windows.Forms.Screen;

namespace MEPlayer.Pages;

/// <summary>
/// 视频播放页（对应 Flutter 端 video_player_page.dart）。
///
/// 结构：左侧视频区（MpvVideoHost + 覆盖层）+ 右侧播放列表面板（260px，全屏时隐藏）。
/// 覆盖层包含缓冲指示器、顶栏（返回 + 标题）、底栏（进度条 + 控制按钮）。
/// 控制条：鼠标移入视频区域立即显示，移出视频区域立即隐藏。
/// </summary>
public partial class VideoPlayerPage : UserControl
{
    // ── 播放参数 ──────────────────────────────────────────────────────────
    private string _url = "";
    private string _title = "";
    private bool _isLocal;
    private IReadOnlyList<VideoFile> _playlist = Array.Empty<VideoFile>();
    private int _currentIndex;
    private double _resumePositionSec; // 续播位置（秒）

    // ── 播放器 ────────────────────────────────────────────────────────────
    private MpvPlayer? _player;
    private MpvVideoHost? _videoHost;

    // ── UI 状态 ───────────────────────────────────────────────────────────
    private bool _isPlaying;
    private bool _isBuffering;
    private double _position;
    private double _duration;
    private double _volume = 100;
    private double _speed = 1.0;
    private bool _showControls = true;
    private bool _isFullScreen;
    // 【BUG 修复】_isDragging / _updatingFromPosition 此前是进度条和音量条共用的
    // 两个标志位。拖动音量条时会把 _isDragging 置为 true，这个标志同时被
    // OnPositionChanged 用来判断"是否正在拖动、要不要跳过刷新进度条"——于是
    // 用户只是在调节音量，进度条却会被误判为"正在拖动"而停止刷新，看起来像是
    // 进度卡住了。同理 _updatingFromPosition 也被进度条和音量条的程序化赋值
    // 共用，两者在 UI 线程上虽不会真正并发，但语义上完全无关，容易在后续维护中
    // 引入新的相互干扰。这里拆成两组独立的标志位，各自只影响对应的滑块。
    private bool _isDraggingProgress;
    private bool _isDraggingVolume;
    private bool _updatingProgressFromPosition;
    private bool _updatingVolumeFromPosition;
    private bool _pendingResume;

    // ── 鼠标是否在视频区域内的轮询定时器 ──────────────────────────────────
    // 用于判断鼠标当前是否位于视频区域内（用于决定点击穿透等）。
    // 因为 MpvVideoHost 是原生子窗口（HwndHost），WPF 的 MouseEnter/MouseLeave
    // 在其上不可靠（Airspace 问题），所以用屏幕坐标轮询代替。
    private DispatcherTimer? _mouseTrackTimer;
    private bool _mouseInVideoArea;

    // ── 控制条自动隐藏定时器 ─────────────────────────────────────────────
    // 不论普通模式还是全屏模式：鼠标移动 → 立即显示控制条并重置该定时器；
    // 定时器到期（默认无操作 3 秒）→ 自动隐藏控制条。
    private DispatcherTimer? _autoHideTimer;
    private static readonly TimeSpan AutoHideDelay = TimeSpan.FromSeconds(3);

    // ── 全屏前保存的窗口状态 ──────────────────────────────────────────────
    private WindowStyle _savedWindowStyle = WindowStyle.SingleBorderWindow;
    private WindowState _savedWindowState = WindowState.Normal;
    private ResizeMode _savedResizeMode = ResizeMode.CanResize;
    private Rect _savedWindowBounds;
    private Window? _hostWindow;

    // ── 速度选项 ──────────────────────────────────────────────────────────
    private static readonly double[] Speeds = { 0.5, 0.75, 1.0, 1.25, 1.5, 2.0 };

    // ── UI 控件引用 ───────────────────────────────────────────────────────
    private Grid _leftPanel = null!;      // 左侧整体区域（顶栏+视频+底栏），悬停检测以此为准
    private Grid _videoAreaGrid = null!;
    private Border _topBar = null!;
    private Border _bottomBar = null!;
    private Border _bufferingIndicator = null!;
    private TextBlock _titleText = null!;
    private Slider _progressSlider = null!;
    private TextBlock _positionText = null!;
    private TextBlock _durationText = null!;
    private Button _playPauseButton = null!;
    private Slider _volumeSlider = null!;
    private TextBlock _volumeIcon = null!;
    private Button _speedButton = null!;
    private Button _fullscreenButton = null!;
    private TextBlock _fullscreenIcon = null!;
    private ColumnDefinition _playlistColumn = null!;
    private Border _playlistPanel = null!;
    private ScrollViewer _playlistScroll = null!;
    private StackPanel _playlistStack = null!;

    // 顶栏/底栏、缓冲指示器改用 Popup 承载，规避 HwndHost 的 WPF Airspace 遮挡问题。
    private Popup _controlsPopup = null!;
    private Popup _bufferingPopup = null!;

    // ── 播放列表项 UI 引用（用于高亮当前项）────────────────────────────────
    private sealed class PlaylistItemUi
    {
        public Border Container = null!;
        public TextBlock IndexOrIcon = null!;
        public TextBlock TitleText = null!;
    }
    private readonly List<PlaylistItemUi> _playlistItems = new();

    private readonly GlobalPlayerService _globalPlayer;
    private readonly AppSettingsService _settings;

    // ══════════════════════════════════════════════════════════════════════
    // 构造
    // ══════════════════════════════════════════════════════════════════════

    public VideoPlayerPage(VideoResumeArgs args)
    {
        InitializeComponent();

        _globalPlayer = App.Current.Player;
        _settings = App.Current.Settings;

        // 解析参数（对应 Flutter onInit 中的 args 解析）
        _url = args.Url;
        _title = args.Title;
        _isLocal = args.IsLocal;
        _playlist = args.Playlist;
        _currentIndex = args.StartIndex;
        _resumePositionSec = args.ResumePositionMs / 1000.0;

        BuildUi();

        Loaded += OnLoaded;
        Unloaded += OnUnloaded;
        PreviewKeyDown += OnPreviewKeyDown;
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI 构建
    // ══════════════════════════════════════════════════════════════════════

    private void BuildUi()
    {
        RootGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        _playlistColumn = new ColumnDefinition { Width = new GridLength(260) };
        RootGrid.ColumnDefinitions.Add(_playlistColumn);

        // ── 左侧：视频区（单行铺满 + Popup 覆盖层）──
        // 之前的实现把顶栏/底栏放在 Auto 高度的独立行中，隐藏时用 Visibility.Hidden
        // 保持占位——但 WPF 中 Hidden 元素仍参与布局测量，Auto 行高不会塌陷为 0，
        // 导致顶栏/底栏始终占据固定空间，视频画面永远无法真正铺满整个区域，这就是
        // "全屏但控制条常驻显示、不是真全屏"的根因。
        // 改为单行 Grid：视频区始终 100% 铺满。顶栏/底栏/缓冲指示器不能直接作为
        // _videoAreaGrid 的普通子元素叠加在视频上——MpvVideoHost 是原生子窗口
        // （HwndHost），存在 WPF Airspace 问题：普通 WPF 兄弟元素无论 Z 顺序如何都会
        // 被原生子窗口盖住、不可见。因此顶栏/底栏改用 Popup 承载：Popup 会创建独立的
        // 顶层原生窗口，天然不受 Airspace 限制，能正确显示在视频画面之上，并跟随宿主
        // 窗口移动/缩放/进入退出全屏实时重新定位。
        var leftPanel = new Grid();
        _leftPanel = leftPanel;
        leftPanel.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) }); // 视频区（唯一一行，铺满）
        Grid.SetColumn(leftPanel, 0);
        RootGrid.Children.Add(leftPanel);

        _videoAreaGrid = new Grid
        {
            Background = Brushes.Black,
        };
        Grid.SetRow(_videoAreaGrid, 0);
        leftPanel.Children.Add(_videoAreaGrid);

        // 缓冲指示器：同样存在 Airspace 问题，改用 Popup 承载。
        _bufferingIndicator = new Border
        {
            Width = 48,
            Height = 48,
            CornerRadius = new CornerRadius(24),
            Background = new SolidColorBrush(Color.FromArgb(120, 0, 0, 0)),
            Visibility = Visibility.Collapsed,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            Child = new ProgressBar
            {
                IsIndeterminate = true,
                Width = 28,
                Height = 28,
                Foreground = Brushes.White,
                Background = Brushes.Transparent,
                BorderBrush = Brushes.Transparent,
            },
        };
        _bufferingPopup = new Popup
        {
            Placement = PlacementMode.Relative,
            PlacementTarget = _videoAreaGrid,
            AllowsTransparency = true,
            IsOpen = false,
            Child = new Grid
            {
                Width = 1, Height = 1, // 占位，实际尺寸由 SyncOverlayPopups 同步
                Children = { _bufferingIndicator },
            },
        };

        BuildTopBar();
        BuildBottomBar();

        // 顶栏 + 底栏放进同一个覆盖 Popup（Popup 内部仍是普通 WPF 元素树，
        // 顶栏 VerticalAlignment=Top、底栏 VerticalAlignment=Bottom，
        // 在 Popup 内部按视频区尺寸铺满即可各自贴边）。
        var overlayRoot = new Grid { IsHitTestVisible = true };
        overlayRoot.Children.Add(_topBar);
        overlayRoot.Children.Add(_bottomBar);
        _controlsPopup = new Popup
        {
            Placement = PlacementMode.Relative,
            PlacementTarget = _videoAreaGrid,
            AllowsTransparency = true,
            IsOpen = false,
            StaysOpen = true,
            Child = overlayRoot,
        };

        // ── 分隔线 ────────────────────────────────────────────────────
        var separator = new Border
        {
            Width = 1,
            Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 80),
            HorizontalAlignment = HorizontalAlignment.Right,
        };
        Grid.SetColumn(separator, 0);
        RootGrid.Children.Add(separator);

        // ── 右侧：播放列表面板 ────────────────────────────────────────
        _playlistPanel = new Border
        {
            Background = UiHelper.BrushOf("M3.Surface"),
        };
        Grid.SetColumn(_playlistPanel, 1);
        RootGrid.Children.Add(_playlistPanel);

        BuildPlaylistPanel();
    }

    /// <summary>顶栏：返回按钮 + 标题，黑色渐变背景。</summary>
    private void BuildTopBar()
    {
        var content = new Grid
        {
            Margin = new Thickness(2, 4, 12, 18),
        };
        content.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        content.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        // 返回按钮（缩小）
        var backBtn = MakeIconButton(Mdl2.Back, "返回 (Esc)", () =>
        {
            if (_isFullScreen) ExitFullScreen();
            else GoBack();
        }, 16, 28);
        content.Children.Add(backBtn);

        // 标题（缩小）
        _titleText = new TextBlock
        {
            Text = _title,
            Foreground = Brushes.White,
            FontSize = 12,
            FontWeight = FontWeights.SemiBold,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(4, 0, 0, 0),
            TextTrimming = TextTrimming.CharacterEllipsis,
        };
        Grid.SetColumn(_titleText, 1);
        content.Children.Add(_titleText);

        _topBar = new Border
        {
            VerticalAlignment = VerticalAlignment.Top,
            Child = content,
            Background = CreateVerticalGradient(0xDD, 0x00),
        };
        // 控制条显隐现在完全由鼠标是否位于视频区域决定（悬停显示/移出立即隐藏），
        // 不再需要点击切换。
        // _topBar 由 BuildUi 加入 leftPanel，这里不再 Add
    }

    /// <summary>底栏：进度条 + 时间戳 + 控制按钮行，黑色渐变背景。</summary>
    private void BuildBottomBar()
    {
        var outerStack = new StackPanel
        {
            Margin = new Thickness(10, 14, 10, 6),
        };

        // ── 进度条 + 时间戳 ───────────────────────────────────────────
        _progressSlider = new Slider
        {
            Minimum = 0,
            Maximum = 1,
            Value = 0,
            IsMoveToPointEnabled = true, // 点击轨道任意位置直接跳转（修复进度条点击不生效）
            SmallChange = 0.001,
            LargeChange = 0.05,
            VerticalAlignment = VerticalAlignment.Center,
            Template = CreateWhiteSliderTemplate(3, 6),
            Focusable = false,
        };
        // 拖动开始/结束
        _progressSlider.AddHandler(Thumb.DragStartedEvent, new DragStartedEventHandler((s, e) =>
        {
            _isDraggingProgress = true;
            OnMouseMove();
        }));
        _progressSlider.AddHandler(Thumb.DragCompletedEvent, new DragCompletedEventHandler((s, e) =>
        {
            _isDraggingProgress = false;
            if (_duration > 0 && _player != null)
                _player.Seek(_progressSlider.Value * _duration);
            ResetAutoHideTimer();
        }));
        // 轨道点击（非拖动，含 IsMoveToPointEnabled 触发的点击跳转）→ 立即 seek
        _progressSlider.ValueChanged += (s, e) =>
        {
            if (_updatingProgressFromPosition) return;
            if (_isDraggingProgress) return;
            if (_duration > 0 && _player != null)
                _player.Seek(_progressSlider.Value * _duration);
        };
        outerStack.Children.Add(_progressSlider);

        // 时间戳行
        var timeRow = new Grid { Margin = new Thickness(0, 2, 0, 0) };
        timeRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        timeRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        timeRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        _positionText = new TextBlock
        {
            Text = "0:00",
            Foreground = new SolidColorBrush(Color.FromArgb(0xBB, 0xFF, 0xFF, 0xFF)),
            FontSize = 10,
        };
        timeRow.Children.Add(_positionText);

        _durationText = new TextBlock
        {
            Text = "0:00",
            Foreground = new SolidColorBrush(Color.FromArgb(0xBB, 0xFF, 0xFF, 0xFF)),
            FontSize = 10,
            HorizontalAlignment = HorizontalAlignment.Right,
        };
        Grid.SetColumn(_durationText, 2);
        timeRow.Children.Add(_durationText);
        outerStack.Children.Add(timeRow);

        // ── 控制按钮行 ────────────────────────────────────────────────
        var btnRow = new Grid { Margin = new Thickness(0, 2, 0, 0) };
        btnRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); // 0: play/pause
        btnRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); // 1: -10s
        btnRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); // 2: +10s
        btnRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); // 3: volume
        btnRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); // 4: spacer
        btnRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); // 5: speed
        btnRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); // 6: fullscreen

        // 播放/暂停
        _playPauseButton = MakeIconButton(Mdl2.Play, "播放/暂停 (Space)", TogglePlay, 18, 30);
        Grid.SetColumn(_playPauseButton, 0);
        btnRow.Children.Add(_playPauseButton);

        // -10s
        var seekBack = MakeIconButton(Mdl2.Replay10, "-10s", () => SeekRelative(-10), 16, 28);
        Grid.SetColumn(seekBack, 1);
        btnRow.Children.Add(seekBack);

        // +10s
        var seekFwd = MakeIconButton(Mdl2.Forward10, "+10s", () => SeekRelative(10), 16, 28);
        Grid.SetColumn(seekFwd, 2);
        btnRow.Children.Add(seekFwd);

        // 音量图标 + 滑块
        var volumePanel = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            VerticalAlignment = VerticalAlignment.Center,
        };
        _volumeIcon = new TextBlock
        {
            Text = Mdl2.VolumeUp,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 15,
            Foreground = new SolidColorBrush(Color.FromArgb(0xBB, 0xFF, 0xFF, 0xFF)),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(6, 0, 4, 0),
        };
        volumePanel.Children.Add(_volumeIcon);

        _volumeSlider = new Slider
        {
            Minimum = 0,
            Maximum = 100,
            Value = 100,
            Width = 64,
            IsMoveToPointEnabled = true,
            VerticalAlignment = VerticalAlignment.Center,
            Template = CreateWhiteSliderTemplate(2, 5),
            Focusable = false,
        };
        _volumeSlider.ValueChanged += (s, e) =>
        {
            if (_isDraggingVolume) return;
            if (_updatingVolumeFromPosition) return;
            SetVolume(_volumeSlider.Value);
        };
        _volumeSlider.AddHandler(Thumb.DragStartedEvent, new DragStartedEventHandler((s, e) =>
        {
            _isDraggingVolume = true;
            OnMouseMove();
        }));
        _volumeSlider.AddHandler(Thumb.DragCompletedEvent, new DragCompletedEventHandler((s, e) =>
        {
            _isDraggingVolume = false;
            SetVolume(_volumeSlider.Value);
            ResetAutoHideTimer();
        }));
        volumePanel.Children.Add(_volumeSlider);

        Grid.SetColumn(volumePanel, 3);
        btnRow.Children.Add(volumePanel);

        // 速度按钮
        var speedMenu = new ContextMenu();
        foreach (var spd in Speeds)
        {
            var captured = spd;
            var item = new MenuItem { Header = $"{spd}x", Tag = spd, Focusable = false };
            item.Click += (_, _) => SetSpeed(captured);
            speedMenu.Items.Add(item);
        }
        _speedButton = new Button
        {
            Content = new TextBlock
            {
                Text = "1x",
                Foreground = Brushes.White,
                FontSize = 11,
            },
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            Padding = new Thickness(6, 3, 6, 3),
            Focusable = false,
            ToolTip = "播放速度",
            ContextMenu = speedMenu,
            Template = CreateIconButtonTemplate(),
        };
        _speedButton.Click += (_, _) =>
        {
            speedMenu.PlacementTarget = _speedButton;
            speedMenu.Placement = PlacementMode.Bottom;
            speedMenu.IsOpen = true;
        };
        speedMenu.Closed += (_, _) => { OnMouseMove(); };
        Grid.SetColumn(_speedButton, 5);
        btnRow.Children.Add(_speedButton);

        // 全屏按钮
        _fullscreenIcon = new TextBlock
        {
            Text = Mdl2.Fullscreen,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 18,
            Foreground = Brushes.White,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        _fullscreenButton = new Button
        {
            Content = _fullscreenIcon,
            Width = 30,
            Height = 30,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            Focusable = false,
            ToolTip = "全屏 (F)",
            Template = CreateIconButtonTemplate(),
        };
        _fullscreenButton.Click += (_, _) =>
        {
            if (_isFullScreen) ExitFullScreen();
            else EnterFullScreen();
        };
        Grid.SetColumn(_fullscreenButton, 6);
        btnRow.Children.Add(_fullscreenButton);

        outerStack.Children.Add(btnRow);

        _bottomBar = new Border
        {
            VerticalAlignment = VerticalAlignment.Bottom,
            Child = outerStack,
            Background = CreateVerticalGradient(0x00, 0xDD, fromBottom: true),
        };
        // _bottomBar 由 BuildUi 加入 leftPanel，这里不再 Add
    }

    /// <summary>构建右侧播放列表面板。</summary>
    private void BuildPlaylistPanel()
    {
        var panel = new Grid();
        panel.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        panel.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });

        // 标题
        var header = new Border
        {
            Height = 48,
            Padding = new Thickness(14, 0, 14, 0),
            BorderBrush = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 80),
            BorderThickness = new Thickness(0, 0, 0, 1),
            Child = new TextBlock
            {
                Text = "播放列表",
                FontSize = 13,
                FontWeight = FontWeights.SemiBold,
                Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
                VerticalAlignment = VerticalAlignment.Center,
            },
        };
        panel.Children.Add(header);

        // 列表
        _playlistStack = new StackPanel
        {
            Margin = new Thickness(0, 4, 0, 4),
        };

        _playlistScroll = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = _playlistStack,
        };
        Grid.SetRow(_playlistScroll, 1);
        panel.Children.Add(_playlistScroll);

        _playlistPanel.Child = panel;

        BuildPlaylistItems();
    }

    /// <summary>构建播放列表项。</summary>
    private void BuildPlaylistItems()
    {
        _playlistStack.Children.Clear();
        _playlistItems.Clear();

        if (_playlist.Count == 0)
        {
            _playlistStack.Children.Add(new TextBlock
            {
                Text = "无播放列表",
                FontSize = 12,
                Foreground = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OnSurfaceVariant"), 120),
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 24, 0, 0),
            });
            return;
        }

        for (int i = 0; i < _playlist.Count; i++)
        {
            var ui = new PlaylistItemUi();
            var file = _playlist[i];

            var itemGrid = new Grid();
            itemGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            itemGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            itemGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

            // 序号 / 播放图标
            ui.IndexOrIcon = new TextBlock
            {
                Width = 20,
                FontSize = 11,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
                Text = (i + 1).ToString(),
                Foreground = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OnSurfaceVariant"), 150),
            };
            itemGrid.Children.Add(ui.IndexOrIcon);

            // 文件名（去扩展名）
            ui.TitleText = new TextBlock
            {
                Text = file.NameWithoutExtension,
                FontSize = 12,
                Margin = new Thickness(8, 0, 6, 0),
                VerticalAlignment = VerticalAlignment.Center,
                TextTrimming = TextTrimming.CharacterEllipsis,
                Foreground = UiHelper.BrushOf("M3.OnSurface"),
            };
            Grid.SetColumn(ui.TitleText, 1);
            itemGrid.Children.Add(ui.TitleText);

            // 格式徽章
            var ext = UiHelper.ExtUpper(file.Name);
            if (!string.IsNullOrEmpty(ext))
            {
                var badge = new Border
                {
                    Background = UiHelper.BrushOf("M3.SurfaceContainerHighest"),
                    CornerRadius = new CornerRadius(3),
                    Padding = new Thickness(4, 1, 4, 1),
                    VerticalAlignment = VerticalAlignment.Center,
                    Child = new TextBlock
                    {
                        Text = ext,
                        FontSize = 9,
                        FontWeight = FontWeights.SemiBold,
                        Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
                    },
                };
                Grid.SetColumn(badge, 2);
                itemGrid.Children.Add(badge);
            }

            ui.Container = new Border
            {
                Margin = new Thickness(6, 2, 6, 2),
                Padding = new Thickness(10, 9, 10, 9),
                CornerRadius = new CornerRadius(7),
                Background = Brushes.Transparent,
                Child = itemGrid,
                Cursor = Cursors.Hand,
            };

            int captured = i;
            ui.Container.MouseLeftButtonUp += (_, _) => PlayIndex(captured);

            _playlistStack.Children.Add(ui.Container);
            _playlistItems.Add(ui);
        }

        UpdatePlaylistHighlight();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════════════════

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        _hostWindow = Window.GetWindow(this);
        this.Focus();

        // 视频优先于音乐：停止全局音乐播放
        _globalPlayer.StopMusicForOtherPlayback();

        // 创建视频宿主
        _videoHost = new MpvVideoHost();
        _videoAreaGrid.Children.Insert(0, _videoHost);
        _videoHost.MouseMoveHook += OnMouseMove;
        // 兜底：宿主窗口级 PreviewMouseMove，确保鼠标在任意位置移动都能唤出控制条
        // （HwndHost 子窗口会吞掉 WPF 路由事件，VideoArea 的 MouseMove 不可靠）
        if (_hostWindow != null)
            _hostWindow.PreviewMouseMove += OnHostMouseMove;

        // 创建并初始化播放器（对应 Flutter onInit 中的 initPlayer + applyMpvOptions）
        _player = new MpvPlayer();
        var options = BuildMpvOptions();
        _player.Init(options);

        // 监听播放器事件
        _player.PlayingChanged += OnPlayingChanged;
        _player.BufferingChanged += OnBufferingChanged;
        _player.PositionChanged += OnPositionChanged;
        _player.DurationChanged += OnDurationChanged;
        _player.VolumeChanged += OnVolumeChanged;
        _player.Ended += OnEnded;
        _player.FileLoaded += OnFileLoaded;

        // 把播放器绑定到视频宿主（mpv 会渲染到该子窗口）
        _videoHost.Player = _player;

        // 续播标记
        _pendingResume = _resumePositionSec > 0;

        // 打开媒体
        _player.Open(_url);
        _settings.AddRecentFile(_url);

        // 打开覆盖层 Popup 并开始跟随视频区域的位置/尺寸（含窗口移动、缩放、全屏切换）。
        _bufferingPopup.IsOpen = true;
        _controlsPopup.IsOpen = true;
        SetPopupClickThrough(_bufferingPopup, true);
        SyncOverlayPopups();
        _videoAreaGrid.SizeChanged += (_, _) => SyncOverlayPopups();
        if (_hostWindow != null)
        {
            _hostWindow.LocationChanged += OnHostWindowGeometryChanged;
            _hostWindow.SizeChanged += OnHostWindowGeometryChanged;
        }

        // 初始显示控制条，随后由自动隐藏定时器在无操作 3 秒后隐藏
        // （鼠标是否移入视频区域仍需轮询，用于点击穿透等判定）
        ShowControls = true;
        StartMouseTrackTimer();
        StartAutoHideTimer();
    }

    /// <summary>宿主窗口位置/尺寸变化（含进入/退出全屏）时，重新同步覆盖 Popup 的位置。</summary>
    private void OnHostWindowGeometryChanged(object? sender, EventArgs e) => SyncOverlayPopups();

    /// <summary>把顶栏/底栏/缓冲指示器所在的 Popup 对齐到当前视频区域的屏幕位置和尺寸。
    /// Popup 是独立的顶层窗口，不会随 _videoAreaGrid 的布局自动跟随，需要手动同步。</summary>
    private void SyncOverlayPopups()
    {
        if (_videoAreaGrid == null || !_videoAreaGrid.IsLoaded) return;
        try
        {
            var w = _videoAreaGrid.ActualWidth;
            var h = _videoAreaGrid.ActualHeight;
            if (w <= 0 || h <= 0) return;

            if (_controlsPopup.Child is FrameworkElement controlsRoot)
            {
                controlsRoot.Width = w;
                controlsRoot.Height = h;
            }
            if (_bufferingPopup.Child is FrameworkElement bufferingRoot)
            {
                bufferingRoot.Width = w;
                bufferingRoot.Height = h;
            }

            // 强制 Popup 重新计算相对位置（PlacementTarget 尺寸变化后 Popup 不会自动重新定位）。
            // 注意：必须同时扰动 Horizontal 和 Vertical 两个偏移量——WPF 的 Popup 在
            // Relative 定位模式下，只有当对应方向的 Offset 发生变化时才会重新计算该方向的
            // 屏幕坐标。之前只扰动了 HorizontalOffset，导致窗口高度变化（例如进入/退出
            // 全屏）时 Popup 的垂直位置不会刷新，底栏（贴底对齐）位置基于旧的窗口高度
            // 计算，从而显示在了屏幕可见区域之外——这正是"全屏下移动鼠标只有顶栏出现，
            // 底栏不出现"的根因（顶栏贴顶，位置比较稳定，恰好不受影响）。
            var offset = _controlsPopup.HorizontalOffset;
            _controlsPopup.HorizontalOffset = offset + 0.001;
            _controlsPopup.HorizontalOffset = offset;
            var vOffset = _controlsPopup.VerticalOffset;
            _controlsPopup.VerticalOffset = vOffset + 0.001;
            _controlsPopup.VerticalOffset = vOffset;

            var bOffset = _bufferingPopup.HorizontalOffset;
            _bufferingPopup.HorizontalOffset = bOffset + 0.001;
            _bufferingPopup.HorizontalOffset = bOffset;
            var bvOffset = _bufferingPopup.VerticalOffset;
            _bufferingPopup.VerticalOffset = bvOffset + 0.001;
            _bufferingPopup.VerticalOffset = bvOffset;
        }
        catch { }
    }

    private void OnUnloaded(object sender, RoutedEventArgs e)
    {
        // 退出全屏（恢复窗口状态）
        if (_isFullScreen && _hostWindow != null)
        {
            try
            {
                _hostWindow.WindowStyle = _savedWindowStyle;
                _hostWindow.WindowState = _savedWindowState;
                _hostWindow.ResizeMode = _savedResizeMode;
            }
            catch { }
            _isFullScreen = false;
        }

        _mouseTrackTimer?.Stop();
        _mouseTrackTimer = null;
        _autoHideTimer?.Stop();
        _autoHideTimer = null;

        if (_hostWindow != null)
        {
            _hostWindow.LocationChanged -= OnHostWindowGeometryChanged;
            _hostWindow.SizeChanged -= OnHostWindowGeometryChanged;
        }
        _controlsPopup.IsOpen = false;
        _bufferingPopup.IsOpen = false;

        // 最后一次上报状态（保留进度供底部播放栏展示 + 续播）
        ReportStateToGlobal();

        // 停止并释放播放器
        if (_player != null)
        {
            try { _player.Stop(); } catch { }
            try { _player.Dispose(); } catch { }
            _player = null;
        }

        if (_videoHost != null)
        {
            _videoHost.MouseMoveHook -= OnMouseMove;
            _videoHost = null;
        }

        if (_hostWindow != null)
        {
            _hostWindow.PreviewMouseMove -= OnHostMouseMove;
        }

        // 通知全局服务：视频播放页已关闭（保留底部栏展示）
        _globalPlayer.OnVideoPageClosed();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 播放器事件处理
    // ══════════════════════════════════════════════════════════════════════

    private void OnPlayingChanged(bool playing)
    {
        _isPlaying = playing;
        UpdatePlayPauseIcon();
    }

    private void OnBufferingChanged(bool buffering)
    {
        _isBuffering = buffering;
        _bufferingIndicator.Visibility = buffering ? Visibility.Visible : Visibility.Collapsed;
    }

    private void OnPositionChanged(double pos)
    {
        _position = pos;
        _positionText.Text = UiHelper.FormatTime(pos);

        if (!_isDraggingProgress && _duration > 0)
        {
            _updatingProgressFromPosition = true;
            _progressSlider.Value = Math.Clamp(pos / _duration, 0, 1);
            _updatingProgressFromPosition = false;
        }

        ReportStateToGlobal();
    }

    private void OnDurationChanged(double dur)
    {
        _duration = dur;
        _durationText.Text = UiHelper.FormatTime(dur);

        // 续播：等 duration 就绪后 seek
        if (_pendingResume && dur > 0)
        {
            _pendingResume = false;
            _player?.Seek(_resumePositionSec);
        }

        ReportStateToGlobal();
    }

    private void OnVolumeChanged(double vol)
    {
        _volume = vol;
        if (!_isDraggingVolume)
        {
            _updatingVolumeFromPosition = true;
            _volumeSlider.Value = vol;
            _updatingVolumeFromPosition = false;
        }
        UpdateVolumeIcon();
    }

    private void OnEnded(bool completed)
    {
        if (completed) PlayNext();
    }

    private void OnFileLoaded()
    {
        // 文件加载完成后，如果 duration 已知且有续播位置，执行 seek
        if (_pendingResume && _duration > 0)
        {
            _pendingResume = false;
            _player?.Seek(_resumePositionSec);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 控制方法（对应 Flutter VideoPlayerController）
    // ══════════════════════════════════════════════════════════════════════

    private void TogglePlay()
    {
        _player?.PlayOrPause();
    }

    private void SeekRelative(int seconds)
    {
        if (_player == null) return;
        var target = _position + seconds;
        var clamped = Math.Clamp(target, 0, _duration);
        _player.Seek(clamped);
    }

    private void SetSpeed(double s)
    {
        _speed = s;
        _player?.SetRate(s);
        UpdateSpeedDisplay();
    }

    private void SetVolume(double v)
    {
        var clamped = Math.Clamp(v, 0, 100);
        _volume = clamped;
        _player?.SetVolume(clamped);
        if (!_isDraggingVolume)
        {
            _updatingVolumeFromPosition = true;
            _volumeSlider.Value = clamped;
            _updatingVolumeFromPosition = false;
        }
        UpdateVolumeIcon();
    }

    private void PlayIndex(int index)
    {
        if (index < 0 || index >= _playlist.Count) return;
        _currentIndex = index;
        var f = _playlist[index];
        _url = f.Path;
        _title = f.NameWithoutExtension;
        _titleText.Text = _title;

        // 【BUG 修复】切换播放列表条目时，进度条完全不动、总时长也不变，看起来像是
        // 换了个视频但进度条毫无反应。根因：_position/_duration 这两个字段以及进度条
        // Slider.Value 只在收到 MpvPlayer 的 PositionChanged/DurationChanged 事件时才会
        // 更新，而这些事件依赖新文件被 mpv 实际加载完成后才会触发（loadfile 是异步的，
        // 期间存在真空期，见 MpvPlayer.Open 的注释）。在这段真空期里，本页缓存的
        // _position/_duration 和进度条 Slider.Value/时间戳文本仍然停留在上一个视频的值，
        // 用户看到的就是"点了新视频，进度条和总时长完全不变"。这里在真正调用 Open()
        // 切换文件之前，立即把本地状态和 UI 控件复位到 0，让界面第一时间反映"已经在
        // 加载新文件"，随后新文件的真实位置/时长到达后会自然覆盖为正确值。
        _position = 0;
        _duration = 0;
        _positionText.Text = UiHelper.FormatTime(0);
        _durationText.Text = UiHelper.FormatTime(0);
        _updatingProgressFromPosition = true;
        _progressSlider.Value = 0;
        _updatingProgressFromPosition = false;

        _pendingResume = false;
        _player?.Open(_url);
        _settings.AddRecentFile(_url);

        UpdatePlaylistHighlight();
        ReportStateToGlobal();
    }

    private void PlayNext()
    {
        if (_currentIndex < _playlist.Count - 1)
            PlayIndex(_currentIndex + 1);
    }

    private void PlayPrev()
    {
        if (_currentIndex > 0)
            PlayIndex(_currentIndex - 1);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 全屏
    // ══════════════════════════════════════════════════════════════════════

    private void EnterFullScreen()
    {
        if (_hostWindow == null || _isFullScreen) return;
        _savedWindowStyle = _hostWindow.WindowStyle;
        _savedWindowState = _hostWindow.WindowState;
        _savedResizeMode = _hostWindow.ResizeMode;
        _savedWindowBounds = new Rect(_hostWindow.Left, _hostWindow.Top, _hostWindow.Width, _hostWindow.Height);

        // 真正的全屏：WindowState.Maximized 在 WindowStyle=None 时仍会遵循
        // 系统工作区（避开任务栏），导致画面无法铺满整个屏幕（黑边）。
        // 这里改为先切到 Normal，再手动把窗口边界设置为整个显示器物理边界。
        var screen = FormsScreen.FromHandle(new System.Windows.Interop.WindowInteropHelper(_hostWindow).Handle)
                     ?? FormsScreen.PrimaryScreen!;
        var bounds = screen.Bounds; // 物理像素，包含任务栏区域，真正铺满屏幕
        var src = System.Windows.PresentationSource.FromVisual(_hostWindow);
        var m = src?.CompositionTarget?.TransformFromDevice ?? Matrix.Identity;
        var topLeft = m.Transform(new Point(bounds.Left, bounds.Top));
        var size = m.Transform(new Point(bounds.Width, bounds.Height));

        _hostWindow.WindowState = WindowState.Normal;
        _hostWindow.WindowStyle = WindowStyle.None;
        _hostWindow.ResizeMode = ResizeMode.NoResize;
        _hostWindow.Topmost = true;
        _hostWindow.Left = topLeft.X;
        _hostWindow.Top = topLeft.Y;
        _hostWindow.Width = size.X;
        _hostWindow.Height = size.Y;

        _isFullScreen = true;

        // 全屏时隐藏播放列表面板
        _playlistColumn.Width = new GridLength(0);
        _playlistPanel.Visibility = Visibility.Collapsed;

        UpdateFullscreenIcon();
        ShowControls = true;
        ResetAutoHideTimer();
        // 布局更新（隐藏播放列表、视频区扩展到整屏）在下一次布局过程后才生效，
        // Dispatcher 排队到 Loaded 优先级以确保拿到更新后的 ActualWidth/Height。
        Dispatcher.BeginInvoke(new Action(SyncOverlayPopups), DispatcherPriority.Loaded);
        // 保险起见再排一次较低优先级的重新同步：部分系统下全屏窗口过渡（尤其是多显示器/
        // 高 DPI）可能比一次 Loaded 优先级回调更慢完成，晚一帧再同步一次避免底栏错位。
        Dispatcher.BeginInvoke(new Action(SyncOverlayPopups), DispatcherPriority.Background);
    }

    private void ExitFullScreen()
    {
        if (_hostWindow == null || !_isFullScreen) return;
        _hostWindow.Topmost = false;
        _hostWindow.WindowStyle = _savedWindowStyle;
        _hostWindow.ResizeMode = _savedResizeMode;
        if (_savedWindowState == WindowState.Maximized)
        {
            _hostWindow.WindowState = WindowState.Maximized;
        }
        else
        {
            _hostWindow.WindowState = WindowState.Normal;
            _hostWindow.Left = _savedWindowBounds.Left;
            _hostWindow.Top = _savedWindowBounds.Top;
            _hostWindow.Width = _savedWindowBounds.Width;
            _hostWindow.Height = _savedWindowBounds.Height;
        }
        _isFullScreen = false;

        // 恢复播放列表面板
        _playlistColumn.Width = new GridLength(260);
        _playlistPanel.Visibility = Visibility.Visible;

        UpdateFullscreenIcon();
        ShowControls = true;
        ResetAutoHideTimer();
        Dispatcher.BeginInvoke(new Action(SyncOverlayPopups), DispatcherPriority.Loaded);
        Dispatcher.BeginInvoke(new Action(SyncOverlayPopups), DispatcherPriority.Background);
    }

    private void GoBack()
    {
        // 同窗口覆盖层模式：关闭播放页覆盖层回到 Tab 主页
        if (App.Current.MainWindow is Views.MainWindow mw)
            mw.ClosePlayerPage();
        else
            _hostWindow?.Close();
    }

    // ══════════════════════════════════════════════════════════════════════
    // 控制条自动隐藏
    // ══════════════════════════════════════════════════════════════════════

    private bool ShowControls
    {
        get => _showControls;
        set
        {
            _showControls = value;
            // 顶栏/底栏现在承载于独立的覆盖 Popup 中，不再与视频区共享 Grid 行，
            // 因此 Hidden/Collapsed 均不会影响视频区域尺寸；这里继续用 Hidden 只是
            // 保留原有行为习惯（无实际布局影响）。
            _topBar.Visibility = value ? Visibility.Visible : Visibility.Hidden;
            _bottomBar.Visibility = value ? Visibility.Visible : Visibility.Hidden;
            // 隐藏时禁止命中测试，避免挡住视频区域的鼠标事件/悬停判定
            _topBar.IsHitTestVisible = value;
            _bottomBar.IsHitTestVisible = value;
            // Popup 是独立的原生顶层窗口，覆盖在视频画面之上。WPF 的 IsHitTestVisible
            // 只影响 WPF 内部路由事件，并不能阻止该原生窗口在 Win32 层面继续接收/吞掉
            // 鼠标消息——如果不处理，控制条隐藏后这块原生窗口仍会挡住下面视频 HwndHost
            // 的 WM_MOUSEMOVE，导致"鼠标移到视频上却无法再次唤出控制条"。
            // 这里在控制条隐藏时，给 Popup 窗口加上 WS_EX_TRANSPARENT（点击穿透），
            // 让所有鼠标消息穿透到下层视频窗口；控制条显示时移除该样式以便按钮/
            // 进度条可以正常响应点击。
            SetPopupClickThrough(_controlsPopup, !value);
        }
    }

    private void ToggleControls()
    {
        ShowControls = !ShowControls;
        if (ShowControls) ResetAutoHideTimer();
    }

    /// <summary>鼠标发生任意移动：显示控制条，并重新开始 3 秒无操作倒计时。
    /// 普通模式和全屏模式使用同一套逻辑——移动鼠标就显示，停止移动 3 秒后自动隐藏。</summary>
    private void OnMouseMove()
    {
        ShowControls = true;
        ResetAutoHideTimer();
    }

    /// <summary>宿主窗口级鼠标移动兜底：HwndHost 子窗口会吞掉 WPF 路由事件，
    /// 视频区内的 MouseMove 不可靠，用窗口级 PreviewMouseMove 确保能唤出控制条。</summary>
    private void OnHostMouseMove(object sender, MouseEventArgs e) => OnMouseMove();

    /// <summary>启动鼠标位置轮询：仅用于判断鼠标当前是否位于视频区域内
    /// （用于 Popup 点击穿透等判定），不再用于显隐控制条。
    /// 因为视频区域内嵌了原生子窗口（HwndHost），标准 WPF MouseEnter/MouseLeave 不可靠，
    /// 因此改用窗口坐标系 + 控件包围盒判断（Mouse.GetPosition 相对窗口取值，避免物理像素/
    /// 逻辑像素 DPI 换算误差）。</summary>
    private void StartMouseTrackTimer()
    {
        _mouseTrackTimer ??= new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) };
        _mouseTrackTimer.Tick -= OnMouseTrackTick;
        _mouseTrackTimer.Tick += OnMouseTrackTick;
        _mouseTrackTimer.Start();
    }

    private void OnMouseTrackTick(object? sender, EventArgs e)
    {
        if (_leftPanel == null || !_leftPanel.IsLoaded || _hostWindow == null) return;

        bool inside;
        try
        {
            // 用 WPF 自身坐标系而不是 Win32 GetCursorPos 的物理像素坐标，避免高 DPI /
            // 多显示器下物理像素与 WPF 逻辑像素换算不一致，导致越靠下（离顶部越远）
            // 误差越大，出现"上面能触发、下面黑边触发不了"的问题。
            // 以窗口为参照系查询鼠标位置（比直接以视频区域为参照更可靠，
            // 因为鼠标可能正处于 HwndHost 原生子窗口上方），再换算到左侧区域的本地坐标。
            var posInWindow = Mouse.GetPosition(_hostWindow);
            var origin = _leftPanel.TranslatePoint(new Point(0, 0), _hostWindow);
            var local = new Point(posInWindow.X - origin.X, posInWindow.Y - origin.Y);
            var bounds = new Rect(0, 0,
                Math.Max(_leftPanel.ActualWidth, 0),
                Math.Max(_leftPanel.ActualHeight, 0));
            inside = bounds.Contains(local);
        }
        catch
        {
            inside = false;
        }

        _mouseInVideoArea = inside;
    }

    /// <summary>启动/重启控制条自动隐藏定时器：无操作 <see cref="AutoHideDelay"/> 后自动隐藏。
    /// 普通模式和全屏模式共用同一逻辑。</summary>
    private void StartAutoHideTimer()
    {
        _autoHideTimer ??= new DispatcherTimer { Interval = AutoHideDelay };
        _autoHideTimer.Tick -= OnAutoHideTick;
        _autoHideTimer.Tick += OnAutoHideTick;
        _autoHideTimer.Start();
    }

    /// <summary>重置自动隐藏倒计时（鼠标移动、点击控制条等交互都应调用）。</summary>
    private void ResetAutoHideTimer()
    {
        if (_autoHideTimer == null) { StartAutoHideTimer(); return; }
        _autoHideTimer.Stop();
        _autoHideTimer.Start();
    }

    private void OnAutoHideTick(object? sender, EventArgs e)
    {
        // 速度菜单等弹出菜单打开时，不要隐藏控制条（否则菜单会显得突兀地悬空）。
        if (_speedButton?.ContextMenu?.IsOpen == true) return;
        // 正在拖动进度条/音量条时不要隐藏。
        if (_isDraggingProgress || _isDraggingVolume) return;

        ShowControls = false;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 键盘快捷键（对应 Flutter _handleKey）
    // ══════════════════════════════════════════════════════════════════════

    private void OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        // 速度菜单打开时不拦截
        if (_speedButton?.ContextMenu?.IsOpen == true) return;

        switch (e.Key)
        {
            case Key.Space:
                TogglePlay();
                OnMouseMove();
                e.Handled = true;
                break;
            case Key.Right:
                SeekRelative(5);
                OnMouseMove();
                e.Handled = true;
                break;
            case Key.Left:
                SeekRelative(-5);
                OnMouseMove();
                e.Handled = true;
                break;
            case Key.Up:
                SetVolume(_volume + 5);
                OnMouseMove();
                e.Handled = true;
                break;
            case Key.Down:
                SetVolume(_volume - 5);
                OnMouseMove();
                e.Handled = true;
                break;
            case Key.Escape:
                if (_isFullScreen)
                    ExitFullScreen();
                else
                    GoBack();
                e.Handled = true;
                break;
            case Key.F:
                if (_isFullScreen)
                    ExitFullScreen();
                else
                    EnterFullScreen();
                e.Handled = true;
                break;
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI 更新
    // ══════════════════════════════════════════════════════════════════════

    private void UpdatePlayPauseIcon()
    {
        if (_playPauseButton?.Content is TextBlock tb)
            tb.Text = _isPlaying ? Mdl2.Pause : Mdl2.Play;
    }

    private void UpdateVolumeIcon()
    {
        if (_volumeIcon != null)
            _volumeIcon.Text = _volume == 0 ? Mdl2.VolumeOff : Mdl2.VolumeUp;
    }

    private void UpdateFullscreenIcon()
    {
        if (_fullscreenIcon != null)
        {
            _fullscreenIcon.Text = _isFullScreen ? Mdl2.FullscreenExit : Mdl2.Fullscreen;
            _fullscreenButton.ToolTip = _isFullScreen ? "退出全屏 (F/Esc)" : "全屏 (F)";
        }
    }

    private void UpdateSpeedDisplay()
    {
        if (_speedButton?.Content is TextBlock tb)
            tb.Text = $"{_speed}x";
    }

    private void UpdatePlaylistHighlight()
    {
        for (int i = 0; i < _playlistItems.Count; i++)
        {
            var ui = _playlistItems[i];
            var isActive = i == _currentIndex;
            ui.Container.Background = isActive
                ? UiHelper.BrushOf("M3.PrimaryContainer")
                : Brushes.Transparent;
            ui.Container.BorderBrush = isActive
                ? UiHelper.WithAlpha(UiHelper.BrushOf("M3.Primary"), 80)
                : Brushes.Transparent;
            ui.Container.BorderThickness = isActive ? new Thickness(1) : new Thickness(0);
            ui.TitleText.Foreground = isActive
                ? UiHelper.BrushOf("M3.OnPrimaryContainer")
                : UiHelper.BrushOf("M3.OnSurface");
            ui.TitleText.FontWeight = isActive ? FontWeights.SemiBold : FontWeights.Normal;
            ui.IndexOrIcon.Text = isActive ? Mdl2.Play : (i + 1).ToString();
            ui.IndexOrIcon.FontFamily = isActive
                ? new FontFamily("Segoe MDL2 Assets")
                : (FontFamily)Application.Current.Resources["App.Font"];
            ui.IndexOrIcon.FontSize = isActive ? 16 : 11;
            ui.IndexOrIcon.Foreground = isActive
                ? UiHelper.BrushOf("M3.Primary")
                : UiHelper.WithAlpha(UiHelper.BrushOf("M3.OnSurfaceVariant"), 150);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 状态上报
    // ══════════════════════════════════════════════════════════════════════

    /// <summary>把当前播放信息同步给全局控制器（对应 Flutter _reportStateToGlobal）。</summary>
    private void ReportStateToGlobal()
    {
        var resumeArgs = new VideoResumeArgs
        {
            Url = _url,
            Title = _title,
            IsLocal = _isLocal,
            Playlist = _playlist,
            StartIndex = _currentIndex,
            ResumePositionMs = (int)(_position * 1000),
        };
        _globalPlayer.UpdateVideoState(_title, _position, _duration, resumeArgs);
    }

    // ══════════════════════════════════════════════════════════════════════
    // mpv 配置
    // ══════════════════════════════════════════════════════════════════════

    /// <summary>根据 HardwareDecode 和 MpvProfile 构建 mpv 初始化选项。</summary>
    private List<KeyValuePair<string, string>> BuildMpvOptions()
    {
        var options = new List<KeyValuePair<string, string>>();

        // 硬件解码：Init 默认已设 hwdec=auto-safe，软解时覆盖为 no
        if (!_settings.HardwareDecode)
            options.Add(new KeyValuePair<string, string>("hwdec", "no"));

        // 【重要】openal 驱动不支持"可重入"：同一进程内如果音乐播放器（GlobalPlayerService,
        // 全局常驻）和视频播放器同时各自持有一个使用 openal 的 mpv 实例，后初始化的那个会
        // 报 "Not reentrant!" 失败（见 mpv.log: "先放音乐、再切视频"场景下视频会无声，
        // 且进度条不走——因为音频设备初始化失败导致 mpv 播放核心没有真正跑起来）。
        // 音乐播放器（GlobalPlayerService.EnsureMusicPlayer）用的是 openal，因此视频播放器
        // 必须换一个不同的驱动。实测 wasapi 使用系统默认输出设备时是有声音的（之前认为
        // wasapi 会路由到无声虚拟设备是误判，实际测试单独播放视频用 wasapi 声音正常）。
        options.Add(new KeyValuePair<string, string>("ao", "wasapi"));

        // 画质预设
        switch (_settings.MpvProfile)
        {
            case "performance":
                // 性能优先：最低延迟
                options.Add(new KeyValuePair<string, string>("cache", "yes"));
                options.Add(new KeyValuePair<string, string>("cache-secs", "5"));
                break;
            case "quality":
                // 画质优先：最佳画质
                options.Add(new KeyValuePair<string, string>("cache", "yes"));
                options.Add(new KeyValuePair<string, string>("cache-secs", "30"));
                options.Add(new KeyValuePair<string, string>("profile", "high-quality"));
                break;
            default:
                // 均衡（推荐）
                options.Add(new KeyValuePair<string, string>("cache", "yes"));
                options.Add(new KeyValuePair<string, string>("cache-secs", "10"));
                break;
        }

        return options;
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI 辅助方法
    // ══════════════════════════════════════════════════════════════════════

    /// <summary>创建白色图标按钮。</summary>
    private static Button MakeIconButton(string glyph, string tooltip, Action onClick, double size = 20, double buttonSize = 36)
    {
        var btn = new Button
        {
            Content = new TextBlock
            {
                Text = glyph,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = size,
                Foreground = Brushes.White,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            },
            Width = buttonSize,
            Height = buttonSize,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            Focusable = false,
            ToolTip = tooltip,
            Template = CreateIconButtonTemplate(),
        };
        btn.Click += (_, _) => onClick();
        return btn;
    }

    /// <summary>带 hover 效果的透明按钮模板。</summary>
    private static ControlTemplate CreateIconButtonTemplate()
    {
        var xaml = @"
<ControlTemplate TargetType=""Button"" xmlns=""http://schemas.microsoft.com/winfx/2006/xaml/presentation"">
    <Border Background=""{TemplateBinding Background}"" CornerRadius=""18"">
        <ContentPresenter HorizontalAlignment=""Center"" VerticalAlignment=""Center""/>
    </Border>
    <ControlTemplate.Triggers>
        <Trigger Property=""IsMouseOver"" Value=""True"">
            <Setter Property=""Background"" Value=""#33FFFFFF""/>
        </Trigger>
    </ControlTemplate.Triggers>
</ControlTemplate>";
        return (ControlTemplate)System.Windows.Markup.XamlReader.Parse(xaml);
    }

    /// <summary>白色轨道 + 白色滑块的 Slider 模板（用于视频进度条和音量条）。</summary>
    private static ControlTemplate CreateWhiteSliderTemplate(double trackHeight, double thumbRadius)
    {
        var xaml = $@"
<ControlTemplate TargetType=""Slider"" xmlns=""http://schemas.microsoft.com/winfx/2006/xaml/presentation"">
    <Grid VerticalAlignment=""Center"" Height=""20"">
        <Border Height=""{trackHeight:F1}"" VerticalAlignment=""Center"" CornerRadius=""2"" Background=""#55FFFFFF""/>
        <Track Name=""PART_Track"">
            <Track.DecreaseRepeatButton>
                <RepeatButton Command=""Slider.DecreaseLarge"">
                    <RepeatButton.Template>
                        <ControlTemplate TargetType=""RepeatButton"">
                            <Border Height=""{trackHeight:F1}"" Background=""White"" CornerRadius=""2""/>
                        </ControlTemplate>
                    </RepeatButton.Template>
                </RepeatButton>
            </Track.DecreaseRepeatButton>
            <Track.Thumb>
                <Thumb>
                    <Thumb.Template>
                        <ControlTemplate TargetType=""Thumb"">
                            <Ellipse Width=""{thumbRadius * 2:F1}"" Height=""{thumbRadius * 2:F1}"" Fill=""White""/>
                        </ControlTemplate>
                    </Thumb.Template>
                </Thumb>
            </Track.Thumb>
            <Track.IncreaseRepeatButton>
                <RepeatButton Command=""Slider.IncreaseLarge"">
                    <RepeatButton.Template>
                        <ControlTemplate TargetType=""RepeatButton"">
                            <Border Background=""Transparent""/>
                        </ControlTemplate>
                    </RepeatButton.Template>
                </RepeatButton>
            </Track.IncreaseRepeatButton>
        </Track>
    </Grid>
</ControlTemplate>";
        return (ControlTemplate)System.Windows.Markup.XamlReader.Parse(xaml);
    }

    /// <summary>创建从上到下（或从下到上）的黑色渐变画刷。</summary>
    private static LinearGradientBrush CreateVerticalGradient(int topAlpha, int bottomAlpha, bool fromBottom = false)
    {
        var brush = new LinearGradientBrush
        {
            StartPoint = fromBottom ? new Point(0, 1) : new Point(0, 0),
            EndPoint = fromBottom ? new Point(0, 0) : new Point(0, 1),
        };
        var c1 = Color.FromArgb((byte)(topAlpha & 0xFF), 0, 0, 0);
        var c2 = Color.FromArgb((byte)(bottomAlpha & 0xFF), 0, 0, 0);
        brush.GradientStops.Add(new GradientStop(fromBottom ? c2 : c1, 0));
        brush.GradientStops.Add(new GradientStop(fromBottom ? c1 : c2, 1));
        return brush;
    }

    // ══════════════════════════════════════════════════════════════════════
    // Popup 原生窗口点击穿透（用于覆盖层控制条隐藏时不阻挡下层视频窗口的鼠标消息）
    // ══════════════════════════════════════════════════════════════════════

    private const int GWL_EXSTYLE = -20;
    private const int WS_EX_TRANSPARENT = 0x00000020;
    private const int WS_EX_LAYERED = 0x00080000;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern int GetWindowLong(IntPtr hWnd, int nIndex);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern int SetWindowLong(IntPtr hWnd, int nIndex, int dwNewLong);

    /// <summary>让指定 Popup 对应的原生窗口在 transparent=true 时点击穿透（不接收任何
    /// 鼠标消息，消息直接落到下层窗口），transparent=false 时恢复正常接收点击。
    /// Popup 必须已经 IsOpen=true（已创建 HWND）才能生效。</summary>
    private static void SetPopupClickThrough(Popup popup, bool transparent)
    {
        try
        {
            if (popup.Child == null) return;
            var src = PresentationSource.FromVisual(popup.Child) as HwndSource;
            if (src == null) return;
            var hwnd = src.Handle;
            if (hwnd == IntPtr.Zero) return;

            int ex = GetWindowLong(hwnd, GWL_EXSTYLE);
            int target = transparent
                ? (ex | WS_EX_TRANSPARENT | WS_EX_LAYERED)
                : (ex & ~WS_EX_TRANSPARENT);
            if (target != ex)
                SetWindowLong(hwnd, GWL_EXSTYLE, target);
        }
        catch { }
    }
}
