using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.IO;
using System.Linq;
using System.Net.Http;
using System.Runtime.InteropServices;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using MEPlayer.Helpers;
using MEPlayer.Player;
using MEPlayer.Services;
using FormsScreen = System.Windows.Forms.Screen;

namespace MEPlayer.Pages;

/// <summary>
/// IPTV 播放页（对应 Flutter 端 iptv_player_page.dart）。
///
/// 分屏模式：左侧视频区（flex 7）+ 右侧频道面板（360px，三列：分组/频道/源）。
/// 全屏模式：仅视频区 + 覆盖层。
/// 控制条：鼠标移入视频区域立即显示，移出视频区域立即隐藏；mpv 每次切流后重新应用去交错滤镜与 TS 容错缓冲。
/// </summary>
public partial class IptvPlayerPage : UserControl
{
    // ── 依赖服务 ──────────────────────────────────────────────────────────
    private readonly AppSettingsService _settings;
    private readonly GlobalPlayerService _globalPlayer;
    private readonly ObservableCollection<Dictionary<string, string>> _sources;

    // ── mpv ────────────────────────────────────────────────────────────────
    private MpvPlayer? _mpv;
    private MpvVideoHost? _videoHost;

    // ── 恢复参数 ───────────────────────────────────────────────────────────
    private readonly string _initialUrl;
    private readonly string _initialChannelName;
    private readonly string _initialGroupName;
    private readonly int _initialSourceIdx;

    // ── 频道数据（对应 Flutter IptvPlayerController 的可观察字段）──────────
    private List<M3uChannel> _allChannels = new();
    private Dictionary<string, List<M3uChannel>> _grouped = new();
    private List<string> _groups = new();

    private string _browseGroup = "";
    private string _playingGroup = "";
    private string _channelName = "";

    private List<string> _streamUrls = new();
    private int _streamIndex;
    private int _sourceIndex;

    // ── 播放状态 ───────────────────────────────────────────────────────────
    private bool _isBuffering;
    private bool _isPlaying;
    private double _volume = 100;
    private bool _isLoading;

    // ── 控制条显隐：鼠标移动 → 立即显示；无操作 3 秒 → 自动隐藏 ─────────────
    // 普通模式和全屏模式使用同一套逻辑。_mouseTrackTimer 仅用于判断鼠标是否
    // 位于视频区域内（用于 Popup 点击穿透等判定），不再直接驱动显隐。
    private bool _showControls = true;
    private readonly DispatcherTimer _mouseTrackTimer;
    private bool _mouseInVideoArea;

    // ── 控制条自动隐藏定时器：无操作 3 秒后隐藏 ─────────────────────────────
    private readonly DispatcherTimer _autoHideTimer;
    private static readonly TimeSpan AutoHideDelay = TimeSpan.FromSeconds(3);

    // ── 全屏 ───────────────────────────────────────────────────────────────
    private bool _isFullScreen;
    private Window? _hostWindow;
    private WindowStyle _savedWindowStyle;
    private WindowState _savedWindowState;
    private ResizeMode _savedResizeMode;
    private double _savedLeft, _savedTop, _savedWidth, _savedHeight;

    private bool _disposed;

    // 共享 HttpClient（网络源拉取 M3U）
    private static readonly HttpClient _http = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };

    public IptvPlayerPage(IptvResumeArgs args)
    {
        InitializeComponent();

        _settings = App.Current.Settings;
        _globalPlayer = App.Current.Player;
        _sources = _settings.IptvSources;

        _initialUrl = args.Url ?? "";
        _initialChannelName = args.ChannelName ?? "";
        _initialGroupName = args.GroupName ?? "";
        _initialSourceIdx = args.SourceIndex;

        _channelName = _initialChannelName;
        _playingGroup = _initialGroupName;

        _mouseTrackTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(100) };
        _mouseTrackTimer.Tick += OnMouseTrackTick;

        _autoHideTimer = new DispatcherTimer { Interval = AutoHideDelay };
        _autoHideTimer.Tick += OnAutoHideTick;

        Loaded += OnLoaded;
        Unloaded += Page_Unloaded;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════════════════

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        // 开始播放 IPTV：停止全局音乐播放（视频/IPTV 优先于音乐）
        _globalPlayer.StopMusicForOtherPlayback();

        try
        {
            _mpv = new MpvPlayer();
            // 与 Flutter buildControllerConfig + applyMpvOptions 一致的基础选项
            _mpv.Init(new[]
            {
                new KeyValuePair<string, string>("vo", "gpu"),
                new KeyValuePair<string, string>("hwdec", "auto-safe"),
                // wasapi 会路由到 Senary Audio 虚拟设备（无声），改用 openal
                new KeyValuePair<string, string>("ao", "openal"),
                new KeyValuePair<string, string>("cache", "yes"),
                new KeyValuePair<string, string>("cache-secs", "10"),
            });
            _mpv.SetVolume(_volume);

            // 监听缓冲 / 播放 / 音量（mpv 事件已回发到 UI 线程）
            _mpv.BufferingChanged += b => { _isBuffering = b; RefreshBuffering(); };
            _mpv.PlayingChanged += p => { _isPlaying = p; RefreshPlayPause(); };
            _mpv.VolumeChanged += v =>
            {
                _volume = v;
                if (Math.Abs(VolumeSlider.Value - v) > 0.5) VolumeSlider.Value = v;
            };
            // IPTV 直播流理论上不应该"播完"，但部分源在网络抖动/服务端主动断流时
            // 会触发 EOF，此前没有处理，导致画面停在最后一帧、控制条也不会提示，
            // 表现为"播放完之后直接停了"。这里参照视频播放页的续播逻辑，收到 Ended
            // 后自动重新拉流重连，而不是放任其停在原地。
            _mpv.Ended += completed =>
            {
                if (!completed) return;
                if (_streamUrls.Count > 0 && _streamIndex < _streamUrls.Count)
                    PlayStream(_streamUrls[_streamIndex]);
            };

            // 创建视频宿主并嵌入到视频区最底层
            _videoHost = new MpvVideoHost { Player = _mpv };
            VideoAreaGrid.Children.Insert(0, _videoHost);
            _videoHost.MouseMoveHook += () => Dispatcher.BeginInvoke(new Action(AutoHideControls));

            // 兜底：宿主窗口级 PreviewMouseMove，确保鼠标在任意位置移动都能唤出控制条
            // （HwndHost 子窗口会吞掉 WPF 路由事件，VideoArea 的 MouseMove 不可靠）
            _hostWindow ??= Window.GetWindow(this);
            if (_hostWindow != null)
                _hostWindow.PreviewMouseMove += HostWindow_PreviewMouseMove;

            // 顶栏/底栏/缓冲指示器现在放在 XAML 中的独立 Popup 里（Popup 创建自己的
            // 顶层原生窗口，不受 Airspace 限制，可以正确叠加显示在视频画面之上）。

            // 初始应用去交错 + TS 容错（每次切流后会再次应用）
            ApplyMpvOptions();
        }
        catch (Exception ex)
        {
            EmptyStateBorder.Visibility = Visibility.Visible;
            EmptyStateText.Text = "播放器初始化失败：" + ex.Message;
            return;
        }

        // 打开覆盖层 Popup 并开始跟随视频区域的位置/尺寸（含窗口移动、缩放、全屏切换）。
        BufferingPopup.IsOpen = true;
        ControlsPopup.IsOpen = true;
        SetPopupClickThrough(BufferingPopup, true);
        SyncOverlayPopups();
        VideoAreaGrid.SizeChanged += (_, _) => SyncOverlayPopups();
        if (_hostWindow != null)
        {
            _hostWindow.LocationChanged += OnHostWindowGeometryChanged;
            _hostWindow.SizeChanged += OnHostWindowGeometryChanged;
        }

        // 初始显示控制条，随后由自动隐藏定时器在无操作 3 秒后隐藏
        ShowControls(true);
        _mouseTrackTimer.Start();
        ResetAutoHideTimer();
        RefreshPanelState();

        // 加载初始源
        if (_sources.Count > 0)
        {
            var idx = Math.Clamp(_initialSourceIdx, 0, _sources.Count - 1);
            LoadSource(idx);
        }
        else
        {
            RefreshPanelState();
        }

        // 确保键盘快捷键可用
        Focus();
    }

    /// <summary>宿主窗口位置/尺寸变化（含进入/退出全屏）时，重新同步覆盖 Popup 的位置。</summary>
    private void OnHostWindowGeometryChanged(object? sender, EventArgs e) => SyncOverlayPopups();

    /// <summary>把顶栏/底栏/缓冲指示器所在的 Popup 对齐到当前视频区域的屏幕位置和尺寸。
    /// Popup 是独立的顶层窗口，不会随 VideoAreaGrid 的布局自动跟随，需要手动同步。</summary>
    private void SyncOverlayPopups()
    {
        if (VideoAreaGrid == null || !VideoAreaGrid.IsLoaded) return;
        try
        {
            var w = VideoAreaGrid.ActualWidth;
            var h = VideoAreaGrid.ActualHeight;
            if (w <= 0 || h <= 0) return;

            ControlsPopupRoot.Width = w;
            ControlsPopupRoot.Height = h;
            BufferingPopupRoot.Width = w;
            BufferingPopupRoot.Height = h;

            // 强制 Popup 重新计算相对位置（PlacementTarget 尺寸变化后 Popup 不会自动重新定位）。
            // 注意：必须同时扰动 Horizontal 和 Vertical 两个偏移量——WPF 的 Popup 在
            // Relative 定位模式下，只有当对应方向的 Offset 发生变化时才会重新计算该方向的
            // 屏幕坐标。之前只扰动了 HorizontalOffset，导致窗口高度变化（例如进入/退出
            // 全屏）时 Popup 的垂直位置不会刷新，底栏（贴底对齐）位置基于旧的窗口高度
            // 计算，从而显示在了屏幕可见区域之外——这正是"全屏下移动鼠标只有顶栏出现，
            // 底栏不出现"的根因（顶栏贴顶，位置比较稳定，恰好不受影响）。
            var offset = ControlsPopup.HorizontalOffset;
            ControlsPopup.HorizontalOffset = offset + 0.001;
            ControlsPopup.HorizontalOffset = offset;
            var vOffset = ControlsPopup.VerticalOffset;
            ControlsPopup.VerticalOffset = vOffset + 0.001;
            ControlsPopup.VerticalOffset = vOffset;

            var bOffset = BufferingPopup.HorizontalOffset;
            BufferingPopup.HorizontalOffset = bOffset + 0.001;
            BufferingPopup.HorizontalOffset = bOffset;
            var bvOffset = BufferingPopup.VerticalOffset;
            BufferingPopup.VerticalOffset = bvOffset + 0.001;
            BufferingPopup.VerticalOffset = bvOffset;
        }
        catch { }
    }

    private void Page_Unloaded(object sender, RoutedEventArgs e)
    {
        if (_disposed) return;
        _disposed = true;

        _mouseTrackTimer.Stop();
        _autoHideTimer.Stop();
        try { _hostWindow ??= Window.GetWindow(this); } catch { }
        if (_hostWindow != null)
        {
            _hostWindow.PreviewMouseMove -= HostWindow_PreviewMouseMove;
            _hostWindow.LocationChanged -= OnHostWindowGeometryChanged;
            _hostWindow.SizeChanged -= OnHostWindowGeometryChanged;
        }
        ControlsPopup.IsOpen = false;
        BufferingPopup.IsOpen = false;

        // 退出播放页前把当前频道信息同步给全局，供底部播放栏展示 + 续播
        if (!string.IsNullOrEmpty(_channelName)) ReportStateToGlobal();

        try { _mpv?.Stop(); } catch { }
        try { _mpv?.Dispose(); } catch { }
        _mpv = null;
    }

    // ══════════════════════════════════════════════════════════════════════
    // mpv 选项应用（对应 Flutter applyMpvOptions：forceDeinterlaceFilter + forceTsResilience）
    // ══════════════════════════════════════════════════════════════════════

    private void ApplyMpvOptions()
    {
        if (_mpv == null) return;
        // 注意：yadif 去交错滤镜与 d3d11 硬件解码不兼容（d3d11 像素格式 yadif 不支持），
        // 会报 "Impossible to convert between the formats" 错误。
        // IPTV 直播流通常不需要去交错，这里禁用 yadif 避免冲突。
        // 用 SetPropertyString("vf","") 清空滤镜链，避免 vf 命令参数格式问题。
        try { _mpv.SetPropertyString("vf", ""); } catch { }
        // forceTsResilience：强制 TS 流容错缓冲
        try { _mpv.SetPropertyString("cache-secs", "10"); } catch { }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 源加载（对应 Flutter loadSource）
    // ══════════════════════════════════════════════════════════════════════

    private async void LoadSource(int idx)
    {
        if (idx < 0 || idx >= _sources.Count)
        {
            RefreshPanelState();
            return;
        }
        _sourceIndex = idx;
        _isLoading = true;
        RefreshPanelState();

        try
        {
            var src = _sources[idx];
            string content = "";

            if (src.TryGetValue("type", out var t) && t == "file")
            {
                var path = src.TryGetValue("filePath", out var p) ? p : "";
                if (!string.IsNullOrEmpty(path))
                    content = await File.ReadAllTextAsync(path);
            }
            else
            {
                var url = src.TryGetValue("url", out var u) ? u : "";
                if (!string.IsNullOrEmpty(url))
                    content = await _http.GetStringAsync(url);
            }

            if (!string.IsNullOrEmpty(content))
            {
                var parsed = M3uParser.Parse(content);
                _allChannels = parsed;
                _grouped = M3uParser.GroupBy(parsed);
                _groups = _grouped.Keys.ToList();

                if (_groups.Count == 0)
                {
                    RefreshPanelState();
                    return;
                }

                // 确定目标分组 + 频道：优先 initialGroupName + initialChannelName，否则首个分组首个频道
                string targetGroup = _groups[0];
                M3uChannel? targetCh = null;

                if (!string.IsNullOrEmpty(_initialGroupName) && _grouped.ContainsKey(_initialGroupName))
                    targetGroup = _initialGroupName;

                if (!string.IsNullOrEmpty(_initialChannelName) &&
                    _grouped.TryGetValue(targetGroup, out var glist))
                {
                    targetCh = glist.FirstOrDefault(c => c.Name == _initialChannelName);
                }
                targetCh ??= _grouped.TryGetValue(targetGroup, out var flist) && flist.Count > 0 ? flist[0] : null;

                _browseGroup = targetGroup;
                _playingGroup = targetGroup;

                if (targetCh != null)
                {
                    // autoPlay：只要匹配到目标频道即自动播放
                    SelectChannelInternal(targetCh, autoPlay: true);
                }
                else if (!string.IsNullOrEmpty(_initialUrl))
                {
                    // fallback：直接播放传入的 url
                    _streamUrls = new List<string> { _initialUrl };
                    _streamIndex = 0;
                    _channelName = !string.IsNullOrEmpty(_initialChannelName) ? _initialChannelName : "(未知频道-fallback)";
                    PlayStream(_initialUrl);
                }
            }
        }
        catch
        {
            // 静默
        }
        finally
        {
            _isLoading = false;
            RefreshPanelState();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 频道选择（对应 Flutter _selectChannelInternal / selectStream）
    // ══════════════════════════════════════════════════════════════════════

    private void SelectChannelInternal(M3uChannel ch, bool autoPlay)
    {
        _channelName = ch.Name;
        _playingGroup = ch.Group;

        // 收集所有同名频道的 url（去重）= 源列表
        var urls = new List<string>();
        var seen = new HashSet<string>();
        foreach (var c in _allChannels)
            if (c.Name == ch.Name && seen.Add(c.Url)) urls.Add(c.Url);

        _streamUrls = urls.Count > 0 ? urls : new List<string> { ch.Url };
        _streamIndex = 0;

        if (autoPlay)
            PlayStream(_streamUrls[0]);

        ReportStateToGlobal();
        RefreshOverlay();
        RefreshGroupList();     // playingGroup 高亮
        RefreshChannelList();   // 选中频道高亮
        RefreshStreamList();    // 源列表
    }

    private void SelectStream(int idx)
    {
        if (idx < 0 || idx >= _streamUrls.Count) return;
        _streamIndex = idx;
        PlayStream(_streamUrls[idx]);
        ReportStateToGlobal();
        RefreshStreamList();
    }

    private void PlayStream(string url)
    {
        try { _mpv?.Open(url); } catch { }
        // 修复：open() 会重置 mpv 内部状态，之前应用的滤镜链被清空，
        // 故每次真正切流之后都需要重新应用一次。
        ApplyMpvOptions();
        AutoHideControls();
    }

    private void ReportStateToGlobal()
    {
        var url = _streamUrls.Count > 0 ? _streamUrls[_streamIndex] : _initialUrl;
        _globalPlayer.UpdateIptvState(_channelName, _playingGroup, new IptvResumeArgs
        {
            Url = url,
            ChannelName = _channelName,
            GroupName = _playingGroup,
            SourceIndex = _sourceIndex,
        });
    }

    // ══════════════════════════════════════════════════════════════════════
    // 控制
    // ══════════════════════════════════════════════════════════════════════

    private void TogglePlay()
    {
        try { _mpv?.PlayOrPause(); } catch { }
    }

    private void SetVolume(double v)
    {
        _volume = v;
        try { _mpv?.SetVolume(v); } catch { }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 全屏（对应 Flutter enterFullScreen / exitFullScreen）
    // ══════════════════════════════════════════════════════════════════════

    private void ToggleFullScreen()
    {
        if (_isFullScreen) ExitFullScreen();
        else EnterFullScreen();
    }

    private void EnterFullScreen()
    {
        if (_isFullScreen) return;
        _hostWindow ??= Window.GetWindow(this);
        if (_hostWindow == null) return;

        _savedWindowStyle = _hostWindow.WindowStyle;
        _savedWindowState = _hostWindow.WindowState;
        _savedResizeMode = _hostWindow.ResizeMode;
        _savedLeft = _hostWindow.Left;
        _savedTop = _hostWindow.Top;
        _savedWidth = _hostWindow.Width;
        _savedHeight = _hostWindow.Height;

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
        PanelColumn.Width = new GridLength(0);
        ChannelPanelBorder.Visibility = Visibility.Collapsed;
        VideoColumn.Width = new GridLength(1, GridUnitType.Star);

        FullscreenIcon.Text = Mdl2.FullscreenExit;
        FullscreenBtn.ToolTip = "退出全屏";
        ShowControls(true);
        ResetAutoHideTimer();
        // 布局更新（隐藏频道面板、视频区扩展到整屏）在下一次布局过程后才生效，
        // Dispatcher 排队到 Loaded 优先级以确保拿到更新后的 ActualWidth/Height。
        Dispatcher.BeginInvoke(new Action(SyncOverlayPopups), DispatcherPriority.Loaded);
        // 保险起见再排一次较低优先级的重新同步：部分系统下全屏窗口过渡（尤其是多显示器/
        // 高 DPI）可能比一次 Loaded 优先级回调更慢完成，晚一帧再同步一次避免底栏错位。
        Dispatcher.BeginInvoke(new Action(SyncOverlayPopups), DispatcherPriority.Background);
    }

    private void ExitFullScreen()
    {
        if (!_isFullScreen) return;
        _hostWindow ??= Window.GetWindow(this);
        if (_hostWindow == null) return;

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
            _hostWindow.Left = _savedLeft;
            _hostWindow.Top = _savedTop;
            _hostWindow.Width = _savedWidth;
            _hostWindow.Height = _savedHeight;
        }

        _isFullScreen = false;
        PanelColumn.Width = new GridLength(360);
        ChannelPanelBorder.Visibility = Visibility.Visible;
        VideoColumn.Width = new GridLength(7, GridUnitType.Star);

        FullscreenIcon.Text = Mdl2.Fullscreen;
        FullscreenBtn.ToolTip = "全屏";
        ShowControls(true);
        ResetAutoHideTimer();
        Dispatcher.BeginInvoke(new Action(SyncOverlayPopups), DispatcherPriority.Loaded);
        Dispatcher.BeginInvoke(new Action(SyncOverlayPopups), DispatcherPriority.Background);
    }

    // ══════════════════════════════════════════════════════════════════════
    // 控制条显隐（鼠标进入视频区域立即显示，移出立即隐藏）
    // ══════════════════════════════════════════════════════════════════════

    private void ShowControls(bool show)
    {
        _showControls = show;
        // 顶栏/底栏现在承载于独立的覆盖 Popup 中，不再与视频区共享 Grid 行，
        // 因此 Hidden/Collapsed 均不会影响视频区域尺寸。
        TopBarGrid.Visibility = show ? Visibility.Visible : Visibility.Hidden;
        BottomBarGrid.Visibility = show ? Visibility.Visible : Visibility.Hidden;
        // 隐藏时禁止命中测试，避免挡住视频区域的鼠标事件/悬停判定
        TopBarGrid.IsHitTestVisible = show;
        BottomBarGrid.IsHitTestVisible = show;
        // Popup 是独立的原生顶层窗口，覆盖在视频画面之上。WPF 的 IsHitTestVisible
        // 只影响 WPF 内部路由事件，并不能阻止该原生窗口在 Win32 层面继续接收/吞掉
        // 鼠标消息——如果不处理，控制条隐藏后这块原生窗口仍会挡住下面视频 HwndHost
        // 的 WM_MOUSEMOVE，导致"鼠标移到视频上却无法再次唤出控制条"。
        // 这里在控制条隐藏时，给 Popup 窗口加上 WS_EX_TRANSPARENT（点击穿透），
        // 让所有鼠标消息穿透到下层视频窗口；控制条显示时移除该样式以便按钮/
        // 音量条可以正常响应点击。
        SetPopupClickThrough(ControlsPopup, !show);
    }

    /// <summary>任意鼠标移动信号：显示控制条，并重新开始 3 秒无操作倒计时。
    /// 普通模式和全屏模式使用同一套逻辑——移动鼠标就显示，停止移动 3 秒后自动隐藏。</summary>
    private void AutoHideControls()
    {
        ShowControls(true);
        ResetAutoHideTimer();
    }

    /// <summary>轮询鼠标屏幕坐标，判断是否仍在视频区域内（用于 Popup 点击穿透等判定）。
    /// 因为 MpvVideoHost 是原生子窗口（HwndHost），标准 WPF MouseEnter/MouseLeave
    /// 在其上不可靠（Airspace 问题），所以改用屏幕坐标 + 控件包围盒判断。</summary>
    private void OnMouseTrackTick(object? sender, EventArgs e)
    {
        if (VideoOuterGrid == null || !VideoOuterGrid.IsLoaded) return;
        _hostWindow ??= Window.GetWindow(this);
        if (_hostWindow == null) return;

        bool inside;
        try
        {
            // 用 WPF 自身坐标系而不是 Win32 GetCursorPos 的物理像素坐标，避免高 DPI /
            // 多显示器下物理像素与 WPF 逻辑像素换算不一致，导致越靠下（离顶部越远）
            // 误差越大，出现"上面能触发、下面黑边触发不了"的问题。
            // 以窗口为参照系查询鼠标位置（比直接以视频区域为参照更可靠，
            // 因为鼠标可能正处于 HwndHost 原生子窗口上方），再换算到视频整体区域的本地坐标。
            var posInWindow = Mouse.GetPosition(_hostWindow);
            var origin = VideoOuterGrid.TranslatePoint(new Point(0, 0), _hostWindow);
            var local = new Point(posInWindow.X - origin.X, posInWindow.Y - origin.Y);
            var bounds = new Rect(0, 0,
                Math.Max(VideoOuterGrid.ActualWidth, 0),
                Math.Max(VideoOuterGrid.ActualHeight, 0));
            inside = bounds.Contains(local);
        }
        catch
        {
            inside = false;
        }

        _mouseInVideoArea = inside;
    }

    /// <summary>重置自动隐藏倒计时（鼠标移动、点击控制条、拖动滑块等交互都应调用）。</summary>
    private void ResetAutoHideTimer()
    {
        _autoHideTimer.Stop();
        _autoHideTimer.Start();
    }

    private void OnAutoHideTick(object? sender, EventArgs e)
    {
        ShowControls(false);
    }

    // ══════════════════════════════════════════════════════════════════════
    // UI 刷新
    // ══════════════════════════════════════════════════════════════════════

    private void RefreshBuffering()
    {
        if (_isBuffering)
        {
            BufferingIndicator.Visibility = Visibility.Visible;
            var anim = new DoubleAnimation(0, 360, TimeSpan.FromSeconds(1)) { RepeatBehavior = RepeatBehavior.Forever };
            BufferingRotate.BeginAnimation(RotateTransform.AngleProperty, anim);
        }
        else
        {
            BufferingIndicator.Visibility = Visibility.Collapsed;
            BufferingRotate.BeginAnimation(RotateTransform.AngleProperty, null);
        }
    }

    private void RefreshPlayPause()
    {
        PlayPauseIcon.Text = _isPlaying ? Mdl2.Pause : Mdl2.PlayArrow;
        PlayPauseBtn.ToolTip = _isPlaying ? "暂停" : "播放";
    }

    private void RefreshOverlay()
    {
        ChannelNameText.Text = _channelName ?? "";
        PanelTitleText.Text = string.IsNullOrEmpty(_channelName) ? "IPTV" : _channelName;
    }

    private void RefreshPanelState()
    {
        if (_isLoading)
        {
            EmptyStateBorder.Visibility = Visibility.Visible;
            EmptyStateText.Text = "加载中…";
            return;
        }
        if (_grouped == null || _grouped.Count == 0)
        {
            EmptyStateBorder.Visibility = Visibility.Visible;
            EmptyStateText.Text = "暂无频道";
            return;
        }
        EmptyStateBorder.Visibility = Visibility.Collapsed;
        RefreshGroupList();
        RefreshChannelList();
        RefreshStreamList();
    }

    // ── 列1：分组 ──────────────────────────────────────────────────────────
    private void RefreshGroupList()
    {
        GroupPanel.Children.Clear();
        foreach (var g in _groups)
            GroupPanel.Children.Add(MakeGroupItem(g));
    }

    private FrameworkElement MakeGroupItem(string g)
    {
        bool isBrowsing = _browseGroup == g;
        bool isPlaying = _playingGroup == g;

        Brush bg = isBrowsing ? PrimaryBrush
                  : isPlaying ? UiHelper.WithAlpha(PrimaryContainerBrush, 120)
                  : Brushes.Transparent;
        Brush fg = isBrowsing ? OnPrimaryBrush
                  : isPlaying ? PrimaryBrush
                  : OnSurfaceBrush;

        var tb = new TextBlock
        {
            Text = g,
            TextAlignment = TextAlignment.Center,
            FontSize = 11,
            TextWrapping = TextWrapping.Wrap,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Foreground = fg,
            FontWeight = (isBrowsing || isPlaying) ? FontWeights.SemiBold : FontWeights.Normal,
            MaxHeight = 40,
        };

        var b = new Border
        {
            Background = bg,
            CornerRadius = new CornerRadius(6),
            Margin = new Thickness(4, 2, 4, 2),
            Padding = new Thickness(6, 10, 6, 10),
            Cursor = Cursors.Hand,
            Child = tb,
        };

        var captured = g;
        b.MouseLeftButtonDown += (_, __) =>
        {
            _browseGroup = captured;
            RefreshGroupList();
            RefreshChannelList();
        };
        return b;
    }

    // ── 列2：频道 ──────────────────────────────────────────────────────────
    private void RefreshChannelList()
    {
        ChannelPanel.Children.Clear();

        if (string.IsNullOrEmpty(_browseGroup) || !_grouped.TryGetValue(_browseGroup, out var list))
        {
            ChannelPanel.Children.Add(new TextBlock
            {
                Text = "请选择分组",
                FontSize = 12,
                Foreground = OnSurfaceVariantBrush,
                Margin = new Thickness(10, 8, 0, 0),
            });
            return;
        }

        // 按频道名去重
        var seen = new HashSet<string>();
        foreach (var ch in list)
        {
            if (!seen.Add(ch.Name)) continue;
            ChannelPanel.Children.Add(MakeChannelItem(ch));
        }
    }

    private FrameworkElement MakeChannelItem(M3uChannel ch)
    {
        bool selected = _channelName == ch.Name;

        var wrap = new Border
        {
            Background = selected ? UiHelper.WithAlpha(PrimaryContainerBrush, 180) : Brushes.Transparent,
            Padding = new Thickness(10, 9, 10, 9),
            Cursor = Cursors.Hand,
        };

        if (!selected)
        {
            wrap.MouseEnter += (_, __) => wrap.Background = SurfaceContainerHighestBrush;
            wrap.MouseLeave += (_, __) => wrap.Background = Brushes.Transparent;
        }

        var row = new Grid();
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        var logo = MakeLogo(ch.Logo);
        Grid.SetColumn(logo, 0);
        row.Children.Add(logo);

        var name = new TextBlock
        {
            Text = ch.Name,
            FontSize = 12,
            TextTrimming = TextTrimming.CharacterEllipsis,
            FontWeight = selected ? FontWeights.Bold : FontWeights.Normal,
            Foreground = selected ? OnPrimaryContainerBrush : OnSurfaceBrush,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(6, 0, 0, 0),
        };
        Grid.SetColumn(name, 1);
        row.Children.Add(name);

        if (selected)
        {
            var arrow = new TextBlock
            {
                Text = Mdl2.PlayArrow,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 13,
                Foreground = PrimaryBrush,
                VerticalAlignment = VerticalAlignment.Center,
            };
            Grid.SetColumn(arrow, 2);
            row.Children.Add(arrow);
        }

        wrap.Child = row;

        var captured = ch;
        wrap.MouseLeftButtonDown += (_, __) => SelectChannelInternal(captured, autoPlay: true);
        return wrap;
    }

    private FrameworkElement MakeLogo(string logoUrl)
    {
        var grid = new Grid { Width = 24, Height = 24 };
        grid.Children.Add(MakeLogoFallback());

        if (!string.IsNullOrEmpty(logoUrl))
        {
            try
            {
                var uri = new Uri(logoUrl, UriKind.Absolute);
                if (uri.Scheme.StartsWith("http", StringComparison.OrdinalIgnoreCase))
                {
                    var img = new Image
                    {
                        Stretch = Stretch.Uniform,
                        VerticalAlignment = VerticalAlignment.Center,
                        HorizontalAlignment = HorizontalAlignment.Center,
                    };
                    var bmp = new BitmapImage();
                    bmp.BeginInit();
                    bmp.UriSource = uri;
                    bmp.CacheOption = BitmapCacheOption.OnLoad;
                    bmp.EndInit();
                    img.Source = bmp;
                    img.ImageFailed += (_, __) => img.Visibility = Visibility.Collapsed;
                    grid.Children.Add(img);
                }
            }
            catch { }
        }
        return grid;
    }

    private FrameworkElement MakeLogoFallback()
    {
        return new Border
        {
            Width = 24,
            Height = 24,
            CornerRadius = new CornerRadius(3),
            Background = UiHelper.WithAlpha(PrimaryContainerBrush, 60),
            Child = new TextBlock
            {
                Text = Mdl2.LiveTv,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 13,
                Foreground = PrimaryBrush,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            },
        };
    }

    // ── 列3：源 ────────────────────────────────────────────────────────────
    private void RefreshStreamList()
    {
        StreamPanel.Children.Clear();
        for (int i = 0; i < _streamUrls.Count; i++)
            StreamPanel.Children.Add(MakeStreamItem(i));
    }

    private FrameworkElement MakeStreamItem(int i)
    {
        bool sel = _streamIndex == i;
        var b = new Border
        {
            Background = sel ? PrimaryBrush : SurfaceContainerHighestBrush,
            CornerRadius = new CornerRadius(16),
            Margin = new Thickness(6, 3, 6, 3),
            Padding = new Thickness(8, 8, 8, 8),
            Cursor = Cursors.Hand,
            Child = new TextBlock
            {
                Text = "源" + (i + 1),
                TextAlignment = TextAlignment.Center,
                FontSize = 12,
                FontWeight = sel ? FontWeights.Bold : FontWeights.Normal,
                Foreground = sel ? OnPrimaryBrush : OnSurfaceVariantBrush,
            },
        };

        var captured = i;
        b.MouseLeftButtonDown += (_, __) => SelectStream(captured);
        return b;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 事件处理
    // ══════════════════════════════════════════════════════════════════════

    private void Page_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.Space:
                TogglePlay();
                AutoHideControls();
                e.Handled = true;
                break;
            case Key.Escape:
                if (_isFullScreen) ExitFullScreen();
                else GoBack();
                e.Handled = true;
                break;
            case Key.F:
                ToggleFullScreen();
                e.Handled = true;
                break;
        }
    }

    private void BackBtn_Click(object sender, RoutedEventArgs e)
    {
        if (_isFullScreen) { ExitFullScreen(); return; }
        GoBack();
    }

    /// <summary>关闭播放页覆盖层，回到 Tab 主页（同窗口模式）。</summary>
    private void GoBack()
    {
        if (App.Current.MainWindow is Views.MainWindow mw)
            mw.ClosePlayerPage();
        else
        {
            var w = Window.GetWindow(this);
            w?.Close();
        }
    }

    private void PlayPauseBtn_Click(object sender, RoutedEventArgs e) => TogglePlay();

    private void FullscreenBtn_Click(object sender, RoutedEventArgs e) => ToggleFullScreen();

    private void VolumeSlider_ValueChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (!IsLoaded || _mpv == null) return;
        SetVolume(VolumeSlider.Value);
        AutoHideControls();
    }

    private void VideoArea_MouseMove(object sender, MouseEventArgs e) => AutoHideControls();

    /// <summary>宿主窗口级鼠标移动兜底：HwndHost 子窗口会吞掉 WPF 路由事件，
    /// 视频区内的 MouseMove 不可靠，用窗口级 PreviewMouseMove 确保能唤出控制条。</summary>
    private void HostWindow_PreviewMouseMove(object sender, MouseEventArgs e) => AutoHideControls();

    // ══════════════════════════════════════════════════════════════════════
    // 主题色 brush 便捷访问
    // ══════════════════════════════════════════════════════════════════════

    private static Brush PrimaryBrush => (Brush)Application.Current.Resources["M3.Primary"];
    private static Brush OnPrimaryBrush => (Brush)Application.Current.Resources["M3.OnPrimary"];
    private static Brush PrimaryContainerBrush => (Brush)Application.Current.Resources["M3.PrimaryContainer"];
    private static Brush OnPrimaryContainerBrush => (Brush)Application.Current.Resources["M3.OnPrimaryContainer"];
    private static Brush OnSurfaceBrush => (Brush)Application.Current.Resources["M3.OnSurface"];
    private static Brush OnSurfaceVariantBrush => (Brush)Application.Current.Resources["M3.OnSurfaceVariant"];
    private static Brush SurfaceContainerHighestBrush => (Brush)Application.Current.Resources["M3.SurfaceContainerHighest"];

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
