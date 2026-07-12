using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
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
/// 音乐库页（对应 Flutter 端 music_tab_page.dart）。
///
/// 结构：左侧栏（标题 + 添加路径 / 全部音乐 / 路径列表）+ 中间分隔线 +
/// 右侧内容区（工具栏：面包屑 / 计数 / 搜索框 / 刷新 / 分类 / 排序；分隔线；
/// 歌曲列表或分组列表或空状态）。
///
/// 控制器逻辑（对应 Flutter MusicLibraryController）：
/// _filesByPath 保存每个扫描路径下的音乐列表；分类/排序持久化到 StorageService；
/// 封面采用内存态分批增量加载（200 首/批），不落盘。
/// </summary>
public partial class MusicTabPage : UserControl
{
    // ── 枚举（与 Flutter MusicCategory / SongSortMode / GroupSortMode 一一对应） ──
    private enum MusicCategory { Song, Album, Artist }
    private enum SongSortMode { TitleAsc, TitleDesc, ArtistAsc, ArtistDesc, TimeAsc, TimeDesc }
    private enum GroupSortMode { NameAsc, NameDesc, TimeAsc, TimeDesc }

    // ── 支持的音频扩展名（与 Flutter musicExtensions 一致） ──────────────────
    private static readonly HashSet<string> MusicExtensions = new(StringComparer.OrdinalIgnoreCase)
    {
        ".mp3", ".flac", ".aac", ".wav", ".ogg",
        ".m4a", ".wma", ".opus", ".ape",
    };

    // ── 服务 ─────────────────────────────────────────────────────────────
    private readonly AppSettingsService _settings;
    private readonly StorageService _storage;
    private readonly GlobalPlayerService _player;
    private readonly AudioCoverMemoryCache<RichMusicFile> _coverCache;

    // ── 控制器状态（对应 Flutter MusicLibraryController 的字段） ───────────
    private readonly Dictionary<string, List<RichMusicFile>> _filesByPath = new();
    private bool _isScanning;
    private int _scanProgress;
    private int _scanTotal;
    private string _search = "";
    private string _selectedPath = "";      // '' = 全部音乐
    private MusicCategory _category = MusicCategory.Song;
    private SongSortMode _songSort = SongSortMode.TitleAsc;
    private GroupSortMode _groupSort = GroupSortMode.NameAsc;
    private string? _drillGroup;             // null = 未进入分组详情

    // ── UI 元素引用 ──────────────────────────────────────────────────────
    private StackPanel _sidebarList = null!;
    private TextBlock _breadcrumbText = null!;
    private TextBlock _statusText = null!;
    private TextBox _searchBox = null!;
    private Grid _contentHost = null!;
    private TextBlock _refreshIcon = null!;
    private RotateTransform _refreshRotate = null!;
    private DoubleAnimation _refreshAnim = null!;
    private ComboBox _categoryCombo = null!;
    private ComboBox _sortCombo = null!;

    public MusicTabPage()
    {
        InitializeComponent();
        _settings = App.Current.Settings;
        _storage = App.Current.Storage;
        _player = App.Current.Player;

        _coverCache = new AudioCoverMemoryCache<RichMusicFile>(
            batchSize: 200,
            pathOf: f => f.Path,
            hasCover: f => f.HasCover,
            applyCover: (f, meta) => f.CoverBytes = meta.CoverBytes);

        BuildShell();
        LoadCategoryAndSort();
        RefreshSidebar();
        UpdateToolbar();
        RefreshContent();
        Loaded += OnLoaded;
    }

    // ── 启动：从缓存恢复 + 扫描新路径 ──────────────────────────────────────
    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        Loaded -= OnLoaded;
        await LoadFromCacheThenScanNewPaths();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  外层骨架构建（左侧栏 | 分隔线 | 右侧内容区）
    // ═══════════════════════════════════════════════════════════════════════
    private void BuildShell()
    {
        RootGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(200, GridUnitType.Pixel) });
        RootGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Pixel) });
        RootGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        // ── 左侧栏 ──────────────────────────────────────────────────────
        var sidebar = new Grid { Background = UiHelper.BrushOf("M3.Surface") };
        sidebar.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });   // 标题行
        sidebar.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) }); // 列表
        Grid.SetColumn(sidebar, 0);
        RootGrid.Children.Add(sidebar);

        // 标题行："音乐库" + 添加路径按钮
        var header = new Grid { Margin = new Thickness(14, 14, 8, 6) };
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        header.Children.Add(new TextBlock
        {
            Text = "音乐库",
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
        });
        var addBtn = new Button
        {
            Content = new TextBlock
            {
                Text = Mdl2.NewFolder,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 16,
                Foreground = UiHelper.BrushOf("M3.Primary"),
            },
            Width = 28, Height = 28,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            ToolTip = "添加本地路径",
            Template = UiHelper.TransparentButtonTemplate(),
        };
        addBtn.Click += (_, _) => { _ = AddPath(); };
        Grid.SetColumn(addBtn, 1);
        header.Children.Add(addBtn);
        Grid.SetRow(header, 0);
        sidebar.Children.Add(header);

        // 侧栏列表（全部音乐 + 分隔线 + 路径项）
        _sidebarList = new StackPanel();
        var sidebarScroll = new ScrollViewer
        {
            Content = _sidebarList,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        };
        Grid.SetRow(sidebarScroll, 1);
        sidebar.Children.Add(sidebarScroll);

        // ── 分隔线 ──────────────────────────────────────────────────────
        var divider = new Border { Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 60) };
        Grid.SetColumn(divider, 1);
        RootGrid.Children.Add(divider);

        // ── 右侧内容区 ──────────────────────────────────────────────────
        var content = new Grid();
        content.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });  // 工具栏
        content.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Pixel) }); // 分隔线
        content.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });  // 内容
        Grid.SetColumn(content, 2);
        RootGrid.Children.Add(content);

        BuildToolbar(content);
        var toolbarDivider = new Border { Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 60) };
        Grid.SetRow(toolbarDivider, 1);
        content.Children.Add(toolbarDivider);

        _contentHost = new Grid();
        Grid.SetRow(_contentHost, 2);
        content.Children.Add(_contentHost);
    }

    private void BuildToolbar(Grid parent)
    {
        var toolbar = new Grid { Margin = new Thickness(16, 10, 8, 6) };
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 面包屑
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 计数
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); // 弹性
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 搜索框
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 刷新
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 分类
        toolbar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 排序
        Grid.SetRow(toolbar, 0);
        parent.Children.Add(toolbar);

        _breadcrumbText = new TextBlock
        {
            FontSize = 14,
            FontWeight = FontWeights.SemiBold,
            Foreground = UiHelper.BrushOf("M3.OnSurface"),
            VerticalAlignment = VerticalAlignment.Center,
        };
        toolbar.Children.Add(_breadcrumbText);

        _statusText = new TextBlock
        {
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(8, 0, 0, 0),
        };
        Grid.SetColumn(_statusText, 1);
        toolbar.Children.Add(_statusText);

        // 搜索框
        _searchBox = new TextBox
        {
            Width = 200,
            Height = 30,
            VerticalContentAlignment = VerticalAlignment.Center,
            Margin = new Thickness(0, 0, 4, 0),
            ToolTip = "搜索音乐",
        };
        _searchBox.TextChanged += (_, _) =>
        {
            _search = _searchBox.Text;
            ResetCoverBatchesAndReload();
            RefreshContent();
            UpdateToolbar();
        };
        Grid.SetColumn(_searchBox, 3);
        toolbar.Children.Add(_searchBox);

        // 刷新按钮
        _refreshRotate = new RotateTransform { Angle = 0 };
        _refreshAnim = new DoubleAnimation(0, 360, TimeSpan.FromSeconds(1)) { RepeatBehavior = RepeatBehavior.Forever };
        _refreshIcon = new TextBlock
        {
            Text = Mdl2.Refresh,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 16,
            Foreground = UiHelper.BrushOf("M3.OnSurface"),
            RenderTransform = _refreshRotate,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        var refreshBtn = new Button
        {
            Content = _refreshIcon,
            Width = 32, Height = 32,
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Cursor = Cursors.Hand,
            ToolTip = "重新扫描",
            Template = UiHelper.TransparentButtonTemplate(),
        };
        refreshBtn.Click += (_, _) => { _ = Refresh(); };
        Grid.SetColumn(refreshBtn, 4);
        toolbar.Children.Add(refreshBtn);

        // 分类下拉
        _categoryCombo = new ComboBox
        {
            Width = 110,
            Height = 30,
            Margin = new Thickness(4, 0, 4, 0),
            ToolTip = "分类",
        };
        _categoryCombo.Items.Add(new ComboBoxItem { Content = "按歌曲", Tag = MusicCategory.Song });
        _categoryCombo.Items.Add(new ComboBoxItem { Content = "按专辑", Tag = MusicCategory.Album });
        _categoryCombo.Items.Add(new ComboBoxItem { Content = "按歌手", Tag = MusicCategory.Artist });
        _categoryCombo.SelectedIndex = (int)_category;
        _categoryCombo.SelectionChanged += (_, _) =>
        {
            if (_categoryCombo.SelectedIndex < 0) return;
            _category = (MusicCategory)((ComboBoxItem)_categoryCombo.SelectedItem).Tag;
            _drillGroup = null;
            _storage.Set(StorageService.KMusicCategory, _category.ToString());
            ResetCoverBatchesAndReload();
            RefreshContent();
            UpdateToolbar();
            RebuildSortCombo();
        };
        Grid.SetColumn(_categoryCombo, 5);
        toolbar.Children.Add(_categoryCombo);

        // 排序下拉
        _sortCombo = new ComboBox
        {
            Width = 140,
            Height = 30,
            Margin = new Thickness(4, 0, 0, 0),
            ToolTip = "排序",
        };
        RebuildSortCombo();
        Grid.SetColumn(_sortCombo, 6);
        toolbar.Children.Add(_sortCombo);
    }

    /// <summary>根据当前分类重建排序下拉项（歌曲视图用 SongSortMode，分组根视图用 GroupSortMode）。</summary>
    private void RebuildSortCombo()
    {
        _sortCombo.SelectionChanged -= OnSortChanged;
        _sortCombo.Items.Clear();
        if (_category == MusicCategory.Song || _drillGroup != null)
        {
            _sortCombo.Items.Add(new ComboBoxItem { Content = "按歌曲名升序", Tag = SongSortMode.TitleAsc });
            _sortCombo.Items.Add(new ComboBoxItem { Content = "按歌曲名降序", Tag = SongSortMode.TitleDesc });
            _sortCombo.Items.Add(new ComboBoxItem { Content = "按艺术家升序", Tag = SongSortMode.ArtistAsc });
            _sortCombo.Items.Add(new ComboBoxItem { Content = "按艺术家降序", Tag = SongSortMode.ArtistDesc });
            _sortCombo.Items.Add(new ComboBoxItem { Content = "按修改时间升序", Tag = SongSortMode.TimeAsc });
            _sortCombo.Items.Add(new ComboBoxItem { Content = "按修改时间降序", Tag = SongSortMode.TimeDesc });
            _sortCombo.SelectedIndex = (int)_songSort;
        }
        else
        {
            _sortCombo.Items.Add(new ComboBoxItem { Content = "按名称升序", Tag = GroupSortMode.NameAsc });
            _sortCombo.Items.Add(new ComboBoxItem { Content = "按名称降序", Tag = GroupSortMode.NameDesc });
            _sortCombo.Items.Add(new ComboBoxItem { Content = "按修改时间升序", Tag = GroupSortMode.TimeAsc });
            _sortCombo.Items.Add(new ComboBoxItem { Content = "按修改时间降序", Tag = GroupSortMode.TimeDesc });
            _sortCombo.SelectedIndex = (int)_groupSort;
        }
        _sortCombo.SelectionChanged += OnSortChanged;
    }

    private void OnSortChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_sortCombo.SelectedIndex < 0) return;
        var tag = ((ComboBoxItem)_sortCombo.SelectedItem).Tag;
        if (tag is SongSortMode ssm)
        {
            _songSort = ssm;
            _storage.Set(StorageService.KMusicSongSort, _songSort.ToString());
        }
        else if (tag is GroupSortMode gsm)
        {
            _groupSort = gsm;
            _storage.Set(StorageService.KMusicGroupSort, _groupSort.ToString());
        }
        ResetCoverBatchesAndReload();
        RefreshContent();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  侧栏
    // ═══════════════════════════════════════════════════════════════════════
    private void RefreshSidebar()
    {
        _sidebarList.Children.Clear();

        // "全部音乐" 项
        var allItem = MakeSidebarItem(Mdl2.LibraryMusic, "全部音乐", AllFilesCount,
            _selectedPath.Length == 0, null);
        allItem.MouseLeftButtonDown += (_, _) => SelectAllMusic();
        _sidebarList.Children.Add(allItem);

        // 分隔线
        _sidebarList.Children.Add(new Border
        {
            Height = 1,
            Margin = new Thickness(14, 6, 14, 6),
            Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OutlineVariant"), 60),
        });

        // 路径列表
        if (_settings.MusicScanPaths.Count == 0)
        {
            _sidebarList.Children.Add(new TextBlock
            {
                Text = "点击右上角 + 添加路径",
                FontSize = 12,
                Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
                Margin = new Thickness(14, 8, 14, 8),
                TextWrapping = TextWrapping.Wrap,
                TextAlignment = TextAlignment.Center,
            });
            return;
        }

        foreach (var path in _settings.MusicScanPaths)
        {
            var count = _filesByPath.TryGetValue(path, out var list) ? list.Count : 0;
            var item = MakeSidebarItem(Mdl2.FolderOutlined, UiHelper.LastPathSegment(path), count,
                _selectedPath == path, path);
            item.ToolTip = path;
            var captured = path;
            item.MouseLeftButtonDown += (_, _) => SelectPath(captured);
            // 鼠标悬停时显示删除按钮（简化：用右键菜单）
            item.ContextMenu = new ContextMenu();
            var removeItem = new MenuItem { Header = "移除路径" };
            removeItem.Click += (_, _) => { _ = RemovePath(captured); };
            item.ContextMenu.Items.Add(removeItem);
            _sidebarList.Children.Add(item);
        }
    }

    private Border MakeSidebarItem(string glyph, string label, int count, bool selected, string? tooltip)
    {
        var bg = selected
            ? UiHelper.WithAlpha(UiHelper.BrushOf("M3.PrimaryContainer"), 160)
            : Brushes.Transparent;
        var fg = selected ? UiHelper.BrushOf("M3.Primary") : UiHelper.BrushOf("M3.OnSurfaceVariant");

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 图标
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); // 标签
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 计数

        var icon = new TextBlock
        {
            Text = glyph,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 14,
            Foreground = fg,
            VerticalAlignment = VerticalAlignment.Center,
        };
        grid.Children.Add(icon);

        var name = new TextBlock
        {
            Text = label,
            FontSize = 12,
            FontWeight = selected ? FontWeights.SemiBold : FontWeights.Normal,
            Foreground = selected ? UiHelper.BrushOf("M3.OnPrimaryContainer") : UiHelper.BrushOf("M3.OnSurface"),
            Margin = new Thickness(8, 0, 4, 0),
            TextTrimming = TextTrimming.CharacterEllipsis,
            VerticalAlignment = VerticalAlignment.Center,
        };
        Grid.SetColumn(name, 1);
        grid.Children.Add(name);

        var cnt = new TextBlock
        {
            Text = count.ToString(),
            FontSize = 11,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
        };
        Grid.SetColumn(cnt, 2);
        grid.Children.Add(cnt);

        return new Border
        {
            Child = grid,
            Background = bg,
            CornerRadius = new CornerRadius(7),
            Margin = new Thickness(8, 2, 8, 2),
            Padding = new Thickness(10, 8, 10, 8),
            Cursor = Cursors.Hand,
        };
    }

    private void SelectAllMusic()
    {
        _selectedPath = "";
        _drillGroup = null;
        ResetCoverBatchesAndReload();
        RefreshSidebar();
        RefreshContent();
        UpdateToolbar();
    }

    private void SelectPath(string path)
    {
        _selectedPath = path;
        _drillGroup = null;
        ResetCoverBatchesAndReload();
        RefreshSidebar();
        RefreshContent();
        UpdateToolbar();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  工具栏状态更新
    // ═══════════════════════════════════════════════════════════════════════
    private static T? FindAncestor<T>(DependencyObject d) where T : DependencyObject
    {
        while (d != null && d is not T)
            d = VisualTreeHelper.GetParent(d);
        return d as T;
    }

    private void UpdateToolbar()
    {
        // 面包屑
        var pathLabel = _selectedPath.Length == 0 ? "全部音乐" : UiHelper.LastPathSegment(_selectedPath);
        if (_drillGroup != null)
            _breadcrumbText.Text = pathLabel + " › " + _drillGroup;
        else
            _breadcrumbText.Text = pathLabel;

        // 计数 / 扫描状态
        if (_isScanning)
        {
            _statusText.Text = $"扫描中 {_scanProgress}/{_scanTotal}";
            _statusText.Foreground = UiHelper.BrushOf("M3.Primary");
            _refreshIcon.BeginAnimation(TextBlock.RenderTransformProperty, null);
            _refreshIcon.RenderTransform = _refreshRotate;
            _refreshRotate.BeginAnimation(RotateTransform.AngleProperty, _refreshAnim);
        }
        else
        {
            _refreshRotate.BeginAnimation(RotateTransform.AngleProperty, null);
            _statusText.Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant");
            int count;
            string unit;
            if (_drillGroup != null)
            {
                count = DrillSongs.Count;
                unit = "首";
            }
            else if (_category == MusicCategory.Song)
            {
                count = SortedSongs.Count;
                unit = "首";
            }
            else if (_category == MusicCategory.Album)
            {
                count = SortedGroups.Count;
                unit = "张专辑";
            }
            else
            {
                count = SortedGroups.Count;
                unit = "位歌手";
            }
            _statusText.Text = $"{count} {unit}";
        }

        // 搜索框仅在歌曲视图或进入分组详情时可见
        _searchBox.Visibility = (_category == MusicCategory.Song || _drillGroup != null)
            ? Visibility.Visible : Visibility.Collapsed;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  内容区刷新
    // ═══════════════════════════════════════════════════════════════════════
    private void RefreshContent()
    {
        _contentHost.Children.Clear();

        if (_settings.MusicScanPaths.Count == 0)
        {
            _contentHost.Children.Add(BuildEmptyState());
            return;
        }

        if (_isScanning && AllFilesCount == 0)
        {
            _contentHost.Children.Add(BuildScanningState());
            return;
        }

        if (_category == MusicCategory.Song)
        {
            var songs = SortedSongs;
            if (songs.Count == 0)
            {
                _contentHost.Children.Add(BuildNotFoundState());
                return;
            }
            _contentHost.Children.Add(BuildSongList(songs));
            return;
        }

        // 分组视图
        if (_drillGroup == null)
        {
            var groups = SortedGroups;
            if (groups.Count == 0)
            {
                _contentHost.Children.Add(BuildNotFoundState());
                return;
            }
            _contentHost.Children.Add(BuildGroupList(groups));
        }
        else
        {
            var songs = DrillSongs;
            if (songs.Count == 0)
            {
                _contentHost.Children.Add(BuildNotFoundState());
                return;
            }
            _contentHost.Children.Add(BuildSongList(songs));
        }
    }

    // ── 空状态 / 扫描中 / 未找到 ───────────────────────────────────────────
    private FrameworkElement BuildEmptyState()
    {
        var sp = new StackPanel
        {
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        sp.Children.Add(new TextBlock
        {
            Text = Mdl2.LibraryMusic,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 64,
            Foreground = UiHelper.WithAlpha(UiHelper.BrushOf("M3.OnSurfaceVariant"), 100),
            HorizontalAlignment = HorizontalAlignment.Center,
        });
        sp.Children.Add(new TextBlock
        {
            Text = "没有音乐库路径",
            FontSize = 16,
            FontWeight = FontWeights.SemiBold,
            Margin = new Thickness(0, 16, 0, 0),
            HorizontalAlignment = HorizontalAlignment.Center,
        });
        sp.Children.Add(new TextBlock
        {
            Text = "在左侧点击 + 添加本地文件夹",
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            Margin = new Thickness(0, 8, 0, 0),
            HorizontalAlignment = HorizontalAlignment.Center,
        });
        var btn = new Button
        {
            Content = "添加音乐库路径",
            Margin = new Thickness(0, 24, 0, 0),
            Padding = new Thickness(16, 8, 16, 8),
            Cursor = Cursors.Hand,
        };
        btn.Click += (_, _) => { _ = AddPath(); };
        sp.Children.Add(btn);
        return sp;
    }

    private FrameworkElement BuildScanningState()
    {
        var sp = new StackPanel
        {
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        var spinner = new TextBlock
        {
            Text = Mdl2.Refresh,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 28,
            Foreground = UiHelper.BrushOf("M3.Primary"),
            HorizontalAlignment = HorizontalAlignment.Center,
            RenderTransform = new RotateTransform(),
        };
        var anim = new DoubleAnimation(0, 360, TimeSpan.FromSeconds(1)) { RepeatBehavior = RepeatBehavior.Forever };
        ((RotateTransform)spinner.RenderTransform).BeginAnimation(RotateTransform.AngleProperty, anim);
        sp.Children.Add(spinner);
        sp.Children.Add(new TextBlock
        {
            Text = $"正在读取音频标签 {_scanProgress}/{_scanTotal}",
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            Margin = new Thickness(0, 12, 0, 0),
            HorizontalAlignment = HorizontalAlignment.Center,
        });
        return sp;
    }

    private FrameworkElement BuildNotFoundState()
    {
        return new TextBlock
        {
            Text = "未找到音乐文件",
            FontSize = 14,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
    }

    // ── 歌曲列表 ──────────────────────────────────────────────────────────
    private FrameworkElement BuildSongList(List<RichMusicFile> songs)
    {
        // 兜底：确保前 200 首封面已加载
        _ = EnsureCoversForCurrentView();

        // 用 ListBox + VirtualizingStackPanel 实现列表虚拟化，避免大列表卡死。
        // 直接用 StackPanel 装几千个 tile 会让 WPF 一次性创建所有可视元素。
        var listBox = new ListBox
        {
            Background = Brushes.Transparent,
            BorderThickness = new Thickness(0),
            Padding = new Thickness(0),
        };
        // 附加属性不能写在对象初始化器里，必须用静态 Set 方法
        ScrollViewer.SetHorizontalScrollBarVisibility(listBox, ScrollBarVisibility.Disabled);
        ScrollViewer.SetVerticalScrollBarVisibility(listBox, ScrollBarVisibility.Auto);
        VirtualizingPanel.SetIsVirtualizing(listBox, true);
        VirtualizingPanel.SetVirtualizationMode(listBox, VirtualizationMode.Recycling);
        // 隐藏 ListBoxItem 的默认选中样式
        listBox.ItemContainerStyle = new Style(typeof(ListBoxItem))
        {
            Setters =
            {
                new Setter(ListBoxItem.PaddingProperty, new Thickness(0)),
                new Setter(ListBoxItem.MarginProperty, new Thickness(0)),
                new Setter(ListBoxItem.BackgroundProperty, Brushes.Transparent),
                new Setter(ListBoxItem.BorderThicknessProperty, new Thickness(0)),
                new Setter(ListBoxItem.HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch),
            },
        };

        var capturedSongs = songs;
        // AlternationCount 用于序号显示（1-based 在 Loaded 事件里 +1）
        listBox.AlternationCount = songs.Count;
        listBox.ItemsSource = songs;
        listBox.ItemTemplate = CreateTrackTileTemplate(capturedSongs);

        return listBox;
    }

    /// <summary>
    /// 用 DataTemplate 构建歌曲行模板（虚拟化时每个可见行才会实例化）。
    /// 用 DockPanel 而非 Grid 布局，因为 FrameworkElementFactory 设置
    /// Grid.ColumnDefinitions 需要 ColumnDefinitionCollection（无公共构造函数），
    /// DockPanel 用附加属性 DockPanel.Dock 即可，无需处理列集合。
    /// </summary>
    private DataTemplate CreateTrackTileTemplate(List<RichMusicFile> songs)
    {
        var tileFactory = new FrameworkElementFactory(typeof(Border));
        tileFactory.SetValue(Border.PaddingProperty, new Thickness(8, 4, 8, 4));
        tileFactory.SetValue(Border.BackgroundProperty, Brushes.Transparent);
        tileFactory.AddHandler(MouseLeftButtonDownEvent, new MouseButtonEventHandler((s, e) =>
        {
            if (s is FrameworkElement fe && fe.DataContext is RichMusicFile f)
            {
                var idx = songs.IndexOf(f);
                if (idx >= 0) Play(songs, idx);
            }
        }));

        var dockFactory = new FrameworkElementFactory(typeof(DockPanel));
        dockFactory.SetValue(DockPanel.LastChildFillProperty, true);

        // 序号（Dock=Left，宽 36）
        var idxFactory = new FrameworkElementFactory(typeof(TextBlock));
        idxFactory.SetValue(DockPanel.DockProperty, Dock.Left);
        idxFactory.SetValue(TextBlock.WidthProperty, 36.0);
        idxFactory.SetValue(TextBlock.FontSizeProperty, 12.0);
        idxFactory.SetValue(TextBlock.ForegroundProperty, UiHelper.BrushOf("M3.OnSurfaceVariant"));
        idxFactory.SetValue(TextBlock.HorizontalAlignmentProperty, HorizontalAlignment.Center);
        idxFactory.SetValue(TextBlock.VerticalAlignmentProperty, VerticalAlignment.Center);
        // 序号在行加载时通过 DataContext 反查 songs 索引回填
        idxFactory.AddHandler(LoadedEvent, new RoutedEventHandler((s, e) =>
        {
            if (s is TextBlock tb)
            {
                var item = FindAncestor<ListBoxItem>(tb);
                if (item?.DataContext is RichMusicFile f)
                {
                    var i = songs.IndexOf(f);
                    tb.Text = (i + 1).ToString();
                }
            }
        }));
        dockFactory.AppendChild(idxFactory);

        // 封面（Dock=Left，36x36）
        var coverFactory = new FrameworkElementFactory(typeof(Border));
        coverFactory.SetValue(DockPanel.DockProperty, Dock.Left);
        coverFactory.SetValue(Border.WidthProperty, 36.0);
        coverFactory.SetValue(Border.HeightProperty, 36.0);
        coverFactory.SetValue(Border.CornerRadiusProperty, new CornerRadius(6));
        coverFactory.SetValue(Border.MarginProperty, new Thickness(4, 0, 0, 0));
        coverFactory.SetValue(Border.BackgroundProperty, UiHelper.WithAlpha(UiHelper.BrushOf("M3.TertiaryContainer"), 80));
        coverFactory.SetValue(Border.ClipToBoundsProperty, true);
        var coverImgFactory = new FrameworkElementFactory(typeof(Image));
        coverImgFactory.SetValue(Image.StretchProperty, Stretch.UniformToFill);
        coverImgFactory.SetBinding(Image.SourceProperty, new Binding(nameof(RichMusicFile.CoverImage)));
        coverFactory.AppendChild(coverImgFactory);
        dockFactory.AppendChild(coverFactory);

        // 格式标签（Dock=Right）
        var tagFactory = new FrameworkElementFactory(typeof(Border));
        tagFactory.SetValue(DockPanel.DockProperty, Dock.Right);
        tagFactory.SetValue(Border.CornerRadiusProperty, new CornerRadius(4));
        tagFactory.SetValue(Border.PaddingProperty, new Thickness(6, 2, 6, 2));
        tagFactory.SetValue(Border.MarginProperty, new Thickness(8, 0, 0, 0));
        tagFactory.SetValue(Border.VerticalAlignmentProperty, VerticalAlignment.Center);
        tagFactory.SetValue(Border.BackgroundProperty, UiHelper.WithAlpha(UiHelper.BrushOf("M3.SurfaceVariant"), 120));
        var tagTextFactory = new FrameworkElementFactory(typeof(TextBlock));
        tagTextFactory.SetValue(TextBlock.FontSizeProperty, 10.0);
        tagTextFactory.SetValue(TextBlock.ForegroundProperty, UiHelper.BrushOf("M3.OnSurfaceVariant"));
        tagTextFactory.SetBinding(TextBlock.TextProperty, new Binding(nameof(RichMusicFile.ExtUpper)));
        tagFactory.AppendChild(tagTextFactory);
        dockFactory.AppendChild(tagFactory);

        // 标题+副标题（最后一个子元素，自动填充剩余空间）
        var infoFactory = new FrameworkElementFactory(typeof(StackPanel));
        infoFactory.SetValue(StackPanel.VerticalAlignmentProperty, VerticalAlignment.Center);
        infoFactory.SetValue(StackPanel.MarginProperty, new Thickness(12, 0, 0, 0));
        var titleFactory = new FrameworkElementFactory(typeof(TextBlock));
        titleFactory.SetValue(TextBlock.FontSizeProperty, 13.0);
        titleFactory.SetValue(TextBlock.FontWeightProperty, FontWeights.SemiBold);
        titleFactory.SetValue(TextBlock.ForegroundProperty, UiHelper.BrushOf("M3.OnSurface"));
        titleFactory.SetValue(TextBlock.TextTrimmingProperty, TextTrimming.CharacterEllipsis);
        titleFactory.SetBinding(TextBlock.TextProperty, new Binding(nameof(RichMusicFile.Title)) { TargetNullValue = "未知曲目" });
        infoFactory.AppendChild(titleFactory);
        var subFactory = new FrameworkElementFactory(typeof(TextBlock));
        subFactory.SetValue(TextBlock.FontSizeProperty, 11.0);
        subFactory.SetValue(TextBlock.ForegroundProperty, UiHelper.BrushOf("M3.OnSurfaceVariant"));
        subFactory.SetValue(TextBlock.TextTrimmingProperty, TextTrimming.CharacterEllipsis);
        subFactory.SetBinding(TextBlock.TextProperty, new Binding(nameof(RichMusicFile.Subtitle)) { TargetNullValue = "" });
        infoFactory.AppendChild(subFactory);
        dockFactory.AppendChild(infoFactory);

        tileFactory.AppendChild(dockFactory);

        return new DataTemplate { VisualTree = tileFactory };
    }

    private Border MakeTrackTile(int index, RichMusicFile f)
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(36) });  // 序号
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });     // 封面
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); // 标题+副标题
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });     // 格式标签

        var idx = new TextBlock
        {
            Text = index.ToString(),
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        grid.Children.Add(idx);

        var cover = new Border
        {
            Width = 36, Height = 36,
            CornerRadius = new CornerRadius(6),
            Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.TertiaryContainer"), 80),
            Margin = new Thickness(8, 0, 0, 0),
            ClipToBounds = true,
        };
        if (f.HasCover)
        {
            try
            {
                var bmp = new BitmapImage();
                bmp.BeginInit();
                bmp.StreamSource = new MemoryStream(f.CoverBytes!);
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
                Text = Mdl2.MusicNote,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 16,
                Foreground = UiHelper.BrushOf("M3.Tertiary"),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            };
        }
        Grid.SetColumn(cover, 1);
        grid.Children.Add(cover);

        var info = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 0, 0, 0) };
        info.Children.Add(new TextBlock
        {
            Text = f.Title,
            FontSize = 13,
            TextTrimming = TextTrimming.CharacterEllipsis,
        });
        var sub = new List<string>();
        if (!string.IsNullOrEmpty(f.Artist)) sub.Add(f.Artist);
        if (!string.IsNullOrEmpty(f.Album)) sub.Add(f.Album);
        if (sub.Count > 0)
        {
            info.Children.Add(new TextBlock
            {
                Text = string.Join(" · ", sub),
                FontSize = 11,
                Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
                TextTrimming = TextTrimming.CharacterEllipsis,
            });
        }
        Grid.SetColumn(info, 2);
        grid.Children.Add(info);

        var fmt = new Border
        {
            Background = UiHelper.BrushOf("M3.SurfaceContainerHighest"),
            CornerRadius = new CornerRadius(4),
            Padding = new Thickness(8, 2, 8, 2),
            Margin = new Thickness(8, 0, 0, 0),
            Child = new TextBlock
            {
                Text = UiHelper.ExtUpper(f.Name),
                FontSize = 11,
                Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            },
        };
        Grid.SetColumn(fmt, 3);
        grid.Children.Add(fmt);

        return new Border
        {
            Child = grid,
            Padding = new Thickness(16, 8, 16, 8),
            Cursor = Cursors.Hand,
        };
    }

    // ── 分组列表 ──────────────────────────────────────────────────────────
    private FrameworkElement BuildGroupList(List<KeyValuePair<string, List<RichMusicFile>>> groups)
    {
        var list = new StackPanel();
        var scroll = new ScrollViewer
        {
            Content = list,
            VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        };
        bool isAlbum = _category == MusicCategory.Album;
        foreach (var g in groups)
        {
            var tile = MakeGroupTile(g.Key, g.Value.Count, isAlbum);
            var captured = g.Key;
            tile.MouseLeftButtonDown += (_, _) => EnterGroup(captured);
            list.Children.Add(tile);
        }
        return scroll;
    }

    private Border MakeGroupTile(string name, int count, bool isAlbum)
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 图标
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); // 名称
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 计数
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });  // 箭头

        var icon = new Border
        {
            Width = 40, Height = 40,
            CornerRadius = new CornerRadius(8),
            Background = UiHelper.WithAlpha(UiHelper.BrushOf("M3.SecondaryContainer"), 100),
            Child = new TextBlock
            {
                Text = isAlbum ? Mdl2.Album : Mdl2.Person,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 18,
                Foreground = UiHelper.BrushOf("M3.Secondary"),
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            },
        };
        grid.Children.Add(icon);

        var nm = new TextBlock
        {
            Text = name,
            FontSize = 13,
            TextTrimming = TextTrimming.CharacterEllipsis,
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(12, 0, 0, 0),
        };
        Grid.SetColumn(nm, 1);
        grid.Children.Add(nm);

        var cnt = new TextBlock
        {
            Text = $"{count} 首",
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(4, 0, 4, 0),
        };
        Grid.SetColumn(cnt, 2);
        grid.Children.Add(cnt);

        var arrow = new TextBlock
        {
            Text = Mdl2.ChevronRight,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 14,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
        };
        Grid.SetColumn(arrow, 3);
        grid.Children.Add(arrow);

        return new Border
        {
            Child = grid,
            Padding = new Thickness(16, 10, 16, 10),
            Cursor = Cursors.Hand,
        };
    }

    private void EnterGroup(string name)
    {
        _drillGroup = name;
        ResetCoverBatchesAndReload();
        RebuildSortCombo();
        RefreshContent();
        UpdateToolbar();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  计算属性（对应 Flutter MusicLibraryController 的 getter）
    // ═══════════════════════════════════════════════════════════════════════
    private List<RichMusicFile> AllFiles
    {
        get
        {
            var result = new List<RichMusicFile>();
            foreach (var l in _filesByPath.Values) result.AddRange(l);
            return result;
        }
    }

    private int AllFilesCount
    {
        get
        {
            int n = 0;
            foreach (var l in _filesByPath.Values) n += l.Count;
            return n;
        }
    }

    private List<RichMusicFile> SourceFiles
    {
        get
        {
            if (_selectedPath.Length == 0) return AllFiles;
            return _filesByPath.TryGetValue(_selectedPath, out var l) ? l : new List<RichMusicFile>();
        }
    }

    private List<RichMusicFile> SearchFiltered
    {
        get
        {
            var list = SourceFiles;
            var q = _search.ToLowerInvariant().Trim();
            if (q.Length == 0) return list;
            return list.Where(f =>
                f.Title.ToLowerInvariant().Contains(q) ||
                f.Artist.ToLowerInvariant().Contains(q) ||
                f.Album.ToLowerInvariant().Contains(q)).ToList();
        }
    }

    private List<RichMusicFile> SortedSongs
    {
        get
        {
            var list = new List<RichMusicFile>(SearchFiltered);
            ApplySongSort(list);
            return list;
        }
    }

    private void ApplySongSort(List<RichMusicFile> list)
    {
        switch (_songSort)
        {
            case SongSortMode.TitleAsc:
                list.Sort((a, b) => string.Compare(a.Title.ToLowerInvariant(), b.Title.ToLowerInvariant(), StringComparison.Ordinal));
                break;
            case SongSortMode.TitleDesc:
                list.Sort((a, b) => string.Compare(b.Title.ToLowerInvariant(), a.Title.ToLowerInvariant(), StringComparison.Ordinal));
                break;
            case SongSortMode.ArtistAsc:
                list.Sort((a, b) => string.Compare(a.Artist.ToLowerInvariant(), b.Artist.ToLowerInvariant(), StringComparison.Ordinal));
                break;
            case SongSortMode.ArtistDesc:
                list.Sort((a, b) => string.Compare(b.Artist.ToLowerInvariant(), a.Artist.ToLowerInvariant(), StringComparison.Ordinal));
                break;
            case SongSortMode.TimeAsc:
                list.Sort((a, b) => a.ModifiedMs.CompareTo(b.ModifiedMs));
                break;
            case SongSortMode.TimeDesc:
                list.Sort((a, b) => b.ModifiedMs.CompareTo(a.ModifiedMs));
                break;
        }
    }

    private Dictionary<string, List<RichMusicFile>> Groups
    {
        get
        {
            var map = new Dictionary<string, List<RichMusicFile>>();
            foreach (var f in SourceFiles)
            {
                List<string> keys;
                if (_category == MusicCategory.Artist)
                    keys = ArtistSplitter.Split(f.Artist);
                else
                    keys = new() { f.Album.Length > 0 ? f.Album : "未知专辑" };

                foreach (var k in keys)
                {
                    if (!map.TryGetValue(k, out var l)) { l = new(); map[k] = l; }
                    l.Add(f);
                }
            }
            return map;
        }
    }

    private List<KeyValuePair<string, List<RichMusicFile>>> SortedGroups
    {
        get
        {
            var entries = Groups.ToList();
            switch (_groupSort)
            {
                case GroupSortMode.NameAsc:
                    entries.Sort((a, b) => string.Compare(a.Key.ToLowerInvariant(), b.Key.ToLowerInvariant(), StringComparison.Ordinal));
                    break;
                case GroupSortMode.NameDesc:
                    entries.Sort((a, b) => string.Compare(b.Key.ToLowerInvariant(), a.Key.ToLowerInvariant(), StringComparison.Ordinal));
                    break;
                case GroupSortMode.TimeAsc:
                    entries.Sort((a, b) => MinTime(a.Value).CompareTo(MinTime(b.Value)));
                    break;
                case GroupSortMode.TimeDesc:
                    entries.Sort((a, b) => MaxTime(b.Value).CompareTo(MaxTime(a.Value)));
                    break;
            }
            return entries;
        }
    }

    private List<RichMusicFile> DrillSongs
    {
        get
        {
            if (_drillGroup == null) return new();
            var list = new List<RichMusicFile>(Groups.TryGetValue(_drillGroup, out var l) ? l : new());
            ApplySongSort(list);
            return list;
        }
    }

    private static long MinTime(List<RichMusicFile> l) => l.Count == 0 ? 0 : l.Min(f => f.ModifiedMs);
    private static long MaxTime(List<RichMusicFile> l) => l.Count == 0 ? 0 : l.Max(f => f.ModifiedMs);

    // ═══════════════════════════════════════════════════════════════════════
    //  分类 / 排序持久化
    // ═══════════════════════════════════════════════════════════════════════
    private void LoadCategoryAndSort()
    {
        var catStr = _storage.Get(StorageService.KMusicCategory, nameof(MusicCategory.Song));
        _category = Enum.TryParse<MusicCategory>(catStr, true, out var c) ? c : MusicCategory.Song;

        var songSortStr = _storage.Get(StorageService.KMusicSongSort, nameof(SongSortMode.TitleAsc));
        _songSort = Enum.TryParse<SongSortMode>(songSortStr, true, out var ss) ? ss : SongSortMode.TitleAsc;

        var groupSortStr = _storage.Get(StorageService.KMusicGroupSort, nameof(GroupSortMode.NameAsc));
        _groupSort = Enum.TryParse<GroupSortMode>(groupSortStr, true, out var gs) ? gs : GroupSortMode.NameAsc;

        _categoryCombo.SelectedIndex = (int)_category;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  封面：内存分批增量加载
    // ═══════════════════════════════════════════════════════════════════════
    private List<RichMusicFile> CurrentOrderedFiles()
    {
        if (_drillGroup != null) return DrillSongs;
        if (_category == MusicCategory.Song) return SortedSongs;
        var result = new List<RichMusicFile>();
        foreach (var e in SortedGroups) result.AddRange(e.Value);
        return result;
    }

    private Task EnsureCoversForCurrentView()
        => _coverCache.EnsureFirstBatch(CurrentOrderedFiles(), RefreshContent);

    private void ResetCoverBatchesAndReload()
    {
        _coverCache.Reset();
        _ = EnsureCoversForCurrentView();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  扫描结果缓存持久化
    // ═══════════════════════════════════════════════════════════════════════
    private Dictionary<string, object> LoadCacheRaw()
    {
        var raw = _storage.Get(StorageService.KMusicLibraryCache, new Dictionary<string, object>());
        return raw;
    }

    private void SaveCache()
    {
        var raw = new Dictionary<string, object>();
        foreach (var entry in _filesByPath)
        {
            var list = new List<Dictionary<string, object>>();
            foreach (var f in entry.Value) list.Add(f.ToJson());
            raw[entry.Key] = list;
        }
        _storage.Set(StorageService.KMusicLibraryCache, raw);
    }

    /// <summary>启动时：先把缓存里已有的路径直接展示出来；对缓存里没有的路径才去扫描一次。</summary>
    private async Task LoadFromCacheThenScanNewPaths()
    {
        var cacheRaw = LoadCacheRaw();
        var configuredPaths = _settings.MusicScanPaths.ToList();

        // 先从缓存恢复所有已配置路径中"缓存里存在"的部分
        foreach (var dir in configuredPaths)
        {
            if (cacheRaw.TryGetValue(dir, out var cachedObj) && cachedObj is JsonElement je && je.ValueKind == JsonValueKind.Array)
            {
                try
                {
                    var list = new List<RichMusicFile>();
                    foreach (var item in je.EnumerateArray())
                    {
                        var dict = new Dictionary<string, object>();
                        foreach (var p in item.EnumerateObject())
                            dict[p.Name] = p.Value;
                        list.Add(RichMusicFile.FromJson(dict));
                    }
                    _filesByPath[dir] = list;
                }
                catch
                {
                    _filesByPath[dir] = new();
                }
            }
        }

        // coverBytes 不落盘缓存，从缓存恢复的曲目此时封面字段都是 null，需要单独触发分批加载
        if (_filesByPath.Count > 0)
        {
            RefreshSidebar();
            RefreshContent();
            UpdateToolbar();
            _ = EnsureCoversForCurrentView();
        }

        // 找出配置了但缓存里完全没有的路径（新添加、从未扫描过），只对这些扫描
        var newPaths = configuredPaths.Where(d => !cacheRaw.ContainsKey(d)).ToList();
        if (newPaths.Count == 0) return;

        _isScanning = true;
        _drillGroup = null;
        UpdateToolbar();
        foreach (var dir in newPaths)
        {
            _filesByPath[dir] = await ScanPath(dir);
        }
        _isScanning = false;
        SaveCache();
        ResetCoverBatchesAndReload();
        RefreshSidebar();
        RefreshContent();
        UpdateToolbar();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  扫描
    // ═══════════════════════════════════════════════════════════════════════
    /// <summary>对"当前正在查看"的目录重新扫描。</summary>
    public async Task Refresh()
    {
        if (_isScanning) return;
        _drillGroup = null;
        var sel = _selectedPath;
        if (sel.Length == 0)
        {
            await ScanAll();
        }
        else
        {
            _isScanning = true;
            UpdateToolbar();
            _filesByPath[sel] = await ScanPath(sel);
            _isScanning = false;
            SaveCache();
        }
        ResetCoverBatchesAndReload();
        RefreshSidebar();
        RefreshContent();
        UpdateToolbar();
    }

    private async Task ScanAll()
    {
        if (_isScanning) return;
        _isScanning = true;
        _drillGroup = null;
        _filesByPath.Clear();
        UpdateToolbar();
        foreach (var dir in _settings.MusicScanPaths)
        {
            _filesByPath[dir] = await ScanPath(dir);
        }
        _isScanning = false;
        SaveCache();
    }

    private async Task<List<RichMusicFile>> ScanPath(string dirPath)
    {
        var result = new List<RichMusicFile>();
        try
        {
            if (!Directory.Exists(dirPath)) return result;

            // 文件枚举在后台线程执行，避免 UI 卡顿
            var files = await Task.Run(() =>
            {
                var list = new List<string>();
                foreach (var f in Directory.EnumerateFiles(dirPath, "*", SearchOption.AllDirectories))
                {
                    var ext = Path.GetExtension(f).ToLowerInvariant();
                    if (MusicExtensions.Contains(ext)) list.Add(f);
                }
                return list;
            });

            _scanTotal = files.Count;
            _scanProgress = 0;
            UpdateToolbar();

            // 元数据读取分批在后台线程执行，每批 50 首后回 UI 线程刷新
            const int BatchSize = 50;
            for (int batchStart = 0; batchStart < files.Count; batchStart += BatchSize)
            {
                var batch = files.Skip(batchStart).Take(BatchSize).ToList();
                var batchResult = await Task.Run(() =>
                {
                    var list = new List<RichMusicFile>();
                    foreach (var filePath in batch)
                    {
                        try
                        {
                            var info = new FileInfo(filePath);
                            var baseFile = new MusicFile(filePath, Path.GetFileName(filePath), info.Length);
                            var meta = AudioMetadataReader.ReadFile(filePath);
                            list.Add(RichMusicFile.FromMetadata(
                                baseFile,
                                meta.Title,
                                meta.Artist,
                                meta.Album,
                                new DateTimeOffset(info.LastWriteTimeUtc).ToUnixTimeMilliseconds()));
                        }
                        catch
                        {
                            var info = new FileInfo(filePath);
                            list.Add(RichMusicFile.FromMetadata(
                                new MusicFile(filePath, Path.GetFileName(filePath), info.Length),
                                null, null, null,
                                new DateTimeOffset(info.LastWriteTimeUtc).ToUnixTimeMilliseconds()));
                        }
                    }
                    return list;
                });

                result.AddRange(batchResult);
                _scanProgress = Math.Min(_scanProgress + batchResult.Count, _scanTotal);
                _filesByPath[dirPath] = new List<RichMusicFile>(result);
                UpdateToolbar();
            }
        }
        catch { }
        return result;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  操作
    // ═══════════════════════════════════════════════════════════════════════
    public async Task AddPath()
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog
        {
            Title = "选择音乐库文件夹",
        };
        if (dialog.ShowDialog() != true) return;
        var result = dialog.FolderName;
        _settings.AddMusicScanPath(result);
        _isScanning = true;
        UpdateToolbar();
        _filesByPath[result] = await ScanPath(result);
        _isScanning = false;
        SaveCache();
        ResetCoverBatchesAndReload();
        RefreshSidebar();
        RefreshContent();
        UpdateToolbar();
    }

    public async Task RemovePath(string path)
    {
        _settings.RemoveMusicScanPath(path);
        _filesByPath.Remove(path);
        if (_selectedPath == path) _selectedPath = "";
        _drillGroup = null;
        SaveCache();
        ResetCoverBatchesAndReload();
        RefreshSidebar();
        RefreshContent();
        UpdateToolbar();
        await Task.CompletedTask;
    }

    private void Play(List<RichMusicFile> playlist, int index)
    {
        var playMap = playlist.Select(f => f.ToPlayMap()).ToList();
        _player.PlayMusicPlaylist(playMap, index);

        // 打开音乐播放页（同窗口覆盖层，不弹独立窗口）
        var page = new MusicPlayerPage(_player.MusicPlaylist, _player.MusicCurrentIdx);
        if (App.Current.MainWindow is Views.MainWindow mw)
            mw.OpenPlayerPage(page);
    }
}
