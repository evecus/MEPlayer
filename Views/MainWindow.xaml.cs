using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MEPlayer.Services;

namespace MEPlayer.Views;

/// <summary>
/// 主窗口（对应 Flutter 端 home_page.dart）。
///
/// 结构：左侧 NavigationRail（视频/IPTV/音乐/设置）+ 右侧内容区（IndexedStack
/// 等价物，4 个 Page 通过 Visibility 切换）+ 底部 MiniPlayerBar。
///
/// 全局隐藏的音乐播放渲染挂载点（Flutter 端 _HiddenMusicVideoSink）在 C# 端
/// 不需要：libmpv 的音乐播放用 vo=null 直接输出音频，不依赖任何渲染窗口。
/// </summary>
public partial class MainWindow : Window
{
    private readonly App _app;
    private readonly AppSettingsService _settings;
    private readonly GlobalPlayerService _player;

    private readonly FrameworkElement[] _tabs;
    private readonly NavButton[] _navButtons;
    private int _index;

    public MainWindow()
    {
        InitializeComponent();
        _app = App.Current;
        _settings = _app.Settings;
        _player = _app.Player;

        _tabs = new FrameworkElement[] { VideoTab, IptvTab, MusicTab, SettingsTab };

        // 导航按钮（对应 Flutter _dests 列表）
        _navButtons = new[]
        {
            new NavButton("视频", "\uE8B2"),     // VideoLibrary
            new NavButton("IPTV", "\uE714"),     // LiveTv
            new NavButton("音乐", "\uE8D6"),     // MusicNote
            new NavButton("设置", "\uE713"),     // Settings
        };
        for (int i = 0; i < _navButtons.Length; i++)
        {
            int idx = i;
            var btn = _navButtons[i];
            btn.Button.Click += (_, _) => SelectTab(idx);
            NavPanel.Children.Add(btn.Button);
        }

        SelectTab(0);

        // 顶部关闭按钮 → 关闭窗口
        Closed += OnClosed;
    }

    private void SelectTab(int i)
    {
        if (i < 0 || i >= _tabs.Length) return;
        _index = i;
        for (int j = 0; j < _tabs.Length; j++)
            _tabs[j].Visibility = (j == i) ? Visibility.Visible : Visibility.Collapsed;
        for (int j = 0; j < _navButtons.Length; j++)
            _navButtons[j].IsSelected = (j == i);
    }

    /// <summary>导航到指定 Tab（供 MiniPlayerBar 等外部组件调用）。</summary>
    public void Navigate(int tab) => SelectTab(tab);

    /// <summary>
    /// 在主窗口右侧内容区上方打开播放页（覆盖 Tab + MiniBar），对应 Flutter
    /// 端 Get.toNamed push 路由的行为——播放页覆盖主页全部内容，不弹独立窗口。
    /// </summary>
    public void OpenPlayerPage(FrameworkElement page)
    {
        // 先释放上一个播放页（如果有），避免泄漏
        ClosePlayerPage();
        PlayerLayer.Content = page;
        PlayerLayer.Visibility = Visibility.Visible;
    }

    /// <summary>关闭播放页覆盖层，回到 Tab 主页。</summary>
    public void ClosePlayerPage()
    {
        if (PlayerLayer.Content is IDisposable d) try { d.Dispose(); } catch { }
        PlayerLayer.Content = null;
        PlayerLayer.Visibility = Visibility.Collapsed;
    }

    /// <summary>当前是否有播放页打开。</summary>
    public bool IsPlayerPageOpen => PlayerLayer.Visibility == Visibility.Visible;

    private void OnClosed(object? sender, EventArgs e)
    {
        // 主窗口关闭 = 退出程序
        Application.Current.Shutdown();
    }

    /// <summary>单个导航按钮（图标 + 标签垂直排列，选中态高亮）。</summary>
    private sealed class NavButton
    {
        public Button Button { get; }
        public bool IsSelected
        {
            get => _selected;
            set
            {
                _selected = value;
                if (value)
                {
                    Button.Background = (Brush)Application.Current.Resources["M3.PrimaryContainer"];
                    Button.Foreground = (Brush)Application.Current.Resources["M3.OnPrimaryContainer"];
                }
                else
                {
                    Button.Background = Brushes.Transparent;
                    Button.Foreground = (Brush)Application.Current.Resources["M3.OnSurfaceVariant"];
                }
            }
        }
        private bool _selected;

        public NavButton(string label, string glyph)
        {
            var sp = new StackPanel { Orientation = Orientation.Vertical, HorizontalAlignment = HorizontalAlignment.Center };
            sp.Children.Add(new TextBlock
            {
                Text = glyph,
                FontFamily = new FontFamily("Segoe MDL2 Assets"),
                FontSize = 22,
                HorizontalAlignment = HorizontalAlignment.Center,
            });
            sp.Children.Add(new TextBlock
            {
                Text = label,
                FontSize = 11,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 4, 0, 0),
            });

            Button = new Button
            {
                Content = sp,
                Width = 60,
                Height = 60,
                Margin = new Thickness(0, 4, 0, 0),
                Padding = new Thickness(0),
                BorderThickness = new Thickness(0),
                Background = Brushes.Transparent,
                Cursor = Cursors.Hand,
                Template = CreateNavTemplate(),
            };
        }

        private static ControlTemplate CreateNavTemplate()
        {
            var xaml = @"
<ControlTemplate TargetType=""Button"" xmlns=""http://schemas.microsoft.com/winfx/2006/xaml/presentation"">
    <Border Background=""{TemplateBinding Background}"" CornerRadius=""28"">
        <ContentPresenter HorizontalAlignment=""Center"" VerticalAlignment=""Center""/>
    </Border>
</ControlTemplate>";
            return (ControlTemplate)System.Windows.Markup.XamlReader.Parse(xaml);
        }
    }
}
