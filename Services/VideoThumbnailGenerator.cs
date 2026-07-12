using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Media.Imaging;

namespace MEPlayer.Services;

/// <summary>
/// 视频缩略图生成器。
///
/// 实现方式：Windows Shell 的 IShellItemImageFactory（Vista 及以上系统自带，
/// 就是资源管理器给视频文件生成缩略图用的同一套机制）。系统内部走
/// Media Foundation + 缩略图缓存 + 硬件加速解码，取一帧的开销通常只有几十毫秒，
/// 比起自己起一个完整 libmpv 实例播放+seek+截图（一两秒级）快一个数量级，
/// 且不需要任何额外依赖（不需要 libmpv，也不需要第三方 NuGet 包）。
///
/// 权衡：取到的帧是系统生成缩略图时选用的帧（通常在视频靠前的位置），
/// 不能像 mpv 方案那样精确 seek 到某个时间点（如 10% 时长）。如果后续
/// 需要"精确定位到某一帧"的强需求，需要换成 Media Foundation SourceReader。
///
/// 缩略图文件以相对 appdata 根目录的相对路径形式持久化进缓存，这样整个程序
/// 文件夹被一起移动到别的磁盘/目录后，缓存依然有效。
/// </summary>
public sealed class VideoThumbnailGenerator : IDisposable
{
    private const int ThumbSize = 160;
    private const int ThumbMaxBytes = 40 * 1024;
    private const int MaxConcurrent = 2;

    private readonly SemaphoreSlim _concurrency = new(MaxConcurrent);
    private volatile bool _disposed;

    public void Dispose() => _disposed = true;

    /// <summary>生成单个视频的缩略图，返回相对 appdata 的路径（失败返回空字符串）。</summary>
    public async Task<string> GenerateAsync(string videoPath)
    {
        if (_disposed) return "";
        await AppDataDir.EnsureCreatedAsync();

        var hash = StableHash(videoPath);
        var outFile = Path.Combine(AppDataDir.ThumbsPath, hash + ".jpg");

        await _concurrency.WaitAsync();
        try
        {
            return await Task.Run(() => GenerateWithShell(videoPath, outFile));
        }
        catch
        {
            return "";
        }
        finally
        {
            _concurrency.Release();
        }
    }

    /// <summary>用 IShellItemImageFactory 取 Shell 缩略图并保存为 jpg。</summary>
    private string GenerateWithShell(string videoPath, string outFile)
    {
        if (_disposed) return "";
        if (!File.Exists(videoPath)) return "";

        IntPtr hBitmap = IntPtr.Zero;
        try
        {
            var riid = typeof(IShellItemImageFactory).GUID;
            var hr = SHCreateItemFromParsingName(videoPath, IntPtr.Zero, ref riid, out var obj);
            if (hr != 0 || obj == null) return "";

            var factory = (IShellItemImageFactory)obj;
            var size = new SIZE { cx = ThumbSize, cy = ThumbSize };
            // ResizeToFit: 按比例缩放并居中，不做裁切拉伸；IconOnly/ThumbnailOnly 可按需切换
            const int SIIGBF_RESIZETOFIT = 0x00000000;
            hr = factory.GetImage(size, SIIGBF_RESIZETOFIT, out hBitmap);
            Marshal.ReleaseComObject(factory);
            if (hr != 0 || hBitmap == IntPtr.Zero) return "";

            if (!SaveHBitmapAsJpeg(hBitmap, outFile, quality: 90)) return "";

            // 体积保险：极端情况下压缩后仍偏大，用更低 quality 重新编码一次
            if (new FileInfo(outFile).Length > ThumbMaxBytes)
            {
                SaveHBitmapAsJpeg(hBitmap, outFile, quality: 60);
            }

            return AppDataDir.ToRelative(outFile);
        }
        catch
        {
            return "";
        }
        finally
        {
            if (hBitmap != IntPtr.Zero) DeleteObject(hBitmap);
        }
    }

    /// <summary>把 GDI HBITMAP 编码保存为 JPEG 文件。</summary>
    private static bool SaveHBitmapAsJpeg(IntPtr hBitmap, string outFile, int quality)
    {
        try
        {
            var bmpSource = System.Windows.Interop.Imaging.CreateBitmapSourceFromHBitmap(
                hBitmap, IntPtr.Zero, System.Windows.Int32Rect.Empty,
                BitmapSizeOptions.FromEmptyOptions());

            var encoder = new JpegBitmapEncoder { QualityLevel = Math.Clamp(quality, 1, 100) };
            encoder.Frames.Add(BitmapFrame.Create(bmpSource));

            using var fs = new FileStream(outFile, FileMode.Create, FileAccess.Write);
            encoder.Save(fs);
            return true;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>FNV-1a 32 位哈希（与 Flutter 端 _stableHash 一致）。</summary>
    private static string StableHash(string input)
    {
        const uint fnvPrime = 0x01000193u;
        uint hash = 0x811c9dc5u;
        foreach (var b in System.Text.Encoding.UTF8.GetBytes(input))
        {
            hash ^= b;
            hash *= fnvPrime;
        }
        return hash.ToString("x8");
    }

    // ── Win32 / Shell 互操作 ────────────────────────────────────────────────

    [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = true)]
    private static extern int SHCreateItemFromParsingName(
        [MarshalAs(UnmanagedType.LPWStr)] string path,
        IntPtr pbc,
        ref Guid riid,
        [MarshalAs(UnmanagedType.Interface)] out object ppv);

    [DllImport("gdi32.dll")]
    private static extern bool DeleteObject(IntPtr hObject);

    [StructLayout(LayoutKind.Sequential)]
    private struct SIZE
    {
        public int cx;
        public int cy;
    }

    [ComImport]
    [Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b")]
    [InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItemImageFactory
    {
        [PreserveSig]
        int GetImage(
            [In, MarshalAs(UnmanagedType.Struct)] SIZE size,
            [In] int flags,
            out IntPtr phbm);
    }
}
