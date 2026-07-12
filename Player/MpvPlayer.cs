using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Threading;
using System.Threading.Tasks;
using MEPlayer.Player;

namespace MEPlayer.Player;

/// <summary>
/// 高级 libmpv 播放器封装。一个实例对应一个 mpv_handle。
/// 负责事件循环、属性观察、状态暴露给上层。
/// </summary>
public sealed class MpvPlayer : IDisposable
{
    private IntPtr _mpv;
    private Thread? _eventThread;
    private volatile bool _running;
    private readonly LibMpv.MpvWakeupCallback _wakeupCb;

    // 【BUG 修复】WriteMpvLog 此前是 static 方法，写入固定路径的 mpv.log，
    // 但 MEPlayer 进程中可以同时存在多个 MpvPlayer 实例（例如视频页面的 _player
    // 和 GlobalPlayerService 里常驻的 _musicPlayer）。二者共用同一个日志文件，
    // 且互不知晓对方的存在，导致日志被交叉写入、无法区分是哪个实例产生的。
    // 之前排查"切换视频后进度条不走"问题时，日志里持续出现的
    // "time-pos ... pos=120.397" 其实并不是视频播放器卡住了，而是后台仍然
    // 存活、处于暂停状态的音乐播放器实例在不断轮询并打印它自己一直不变的
    // （暂停中的）播放位置——两个实例的轮询日志按时间顺序交织在一起，
    // 看起来就像"进度永远不变"。给每个实例分配一个短标识并打到每行日志里，
    // 方便区分到底是哪个 mpv 实例、哪个文件的轮询结果。
    private static int _instanceCounter;
    private readonly int _instanceId = System.Threading.Interlocked.Increment(ref _instanceCounter);

    // 【兜底】部分视频文件容器缺少 timescale（见 mpv.log 中的
    // "stream 0, timescale not set" 警告），会导致 mpv 认为播放时间"未知"，
    // 此时 time-pos / playback-time 等属性会返回 MPV_ERROR_PROPERTY_UNAVAILABLE(-10)
    // ——这是 mpv 自 0.12.0 起的既定行为（见官方 interface-changes.rst），不是 bug。
    // 画面和音频依然能正常播放（走的是独立的渲染/音频管线），只是没法从 mpv 侧
    // 精确读到播放位置。这种情况下用挂钟时间估算播放进度作为兜底，虽然不如
    // mpv 原生 time-pos 精确（不受 seek 精确结果、缓冲/卡顿影响），但足以让
    // 进度条和时间显示正常工作，比一直卡在 0:00 好得多。
    private readonly System.Diagnostics.Stopwatch _fallbackClock = new();
    private double _fallbackBaseSec; // 估算基准点对应的播放位置（秒）
    private bool _timePosUnavailable; // 最近一次轮询 time-pos 是否失败（-10）

    // 状态字段（由事件线程更新，UI 线程读取）
    public bool IsPlaying { get; private set; }
    public bool IsBuffering { get; private set; }
    public bool IsPaused { get; private set; }
    public double PositionSec { get; private set; }
    public double DurationSec { get; private set; }
    public double Volume { get; private set; } = 100;
    public double Rate { get; private set; } = 1.0;
    public bool IsEnded { get; private set; }

    // 事件（UI 线程订阅，在 UI 线程上回发）
    public event Action<bool>? PlayingChanged;
    public event Action<bool>? BufferingChanged;
    public event Action<double>? PositionChanged;
    public event Action<double>? DurationChanged;
    public event Action<double>? VolumeChanged;
    public event Action<bool>? Ended;
    public event Action? FileLoaded;

    private readonly System.Windows.Threading.Dispatcher? _uiDispatcher;

    public IntPtr Handle => _mpv;
    public bool IsValid => _mpv != IntPtr.Zero;

    public MpvPlayer()
    {
        // 用 Application.Current.Dispatcher 确保一定是 UI 线程的 Dispatcher。
        // Dispatcher.CurrentDispatcher 在非 UI 线程调用时会返回该线程的 Dispatcher（非 UI 线程），
        // 导致事件回发到错误线程。Application.Current.Dispatcher 始终指向主 UI 线程。
        _uiDispatcher = System.Windows.Application.Current?.Dispatcher
                        ?? System.Windows.Threading.Dispatcher.CurrentDispatcher;
        _wakeupCb = OnWakeup;
        _mpv = LibMpv.mpv_create();
        if (_mpv == IntPtr.Zero)
            throw new InvalidOperationException("mpv_create 失败，请确认 libmpv-2.dll 存在且位数匹配。");
    }

    /// <summary>
    /// 初始化 mpv。可传入额外的 option（key/value），在 mpv_initialize 之前设置。
    /// </summary>
    public void Init(IEnumerable<KeyValuePair<string, string>>? options = null)
    {
        // 默认视频输出用 gpu；音频自动
        LibMpv.mpv_set_option_string(_mpv, "vo", "gpu");
        LibMpv.mpv_set_option_string(_mpv, "hwdec", "auto-safe");
        LibMpv.mpv_set_option_string(_mpv, "keep-open", "yes"); // 播完不自动清空，由上层决定下一步
        LibMpv.mpv_set_option_string(_mpv, "idle", "yes");
        LibMpv.mpv_set_option_string(_mpv, "input-default-bindings", "yes");
        LibMpv.mpv_set_option_string(_mpv, "input-vo-keyboard", "no");
        LibMpv.mpv_set_option_string(_mpv, "osc", "no");

        if (options != null)
        {
            foreach (var kv in options)
            {
                int r = LibMpv.mpv_set_option_string(_mpv, kv.Key, kv.Value);
                if (r < 0)
                    WriteMpvLog($"[init] mpv_set_option_string({kv.Key}={kv.Value}) FAILED err={r}");
            }
        }

        // 请求 mpv 的错误/警告日志，便于诊断加载失败问题
        LibMpv.mpv_request_log_messages(_mpv, "warn");

        if (LibMpv.mpv_initialize(_mpv) < 0)
            throw new InvalidOperationException("mpv_initialize 失败。");

        // 诊断：记录 mpv 版本和可用的音频驱动列表
        try
        {
            var ver = LibMpv.mpv_client_api_version();
            WriteMpvLog($"[init] mpv API version={ver >> 16}.{ver & 0xFFFF}");
            // 读取 audio-device-list 帮助诊断音频输出问题
            var aoList = GetPropertyString("audio-device-list");
            WriteMpvLog($"[init] audio-device-list={aoList ?? "(null)"}");
            var aoProp = GetPropertyString("ao");
            WriteMpvLog($"[init] active ao={aoProp ?? "(null)"}");
        }
        catch { }

        // 观察属性
        LibMpv.mpv_observe_property(_mpv, 1, "pause", LibMpv.MPV_FORMAT_FLAG);
        LibMpv.mpv_observe_property(_mpv, 2, "time-pos", LibMpv.MPV_FORMAT_DOUBLE);
        LibMpv.mpv_observe_property(_mpv, 3, "duration", LibMpv.MPV_FORMAT_DOUBLE);
        LibMpv.mpv_observe_property(_mpv, 4, "volume", LibMpv.MPV_FORMAT_DOUBLE);
        LibMpv.mpv_observe_property(_mpv, 5, "paused-for-cache", LibMpv.MPV_FORMAT_FLAG);
        LibMpv.mpv_observe_property(_mpv, 6, "core-idle", LibMpv.MPV_FORMAT_FLAG);

        LibMpv.mpv_set_wakeup_callback(_mpv, _wakeupCb, IntPtr.Zero);

        _running = true;
        _eventThread = new Thread(EventLoop) { IsBackground = true, Name = "mpv-event" };
        _eventThread.Start();
    }

    private void OnWakeup(IntPtr ctx)
    {
        // 唤醒由事件线程处理，这里无需做事
    }

    // 【BUG 修复】mpv_wait_event 在超时（无新事件）时依然会返回一个有效的
    // 结构体指针（event_id == MPV_EVENT_NONE），而不是 NULL/IntPtr.Zero。
    // 之前的代码把 "evPtr == IntPtr.Zero" 当作退出条件（break），导致事件循环
    // 线程在启动后很快就被误判退出——mpv 核心仍在正常解码播放（所以能听到声音），
    // 但 C# 侧从此再收不到 time-pos / duration 等属性变更事件，表现为：
    // 进度条不走、时间一直是 0:00、歌词不滚动（歌词高亮依赖 MusicPositionSec）。
    // 视频播放页面复用同一个 MpvPlayer/EventLoop，因此现象一致。
    // 修复：只有真正调用 Dispose（_running=false）或收到 mpv 的
    // MPV_EVENT_SHUTDOWN 事件时才退出循环，超时返回时继续下一轮 wait。
    // 【BUG 修复 2】libmpv 官方文档明确说明：属性变更通知会被"合并"（coalesced），
    // 且逐帧变化的属性（如 time-pos）"不保证每次变化都发送通知"，这取决于具体属性，
    // 在 mpv 的多个历史版本上都出现过 time-pos/duration 的 PROPERTY_CHANGE 事件不触发
    // 或触发不稳定的已知问题（见 mpv-player/mpv#4195、#7122）。
    // 因此这里不再完全依赖被动的 mpv_observe_property 通知，而是在事件循环每次
    // "空闲"（mpv_wait_event 超时返回 MPV_EVENT_NONE）时，主动用 mpv_get_property
    // 轮询一次 time-pos / duration 作为兜底，双保险确保进度条一定会刷新。
    // 轮询间隔即事件循环的 wait 超时时间（0.1s），足够流畅又不会造成明显开销。
    private double _lastPolledPosition = double.NaN;
    private double _lastPolledDuration = double.NaN;

    private void PollPositionAndDuration()
    {
        if (_mpv == IntPtr.Zero) return;

        double pos = 0;
        int rp = LibMpv.mpv_get_property(_mpv, "time-pos", MpvFormat.Double, ref pos);
        // 诊断：记录每次轮询 time-pos 的返回码和原始值，无论是否变化，
        // 节流写日志（沿用 LogPropThrottled 的 2 秒节流），用于排查视频页面
        // "duration 能刷新但 time-pos 不刷新" 的问题——目的是看清楚到底是
        // mpv_get_property 调用失败（rp<0），还是读到的值一直没变化。
        LogPropThrottled($"[poll-raw] time-pos rp={rp} pos={pos:F3} lastPolled={_lastPolledPosition:F3}");

        double effectivePos;
        if (rp >= 0)
        {
            // mpv 能正常给出 time-pos：这是最准确的来源，直接使用，
            // 并同步校准挂钟估算的基准点（防止之后 time-pos 变得不可用时，
            // 估算值和真实值脱节太多）。
            effectivePos = pos;
            _timePosUnavailable = false;
            _fallbackBaseSec = pos;
            _fallbackClock.Restart();
        }
        else
        {
            // 【兜底】time-pos 不可用（通常是 -10 PROPERTY_UNAVAILABLE，
            // 常见于容器缺少 timescale 的视频）。只有在真正播放中（未暂停、
            // 未缓冲）时才用挂钟时间推进估算位置，否则保持基准点不变，
            // 避免暂停/缓冲期间进度条虚假前进。
            if (!_timePosUnavailable)
            {
                // 第一次从"可用"变为"不可用"，以当前已知的最新位置为估算起点
                _fallbackBaseSec = double.IsNaN(_lastPolledPosition) ? 0 : _lastPolledPosition;
                _fallbackClock.Restart();
                _timePosUnavailable = true;
            }
            if (IsPlaying && !IsPaused && !IsBuffering)
            {
                effectivePos = _fallbackBaseSec + _fallbackClock.Elapsed.TotalSeconds * Math.Max(Rate, 0.01);
            }
            else
            {
                effectivePos = _fallbackBaseSec;
            }
        }

        if (double.IsNaN(_lastPolledPosition) || Math.Abs(effectivePos - _lastPolledPosition) > 0.001)
        {
            _lastPolledPosition = effectivePos;
            PositionSec = effectivePos;
            LogPropThrottled($"[poll] time-pos value={effectivePos:F2} (fallback={_timePosUnavailable})");
            Post(() => PositionChanged?.Invoke(PositionSec));
        }

        double dur = 0;
        int rd = LibMpv.mpv_get_property(_mpv, "duration", MpvFormat.Double, ref dur);
        if (rd >= 0 && (double.IsNaN(_lastPolledDuration) || Math.Abs(dur - _lastPolledDuration) > 0.001))
        {
            _lastPolledDuration = dur;
            DurationSec = dur;
            WriteMpvLog($"[poll] duration value={dur:F2}");
            Post(() => DurationChanged?.Invoke(DurationSec));
        }
    }

    private void EventLoop()
    {
        while (_running && _mpv != IntPtr.Zero)
        {
            var evPtr = LibMpv.mpv_wait_event(_mpv, 0.1);
            if (evPtr == IntPtr.Zero)
            {
                // 理论上不会发生，安全起见跳过而非退出线程
                continue;
            }
            var ev = Marshal.PtrToStructure<LibMpv.MpvEvent>(evPtr);
            if (ev.event_id == LibMpv.MPV_EVENT_NONE)
            {
                // 事件队列已空闲：主动轮询一次 time-pos/duration 作为兜底
                PollPositionAndDuration();
                continue;
            }
            HandleEvent(ev);
        }
    }

    private void HandleEvent(LibMpv.MpvEvent ev)
    {
        switch (ev.event_id)
        {
            case LibMpv.MPV_EVENT_PROPERTY_CHANGE:
                if (ev.data != IntPtr.Zero)
                {
                    var prop = Marshal.PtrToStructure<LibMpv.MpvEventProperty>(ev.data);
                    // mpv 返回的属性名是 UTF-8 编码（ASCII 字符不受影响，但统一用 UTF-8 解码更安全）
                    var name = PtrToUtf8String(prop.name) ?? "";
                    switch (name)
                    {
                        case "pause":
                            IsPaused = ReadFlag(prop);
                            IsPlaying = !IsPaused && !IsBuffering;
                            Post(() => PlayingChanged?.Invoke(IsPlaying));
                            break;
                        case "time-pos":
                            PositionSec = ReadDouble(prop);
                            LogPropThrottled($"[prop] time-pos format={prop.format} value={PositionSec:F2}");
                            Post(() => PositionChanged?.Invoke(PositionSec));
                            break;
                        case "duration":
                            DurationSec = ReadDouble(prop);
                            WriteMpvLog($"[prop] duration format={prop.format} value={DurationSec:F2}");
                            Post(() => DurationChanged?.Invoke(DurationSec));
                            break;
                        case "volume":
                            Volume = ReadDouble(prop);
                            Post(() => VolumeChanged?.Invoke(Volume));
                            break;
                        case "paused-for-cache":
                        case "core-idle":
                            {
                                var cache = name == "paused-for-cache";
                                var flag = ReadFlag(prop);
                                if (cache) IsBuffering = flag;
                                else IsBuffering = flag && !IsPaused;
                                Post(() => BufferingChanged?.Invoke(IsBuffering));
                                // IsPlaying 依赖 IsBuffering，缓冲状态变化时也要重新计算并
                                // 上报，否则播放/暂停按钮图标可能在缓冲结束后没有及时刷新。
                                IsPlaying = !IsPaused && !IsBuffering;
                                Post(() => PlayingChanged?.Invoke(IsPlaying));
                            }
                            break;
                    }
                }
                break;
            case LibMpv.MPV_EVENT_END_FILE:
                if (ev.data != IntPtr.Zero)
                {
                    var ef = Marshal.PtrToStructure<LibMpv.MpvEventEndFile>(ev.data);
                    // 记录所有 end-file 原因用于诊断（EOF=0, STOP=2, ERROR=4, REDIRECT=5）
                    System.Diagnostics.Debug.WriteLine(
                        $"[mpv] END_FILE reason={ef.reason} error={ef.error}");
                    if (ef.reason == LibMpv.MPV_END_FILE_REASON_EOF)
                    {
                        Post(() => Ended?.Invoke(true));
                    }
                }
                break;
            case LibMpv.MPV_EVENT_FILE_LOADED:
                System.Diagnostics.Debug.WriteLine("[mpv] FILE_LOADED");
                Post(() => FileLoaded?.Invoke());
                break;
            case LibMpv.MPV_EVENT_LOG_MESSAGE:
                if (ev.data != IntPtr.Zero)
                {
                    try
                    {
                        var log = Marshal.PtrToStructure<LibMpv.MpvEventLogMessage>(ev.data);
                        var prefix = PtrToUtf8String(log.prefix) ?? "";
                        var level = PtrToUtf8String(log.level) ?? "";
                        var text = PtrToUtf8String(log.text) ?? "";
                        WriteMpvLog($"{prefix} [{level}] {text.TrimEnd()}");
                    }
                    catch { }
                }
                break;
            case LibMpv.MPV_EVENT_SHUTDOWN:
                _running = false;
                break;
        }
    }

    private static bool ReadFlag(LibMpv.MpvEventProperty prop)
    {
        if (prop.format != MpvFormat.Flag || prop.data == IntPtr.Zero) return false;
        return Marshal.ReadByte(prop.data) != 0;
    }

    private static double ReadDouble(LibMpv.MpvEventProperty prop)
    {
        if (prop.format != MpvFormat.Double || prop.data == IntPtr.Zero) return 0;
        return Marshal.PtrToStructure<double>(prop.data);
    }

    /// <summary>从非托管内存读取 UTF-8 null 结尾字符串。</summary>
    private static string? PtrToUtf8String(IntPtr p)
    {
        if (p == IntPtr.Zero) return null;
        int len = 0;
        while (Marshal.ReadByte(p, len) != 0) len++;
        var bytes = new byte[len];
        Marshal.Copy(p, bytes, 0, len);
        return System.Text.Encoding.UTF8.GetString(bytes);
    }

    private void Post(Action a)
    {
        if (_uiDispatcher != null) _uiDispatcher.BeginInvoke(a);
        else a();
    }

    /// <summary>把 mpv 日志写入 %LocalAppData%\MEPlayer\mpv.log，用于诊断播放失败。
    /// 【BUG 修复】原先是 static 方法，多个 MpvPlayer 实例（视频播放器、后台音乐播放器）
    /// 共用同一个日志文件却不带任何实例标识，导致日志交叉、无法区分是谁打的日志——
    /// 排查"进度条不走"问题时误把后台暂停中的音乐播放器的日志当成了视频播放器卡住的证据。
    /// 现在改为实例方法，每行前缀加上 [mpv#N] 实例编号。</summary>
    private void WriteMpvLog(string line)
    {
        try
        {
            var dir = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "MEPlayer");
            System.IO.Directory.CreateDirectory(dir);
            var path = System.IO.Path.Combine(dir, "mpv.log");
            var text = $"{DateTime.Now:HH:mm:ss.fff} [mpv#{_instanceId}] {line}{Environment.NewLine}";
            System.IO.File.AppendAllText(path, text);
        }
        catch { }
    }

    // ── 诊断：time-pos 更新频率很高（每 tick 都会触发），节流写日志避免刷屏 ──
    private DateTime _lastPropLogAt = DateTime.MinValue;
    private void LogPropThrottled(string line)
    {
        var now = DateTime.Now;
        if ((now - _lastPropLogAt).TotalSeconds < 2) return;
        _lastPropLogAt = now;
        WriteMpvLog(line);
    }

    // ── 控制方法 ──────────────────────────────────────────────────────────

    public void SetWid(IntPtr hwnd)
    {
        // 把 mpv 视频渲染嵌入到指定窗口句柄
        long wid = hwnd.ToInt64();
        LibMpv.mpv_set_property(_mpv, "wid", MpvFormat.Int64, ref wid);
    }

    public void Open(string url)
    {
        IsEnded = false;

        // 【BUG 修复】切换播放列表中的另一个文件时（VideoPlayerPage.PlayIndex 之类的
        // 场景，同一个 MpvPlayer 实例上连续多次调用 Open），此前 PositionSec/DurationSec
        // 这两个字段只在收到 mpv 的属性变更事件/轮询结果时才会被刷新。新文件刚 loadfile
        // 之后、mpv 真正把 time-pos/duration 更新到新值之前的这段时间里
        // （可能有几十到几百毫秒，网络流或大文件更久），这两个字段以及订阅方（进度条 UI）
        // 拿到的仍然是上一个文件的旧值——表现为"点了播放列表里的另一个视频，进度条和
        // 总时长完全不变，跟没切换一样"。根本原因是新文件加载完成前存在一段"真空期"，
        // 期间没有任何人主动把 UI 状态清零。这里在下发 loadfile 命令后立即把本地缓存的
        // 位置/时长清零并同步（而不是等待下一次事件循环）派发一次 PositionChanged(0)/
        // DurationChanged(0)，让 UI 立刻反映"新文件、从头开始"，而不是继续显示旧文件的
        // 进度条位置和总时长，之后再由正常的属性变更事件/轮询刷新为新文件的真实值。
        PositionSec = 0;
        DurationSec = 0;
        Post(() => PositionChanged?.Invoke(0));
        Post(() => DurationChanged?.Invoke(0));

        WriteMpvLog($"[Open] loadfile url={url}");
        int err = Command("loadfile", url);
        WriteMpvLog($"[Open] loadfile returned err={err} (0=OK, <0=error)");

        // 修复"播完自动播下一个/切换失败"：keep-open=yes 会在上一个文件播放结束时
        // 把 pause 设为 yes 并停留在最后一帧。loadfile 虽然会加载新文件，但 mpv 不一定会
        // 自动把 pause 复位为 no，导致新文件加载后静默停在暂停状态（表现为"播完就停了，
        // 没有继续播放下一个"）。这里显式复位本地缓存的暂停状态并强制取消暂停，
        // 确保每次 Open 都能真正开始播放。
        IsPaused = false;
        SetPropertyString("pause", "no");

        // 重置 time-pos 兜底估算状态：新文件从 0 开始，避免沿用上一个文件的估算基准
        _lastPolledPosition = double.NaN;
        _lastPolledDuration = double.NaN;
        _timePosUnavailable = false;
        _fallbackBaseSec = 0;
        _fallbackClock.Restart();
    }

    /// <summary>切换播放/暂停。用 mpv 的 "cycle pause" 命令而不是手动反转本地缓存的
    /// IsPaused 标志——本地缓存由异步属性变更事件更新，可能存在短暂滞后，或者在极端情况下
    /// 与 mpv 真实状态不同步，导致点击暂停后按钮图标不切换。cycle 命令由 mpv 自己根据
    /// 当前真实状态取反，不依赖客户端缓存，更可靠。</summary>
    public void PlayOrPause() => Command("cycle", "pause");
    public void Pause() => SetPropertyString("pause", "yes");
    public void Play() => SetPropertyString("pause", "no");

    public void Seek(double seconds)
    {
        Command("seek", seconds.ToString("F3"), "absolute");

        // 同步更新 fallback 估算基准：如果当前正处于 time-pos 不可用状态，
        // seek 之后应该立即以目标位置为新的估算起点，而不是继续用旧位置累加，
        // 否则用户拖动进度条后，估算值还是从旧位置开始走，会显得"跳不过去"。
        _fallbackBaseSec = seconds;
        _fallbackClock.Restart();
        _lastPolledPosition = double.NaN; // 强制下一次轮询立即触发一次 PositionChanged
    }

    public void SetVolume(double v)
    {
        var d = v;
        LibMpv.mpv_set_property(_mpv, "volume", MpvFormat.Double, ref d);
    }

    public void SetRate(double r)
    {
        var d = r;
        LibMpv.mpv_set_property(_mpv, "speed", MpvFormat.Double, ref d);
        Rate = r;
    }

    public void SetPropertyString(string name, string value)
    {
        LibMpv.mpv_set_property_string(_mpv, name, value);
    }

    public string? GetPropertyString(string name)
    {
        return LibMpv.PtrToStringAnsiSafe(LibMpv.mpv_get_property_string(_mpv, name));
    }

    public int Command(params string[] args)
    {
        // 构造 null 结尾的 char* 数组。mpv 要求 UTF-8 编码，不能用 StringToHGlobalAnsi
        // （ANSI 代码页编码中文路径会乱码，mpv 找不到文件）
        var ptrs = new IntPtr[args.Length + 1];
        for (int i = 0; i < args.Length; i++)
            ptrs[i] = Utf8StringToHGlobal(args[i]);
        ptrs[args.Length] = IntPtr.Zero;
        try
        {
            return LibMpv.mpv_command(_mpv, ptrs);
        }
        finally
        {
            for (int i = 0; i < args.Length; i++)
                if (ptrs[i] != IntPtr.Zero) Marshal.FreeHGlobal(ptrs[i]);
        }
    }

    /// <summary>把 .NET 字符串按 UTF-8 编码分配到非托管内存（含 null 结尾）。</summary>
    private static IntPtr Utf8StringToHGlobal(string s)
    {
        if (s == null) return IntPtr.Zero;
        var bytes = System.Text.Encoding.UTF8.GetBytes(s);
        var ptr = Marshal.AllocHGlobal(bytes.Length + 1);
        Marshal.Copy(bytes, 0, ptr, bytes.Length);
        Marshal.WriteByte(ptr, bytes.Length, 0);
        return ptr;
    }

    public void Stop()
    {
        Command("stop");
    }

    public void Dispose()
    {
        _running = false;
        try { if (_mpv != IntPtr.Zero) LibMpv.mpv_wakeup(_mpv); } catch { }
        _eventThread?.Join(1000);
        if (_mpv != IntPtr.Zero)
        {
            LibMpv.mpv_terminate_destroy(_mpv);
            _mpv = IntPtr.Zero;
        }
    }
}
