using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using MEPlayer.Helpers;
using MEPlayer.Models;
using MEPlayer.Services;

namespace MEPlayer.Pages;

/// <summary>
/// 视频库页（对应 Flutter 端 video_tab_page.dart）。
///
/// 结构：左侧栏（标题 + 添加路径 / 全部视频 / 路径列表）+ 中间分隔线 +
/// 右侧内容区（工具栏：当前路径 / 计数 / 搜索框 / 刷新 / 排序；分隔线；视频网格或空状态）。
/// 右下角 FAB 用于播放网络视频。
///
/// 控制器逻辑（对应 Flutter VideoLibraryController）：
/// _filesByPath 保存每个扫描路径下的视频列表；thumbnails 缓存视频→缩略图相对路径；
/// 排序模式与缩略图缓存持久化到 StorageService；缩略图后台生成队列最多 2 个并发。
/// </summary>
public partial class VideoTabPage : UserControl
{
    // ── 排序枚举（与 Flutter SortMode 一一对应） ──────────────────────────
    public enum SortMode { Name, Size, Ext }

    // ── 视频扩展名白名单（与 Flutter videoExtensions 一致） ────────────────
    private static readonly HashSet<string> VideoExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".webm",
        ".m4v", ".mpg", ".mpeg", ".ts", ".m2ts", ".3gp",
    };

    // ── 服务 ─────────────────────────────────────────────────────────────
    private readonly AppSettingsService _settings;
    private readonly StorageService _storage;
    private readonly VideoThumbnailGenerator _thumbGen = new();

    // ── 控制器状态（对应 Flutter VideoLibraryController 的字段） ───────────
    private readonly Dictionary<string, List<VideoFile>> _filesByPath = new();
    // 视频路径 → 缩略图相对路径；空字符串 = 已尝试生成但失败，不存在 = 还没处理过
    private Dictionary<string, string> _thumbnails = new();
    private bool _isScanning;
    private string _search = "";
    private SortMode _sortMode = SortMode.Name;
    private string _selectedPath = ""; // '' = 全部

    // ── 缩略图生成队列：最多 2 个并发，避免一次性打开大量解码器实例 ───────
    private const int ThumbMaxConcurrent = 2;
    private readonly List<VideoFile> _thumbQueue = new();
    private int _thumbRunning;
    private readonly object _thumbLock = new();

    // ── 当前已渲染的卡片视图（视频路径 → 视图引用），缩略图生成完成后定向刷新 ──
    private readonly Dictionary<string, CardViews> _cardViews = new();

    // ── UI 元素引用 ──────────────────────────────────────────────────────
    private StackPanel _sidebarList = null!;             // 侧栏列表（全部视频 + 分隔线 + 路径项）
    private TextBlock _currentPathLabel = null!;         // 工具栏当前路径标签
    private TextBlock _statusText = null!;               // "扫描中.../N 个视频"
    private TextBox _searchBox = null!;
    private TextBlock _watermark = null!;
    private TextBlock _refreshIcon = null!;
    private RotateTransform _refreshRotate = null!;
    private DoubleAnimation _refreshAnim = null!;
    private Grid _contentHost = null!;                   // 网格 / 空状态 / spinner 宿主

    public VideoTabPage()
    {
        InitializeComponent();
        _settings = App.Current.Settings;
        _storage = App.Current.Storage;
        BuildShell();
        _isScanning = true; // 即将开始扫描，避免扫描前闪现"未找到视频"
        RefreshSidebar();   // 首次填充侧栏（含空提示）
        UpdateToolbar();
        RefreshGrid();      // 首次显示空状态 / spinner
        Loaded += OnLoaded;
        Unloaded += (_, _) => { try { _thumbGen.Dispose(); } catch { } };
    }

    // ── 启动：加载持久化状态，然后扫描所有路径 ──────────────────────────────
    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnLoaded;
        LoadSortMode();
        LoadThumbCache();
        await ScanAllAsync();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  外层骨架构建（左侧栏 | 分隔线 | 右侧内容区 + FAB）
    // ═══════════════════════════════════════════════════════════════════════
    private void BuildShell()
    {
        // 三列：200px 侧栏 | 1px 分隔线 | 弹性内容区
        RootGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(200, GridUnitType.Pixel) });
        RootGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Pixel) });
        RootGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        // ── 左侧栏 ──────────────────────────────────────────────────────
        var sidebar = new Grid { Background = UiHelper.BrushOf("M3.Surface") };
        sidebar.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });  // 标题行
        sidebar.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) }); // 列表
        Grid.SetColumn(sidebar, 0);
        RootGrid.Children.Add(sidebar);

        // 标题行："媒体库" + 添加路径按钮
        var header = new Grid { Margin = new Thickness(14, 14, 8, 6) };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.Children.Add(new TextBlock
        {
            Text = "媒体库",
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
        });
        var addPathBtn = new Button
        {
            Content = UiHelper.Icon(Mdl2.NewFolder, 20, UiHelper.BrushOf("M3.Primary")),
            Width = 28, Height = 28,
            Padding = new Thickness(0),
            BorderThickness = new Thickness(0),
            Background = Brushes.Transparent,
            Cursor = Cursors.Hand,
            ToolTip = "添加本地路径",
            Template = UiHelper.TransparentButtonTemplate(),
        };
        addPathBtn.Click += (_, _) => _ = AddPathAsync();
        Grid.SetColumn(addPathBtn, 1);
        header.Children.Add(addPathBtn);
        sidebar.Children.Add(header);

        // 侧栏列表（可滚动）：全部视频 + 分隔线 + 路径项，由 RefreshSidebar 统一重建
        _sidebarList = new StackPanel { Orientation = Orientation.Vertical };
        var pathScroll = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = _sidebarList,
            Padding = new Thickness(0),
        };
        Grid.SetRow(pathScroll, 1);
        sidebar.Children.Add(pathScroll);

        // ── 中间分隔线 ──────────────────────────────────────────────────
        var vline = new Border
        {
            Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 60),
        };
        Grid.SetColumn(vline, 1);
        RootGrid.Children.Add(vline);

        // ── 右侧内容区 ──────────────────────────────────────────────────
        var content = new Grid { Background = UiHelper.BrushOf("M3.Surface") };
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 工具栏
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto }); // 分隔线
        content.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) }); // 内容
        Grid.SetColumn(content, 2);
        RootGrid.Children.Add(content);

        content.Children.Add(BuildToolbar());
        var toolbarDivider = new Border
        {
            Height = 1,
            Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 60),
        };
        Grid.SetRow(toolbarDivider, 1);
        content.Children.Add(toolbarDivider);

        // 内容宿主：网格 / 空状态 / spinner 都放进这里，FAB 也叠在同行
        _contentHost = new Grid();
        Grid.SetRow(_contentHost, 2);
        content.Children.Add(_contentHost);

        // FAB：播放网络视频（右下角圆形按钮）
        var fab = new Button
        {
            Width = 56, Height = 56,
            Margin = new Thickness(0, 0, 20, 20),
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Bottom,
            Background = UiHelper.BrushOf("M3.Primary"),
            Foreground = UiHelper.BrushOf("M3.OnPrimary"),
            Cursor = Cursors.Hand,
            ToolTip = "播放网络视频",
            Content = UiHelper.Icon(Mdl2.Cast, 24, UiHelper.BrushOf("M3.OnPrimary")),
            Template = CircleButtonTemplate(),
        };
        fab.Click += (_, _) => ShowNetworkDialog();
        Grid.SetRow(fab, 2);
        content.Children.Add(fab);
    }

    // ── 工具栏 ──────────────────────────────────────────────────────────────
    private FrameworkElement BuildToolbar()
    {
        var bar = new Grid { Margin = new Thickness(16, 10, 12, 6) };
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 路径标签
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 状态
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 搜索框
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 刷新
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 排序

        _currentPathLabel = new TextBlock
        {
            Text = "全部视频",
            FontSize = 15,
            FontWeight = FontWeights.SemiBold,
            Foreground = UiHelper.BrushOf("M3.OnSurface"),
            VerticalAlignment = VerticalAlignment.Center,
        };
        bar.Children.Add(_currentPathLabel);

        _statusText = new TextBlock
        {
            Text = "扫描中...",
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(8, 0, 0, 0),
        };
        Grid.SetColumn(_statusText, 1);
        bar.Children.Add(_statusText);

        // 搜索框（220×34，圆角，带搜索图标 + 水印）
        var searchBorder = new Border
        {
            Width = 220, Height = 34,
            Background = UiHelper.BrushOf("M3.Surface"),
            BorderBrush = UiHelper.BrushOf("M3.OutlineVariant"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(8, 0, 4, 0),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(8, 0, 6, 0),
        };
        Grid.SetColumn(searchBorder, 3);
        bar.Children.Add(searchBorder);

        var searchGrid = new Grid();
        searchGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        searchGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var sIcon = UiHelper.Icon(Mdl2.Search, 16, UiHelper.BrushOf("M3.OnSurfaceVariant"));
        sIcon.VerticalAlignment = VerticalAlignment.Center;
        searchGrid.Children.Add(sIcon);

        var searchCell = new Grid();
        _searchBox = new TextBox
        {
            Margin = new Thickness(6, 0, 0, 0),
            VerticalContentAlignment = VerticalAlignment.Center,
            BorderThickness = new Thickness(0),
            Background = Brushes.Transparent,
            FontSize = 13,
        };
        _watermark = new TextBlock
        {
            Text = "搜索视频...",
            FontSize = 13,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
            IsHitTestVisible = false,
        };
        searchCell.Children.Add(_searchBox);
        searchCell.Children.Add(_watermark);
        Grid.SetColumn(searchCell, 1);
        searchGrid.Children.Add(searchCell);
        searchBorder.Child = searchGrid;

        _searchBox.TextChanged += (_, _) =>
        {
            _search = _searchBox.Text ?? "";
            _watermark.Visibility = string.IsNullOrEmpty(_search) ? Visibility.Visible : Visibility.Collapsed;
            UpdateToolbar();
            RefreshGrid();
        };

        // 刷新按钮（扫描中旋转）
        _refreshRotate = new RotateTransform(0);
        _refreshIcon = UiHelper.Icon(Mdl2.Refresh, 20, UiHelper.BrushOf("M3.OnSurfaceVariant"));
        _refreshIcon.RenderTransformOrigin = new Point(0.5, 0.5);
        _refreshIcon.RenderTransform = _refreshRotate;
        _refreshAnim = new DoubleAnimation(0, 360, TimeSpan.FromSeconds(1))
        {
            RepeatBehavior = RepeatBehavior.Forever,
        };
        var refreshBtn = new Button
        {
            Content = _refreshIcon,
            Width = 34, Height = 34,
            Padding = new Thickness(0),
            BorderThickness = new Thickness(0),
            Background = Brushes.Transparent,
            Cursor = Cursors.Hand,
            ToolTip = "重新扫描",
            Template = UiHelper.TransparentButtonTemplate(),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 2, 0),
        };
        Grid.SetColumn(refreshBtn, 4);
        refreshBtn.Click += (_, _) =>
        {
            if (!_isScanning) _ = ScanAllAsync();
        };
        bar.Children.Add(refreshBtn);

        // 排序下拉菜单（按名称 / 按大小 / 按格式）
        var sortBtn = new Button
        {
            Content = UiHelper.Icon(Mdl2.Sort, 20, UiHelper.BrushOf("M3.OnSurfaceVariant")),
            Width = 34, Height = 34,
            Padding = new Thickness(0),
            BorderThickness = new Thickness(0),
            Background = Brushes.Transparent,
            Cursor = Cursors.Hand,
            ToolTip = "排序",
            Template = UiHelper.TransparentButtonTemplate(),
            VerticalAlignment = VerticalAlignment.Center,
        };
        Grid.SetColumn(sortBtn, 5);
        var sortMenu = new ContextMenu();
        sortMenu.Items.Add(BuildSortMenuItem("按名称", SortMode.Name));
        sortMenu.Items.Add(BuildSortMenuItem("按大小", SortMode.Size));
        sortMenu.Items.Add(BuildSortMenuItem("按格式", SortMode.Ext));
        sortBtn.ContextMenu = sortMenu;
        sortBtn.Click += (_, _) =>
        {
            // 打开前刷新勾选状态与表头
            var labels = new[] { "按名称", "按大小", "按格式" };
            var modes = new[] { SortMode.Name, SortMode.Size, SortMode.Ext };
            for (int i = 0; i < sortMenu.Items.Count; i++)
            {
                var mi = (MenuItem)sortMenu.Items[i];
                mi.Header = (_sortMode == modes[i] ? "✓ " : "   ") + labels[i];
            }
            sortMenu.PlacementTarget = sortBtn;
            sortMenu.IsOpen = true;
        };
        bar.Children.Add(sortBtn);

        return bar;
    }

    private MenuItem BuildSortMenuItem(string header, SortMode mode)
    {
        var mi = new MenuItem { Header = header, Tag = mode };
        mi.Click += (_, _) => SetSortMode(mode);
        return mi;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  左侧栏项
    // ═══════════════════════════════════════════════════════════════════════
    /// <summary>
    /// 构建一个侧栏项（图标 + 标签 + 计数 / 删除按钮）。
    /// 选中态与 hover 态通过闭包内变量维护；计数在构建时写入。
    /// </summary>
    private Border BuildSidebarItem(
        string iconGlyph, string label, string? tooltip,
        int count, bool selected,
        Action onTap, Action? onRemove)
    {
        var row = new Border
        {
            Margin = new Thickness(8, 2, 8, 2),
            Padding = new Thickness(10, 8, 10, 8),
            CornerRadius = new CornerRadius(7),
            Cursor = Cursors.Hand,
            ToolTip = tooltip,
        };
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 图标
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); // 标签
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 计数 / 删除

        var icon = UiHelper.Icon(iconGlyph, 17,
            selected ? UiHelper.BrushOf("M3.Primary") : UiHelper.BrushOf("M3.OnSurfaceVariant"));
        icon.VerticalAlignment = VerticalAlignment.Center;
        grid.Children.Add(icon);

        var name = new TextBlock
        {
            Text = label,
            FontSize = 13,
            FontWeight = selected ? FontWeights.SemiBold : FontWeights.Normal,
            Foreground = selected ? UiHelper.BrushOf("M3.OnPrimaryContainer") : UiHelper.BrushOf("M3.OnSurface"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(8, 0, 4, 0),
            TextTrimming = TextTrimming.CharacterEllipsis,
        };
        Grid.SetColumn(name, 1);
        grid.Children.Add(name);

        var countText = new TextBlock
        {
            Text = count.ToString(),
            FontSize = 11,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
        };
        Grid.SetColumn(countText, 2);
        grid.Children.Add(countText);

        // 删除按钮（仅路径项有，hover 时显示，替换计数）
        Button? removeBtn = null;
        if (onRemove != null)
        {
            removeBtn = new Button
            {
                Content = UiHelper.Icon(Mdl2.Close, 14, UiHelper.BrushOf("M3.OnSurfaceVariant")),
                Width = 20, Height = 20,
                Padding = new Thickness(0),
                BorderThickness = new Thickness(0),
                Background = Brushes.Transparent,
                Cursor = Cursors.Hand,
                ToolTip = "移除路径",
                Template = UiHelper.TransparentButtonTemplate(),
                VerticalAlignment = VerticalAlignment.Center,
                Visibility = Visibility.Collapsed,
            };
            removeBtn.Click += (_, e) => { e.Handled = true; onRemove(); };
            Grid.SetColumn(removeBtn, 2);
            grid.Children.Add(removeBtn);
        }

        row.Child = grid;

        // hover / 选中态背景与删除按钮显隐
        bool hovered = false;
        void UpdateBg()
        {
            if (selected)
                row.Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.PrimaryContainer"), 160);
            else if (hovered)
                row.Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.SurfaceContainerHighest"), 120);
            else
                row.Background = Brushes.Transparent;
            if (removeBtn != null)
                removeBtn.Visibility = hovered ? Visibility.Visible : Visibility.Collapsed;
        }
        UpdateBg();

        row.MouseEnter += (_, _) => { hovered = true; UpdateBg(); };
        row.MouseLeave += (_, _) => { hovered = false; UpdateBg(); };
        row.MouseLeftButtonUp += (_, _) => onTap();

        return row;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  刷新：侧栏 / 工具栏 / 网格
    // ═══════════════════════════════════════════════════════════════════════
    private void RefreshSidebar()
    {
        _sidebarList.Children.Clear();

        // "全部视频" 项（选中当 _selectedPath 为空）
        var allItem = BuildSidebarItem(
            Mdl2.VideoLibraryOutlined, "全部视频", null,
            count: AllFiles.Count,
            selected: _selectedPath.Length == 0,
            onTap: () => SelectPath(""),
            onRemove: null);
        _sidebarList.Children.Add(allItem);

        // 分隔线
        _sidebarList.Children.Add(new Border
        {
            Height = 1,
            Margin = new Thickness(14, 6, 14, 6),
            Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 60),
        });

        // 路径列表
        var paths = _settings.VideoScanPaths;
        if (paths.Count == 0)
        {
            _sidebarList.Children.Add(new TextBlock
            {
                Text = "点击右上角 + 添加路径",
                FontSize = 12,
                Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
                Margin = new Thickness(14, 8, 14, 8),
                TextAlignment = TextAlignment.Center,
                TextWrapping = TextWrapping.Wrap,
            });
            return;
        }

        foreach (var path in paths)
        {
            var p = path; // 闭包捕获
            var cnt = _filesByPath.TryGetValue(p, out var list) ? list.Count : 0;
            var item = BuildSidebarItem(
                Mdl2.FolderOutlined,
                UiHelper.LastPathSegment(p),
                p,
                count: cnt,
                selected: _selectedPath == p,
                onTap: () => SelectPath(p),
                onRemove: () => RemovePath(p));
            _sidebarList.Children.Add(item);
        }
    }

    private void UpdateToolbar()
    {
        var sel = _selectedPath;
        _currentPathLabel.Text = sel.Length == 0 ? "全部视频" : UiHelper.LastPathSegment(sel);
        _statusText.Text = _isScanning ? "扫描中..." : $"{Filtered.Count} 个视频";
    }

    private void RefreshGrid()
    {
        _cardViews.Clear();
        _contentHost.Children.Clear();

        // 没有媒体库路径 → 空状态
        if (_settings.VideoScanPaths.Count == 0)
        {
            _contentHost.Children.Add(BuildEmptyState());
            return;
        }

        // 扫描中且当前过滤列表为空 → spinner
        if (_isScanning && Filtered.Count == 0)
        {
            _contentHost.Children.Add(BuildSpinner());
            return;
        }

        var list = Filtered;
        if (list.Count == 0)
        {
            _contentHost.Children.Add(new TextBlock
            {
                Text = "未找到视频",
                FontSize = 14,
                Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            });
            return;
        }

        // 网格：WrapPanel，每张卡片 240×150（maxCrossAxisExtent=240, childAspectRatio=16/10）
        var wrap = new WrapPanel
        {
            Orientation = Orientation.Horizontal,
            Margin = new Thickness(16),
        };
        foreach (var f in list)
            wrap.Children.Add(BuildCard(f));

        var scroll = new ScrollViewer
        {
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
            Content = wrap,
        };
        _contentHost.Children.Add(scroll);
    }

    private void RefreshAll()
    {
        RefreshSidebar();
        UpdateToolbar();
        RefreshGrid();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  空状态 / Spinner
    // ═══════════════════════════════════════════════════════════════════════
    private FrameworkElement BuildEmptyState()
    {
        var sp = new StackPanel
        {
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        var icon = UiHelper.Icon(Mdl2.VideoLibraryOutlined, 72,
            UiHelper.WithAlpha(UiHelper.BrushOf("M3.OnSurfaceVariant"), 120));
        icon.HorizontalAlignment = HorizontalAlignment.Center;
        icon.Margin = new Thickness(0, 0, 0, 16);
        sp.Children.Add(icon);
        sp.Children.Add(new TextBlock
        {
            Text = "没有媒体库路径",
            FontSize = 16,
            FontWeight = FontWeights.SemiBold,
            Foreground = UiHelper.BrushOf("M3.OnSurface"),
            HorizontalAlignment = HorizontalAlignment.Center,
        });
        sp.Children.Add(new TextBlock
        {
            Text = "在左侧点击 + 添加本地文件夹",
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            HorizontalAlignment = HorizontalAlignment.Center,
            Margin = new Thickness(0, 8, 0, 24),
        });
        var addBtn = new Button
        {
            Style = (Style)Application.Current.Resources["FilledButton"],
            Cursor = Cursors.Hand,
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        var btnContent = new StackPanel { Orientation = Orientation.Horizontal };
        btnContent.Children.Add(UiHelper.Icon(Mdl2.NewFolder, 18, UiHelper.BrushOf("M3.OnPrimary")));
        btnContent.Children.Add(new TextBlock
        {
            Text = "添加媒体库路径",
            Foreground = UiHelper.BrushOf("M3.OnPrimary"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(6, 0, 0, 0),
        });
        addBtn.Content = btnContent;
        addBtn.Click += (_, _) => _ = AddPathAsync();
        sp.Children.Add(addBtn);
        return sp;
    }

    private FrameworkElement BuildSpinner()
    {
        // 用旋转的刷新图标近似 CircularProgressIndicator
        var rt = new RotateTransform(0);
        var icon = UiHelper.Icon(Mdl2.Refresh, 32, UiHelper.BrushOf("M3.Primary"));
        icon.RenderTransformOrigin = new Point(0.5, 0.5);
        icon.RenderTransform = rt;
        var anim = new DoubleAnimation(0, 360, TimeSpan.FromSeconds(1))
        {
            RepeatBehavior = RepeatBehavior.Forever,
        };
        var host = new Grid
        {
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        host.Children.Add(icon);
        host.Loaded += (_, _) => rt.BeginAnimation(RotateTransform.AngleProperty, anim);
        return host;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  视频卡片
    // ═══════════════════════════════════════════════════════════════════════
    private FrameworkElement BuildCard(VideoFile f)
    {
        var ext = UiHelper.ExtUpper(f.Name);
        var nameNoExt = UiHelper.WithoutExtension(f.Name);

        // 当前缓存里是否已有可用缩略图
        _thumbnails.TryGetValue(f.Path, out var cachedRel);
        var hasThumbAtBuild = !string.IsNullOrEmpty(cachedRel)
            && File.Exists(AppDataDir.ToAbsolute(cachedRel));

        var card = new Border
        {
            Width = 240, Height = 150,
            Margin = new Thickness(5),
            CornerRadius = new CornerRadius(9),
            ClipToBounds = true,
            Cursor = Cursors.Hand,
            Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.SurfaceContainerHighest"), 70),
            BorderBrush = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 50),
            BorderThickness = new Thickness(1),
        };

        var grid = new Grid();

        // 缩略图（铺满，UniformToFill ≈ BoxFit.cover）
        var thumb = new Image
        {
            Stretch = Stretch.UniformToFill,
            Visibility = hasThumbAtBuild ? Visibility.Visible : Visibility.Collapsed,
        };
        if (hasThumbAtBuild)
        {
            try
            {
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.CacheOption = BitmapCacheOption.OnLoad;
                bmp.UriSource = new Uri(AppDataDir.ToAbsolute(cachedRel!));
                bmp.EndInit();
                bmp.Freeze();
                thumb.Source = bmp;
            }
            catch { thumb.Visibility = Visibility.Collapsed; }
        }
        grid.Children.Add(thumb);

        // 无缩略图占位：图标 + 文件名
        var placeholder = new StackPanel
        {
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
            Visibility = hasThumbAtBuild ? Visibility.Collapsed : Visibility.Visible,
        };
        var placeholderIcon = UiHelper.Icon(Mdl2.Movie, 34,
            UiHelper.BrushOf("M3.OnSurfaceVariant"));
        placeholderIcon.HorizontalAlignment = HorizontalAlignment.Center;
        placeholderIcon.Margin = new Thickness(0, 0, 0, 8);
        placeholder.Children.Add(placeholderIcon);
        var placeholderName = new TextBlock
        {
            Text = nameNoExt,
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurface"),
            TextAlignment = TextAlignment.Center,
            TextWrapping = TextWrapping.NoWrap,
            TextTrimming = TextTrimming.CharacterEllipsis,
            Margin = new Thickness(10, 0, 10, 0),
        };
        placeholder.Children.Add(placeholderName);
        grid.Children.Add(placeholder);

        // 有缩略图时底部的渐变 + 文件名
        var gradient = new Border
        {
            Visibility = hasThumbAtBuild ? Visibility.Visible : Visibility.Collapsed,
            VerticalAlignment = VerticalAlignment.Bottom,
            Padding = new Thickness(8, 20, 8, 6),
        };
        gradient.Background = new LinearGradientBrush
        {
            StartPoint = new Point(0, 0),
            EndPoint = new Point(0, 1),
            GradientStops = new GradientStopCollection
            {
                new GradientStop(Color.FromArgb(0, 0, 0, 0), 0),
                new GradientStop(Color.FromArgb(222, 0, 0, 0), 1),
            },
        };
        gradient.Child = new TextBlock
        {
            Text = nameNoExt,
            Foreground = Brushes.White,
            FontSize = 12,
            FontWeight = FontWeights.Medium,
            TextTrimming = TextTrimming.CharacterEllipsis,
            TextWrapping = TextWrapping.NoWrap,
        };
        grid.Children.Add(gradient);

        // 悬停时居中的播放图标
        var playIcon = UiHelper.Icon(Mdl2.Play, 40,
            hasThumbAtBuild ? Brushes.White : UiHelper.BrushOf("M3.Primary"));
        playIcon.HorizontalAlignment = HorizontalAlignment.Center;
        playIcon.VerticalAlignment = VerticalAlignment.Center;
        playIcon.Visibility = Visibility.Collapsed;
        grid.Children.Add(playIcon);

        // 格式徽章（右上角）
        var badge = new Border
        {
            HorizontalAlignment = HorizontalAlignment.Right,
            VerticalAlignment = VerticalAlignment.Top,
            Margin = new Thickness(0, 6, 6, 0),
            Padding = new Thickness(6, 2, 6, 2),
            CornerRadius = new CornerRadius(4),
            Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.Primary"), 200),
        };
        badge.Child = new TextBlock
        {
            Text = ext,
            Foreground = Brushes.White,
            FontSize = 10,
            FontWeight = FontWeights.SemiBold,
        };
        grid.Children.Add(badge);

        card.Child = grid;

        // hover 高亮：切换背景 / 边框 / 播放图标 / 占位图标
        bool hovered = false;
        void UpdateHover()
        {
            if (hovered)
            {
                card.Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.PrimaryContainer"), 100);
                card.BorderBrush = UiHelper.WithAlpha(UiHelper.BrushOf("M3.Primary"), 160);
                playIcon.Visibility = Visibility.Visible;
                placeholderIcon.Text = Mdl2.Play;
                placeholderIcon.Foreground = UiHelper.BrushOf("M3.Primary");
            }
            else
            {
                card.Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.SurfaceContainerHighest"), 70);
                card.BorderBrush = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 50);
                playIcon.Visibility = Visibility.Collapsed;
                placeholderIcon.Text = Mdl2.Movie;
                placeholderIcon.Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant");
            }
        }
        card.MouseEnter += (_, _) => { hovered = true; UpdateHover(); };
        card.MouseLeave += (_, _) => { hovered = false; UpdateHover(); };
        card.MouseLeftButtonUp += (_, _) => Play(f);

        // 记录视图引用，便于缩略图生成完成后定向刷新
        _cardViews[f.Path] = new CardViews
        {
            Thumb = thumb,
            Gradient = gradient,
            Placeholder = placeholder,
            PlaceholderIcon = placeholderIcon,
            PlayIcon = playIcon,
        };

        return card;
    }

    /// <summary>缩略图生成完成后，定向刷新对应卡片的缩略图显示。</summary>
    private void UpdateCardThumbnail(string videoPath, string relResult)
    {
        if (!_cardViews.TryGetValue(videoPath, out var v)) return;
        var hasThumb = !string.IsNullOrEmpty(relResult)
            && File.Exists(AppDataDir.ToAbsolute(relResult));
        if (!hasThumb) return; // 生成失败：保持占位

        try
        {
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            bmp.UriSource = new Uri(AppDataDir.ToAbsolute(relResult));
            bmp.EndInit();
            bmp.Freeze();
            v.Thumb.Source = bmp;
            v.Thumb.Visibility = Visibility.Visible;
            v.Gradient.Visibility = Visibility.Visible;
            v.Placeholder.Visibility = Visibility.Collapsed;
            // 悬停播放图标在有缩略图时改用白色
            v.PlayIcon.Foreground = Brushes.White;
        }
        catch
        {
            // 读取失败：保持占位
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  控制器：文件列表 / 过滤 / 排序
    // ═══════════════════════════════════════════════════════════════════════
    private List<VideoFile> AllFiles => _filesByPath.Values.SelectMany(l => l).ToList();

    private List<VideoFile> SourceFiles
    {
        get
        {
            if (_selectedPath.Length == 0) return AllFiles;
            return _filesByPath.TryGetValue(_selectedPath, out var list) ? list : new List<VideoFile>();
        }
    }

    private List<VideoFile> Filtered
    {
        get
        {
            var list = SourceFiles;
            var q = _search.ToLowerInvariant();
            if (q.Length > 0)
                list = list.Where(f => f.Name.ToLowerInvariant().Contains(q)).ToList();
            list = new List<VideoFile>(list);
            switch (_sortMode)
            {
                case SortMode.Size:
                    list.Sort((a, b) => b.Size.CompareTo(a.Size));
                    break;
                case SortMode.Ext:
                    list.Sort((a, b) => string.CompareOrdinal(
                        UiHelper.Extension(a.Name), UiHelper.Extension(b.Name)));
                    break;
                default:
                    list.Sort((a, b) => string.CompareOrdinal(a.Name, b.Name));
                    break;
            }
            return list;
        }
    }

    private void SelectPath(string path)
    {
        _selectedPath = path;
        RefreshSidebar();
        UpdateToolbar();
        RefreshGrid();
    }

    private void SetSortMode(SortMode mode)
    {
        if (_sortMode == mode) return;
        _sortMode = mode;
        _storage.Set(StorageService.KVideoSortMode, mode.ToString().ToLowerInvariant());
        UpdateToolbar();
        RefreshGrid();
    }

    private void LoadSortMode()
    {
        var saved = _storage.Get(StorageService.KVideoSortMode, "name");
        _sortMode = saved switch
        {
            "size" => SortMode.Size,
            "ext" => SortMode.Ext,
            _ => SortMode.Name,
        };
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  扫描
    // ═══════════════════════════════════════════════════════════════════════
    private async Task ScanAllAsync()
    {
        _isScanning = true;
        StartRefreshSpin();
        UpdateToolbar();
        RefreshGrid(); // 进入 spinner（filtered 为空时）

        _filesByPath.Clear();
        var paths = _settings.VideoScanPaths.ToList();
        foreach (var dir in paths)
        {
            var files = await Task.Run(() => ScanPath(dir));
            _filesByPath[dir] = files;
            // 每扫完一个路径就刷新，让用户看到进度（_isScanning 仍为 true，
            // 状态文案保持"扫描中..."，网格随已扫路径逐步显现）
            RefreshSidebar();
            UpdateToolbar();
            RefreshGrid();
        }

        _isScanning = false;
        StopRefreshSpin();
        RefreshAll();
        EnqueueMissingThumbnails(AllFiles);
    }

    private static List<VideoFile> ScanPath(string dirPath)
    {
        var result = new List<VideoFile>();
        try
        {
            if (!Directory.Exists(dirPath)) return result;
            var opts = new EnumerationOptions
            {
                RecurseSubdirectories = true,
                IgnoreInaccessible = true,
            };
            foreach (var fp in Directory.EnumerateFiles(dirPath, "*", opts))
            {
                var ext = Path.GetExtension(fp);
                if (!VideoExtensions.Contains(ext)) continue;
                long size = 0;
                try { size = new FileInfo(fp).Length; } catch { }
                result.Add(new VideoFile(fp, Path.GetFileName(fp), size));
            }
        }
        catch { }
        return result;
    }

    private async Task AddPathAsync()
    {
        var dlg = new Microsoft.Win32.OpenFolderDialog { Title = "选择视频文件夹" };
        if (dlg.ShowDialog() != true) return;
        var path = dlg.FolderName;
        if (string.IsNullOrEmpty(path)) return;

        _settings.AddVideoScanPath(path);

        _isScanning = true;
        StartRefreshSpin();
        UpdateToolbar();
        RefreshSidebar();

        var files = await Task.Run(() => ScanPath(path));
        _filesByPath[path] = files;

        _isScanning = false;
        StopRefreshSpin();
        RefreshAll();
        EnqueueMissingThumbnails(files);
    }

    private void RemovePath(string path)
    {
        _settings.RemoveVideoScanPath(path);
        _filesByPath.Remove(path);
        if (_selectedPath == path) _selectedPath = "";
        RefreshAll();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  缩略图生成队列（最多 2 个并发）
    // ═══════════════════════════════════════════════════════════════════════
    private void EnqueueMissingThumbnails(List<VideoFile> files)
    {
        lock (_thumbLock)
        {
            foreach (var f in files)
            {
                if (_thumbnails.TryGetValue(f.Path, out var cached))
                {
                    // 空字符串 = 已尝试生成但失败，不重试，避免反复对坏文件截图
                    if (string.IsNullOrEmpty(cached)) continue;
                    // 非空则确认缩略图文件当下确实存在，否则视为记录失效
                    if (File.Exists(AppDataDir.ToAbsolute(cached))) continue;
                    _thumbnails.Remove(f.Path);
                }
                if (_thumbQueue.Any(q => q.Path == f.Path)) continue; // 已在队列里
                _thumbQueue.Add(f);
            }
        }
        PumpThumbQueue();
    }

    private void PumpThumbQueue()
    {
        List<VideoFile> toStart = new();
        lock (_thumbLock)
        {
            while (_thumbRunning < ThumbMaxConcurrent && _thumbQueue.Count > 0)
            {
                toStart.Add(_thumbQueue[0]);
                _thumbQueue.RemoveAt(0);
                _thumbRunning++;
            }
        }
        foreach (var f in toStart)
            _ = RunThumbAsync(f);
    }

    private async Task RunThumbAsync(VideoFile f)
    {
        try
        {
            string result;
            try { result = await _thumbGen.GenerateAsync(f.Path); }
            catch { result = ""; }

            // 缩略图结果 / 缓存 / 卡片刷新都在 UI 线程执行，避免字典竞争
            try
            {
                await Dispatcher.InvokeAsync(() =>
                {
                    _thumbnails[f.Path] = result ?? "";
                    SaveThumbCache();
                    UpdateCardThumbnail(f.Path, result ?? "");
                });
            }
            catch { }
        }
        finally
        {
            lock (_thumbLock) { _thumbRunning--; }
            PumpThumbQueue();
        }
    }

    // ── 缩略图缓存持久化（存为 JSON 字符串，自包含、可靠） ─────────────────
    private void LoadThumbCache()
    {
        try
        {
            var json = _storage.Get(StorageService.KVideoThumbCache, "{}");
            _thumbnails = JsonSerializer.Deserialize<Dictionary<string, string>>(json ?? "{}")
                          ?? new Dictionary<string, string>();
        }
        catch
        {
            _thumbnails = new Dictionary<string, string>();
        }
    }

    private void SaveThumbCache()
    {
        try
        {
            var json = JsonSerializer.Serialize(_thumbnails);
            _storage.Set(StorageService.KVideoThumbCache, json);
        }
        catch { }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  播放 / 网络视频对话框
    // ═══════════════════════════════════════════════════════════════════════
    private void Play(VideoFile f)
    {
        var list = Filtered;
        var idx = list.IndexOf(f);
        OpenVideoPlayer(f, list, idx < 0 ? 0 : idx);
    }

    /// <summary>
    /// 打开视频播放页（本地视频）。
    /// </summary>
    private void OpenVideoPlayer(VideoFile f, List<VideoFile> playlist, int idx)
    {
        var title = UiHelper.WithoutExtension(f.Name);
        var args = new VideoResumeArgs
        {
            Url = f.Path,
            Title = title,
            IsLocal = true,
            Playlist = playlist,
            StartIndex = idx,
        };
        var page = new VideoPlayerPage(args);
        // 同窗口覆盖层（不弹独立窗口）
        if (App.Current.MainWindow is Views.MainWindow mw)
            mw.OpenPlayerPage(page);
    }

    private void OpenVideoPlayerUrl(string url)
    {
        var u = url?.Trim() ?? "";
        if (u.Length == 0) return;
        var args = new VideoResumeArgs
        {
            Url = u,
            Title = u,
            IsLocal = false,
        };
        var page = new VideoPlayerPage(args);
        if (App.Current.MainWindow is Views.MainWindow mw)
            mw.OpenPlayerPage(page);
    }

    private void ShowNetworkDialog()
    {
        var owner = Window.GetWindow(this);
        var dlg = new Window
        {
            Title = "播放网络视频",
            Width = 460, Height = 200,
            Owner = owner,
            WindowStartupLocation = owner != null
                ? WindowStartupLocation.CenterOwner
                : WindowStartupLocation.CenterScreen,
            ResizeMode = ResizeMode.NoResize,
            Background = UiHelper.BrushOf("M3.Surface"),
        };

        var panel = new StackPanel { Margin = new Thickness(24) };
        panel.Children.Add(new TextBlock
        {
            Text = "播放网络视频",
            FontSize = 16,
            FontWeight = FontWeights.SemiBold,
            Foreground = UiHelper.BrushOf("M3.OnSurface"),
            Margin = new Thickness(0, 0, 0, 14),
        });

        // 输入框：图标 + TextBox，圆角描边
        var inputBorder = new Border
        {
            Height = 36,
            Background = UiHelper.BrushOf("M3.Surface"),
            BorderBrush = UiHelper.BrushOf("M3.OutlineVariant"),
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(8, 0, 4, 0),
        };
        var inputGrid = new Grid();
        inputGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        inputGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var linkIcon = UiHelper.Icon(Mdl2.Link, 16, UiHelper.BrushOf("M3.OnSurfaceVariant"));
        linkIcon.VerticalAlignment = VerticalAlignment.Center;
        inputGrid.Children.Add(linkIcon);
        var urlBox = new TextBox
        {
            Margin = new Thickness(8, 0, 0, 0),
            VerticalContentAlignment = VerticalAlignment.Center,
            BorderThickness = new Thickness(0),
            Background = Brushes.Transparent,
            FontSize = 13,
        };
        Grid.SetColumn(urlBox, 1);
        inputGrid.Children.Add(urlBox);
        inputBorder.Child = inputGrid;
        panel.Children.Add(inputBorder);

        var btnRow = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
            Margin = new Thickness(0, 18, 0, 0),
        };
        var cancelBtn = new Button { Content = "取消", Padding = new Thickness(16, 6, 16, 6) };
        cancelBtn.Click += (_, _) => dlg.Close();
        btnRow.Children.Add(cancelBtn);
        var playBtn = new Button
        {
            Content = "播放",
            Style = (Style)Application.Current.Resources["FilledButton"],
            Margin = new Thickness(8, 0, 0, 0),
            Padding = new Thickness(16, 6, 16, 6),
        };
        playBtn.Click += (_, _) => { dlg.Close(); OpenVideoPlayerUrl(urlBox.Text); };
        btnRow.Children.Add(playBtn);
        panel.Children.Add(btnRow);

        dlg.Content = panel;

        // 回车直接播放
        urlBox.KeyDown += (_, e) =>
        {
            if (e.Key == Key.Enter)
            {
                dlg.Close();
                OpenVideoPlayerUrl(urlBox.Text);
            }
        };

        dlg.Loaded += (_, _) => urlBox.Focus();
        dlg.ShowDialog();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  辅助：刷新按钮旋转动画 / 圆形 FAB 模板
    // ═══════════════════════════════════════════════════════════════════════
    private void StartRefreshSpin()
    {
        _refreshRotate.BeginAnimation(RotateTransform.AngleProperty, _refreshAnim);
    }

    private void StopRefreshSpin()
    {
        _refreshRotate.BeginAnimation(RotateTransform.AngleProperty, null);
        _refreshRotate.Angle = 0;
    }

    private static ControlTemplate CircleButtonTemplate()
    {
        var xaml = @"
<ControlTemplate TargetType=""Button"" xmlns=""http://schemas.microsoft.com/winfx/2006/xaml/presentation"">
    <Border Background=""{TemplateBinding Background}"" CornerRadius=""28"">
        <ContentPresenter HorizontalAlignment=""Center"" VerticalAlignment=""Center""/>
    </Border>
    <ControlTemplate.Triggers>
        <Trigger Property=""IsMouseOver"" Value=""True"">
            <Setter Property=""Background"" Value=""{DynamicResource M3.PrimaryContainer}""/>
        </Trigger>
    </ControlTemplate.Triggers>
</ControlTemplate>";
        return (ControlTemplate)System.Windows.Markup.XamlReader.Parse(xaml);
    }

    /// <summary>当前已渲染的卡片视图引用，供缩略图生成完成后定向刷新。</summary>
    private sealed class CardViews
    {
        public Image Thumb = null!;
        public Border Gradient = null!;
        public StackPanel Placeholder = null!;
        public TextBlock PlaceholderIcon = null!;
        public TextBlock PlayIcon = null!;
    }
}
