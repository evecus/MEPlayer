using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Interop;

namespace MEPlayer.Player;

/// <summary>
/// 承载 libmpv 视频渲染的原生子窗口宿主。
/// mpv 通过 wid 属性把画面直接绘制到这个 HWND 上。
/// </summary>
public class MpvVideoHost : HwndHost
{
    private IntPtr _hwndHost;
    private MpvPlayer? _player;
    private HwndSource? _src;
    private HwndSourceHook? _hook;

    public MpvPlayer? Player
    {
        get => _player;
        set
        {
            _player = value;
            if (_player != null && _hwndHost != IntPtr.Zero)
            {
                _player.SetWid(_hwndHost);
            }
        }
    }

    // 鼠标移动事件，供播放页自动隐藏控制条
    public event Action? MouseMoveHook;

    protected override HandleRef BuildWindowCore(HandleRef hwndParent)
    {
        // 直接用系统自带的 "STATIC" 控件作为 mpv 的渲染目标。
        // 不需要手动 RegisterClass（手动注册在 .NET 8 下容易因委托 GC、hInstance、
        // 类名冲突等原因失败）。STATIC 控件系统自带，CreateWindowEx 100% 成功。
        var hInstance = GetModuleHandle(IntPtr.Zero);
        var w = Math.Max((int)ActualWidth, 1);
        var h = Math.Max((int)ActualHeight, 1);

        _hwndHost = CreateWindowEx(
            0, "STATIC", "",
            WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN,
            0, 0, w, h,
            hwndParent.Handle, IntPtr.Zero, hInstance, IntPtr.Zero);

        if (_hwndHost == IntPtr.Zero)
        {
            var err = Marshal.GetLastWin32Error();
            throw new InvalidOperationException($"CreateWindowEx(STATIC) 失败，错误码 {err}");
        }

        // 挂载子窗口消息 hook，捕获鼠标移动（供播放页自动隐藏控制条）
        _hook = new HwndSourceHook(OnHookMsg);
        _src = HwndSource.FromHwnd(_hwndHost);
        _src?.AddHook(_hook);

        if (_player != null)
        {
            _player.SetWid(_hwndHost);
        }

        return new HandleRef(this, _hwndHost);
    }

    private IntPtr OnHookMsg(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WM_MOUSEMOVE) MouseMoveHook?.Invoke();
        return IntPtr.Zero;
    }

    protected override void DestroyWindowCore(HandleRef hwnd)
    {
        try { _src?.RemoveHook(_hook); } catch { }
        _src = null;
        _hook = null;
        DestroyWindow(hwnd.Handle);
    }

    public void InvalidateLayout()
    {
        if (_hwndHost != IntPtr.Zero)
        {
            // 强制 mpv 重新适配尺寸（mpv 通过 wid 会自动跟随窗口尺寸）
            SetWindowPos(_hwndHost, IntPtr.Zero, 0, 0,
                (int)ActualWidth, (int)ActualHeight,
                SWP_NOZORDER | SWP_NOACTIVATE);
        }
    }

    protected override void OnRenderSizeChanged(SizeChangedInfo sizeInfo)
    {
        base.OnRenderSizeChanged(sizeInfo);
        InvalidateLayout();
    }

    // ── Win32 ──────────────────────────────────────────────────────────────
    private const int WS_CHILD = 0x40000000;
    private const int WS_VISIBLE = 0x10000000;
    private const int WS_CLIPCHILDREN = 0x02000000;
    private const int WM_MOUSEMOVE = 0x0200;
    private const uint SWP_NOZORDER = 0x0004;
    private const uint SWP_NOACTIVATE = 0x0010;

    [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr CreateWindowEx(int dwExStyle, string lpClassName, string lpWindowName,
        int dwStyle, int x, int y, int nWidth, int nHeight, IntPtr hWndParent, IntPtr hMenu, IntPtr hInstance, IntPtr lpParam);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool DestroyWindow(IntPtr hwnd);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SetWindowPos(IntPtr hwnd, IntPtr hwndInsertAfter, int x, int y, int cx, int cy, uint flags);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(IntPtr lpModuleName);
}
