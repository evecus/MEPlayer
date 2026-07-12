using System;
using System.Drawing;
using System.Windows.Forms;
using Application = System.Windows.Application;

namespace MEPlayer.Services;

/// <summary>
/// 系统托盘服务（与 Flutter 端 tray_service.dart 等价）。
/// 使用 Windows Forms 的 NotifyIcon（最贴近 Windows 原生 API，
/// 不需要引入额外的 WPF 托盘包）。
/// </summary>
public sealed class TrayService : IDisposable
{
    private NotifyIcon? _notify;
    private bool _disposed;

    public void Init()
    {
        if (_notify != null) return;
        _notify = new NotifyIcon
        {
            Text = "MEPlayer",
            Visible = true,
        };

        // 使用嵌入的 app_icon.ico；找不到时退回系统图标
        try
        {
            var exeDir = AppContext.BaseDirectory;
            var iconPath = System.IO.Path.Combine(exeDir, "app_icon.ico");
            if (System.IO.File.Exists(iconPath))
                _notify.Icon = new Icon(iconPath);
            else
                _notify.Icon = SystemIcons.Application;
        }
        catch
        {
            _notify.Icon = SystemIcons.Application;
        }

        var menu = new ContextMenuStrip();
        menu.Items.Add("显示主窗口", null, (_, _) => ShowMainWindow());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("退出", null, (_, _) => ExitApp());
        _notify.ContextMenuStrip = menu;

        _notify.DoubleClick += (_, _) => ShowMainWindow();
    }

    private static void ShowMainWindow()
    {
        var mw = (System.Windows.Window?)Application.Current?.MainWindow;
        if (mw != null)
        {
            mw.Show();
            if (mw.WindowState == System.Windows.WindowState.Minimized)
                mw.WindowState = System.Windows.WindowState.Normal;
            mw.Activate();
        }
    }

    private static void ExitApp()
    {
        if (Application.Current != null)
        {
            foreach (System.Windows.Window w in Application.Current.Windows)
                w.Close();
            Application.Current.Shutdown();
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try
        {
            if (_notify != null)
            {
                _notify.Visible = false;
                _notify.Dispose();
            }
        }
        catch { }
    }
}
