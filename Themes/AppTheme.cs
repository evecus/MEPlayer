using System;
using System.Windows;
using MEPlayer.Services;

namespace MEPlayer.Themes;

/// <summary>
/// 应用主题应用器（与 Flutter 端 app_theme.dart 中的 light()/dark() 等价）。
/// 把当前 Settings.ThemeMode + SeedColor 转成 ResourceDictionary 中的颜色资源，
/// 供所有 Page 通过 DynamicResource 引用。
/// </summary>
public static class AppTheme
{
    public static bool IsDark(AppSettingsService s)
    {
        return s.ThemeMode switch
        {
            0 => IsSystemDarkMode(),   // 跟随系统：读注册表 AppsUseLightTheme
            1 => false,                // 强制浅色
            _ => true,                 // 强制深色
        };
    }

    private static bool IsSystemDarkMode()
    {
        try
        {
            using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
            if (key?.GetValue("AppsUseLightTheme") is int v) return v == 0;
        }
        catch { }
        return false;
    }

    public static void ApplyCurrent(App app)
    {
        var s = App.Current.Settings;
        var dark = IsDark(s);
        var scheme = M3ColorScheme.FromSeed(s.SeedColor, dark);

        var rd = app.Resources;
        // 颜色键以 M3.* 命名，供 XAML 通过 DynamicResource 引用。
        // 必须包装成 SolidColorBrush：XAML 里 Background/Foreground 等属性类型是 Brush，
        // 直接塞 Color 会抛 ""#xxxxxxxx"不是属性"Background"的有效值" 异常。
        rd["M3.Primary"] = ToBrush(scheme.Primary);
        rd["M3.OnPrimary"] = ToBrush(scheme.OnPrimary);
        rd["M3.PrimaryContainer"] = ToBrush(scheme.PrimaryContainer);
        rd["M3.OnPrimaryContainer"] = ToBrush(scheme.OnPrimaryContainer);
        rd["M3.Secondary"] = ToBrush(scheme.Secondary);
        rd["M3.OnSecondary"] = ToBrush(scheme.OnSecondary);
        rd["M3.SecondaryContainer"] = ToBrush(scheme.SecondaryContainer);
        rd["M3.OnSecondaryContainer"] = ToBrush(scheme.OnSecondaryContainer);
        rd["M3.Tertiary"] = ToBrush(scheme.Tertiary);
        rd["M3.OnTertiary"] = ToBrush(scheme.OnTertiary);
        rd["M3.TertiaryContainer"] = ToBrush(scheme.TertiaryContainer);
        rd["M3.OnTertiaryContainer"] = ToBrush(scheme.OnTertiaryContainer);
        rd["M3.Error"] = ToBrush(scheme.Error);
        rd["M3.OnError"] = ToBrush(scheme.OnError);
        rd["M3.ErrorContainer"] = ToBrush(scheme.ErrorContainer);
        rd["M3.OnErrorContainer"] = ToBrush(scheme.OnErrorContainer);
        rd["M3.Background"] = ToBrush(scheme.Background);
        rd["M3.OnBackground"] = ToBrush(scheme.OnBackground);
        rd["M3.Surface"] = ToBrush(scheme.Surface);
        rd["M3.OnSurface"] = ToBrush(scheme.OnSurface);
        rd["M3.SurfaceVariant"] = ToBrush(scheme.SurfaceVariant);
        rd["M3.OnSurfaceVariant"] = ToBrush(scheme.OnSurfaceVariant);
        rd["M3.SurfaceContainerHighest"] = ToBrush(scheme.SurfaceContainerHighest);
        rd["M3.SurfaceContainerHigh"] = ToBrush(scheme.SurfaceContainerHigh);
        rd["M3.Outline"] = ToBrush(scheme.Outline);
        rd["M3.OutlineVariant"] = ToBrush(scheme.OutlineVariant);
        rd["M3.Shadow"] = ToBrush(scheme.Shadow);

        // 主题模式标志
        rd["M3.IsDark"] = dark;
    }

    private static System.Windows.Media.SolidColorBrush ToBrush(System.Windows.Media.Color c)
    {
        var b = new System.Windows.Media.SolidColorBrush(c);
        b.Freeze();
        return b;
    }
}
