using System;
using System.Collections.Generic;
using System.Collections.Specialized;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using MEPlayer.Helpers;
using MEPlayer.Services;
using Microsoft.Win32;

namespace MEPlayer.Pages;

/// <summary>
/// IPTV 源管理页（对应 Flutter 端 iptv_tab_page.dart）。
///
/// 顶栏（标题 + 导入）→ 分隔线 → 源卡片网格（自适应 1-3 列）→ 空状态。
/// 卡片：图标 + 名称/徽章 + 刷新/编辑/删除；hover 高亮 + 阴影；点击进入播放页。
/// 导入/编辑对话框：名称 + 远程/文件切换 + 链接/文件选择 + 自动更新。
/// 远程源刷新：HttpClient GET（fire-and-forget，不阻塞 UI）。
/// </summary>
public partial class IptvTabPage : UserControl
{
    private readonly AppSettingsService _settings;
    private static readonly HttpClient _http = new();

    /// <summary>每个源是否正在刷新（对应 Flutter isRefreshing 映射）。</summary>
    private readonly Dictionary<int, bool> _refreshing = new();

    public IptvTabPage()
    {
        InitializeComponent();
        _settings = App.Current.Settings;

        CardsPanel.SizeChanged += (_, _) => RelayoutCards();
        Loaded += (_, _) =>
        {
            RenderCards();
            RelayoutCards();
        };
        _settings.IptvSources.CollectionChanged += OnSourcesChanged;
    }

    private void OnSourcesChanged(object? sender, NotifyCollectionChangedEventArgs e) => RenderCards();

    // ── 顶栏「导入」按钮 ───────────────────────────────────────────────────
    private void ImportButton_Click(object sender, RoutedEventArgs e) => ShowEditDialog(null);

    // ── 渲染卡片网格 ────────────────────────────────────────────────────────
    private void RenderCards()
    {
        CardsPanel.Children.Clear();

        if (_settings.IptvSources.Count == 0)
        {
            EmptyState.Visibility = Visibility.Visible;
            return;
        }
        EmptyState.Visibility = Visibility.Collapsed;

        for (int i = 0; i < _settings.IptvSources.Count; i++)
            CardsPanel.Children.Add(BuildCard(i, _settings.IptvSources[i]));

        RelayoutCards();
    }

    /// <summary>按可用宽度自适应列数 1-3，等分卡片宽度（对应 Flutter LayoutBuilder 计算 cols）。</summary>
    private void RelayoutCards()
    {
        double avail = CardsPanel.ActualWidth;
        if (avail <= 0) return;

        const double minCardWidth = 280;   // Flutter minCardWidth
        const double margin = 16;          // 每张卡片左右各 8 外边距 → 16 占位
        int cols = (int)Math.Floor(avail / (minCardWidth + margin));
        cols = Math.Clamp(cols, 1, 3);
        double cardWidth = avail / cols - margin;
        if (cardWidth < 120) cardWidth = 120;

        foreach (var child in CardsPanel.Children)
        {
            if (child is FrameworkElement fe) fe.Width = cardWidth;
        }
    }

    /// <summary>构建单张源卡片（对应 Flutter _SourceCard）。</summary>
    private Border BuildCard(int index, Dictionary<string, string> src)
    {
        bool isNetwork = src.TryGetValue("type", out var t) && t == "network";
        bool autoUpdate = src.TryGetValue("autoUpdate", out var au) && au == "true";
        string name = src.TryGetValue("name", out var n) ? n : "";

        var card = new Border
        {
            Width = 280,                  // 默认宽度，RelayoutCards 会按列数覆盖
            Margin = new Thickness(8),
        };
        card.Style = (Style)FindResource("SourceCard");
        card.MouseLeftButtonUp += (_, _) => OpenIptvPlayer(index);

        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });   // 图标
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(12) }); // 间距
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) }); // 信息
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });   // 操作按钮

        // 图标容器（network=Wifi+primary，file=Folder+secondary）
        string iconBgKey = isNetwork ? "M3.PrimaryContainer" : "M3.SecondaryContainer";
        string iconFgKey = isNetwork ? "M3.Primary" : "M3.Secondary";
        string iconGlyph = isNetwork ? Mdl2.Wifi : Mdl2.Folder;
        var iconBox = new Border
        {
            Width = 40,
            Height = 40,
            CornerRadius = new CornerRadius(8),
            Background = UiHelper.WithAlpha(M3Brush(iconBgKey), 0x78),
            Child = UiHelper.Icon(iconGlyph, 20, M3Brush(iconFgKey)),
        };
        Grid.SetColumn(iconBox, 0);
        grid.Children.Add(iconBox);

        // 信息：名称 + 徽章
        var info = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        info.Children.Add(new TextBlock
        {
            Text = name,
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
            TextTrimming = TextTrimming.CharacterEllipsis,
        });

        var badges = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 3, 0, 0) };
        var typeFg = M3Brush(iconFgKey);
        var typeBg = UiHelper.WithAlpha(M3Brush(iconBgKey), 0x50);
        badges.Children.Add(MakeBadge(isNetwork ? "远程" : "文件", typeFg, typeBg));
        if (isNetwork && autoUpdate)
        {
            var green = new SolidColorBrush(Color.FromRgb(0x4C, 0xAF, 0x50));
            var greenBg = new SolidColorBrush(Color.FromArgb(0x1E, 0x4C, 0xAF, 0x50));
            var autoBadge = MakeBadge("自动更新", green, greenBg);
            autoBadge.Margin = new Thickness(4, 0, 0, 0);
            badges.Children.Add(autoBadge);
        }
        info.Children.Add(badges);
        Grid.SetColumn(info, 2);
        grid.Children.Add(info);

        // 操作按钮：刷新（仅远程）/ 编辑 / 删除
        var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        bool refreshing = _refreshing.TryGetValue(index, out var r) && r;
        if (isNetwork)
        {
            actions.Children.Add(MakeActionButton(Mdl2.Refresh, M3Brush("M3.Primary"), "刷新源",
                refreshing, (_, _) => RefreshSource(index)));
        }
        actions.Children.Add(MakeActionButton(Mdl2.Edit, M3Brush("M3.OnSurfaceVariant"), "编辑",
            false, (_, _) => ShowEditDialog(index)));
        var delBrush = UiHelper.WithAlpha(M3Brush("M3.Error"), 0xB4);
        actions.Children.Add(MakeActionButton(Mdl2.Delete, delBrush, "删除",
            false, (_, _) => ConfirmAndDelete(index, name)));
        Grid.SetColumn(actions, 3);
        grid.Children.Add(actions);

        card.Child = grid;
        return card;
    }

    // ── 编辑/导入对话框 ──────────────────────────────────────────────────────
    private void ShowEditDialog(int? index)
    {
        Dictionary<string, string>? existing = null;
        if (index is int idx && idx >= 0 && idx < _settings.IptvSources.Count)
            existing = _settings.IptvSources[idx];

        var owner = Window.GetWindow(this);
        var dlg = new SourceEditDialog(existing, async src =>
        {
            if (index is int i)
            {
                _settings.UpdateIptvSource(i, src);
                MaybeFetchOnUpdate(src);
            }
            else
            {
                _settings.AddIptvSource(src);
                MaybeFetchOnAdd(src);
            }
            await Task.CompletedTask;
        })
        { Owner = owner };
        dlg.ShowDialog();
    }

    /// <summary>新增源后：远程 + 自动更新 + url 非空 → fire-and-forget 拉取（对应 Flutter addSource）。</summary>
    private void MaybeFetchOnAdd(Dictionary<string, string> src)
    {
        if (src.TryGetValue("type", out var t) && t == "network"
            && src.TryGetValue("autoUpdate", out var au) && au == "true"
            && src.TryGetValue("url", out var url) && !string.IsNullOrEmpty(url))
        {
            _ = FetchRemoteAsync(url);
        }
    }

    /// <summary>更新源后：远程 + url 非空 → fire-and-forget 拉取（对应 Flutter updateSource）。</summary>
    private void MaybeFetchOnUpdate(Dictionary<string, string> src)
    {
        if (src.TryGetValue("type", out var t) && t == "network"
            && src.TryGetValue("url", out var url) && !string.IsNullOrEmpty(url))
        {
            _ = FetchRemoteAsync(url);
        }
    }

    private static async Task FetchRemoteAsync(string url)
    {
        try { await _http.GetStringAsync(url); }
        catch { /* fire-and-forget，忽略错误 */ }
    }

    // ── 远程源刷新（对应 Flutter refreshSource）──────────────────────────────
    private async void RefreshSource(int index)
    {
        if (index < 0 || index >= _settings.IptvSources.Count) return;
        var src = _settings.IptvSources[index];
        if (!src.TryGetValue("type", out var t) || t != "network") return;
        if (!src.TryGetValue("url", out var url) || string.IsNullOrEmpty(url)) return;

        _refreshing[index] = true;
        RenderCards();
        try { await _http.GetStringAsync(url); }
        catch { /* 忽略 */ }
        finally
        {
            _refreshing[index] = false;
            RenderCards();
        }
    }

    // ── 删除确认 ─────────────────────────────────────────────────────────────
    private void ConfirmAndDelete(int index, string name)
    {
        var owner = Window.GetWindow(this);
        if (ConfirmDialog.Show(owner, "确认删除", $"确定要删除「{name}」吗？", "删除"))
            _settings.RemoveIptvSource(index);
    }

    /// <summary>占位方法：点击卡片打开 IPTV 播放页（对应 Flutter AppNavigator.toIptvPlayer）。</summary>
    protected virtual void OpenIptvPlayer(int sourceIndex)
    {
        // 打开 IPTV 播放页（同窗口覆盖层，不弹独立窗口）
        var args = new IptvResumeArgs
        {
            SourceIndex = sourceIndex,
        };
        var page = new IptvPlayerPage(args);
        if (App.Current.MainWindow is Views.MainWindow mw)
            mw.OpenPlayerPage(page);
    }

    // ── 共用构造小部件 ───────────────────────────────────────────────────────

    /// <summary>徽章（对应 Flutter _Badge）：圆角小色块 + 文字。</summary>
    private static Border MakeBadge(string label, Brush fg, Brush bg)
    {
        return new Border
        {
            Background = bg,
            CornerRadius = new CornerRadius(4),
            Padding = new Thickness(6, 1, 6, 1),
            Child = new TextBlock { Text = label, FontSize = 10, Foreground = fg },
        };
    }

    /// <summary>32x32 操作按钮（对应 Flutter _ActionBtn），loading 时显示圆形进度（旋转图标）。</summary>
    private static Button MakeActionButton(string glyph, Brush color, string tooltip,
        bool loading, RoutedEventHandler onClick)
    {
        var btn = new Button
        {
            Width = 32,
            Height = 32,
            Padding = new Thickness(0),
            Cursor = Cursors.Hand,
            ToolTip = tooltip,
            Style = (Style)Application.Current.FindResource("IconButton"),
        };
        btn.Content = loading ? MakeSpinner(color, 16) : UiHelper.Icon(glyph, 16, color);
        btn.Click += onClick;
        return btn;
    }

    /// <summary>旋转的刷新图标，模拟 CircularProgressIndicator。</summary>
    internal static FrameworkElement MakeSpinner(Brush color, double size = 16)
    {
        var tb = new TextBlock
        {
            Text = Mdl2.Refresh,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = size,
            Foreground = color,
            HorizontalAlignment = HorizontalAlignment.Center,
            VerticalAlignment = VerticalAlignment.Center,
        };
        var rt = new RotateTransform();
        tb.RenderTransform = rt;
        tb.RenderTransformOrigin = new Point(0.5, 0.5);
        var anim = new DoubleAnimation(0, 360, TimeSpan.FromSeconds(0.9))
        {
            RepeatBehavior = RepeatBehavior.Forever,
        };
        rt.BeginAnimation(RotateTransform.AngleProperty, anim);
        return tb;
    }

    /// <summary>健壮地取主题色为 Brush（资源可能存为 Color 或 Brush，统一处理）。</summary>
    internal static SolidColorBrush M3Brush(string key)
    {
        var v = Application.Current.Resources[key];
        if (v is SolidColorBrush sb) return sb;
        if (v is Color c) return new SolidColorBrush(c);
        return new SolidColorBrush(Colors.Transparent);
    }
}

// ── 编辑/导入对话框（对应 Flutter _SourceEditDialog）──────────────────────────

internal sealed class SourceEditDialog : Window
{
    private readonly Dictionary<string, string>? _existing;
    private readonly Func<Dictionary<string, string>, Task> _onSave;
    private readonly bool _isEdit;

    private string _importType = "network";
    private bool _autoUpdate;
    private string? _filePath;
    private string? _fileName;
    private bool _saving;

    private readonly TextBox _nameBox = new();
    private readonly TextBlock _nameError = new();
    private readonly TextBox _urlBox = new();
    private readonly Button _fileBtn = new();
    private readonly TextBlock _fileNameText = new();
    private readonly CheckBox _autoCheck = new();
    private readonly TextBlock _statusError = new();
    private readonly Button _saveBtn = new();
    private readonly Button _cancelBtn = new();

    private readonly Border _networkToggle = new();
    private readonly Border _fileToggle = new();
    private readonly TextBlock _networkToggleIcon = new();
    private readonly TextBlock _networkToggleLabel = new();
    private readonly TextBlock _fileToggleIcon = new();
    private readonly TextBlock _fileToggleLabel = new();
    private readonly FrameworkElement _urlSection;
    private readonly FrameworkElement _fileSection;
    private readonly FrameworkElement _autoSection;

    public SourceEditDialog(Dictionary<string, string>? existing, Func<Dictionary<string, string>, Task> onSave)
    {
        _existing = existing;
        _onSave = onSave;
        _isEdit = existing != null;

        // 从已有源回填
        if (existing != null)
        {
            _nameBox.Text = existing.TryGetValue("name", out var n) ? n : "";
            _urlBox.Text = existing.TryGetValue("url", out var u) ? u : "";
            _importType = existing.TryGetValue("type", out var t) ? t : "network";
            _autoUpdate = existing.TryGetValue("autoUpdate", out var au) && au == "true";
            _filePath = existing.TryGetValue("filePath", out var fp) ? fp : null;
            _fileName = existing.TryGetValue("fileName", out var fn) ? fn : null;
        }

        Title = _isEdit ? "编辑 IPTV 源" : "导入 IPTV 源";
        Width = 440;
        SizeToContent = SizeToContent.Height;
        ResizeMode = ResizeMode.NoResize;
        WindowStartupLocation = WindowStartupLocation.CenterOwner;
        SetResourceReference(BackgroundProperty, "M3.Surface");
        SetResourceReference(ForegroundProperty, "M3.OnSurface");

        _urlSection = BuildUrlSection();
        _fileSection = BuildFileSection();
        _autoSection = BuildAutoSection();

        Content = BuildRoot();
        ApplyImportType();
        UpdateFileNameText();
        UpdateSaveButton();
    }

    private FrameworkElement BuildRoot()
    {
        var root = new StackPanel();

        // 标题
        root.Children.Add(new TextBlock
        {
            Text = _isEdit ? "编辑 IPTV 源" : "导入 IPTV 源",
            FontSize = 18,
            FontWeight = FontWeights.SemiBold,
            Foreground = IptvTabPage.M3Brush("M3.OnSurface"),
            Margin = new Thickness(0, 0, 0, 20),
        });

        // 名称
        root.Children.Add(new TextBlock
        {
            Text = "名称 *",
            FontSize = 13,
            FontWeight = FontWeights.Medium,
            Foreground = IptvTabPage.M3Brush("M3.OnSurface"),
            Margin = new Thickness(0, 0, 0, 6),
        });
        _nameBox.FontSize = 13;
        root.Children.Add(_nameBox);
        _nameError.FontSize = 12;
        _nameError.Foreground = IptvTabPage.M3Brush("M3.Error");
        _nameError.Margin = new Thickness(0, 4, 0, 0);
        _nameError.Visibility = Visibility.Collapsed;
        root.Children.Add(_nameError);

        // 导入方式
        root.Children.Add(new TextBlock
        {
            Text = "导入方式",
            FontSize = 13,
            FontWeight = FontWeights.Medium,
            Foreground = IptvTabPage.M3Brush("M3.OnSurface"),
            Margin = new Thickness(0, 16, 0, 8),
        });
        root.Children.Add(BuildToggles());

        // 链接
        root.Children.Add(new Border { Height = 16 });
        root.Children.Add(_urlSection);

        // 文件
        root.Children.Add(new Border { Height = 12 });
        root.Children.Add(_fileSection);

        // 自动更新
        root.Children.Add(new Border { Height = 12 });
        root.Children.Add(_autoSection);

        // 分隔线
        root.Children.Add(new Border
        {
            Height = 1,
            Background = IptvTabPage.M3Brush("M3.OutlineVariant"),
            Margin = new Thickness(0, 20, 0, 16),
        });

        // 状态错误
        _statusError.FontSize = 12;
        _statusError.Foreground = IptvTabPage.M3Brush("M3.Error");
        _statusError.Margin = new Thickness(0, 0, 0, 8);
        _statusError.TextWrapping = TextWrapping.Wrap;
        _statusError.Visibility = Visibility.Collapsed;
        root.Children.Add(_statusError);

        // 操作按钮
        var actions = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
        };
        _cancelBtn.Content = "取消";
        _cancelBtn.Padding = new Thickness(16, 6, 16, 6);
        _cancelBtn.Click += (_, _) => DialogResult = false;
        actions.Children.Add(_cancelBtn);

        _saveBtn.Style = (Style)Application.Current.FindResource("FilledButton");
        _saveBtn.Padding = new Thickness(20, 6, 20, 6);
        _saveBtn.MinWidth = 88;
        _saveBtn.Margin = new Thickness(8, 0, 0, 0);
        _saveBtn.Click += Save_Click;
        actions.Children.Add(_saveBtn);
        root.Children.Add(actions);

        return new Border { Padding = new Thickness(24), Child = root };
    }

    /// <summary>导入方式切换：远程导入 / 文件导入（两个可点击卡片式 toggle）。</summary>
    private FrameworkElement BuildToggles()
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(8) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        BuildToggleChild(_networkToggle, _networkToggleIcon, _networkToggleLabel);
        _networkToggle.MouseLeftButtonUp += (_, _) => { _importType = "network"; ApplyImportType(); };
        Grid.SetColumn(_networkToggle, 0);
        grid.Children.Add(_networkToggle);

        BuildToggleChild(_fileToggle, _fileToggleIcon, _fileToggleLabel);
        _fileToggle.MouseLeftButtonUp += (_, _) => { _importType = "file"; ApplyImportType(); };
        Grid.SetColumn(_fileToggle, 2);
        grid.Children.Add(_fileToggle);

        return grid;
    }

    private static void BuildToggleChild(Border box, TextBlock icon, TextBlock label)
    {
        var sp = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Center,
        };
        sp.Children.Add(icon);
        label.Margin = new Thickness(6, 0, 0, 0);
        sp.Children.Add(label);
        box.Child = sp;
    }

    /// <summary>根据选中态刷新 toggle 外观（对应 Flutter _TypeToggle）。</summary>
    private static void ConfigureToggle(Border box, TextBlock icon, TextBlock label,
        string glyph, string text, bool selected)
    {
        box.CornerRadius = new CornerRadius(8);
        box.Padding = new Thickness(12, 10, 12, 10);
        box.Cursor = Cursors.Hand;
        box.BorderThickness = new Thickness(selected ? 1.5 : 1);
        box.Background = selected ? IptvTabPage.M3Brush("M3.PrimaryContainer") : IptvTabPage.M3Brush("M3.SurfaceContainerHighest");
        box.BorderBrush = selected ? IptvTabPage.M3Brush("M3.Primary") : IptvTabPage.M3Brush("M3.OutlineVariant");

        icon.Text = glyph;
        icon.FontFamily = new FontFamily("Segoe MDL2 Assets");
        icon.FontSize = 16;
        icon.Foreground = selected ? IptvTabPage.M3Brush("M3.OnPrimaryContainer") : IptvTabPage.M3Brush("M3.OnSurfaceVariant");
        icon.VerticalAlignment = VerticalAlignment.Center;

        label.Text = text;
        label.FontSize = 13;
        label.FontWeight = selected ? FontWeights.SemiBold : FontWeights.Normal;
        label.Foreground = selected ? IptvTabPage.M3Brush("M3.OnPrimaryContainer") : IptvTabPage.M3Brush("M3.OnSurfaceVariant");
        label.VerticalAlignment = VerticalAlignment.Center;
    }

    /// <summary>远程/文件切换后，更新 toggle 外观 + 启用/禁用对应区段。</summary>
    private void ApplyImportType()
    {
        bool isNetwork = _importType == "network";
        ConfigureToggle(_networkToggle, _networkToggleIcon, _networkToggleLabel, Mdl2.Wifi, "远程导入", isNetwork);
        ConfigureToggle(_fileToggle, _fileToggleIcon, _fileToggleLabel, Mdl2.Folder, "文件导入", !isNetwork);

        _urlSection.IsEnabled = isNetwork;
        _urlSection.Opacity = isNetwork ? 1.0 : 0.38;
        _fileSection.IsEnabled = !isNetwork;
        _fileSection.Opacity = !isNetwork ? 1.0 : 0.38;
        _autoSection.IsEnabled = isNetwork;
        _autoSection.Opacity = isNetwork ? 1.0 : 0.38;
        _autoCheck.IsEnabled = isNetwork;
    }

    /// <summary>链接输入框（带前缀图标，对应 Flutter prefixIcon: Icons.link）。</summary>
    private FrameworkElement BuildUrlSection()
    {
        var box = new Border
        {
            CornerRadius = new CornerRadius(8),
            BorderThickness = new Thickness(1),
            Background = IptvTabPage.M3Brush("M3.Surface"),
            BorderBrush = IptvTabPage.M3Brush("M3.OutlineVariant"),
            Padding = new Thickness(8, 0, 8, 0),
        };
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        var icon = new TextBlock
        {
            Text = Mdl2.Link,
            FontFamily = new FontFamily("Segoe MDL2 Assets"),
            FontSize = 16,
            Foreground = IptvTabPage.M3Brush("M3.OnSurfaceVariant"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(2, 0, 8, 0),
        };
        Grid.SetColumn(icon, 0);
        grid.Children.Add(icon);

        _urlBox.BorderThickness = new Thickness(0);
        _urlBox.Background = Brushes.Transparent;
        _urlBox.Padding = new Thickness(0, 8, 0, 8);
        _urlBox.FontSize = 13;
        _urlBox.VerticalContentAlignment = VerticalAlignment.Center;
        Grid.SetColumn(_urlBox, 1);
        grid.Children.Add(_urlBox);

        box.Child = grid;
        return box;
    }

    /// <summary>文件选择行：选择文件按钮 + 文件名显示。</summary>
    private FrameworkElement BuildFileSection()
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });

        _fileBtn.Content = "选择文件";
        _fileBtn.Padding = new Thickness(12, 6, 12, 6);
        _fileBtn.Click += (_, _) => PickFile();
        Grid.SetColumn(_fileBtn, 0);
        grid.Children.Add(_fileBtn);

        _fileNameText.FontSize = 12;
        _fileNameText.VerticalAlignment = VerticalAlignment.Center;
        _fileNameText.Margin = new Thickness(12, 0, 0, 0);
        _fileNameText.TextTrimming = TextTrimming.CharacterEllipsis;
        Grid.SetColumn(_fileNameText, 1);
        grid.Children.Add(_fileNameText);

        return grid;
    }

    /// <summary>自动更新行（仅远程可用，对应 Flutter Opacity+Checkbox）。</summary>
    private FrameworkElement BuildAutoSection()
    {
        var sp = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            VerticalAlignment = VerticalAlignment.Center,
        };
        _autoCheck.IsChecked = _autoUpdate;
        _autoCheck.Checked += (_, _) => _autoUpdate = true;
        _autoCheck.Unchecked += (_, _) => _autoUpdate = false;
        _autoCheck.VerticalAlignment = VerticalAlignment.Center;
        sp.Children.Add(_autoCheck);

        var label = new TextBlock
        {
            Text = "启动时自动更新",
            FontSize = 13,
            Foreground = IptvTabPage.M3Brush("M3.OnSurface"),
            VerticalAlignment = VerticalAlignment.Center,
            Margin = new Thickness(4, 0, 0, 0),
            Cursor = Cursors.Hand,
        };
        label.MouseLeftButtonUp += (_, _) =>
        {
            if (_autoCheck.IsEnabled)
            {
                _autoUpdate = !_autoUpdate;
                _autoCheck.IsChecked = _autoUpdate;
            }
        };
        sp.Children.Add(label);
        return sp;
    }

    private void PickFile()
    {
        var dlg = new OpenFileDialog
        {
            Filter = "M3U 播放列表|*.m3u;*.m3u8|文本文件|*.txt|所有文件|*.*",
            Title = "选择 IPTV 播放列表",
        };
        if (dlg.ShowDialog() == true)
        {
            _filePath = dlg.FileName;
            _fileName = Path.GetFileName(dlg.FileName);
            UpdateFileNameText();
        }
    }

    private void UpdateFileNameText()
    {
        if (!string.IsNullOrEmpty(_fileName))
        {
            _fileNameText.Text = _fileName;
            _fileNameText.Foreground = IptvTabPage.M3Brush("M3.OnSurface");
        }
        else
        {
            _fileNameText.Text = "未选择文件";
            _fileNameText.Foreground = IptvTabPage.M3Brush("M3.OnSurfaceVariant");
        }
    }

    private async void Save_Click(object sender, RoutedEventArgs e)
    {
        _nameError.Visibility = Visibility.Collapsed;
        _statusError.Visibility = Visibility.Collapsed;

        var name = _nameBox.Text.Trim();
        if (string.IsNullOrEmpty(name))
        {
            _nameError.Text = "名称不能为空";
            _nameError.Visibility = Visibility.Visible;
            return;
        }

        bool isNetwork = _importType == "network";
        var url = _urlBox.Text.Trim();
        if (isNetwork && string.IsNullOrEmpty(url))
        {
            _statusError.Text = "请输入 M3U 链接";
            _statusError.Visibility = Visibility.Visible;
            return;
        }
        if (!isNetwork && string.IsNullOrEmpty(_filePath))
        {
            _statusError.Text = "请选择本地文件";
            _statusError.Visibility = Visibility.Visible;
            return;
        }

        // 组装源数据
        var src = new Dictionary<string, string>
        {
            { "name", name },
            { "type", _importType },
            { "autoUpdate", _autoUpdate.ToString().ToLowerInvariant() },
        };
        if (isNetwork) src["url"] = url;
        else
        {
            src["filePath"] = _filePath!;
            src["fileName"] = _fileName ?? "";
        }

        _saving = true;
        UpdateSaveButton();
        try
        {
            await _onSave(src);
            DialogResult = true;
        }
        catch (Exception ex)
        {
            _statusError.Text = "保存失败: " + ex.Message;
            _statusError.Visibility = Visibility.Visible;
        }
        finally
        {
            _saving = false;
            UpdateSaveButton();
        }
    }

    private void UpdateSaveButton()
    {
        _saveBtn.IsEnabled = !_saving;
        _cancelBtn.IsEnabled = !_saving;
        if (_saving)
            _saveBtn.Content = IptvTabPage.MakeSpinner(IptvTabPage.M3Brush("M3.OnPrimary"), 14);
        else
            _saveBtn.Content = new TextBlock
            {
                Text = _isEdit ? "保存" : "导入",
                FontSize = 13,
                Foreground = IptvTabPage.M3Brush("M3.OnPrimary"),
            };
    }
}

// ── 删除确认对话框（对应 Flutter AlertDialog）────────────────────────────────

internal static class ConfirmDialog
{
    public static bool Show(Window owner, string title, string message, string confirmText)
    {
        var w = new Window
        {
            Title = title,
            Width = 380,
            SizeToContent = SizeToContent.Height,
            ResizeMode = ResizeMode.NoResize,
            WindowStartupLocation = WindowStartupLocation.CenterOwner,
            Owner = owner,
        };
        w.SetResourceReference(Control.BackgroundProperty, "M3.Surface");

        var root = new StackPanel();
        root.Children.Add(new TextBlock
        {
            Text = title,
            FontSize = 16,
            FontWeight = FontWeights.SemiBold,
            Foreground = IptvTabPage.M3Brush("M3.OnSurface"),
        });
        root.Children.Add(new TextBlock
        {
            Text = message,
            FontSize = 13,
            Foreground = IptvTabPage.M3Brush("M3.OnSurfaceVariant"),
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 8, 0, 0),
        });

        var actions = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
            Margin = new Thickness(0, 20, 0, 0),
        };
        var cancel = new Button { Content = "取消", Padding = new Thickness(16, 6, 16, 6) };
        cancel.Click += (_, _) => w.DialogResult = false;
        actions.Children.Add(cancel);

        var confirm = new Button
        {
            Padding = new Thickness(16, 6, 16, 6),
            Margin = new Thickness(8, 0, 0, 0),
            Style = (Style)Application.Current.FindResource("FilledButton"),
        };
        confirm.SetResourceReference(Control.BackgroundProperty, "M3.Error");
        confirm.Content = new TextBlock
        {
            Text = confirmText,
            FontSize = 13,
            Foreground = IptvTabPage.M3Brush("M3.OnError"),
        };
        confirm.Click += (_, _) => w.DialogResult = true;
        actions.Children.Add(confirm);
        root.Children.Add(actions);

        w.Content = new Border { Padding = new Thickness(24), Child = root };
        return w.ShowDialog() == true;
    }
}
