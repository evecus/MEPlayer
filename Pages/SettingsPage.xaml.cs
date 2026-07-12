using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using MEPlayer.Helpers;
using MEPlayer.Services;

namespace MEPlayer.Pages;

/// <summary>
/// 设置页（对应 Flutter 端 settings_page.dart）。
///
/// 三大区块：外观（主题模式 / 种子色）、播放设置（解码方式 / 画质预设）、关于。
/// 所有设置通过 AppSettingsService 持久化。
/// </summary>
public partial class SettingsPage : UserControl
{
    private readonly AppSettingsService _settings;
    private static readonly uint[] SeedColors =
    {
        0xFF3498DB, 0xFF2ECC71, 0xFFE74C3C, 0xFF9B59B6, 0xFFF39C12, 0xFF1ABC9C,
    };

    public SettingsPage()
    {
        InitializeComponent();
        _settings = App.Current.Settings;
        BuildContent();
    }

    private void BuildContent()
    {
        RootPanel.Children.Clear();

        // ── 外观 ──────────────────────────────────────────────────────────
        RootPanel.Children.Add(MakeSection("外观"));

        for (int i = 0; i < 3; i++)
        {
            var label = i switch { 0 => "跟随系统", 1 => "浅色", _ => "深色" };
            var rb = new RadioButton
            {
                Content = label,
                Margin = new Thickness(16, 4, 16, 4),
                Padding = new Thickness(0),
                GroupName = "ThemeMode",
                IsChecked = _settings.ThemeMode == i,
                Cursor = Cursors.Hand,
            };
            int captured = i;
            rb.Checked += (_, _) => _settings.SetThemeMode(captured);
            RootPanel.Children.Add(rb);
        }

        // 种子色选择行
        var seedRow = new Grid { Margin = new Thickness(16, 8, 16, 8) };
        seedRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        seedRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        var seedLabel = new TextBlock
        {
            Text = "主题颜色",
            VerticalAlignment = VerticalAlignment.Center,
            Foreground = UiHelper.BrushOf("M3.OnSurface"),
        };
        seedRow.Children.Add(seedLabel);

        var colorPanel = new StackPanel
        {
            Orientation = Orientation.Horizontal,
            HorizontalAlignment = HorizontalAlignment.Right,
        };
        foreach (var c in SeedColors)
        {
            uint captured = c;
            var current = _settings.SeedColor == c;
            var dot = new Border
            {
                Width = 28, Height = 28,
                Margin = new Thickness(8, 0, 0, 0),
                CornerRadius = new CornerRadius(14),
                Background = new SolidColorBrush(Color.FromRgb(
                    (byte)((c >> 16) & 0xff),
                    (byte)((c >> 8) & 0xff),
                    (byte)(c & 0xff))),
                Cursor = Cursors.Hand,
                BorderBrush = current ? Brushes.White : Brushes.Transparent,
                BorderThickness = new Thickness(2),
                ToolTip = "#" + (c & 0xFFFFFF).ToString("X6"),
            };
            dot.MouseLeftButtonDown += (_, _) => _settings.SetSeedColor(captured);
            colorPanel.Children.Add(dot);
        }
        Grid.SetColumn(colorPanel, 1);
        seedRow.Children.Add(colorPanel);
        RootPanel.Children.Add(seedRow);

        RootPanel.Children.Add(MakeSpacer(16));

        // ── 播放设置 ──────────────────────────────────────────────────────
        RootPanel.Children.Add(MakeSection("播放设置"));

        // 解码方式
        var decodePanel = new Grid { Margin = new Thickness(16, 8, 16, 8) };
        decodePanel.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        decodePanel.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        decodePanel.RowDefinitions.Add(new RowDefinition());
        decodePanel.RowDefinitions.Add(new RowDefinition());

        var decodeTitle = new TextBlock
        {
            Text = "解码方式",
            FontWeight = FontWeights.SemiBold,
            Foreground = UiHelper.BrushOf("M3.OnSurface"),
        };
        Grid.SetRow(decodeTitle, 0); Grid.SetColumn(decodeTitle, 0);
        decodePanel.Children.Add(decodeTitle);

        var decodeCombo = new ComboBox
        {
            Width = 120,
            Cursor = Cursors.Hand,
        };
        decodeCombo.Items.Add(new ComboBoxItem { Content = "硬解", Tag = true });
        decodeCombo.Items.Add(new ComboBoxItem { Content = "软解", Tag = false });
        decodeCombo.SelectedIndex = _settings.HardwareDecode ? 0 : 1;
        decodeCombo.SelectionChanged += (_, _) =>
        {
            if (decodeCombo.SelectedItem is ComboBoxItem item && item.Tag is bool v)
                _settings.SetHardwareDecode(v);
        };
        Grid.SetRow(decodeCombo, 0); Grid.SetColumn(decodeCombo, 1);
        decodePanel.Children.Add(decodeCombo);

        var decodeSub = new TextBlock
        {
            Text = _settings.HardwareDecode ? "硬解 — 使用 GPU 解码，降低 CPU 占用" : "软解 — 使用 CPU 解码，兼容性更好",
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            Margin = new Thickness(0, 4, 0, 0),
        };
        Grid.SetRow(decodeSub, 1); Grid.SetColumn(decodeSub, 0);
        decodePanel.Children.Add(decodeSub);

        RootPanel.Children.Add(decodePanel);

        // 画质预设
        var profilePanel = new Grid { Margin = new Thickness(16, 8, 16, 8) };
        profilePanel.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        profilePanel.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        profilePanel.RowDefinitions.Add(new RowDefinition());
        profilePanel.RowDefinitions.Add(new RowDefinition());

        var profileTitle = new TextBlock
        {
            Text = "画质预设",
            FontWeight = FontWeights.SemiBold,
            Foreground = UiHelper.BrushOf("M3.OnSurface"),
        };
        Grid.SetRow(profileTitle, 0); Grid.SetColumn(profileTitle, 0);
        profilePanel.Children.Add(profileTitle);

        var profileCombo = new ComboBox
        {
            Width = 140,
            Cursor = Cursors.Hand,
        };
        profileCombo.Items.Add(new ComboBoxItem { Content = "性能优先", Tag = "performance" });
        profileCombo.Items.Add(new ComboBoxItem { Content = "均衡", Tag = "balanced" });
        profileCombo.Items.Add(new ComboBoxItem { Content = "画质优先", Tag = "quality" });
        for (int i = 0; i < profileCombo.Items.Count; i++)
        {
            if (((ComboBoxItem)profileCombo.Items[i]).Tag as string == _settings.MpvProfile)
            {
                profileCombo.SelectedIndex = i;
                break;
            }
        }
        profileCombo.SelectionChanged += (_, _) =>
        {
            if (profileCombo.SelectedItem is ComboBoxItem item && item.Tag is string v)
                _settings.SetMpvProfile(v);
        };
        Grid.SetRow(profileCombo, 0); Grid.SetColumn(profileCombo, 1);
        profilePanel.Children.Add(profileCombo);

        var profileSub = new TextBlock
        {
            Text = ProfileLabel(_settings.MpvProfile),
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            Margin = new Thickness(0, 4, 0, 0),
        };
        Grid.SetRow(profileSub, 1); Grid.SetColumn(profileSub, 0);
        profilePanel.Children.Add(profileSub);

        RootPanel.Children.Add(profilePanel);

        RootPanel.Children.Add(MakeSpacer(16));

        // ── 关于 ──────────────────────────────────────────────────────────
        RootPanel.Children.Add(MakeSection("关于"));

        var aboutPanel = new Grid { Margin = new Thickness(16, 8, 16, 8) };
        aboutPanel.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        aboutPanel.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        aboutPanel.Children.Add(UiHelper.Icon(Mdl2.Info, 20, UiHelper.BrushOf("M3.OnSurfaceVariant")));

        var aboutText = new StackPanel { Margin = new Thickness(12, 0, 0, 0) };
        aboutText.Children.Add(new TextBlock
        {
            Text = "MEPlayer for Windows",
            FontWeight = FontWeights.SemiBold,
            Foreground = UiHelper.BrushOf("M3.OnSurface"),
        });
        aboutText.Children.Add(new TextBlock
        {
            Text = "v1.0.0 (C# WPF + libmpv)",
            FontSize = 12,
            Foreground = UiHelper.BrushOf("M3.OnSurfaceVariant"),
            Margin = new Thickness(0, 4, 0, 0),
        });
        Grid.SetColumn(aboutText, 1);
        aboutPanel.Children.Add(aboutText);
        RootPanel.Children.Add(aboutPanel);
    }

    private static TextBlock MakeSection(string title)
    {
        return new TextBlock
        {
            Text = title,
            FontWeight = FontWeights.SemiBold,
            FontSize = 14,
            Foreground = UiHelper.BrushOf("M3.Primary"),
            Margin = new Thickness(4, 8, 4, 4),
        };
    }

    private static FrameworkElement MakeSpacer(double h)
        => new Border { Height = h };

    private static string ProfileLabel(string p) => p switch
    {
        "performance" => "性能优先 — 最低延迟，适合低配设备",
        "quality" => "画质优先 — 最佳画质，需要较强 GPU",
        _ => "均衡 — 画质与性能平衡（推荐）",
    };
}
