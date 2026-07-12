using System;
using System.Windows;
using System.Windows.Media;

namespace MEPlayer.Themes;

/// <summary>
/// 从种子色生成 Material3 ColorScheme（对应 Flutter 端 ColorScheme.fromSeed）。
/// 简化版 HSL 色调旋转算法：取种子色的 H/L，按 Material3 规范旋转出 primary/
/// secondary/tertiary 等关键色调，再各自按亮度模式生成不同明度（90/80/40/30 等）。
/// 不追求与 Flutter 100% 像素级一致，只求视觉风格相近。
/// </summary>
public static class M3ColorScheme
{
    public sealed class Scheme
    {
        public Color Primary { get; set; }
        public Color OnPrimary { get; set; }
        public Color PrimaryContainer { get; set; }
        public Color OnPrimaryContainer { get; set; }
        public Color Secondary { get; set; }
        public Color OnSecondary { get; set; }
        public Color SecondaryContainer { get; set; }
        public Color OnSecondaryContainer { get; set; }
        public Color Tertiary { get; set; }
        public Color OnTertiary { get; set; }
        public Color TertiaryContainer { get; set; }
        public Color OnTertiaryContainer { get; set; }
        public Color Error { get; set; }
        public Color OnError { get; set; }
        public Color ErrorContainer { get; set; }
        public Color OnErrorContainer { get; set; }
        public Color Background { get; set; }
        public Color OnBackground { get; set; }
        public Color Surface { get; set; }
        public Color OnSurface { get; set; }
        public Color SurfaceVariant { get; set; }
        public Color OnSurfaceVariant { get; set; }
        public Color SurfaceContainerHighest { get; set; }
        public Color SurfaceContainerHigh { get; set; }
        public Color Outline { get; set; }
        public Color OutlineVariant { get; set; }
        public Color Shadow { get; set; }
    }

    public static Scheme FromSeed(uint argb, bool dark)
    {
        ToHsl(argb, out double h, out double s, out double l);

        // 主色调偏移：primary 用原色，secondary +60°，tertiary +120°
        double pTone = dark ? 0.70 : 0.45;
        double sTone = dark ? 0.65 : 0.55;
        double tTone = dark ? 0.70 : 0.50;

        if (dark)
        {
            return new Scheme
            {
                Primary = FromHsl(h, s, 0.70),
                OnPrimary = FromHsl(h, s, 0.10),
                PrimaryContainer = FromHsl(h, s, 0.30),
                OnPrimaryContainer = FromHsl(h, s, 0.95),
                Secondary = FromHsl(h + 60, s * 0.8, 0.65),
                OnSecondary = FromHsl(h + 60, s * 0.8, 0.10),
                SecondaryContainer = FromHsl(h + 60, s * 0.8, 0.30),
                OnSecondaryContainer = FromHsl(h + 60, s * 0.8, 0.95),
                Tertiary = FromHsl(h + 120, s * 0.8, 0.70),
                OnTertiary = FromHsl(h + 120, s * 0.8, 0.10),
                TertiaryContainer = FromHsl(h + 120, s * 0.8, 0.30),
                OnTertiaryContainer = FromHsl(h + 120, s * 0.8, 0.95),
                Error = FromHsl(0, 0.75, 0.70),
                OnError = FromHsl(0, 0.75, 0.10),
                ErrorContainer = FromHsl(0, 0.75, 0.30),
                OnErrorContainer = FromHsl(0, 0.75, 0.95),
                Background = FromHsl(h, s * 0.2, 0.10),
                OnBackground = FromHsl(h, s * 0.2, 0.92),
                Surface = FromHsl(h, s * 0.2, 0.12),
                OnSurface = FromHsl(h, s * 0.2, 0.92),
                SurfaceVariant = FromHsl(h, s * 0.3, 0.18),
                OnSurfaceVariant = FromHsl(h, s * 0.3, 0.80),
                SurfaceContainerHighest = FromHsl(h, s * 0.2, 0.20),
                SurfaceContainerHigh = FromHsl(h, s * 0.2, 0.17),
                Outline = FromHsl(h, s * 0.2, 0.55),
                OutlineVariant = FromHsl(h, s * 0.2, 0.30),
                Shadow = Color.FromScRgb(1.0f, 0, 0, 0),
            };
        }
        else
        {
            return new Scheme
            {
                Primary = FromHsl(h, s, 0.45),
                OnPrimary = FromHsl(h, s, 1.00),
                PrimaryContainer = FromHsl(h, s, 0.90),
                OnPrimaryContainer = FromHsl(h, s, 0.12),
                Secondary = FromHsl(h + 60, s * 0.8, 0.45),
                OnSecondary = FromHsl(h + 60, s * 0.8, 1.00),
                SecondaryContainer = FromHsl(h + 60, s * 0.8, 0.90),
                OnSecondaryContainer = FromHsl(h + 60, s * 0.8, 0.12),
                Tertiary = FromHsl(h + 120, s * 0.8, 0.45),
                OnTertiary = FromHsl(h + 120, s * 0.8, 1.00),
                TertiaryContainer = FromHsl(h + 120, s * 0.8, 0.90),
                OnTertiaryContainer = FromHsl(h + 120, s * 0.8, 0.12),
                Error = FromHsl(0, 0.75, 0.45),
                OnError = FromHsl(0, 0.75, 1.00),
                ErrorContainer = FromHsl(0, 0.75, 0.90),
                OnErrorContainer = FromHsl(0, 0.75, 0.12),
                Background = FromHsl(h, s * 0.2, 0.98),
                OnBackground = FromHsl(h, s * 0.2, 0.12),
                Surface = FromHsl(h, s * 0.2, 1.00),
                OnSurface = FromHsl(h, s * 0.2, 0.12),
                SurfaceVariant = FromHsl(h, s * 0.3, 0.85),
                OnSurfaceVariant = FromHsl(h, s * 0.3, 0.30),
                SurfaceContainerHighest = FromHsl(h, s * 0.2, 0.92),
                SurfaceContainerHigh = FromHsl(h, s * 0.2, 0.95),
                Outline = FromHsl(h, s * 0.2, 0.45),
                OutlineVariant = FromHsl(h, s * 0.2, 0.75),
                Shadow = Color.FromScRgb(0.2f, 0, 0, 0),
            };
        }
    }

    // ARGB → HSL
    private static void ToHsl(uint argb, out double h, out double s, out double l)
    {
        byte a = (byte)((argb >> 24) & 0xff);
        byte r = (byte)((argb >> 16) & 0xff);
        byte g = (byte)((argb >> 8) & 0xff);
        byte b = (byte)(argb & 0xff);

        double rf = r / 255.0, gf = g / 255.0, bf = b / 255.0;
        double max = Math.Max(rf, Math.Max(gf, bf));
        double min = Math.Min(rf, Math.Min(gf, bf));
        double delta = max - min;

        l = (max + min) / 2.0;
        if (delta < 1e-6)
        {
            h = 0; s = 0;
            return;
        }
        s = l < 0.5 ? delta / (max + min) : delta / (2 - max - min);

        if (max == rf) h = ((gf - bf) / delta) % 6;
        else if (max == gf) h = (bf - rf) / delta + 2;
        else h = (rf - gf) / delta + 4;
        h *= 60;
        if (h < 0) h += 360;
    }

    private static Color FromHsl(double h, double s, double l)
    {
        h = ((h % 360) + 360) % 360;
        s = Math.Clamp(s, 0, 1);
        l = Math.Clamp(l, 0, 1);

        double c = (1 - Math.Abs(2 * l - 1)) * s;
        double x = c * (1 - Math.Abs((h / 60) % 2 - 1));
        double m = l - c / 2;
        double r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }

        return Color.FromScRgb(1.0f, (float)(r + m), (float)(g + m), (float)(b + m));
    }

    public static Color WithAlpha(Color c, byte alpha)
    {
        return Color.FromArgb(alpha, c.R, c.G, c.B);
    }
}
