using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;
using System.Windows;

namespace MEPlayer;

public partial class App : Application
{
    public static new App Current => (App)Application.Current;

    public Services.StorageService Storage { get; } = new();
    public Services.AppSettingsService Settings { get; private set; } = null!;
    public Services.GlobalPlayerService Player { get; private set; } = null!;
    public Services.TrayService Tray { get; private set; } = null!;

    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        // 兜底：捕获所有未处理异常并弹窗，避免双击 exe 时"完全没反应"
        DispatcherUnhandledException += (_, args) =>
        {
            ShowFatal(args.Exception);
            args.Handled = true;
            Shutdown(1);
        };
        AppDomain.CurrentDomain.UnhandledException += (_, args) =>
        {
            ShowFatal(args.ExceptionObject as Exception);
        };
        TaskScheduler.UnobservedTaskException += (_, args) =>
        {
            ShowFatal(args.Exception);
            args.SetObserved();
        };

        // 先校验 libmpv-2.dll 是否就位，给出明确提示而不是静默崩溃
        EnsureLibMpvAvailable();

        try
        {
            StartupCore();
        }
        catch (Exception ex)
        {
            ShowFatal(ex);
            Shutdown(1);
        }
    }

    private void StartupCore()
    {
        Services.AppDataDir.EnsureCreated();

        Storage.Init(Path.Combine(Services.AppDataDir.RootPath, "settings.json"));

        Settings = new Services.AppSettingsService(Storage);
        Settings.Load();

        // 全局播放服务：先创建实例，Start() 延迟到主窗口 Show 之后，
        // 这样 MpvPlayer 构造时 SynchronizationContext.Current 已是
        // DispatcherSynchronizationContext（非 null），mpv 事件能正确回发到 UI 线程。
        Player = new Services.GlobalPlayerService(Settings);

        Tray = new Services.TrayService();
        Tray.Init();

        // 应用主题
        Themes.AppTheme.ApplyCurrent(this);
        Settings.PropertyChanged += (_, _) => Themes.AppTheme.ApplyCurrent(this);

        // 创建主窗口（不使用 StartupUri，自己实例化以便注入服务）
        var mw = new Views.MainWindow();
        mw.Show();

        // 主窗口显示后再预热 mpv（此时 Dispatcher 已运行）
        Player.Start();
    }

    /// <summary>
    /// 启动前检查 libmpv-2.dll 是否存在于 exe 同目录或进程搜索路径，
    /// 缺失则直接弹窗提示，避免后续 mpv_create 抛异常被吞掉变成"完全没反应"。
    /// </summary>
    private void EnsureLibMpvAvailable()
    {
        const string dllName = "libmpv-2.dll";
        // 先看 exe 同目录
        try
        {
            var exeDir = Path.GetDirectoryName(Environment.ProcessPath ?? typeof(App).Assembly.Location) ?? "";
            var candidate = Path.Combine(exeDir, dllName);
            if (File.Exists(candidate)) return;
        }
        catch { }

        // 再尝试让 Windows 按搜索路径加载一次
        var h = LoadLibrary(dllName);
        if (h != IntPtr.Zero)
        {
            FreeLibrary(h);
            return;
        }

        var err = Marshal.GetLastWin32Error();
        MessageBox.Show(
            $"找不到 {dllName}。\n\n" +
            "请到 https://sourceforge.net/projects/mpv-player-windows/files/libmpv/\n" +
            "下载 mpv-dev-x86_64-xxxx.7z，解压出 libmpv-2.dll，" +
            "放到和 MEPlayer.exe 同一个文件夹后再运行。\n\n" +
            $"(LoadLibrary 错误码: {err})",
            "MEPlayer 启动失败",
            MessageBoxButton.OK,
            MessageBoxImage.Error);
        Environment.Exit(2);
    }

    private static void ShowFatal(Exception? ex)
    {
        if (ex == null) return;
        var sb = new StringBuilder();
        sb.AppendLine("MEPlayer 启动时发生错误：");
        sb.AppendLine();
        // 完整展开 InnerException 链，XamlParseException 的真正原因通常在最内层
        var depth = 0;
        for (var cur = ex; cur != null; cur = cur.InnerException)
        {
            if (depth > 0) sb.AppendLine($"--- 内部异常 #{depth} ---");
            sb.AppendLine("【消息】" + cur.Message);
            sb.AppendLine("【类型】" + cur.GetType().FullName);
            sb.AppendLine("【堆栈】");
            sb.AppendLine(cur.StackTrace);
            sb.AppendLine();
            depth++;
        }
        if (ex is DllNotFoundException)
        {
            sb.AppendLine("看起来缺少某个 DLL。如果是 libmpv-2.dll，请下载放到 exe 同目录。");
            sb.AppendLine("下载地址: https://sourceforge.net/projects/mpv-player-windows/files/libmpv/");
        }

        // 同时写入日志文件，方便复制粘贴
        try
        {
            var logPath = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "MEPlayer", "startup-error.log");
            Directory.CreateDirectory(Path.GetDirectoryName(logPath)!);
            File.WriteAllText(logPath, sb.ToString(), Encoding.UTF8);
            sb.AppendLine();
            sb.AppendLine("==========================================");
            sb.AppendLine("日志已写入: " + logPath);
        }
        catch { }

        MessageBox.Show(sb.ToString(), "MEPlayer 启动失败",
            MessageBoxButton.OK, MessageBoxImage.Error);
    }

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr LoadLibrary(string lpFileName);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool FreeLibrary(IntPtr hModule);

    protected override void OnExit(ExitEventArgs e)
    {
        try { Player?.Dispose(); } catch { }
        try { Tray?.Dispose(); } catch { }
        try { Storage?.Dispose(); } catch { }
        base.OnExit(e);
    }
}
