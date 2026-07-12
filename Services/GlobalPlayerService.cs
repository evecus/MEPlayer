using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Runtime.CompilerServices;
using System.Threading;
using System.Threading.Tasks;
using MEPlayer.Models;
using MEPlayer.Player;

namespace MEPlayer.Services;

/// <summary>底部播放栏当前展示的内容类型（与 Flutter 端 MiniBarKind 一致）。</summary>
public enum MiniBarKind { None, Video, Iptv, Music }

/// <summary>音乐循环模式（与 Flutter 端 GlobalRepeatMode 一致）。</summary>
public enum GlobalRepeatMode { List, Shuffle, One }

/// <summary>单曲元数据缓存（封面/歌词），与 Flutter 端 TrackMeta 等价。</summary>
public sealed class TrackMeta
{
    public string? Title { get; }
    public string? Artist { get; }
    public byte[]? CoverBytes { get; }
    public IReadOnlyList<LrcLine> LrcLines { get; }

    public TrackMeta(string? title, string? artist, byte[]? coverBytes, IReadOnlyList<LrcLine> lrcLines)
    {
        Title = title;
        Artist = artist;
        CoverBytes = coverBytes;
        LrcLines = lrcLines;
    }
}

/// <summary>视频恢复参数。</summary>
public sealed class VideoResumeArgs
{
    public string Url { get; set; } = "";
    public string Title { get; set; } = "";
    public bool IsLocal { get; set; }
    public IReadOnlyList<VideoFile> Playlist { get; set; } = Array.Empty<VideoFile>();
    public int StartIndex { get; set; }
    public int ResumePositionMs { get; set; }
}

/// <summary>IPTV 恢复参数。</summary>
public sealed class IptvResumeArgs
{
    public string Url { get; set; } = "";
    public string ChannelName { get; set; } = "";
    public string GroupName { get; set; } = "";
    public int SourceIndex { get; set; }
}

/// <summary>
/// 全局播放状态服务（对应 Flutter 端 GlobalPlayerController）。
///
/// 职责：
/// - 音乐：真正持有底层 mpv Player 实例，音乐播放贯穿全局生命周期——
///   离开音乐播放页不会停止播放，只有切到视频/IPTV 播放或退出程序才会停止。
///   四个主页面 + 音乐播放页共用同一个 Player。
/// - 视频/IPTV：不持有播放器（这两类退出播放页即停止播放），只记录"恢复信息"，
///   供底部播放栏展示 + 点击后带着 resumePosition 重新进入播放页续播。
/// </summary>
public sealed class GlobalPlayerService : INotifyPropertyChanged, IDisposable
{
    private readonly AppSettingsService _settings;
    private MpvPlayer? _musicPlayer;
    private DateTime _lastPositionLogAt = DateTime.MinValue; // 诊断用：节流 PositionChanged 日志
    private bool _musicInitialized;

    // 音乐播放列表 & 状态
    public List<Dictionary<string, string>> MusicPlaylist { get; } = new();
    private int _musicCurrentIdx;
    private bool _musicIsPlaying;
    private bool _musicIsBuffering;
    private double _musicPositionSec;
    private double _musicDurationSec;
    private double _musicPlaySpeed = 1.0;
    private GlobalRepeatMode _musicRepeatMode = GlobalRepeatMode.List;
    private double _musicVolume = 100;
    private TrackMeta? _musicCurrentMeta;
    private int _musicCurrentLrcIdx = -1;

    // 视频 / IPTV 恢复信息
    private string _videoTitle = "";
    private double _videoPositionSec;
    private double _videoDurationSec;
    public VideoResumeArgs? VideoResume { get; private set; }

    private string _iptvChannelName = "";
    private string _iptvGroupName = "";
    public IptvResumeArgs? IptvResume { get; private set; }

    private MiniBarKind _miniBarKind = MiniBarKind.None;

    private int _playGeneration;
    private bool _readyForCompleted;
    private readonly List<int> _shuffleOrder = new();

    public GlobalPlayerService(AppSettingsService settings)
    {
        _settings = settings;
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public MiniBarKind MiniBarKind
    {
        get => _miniBarKind;
        set { if (_miniBarKind != value) { _miniBarKind = value; Notify(); } }
    }

    public MpvPlayer? MusicPlayer => _musicPlayer;

    // ── 视频状态 ──────────────────────────────────────────────────────────
    public string VideoTitle { get => _videoTitle; set { _videoTitle = value; Notify(); } }
    public double VideoPositionSec { get => _videoPositionSec; set { _videoPositionSec = value; Notify(); } }
    public double VideoDurationSec { get => _videoDurationSec; set { _videoDurationSec = value; Notify(); } }

    public void UpdateVideoState(string title, double positionSec, double durationSec, VideoResumeArgs resumeArgs)
    {
        VideoTitle = title;
        VideoPositionSec = positionSec;
        VideoDurationSec = durationSec;
        VideoResume = resumeArgs;
        MiniBarKind = MiniBarKind.Video;
    }

    public void OnVideoPageClosed()
    {
        // 保留在 video 态，底部栏继续展示最后进度，只是不再"播放中"
    }

    public void ClearVideo()
    {
        VideoResume = null;
        VideoTitle = "";
        if (MiniBarKind == MiniBarKind.Video) MiniBarKind = MiniBarKind.None;
    }

    // ── IPTV 状态 ─────────────────────────────────────────────────────────
    public string IptvChannelName { get => _iptvChannelName; set { _iptvChannelName = value; Notify(); } }
    public string IptvGroupName { get => _iptvGroupName; set { _iptvGroupName = value; Notify(); } }

    public void UpdateIptvState(string channelName, string groupName, IptvResumeArgs resumeArgs)
    {
        IptvChannelName = channelName;
        IptvGroupName = groupName;
        IptvResume = resumeArgs;
        MiniBarKind = MiniBarKind.Iptv;
    }

    public void ClearIptv()
    {
        IptvResume = null;
        IptvChannelName = "";
        if (MiniBarKind == MiniBarKind.Iptv) MiniBarKind = MiniBarKind.None;
    }

    // ══════════════════════════════════════════════════════════════════════
    // 音乐 — 真正持有播放器，贯穿全局生命周期
    // ══════════════════════════════════════════════════════════════════════

    public int MusicCurrentIdx
    {
        get => _musicCurrentIdx;
        private set { _musicCurrentIdx = value; Notify(); Notify(nameof(MusicCurrentTitle)); Notify(nameof(MusicCurrentArtist)); }
    }

    public bool MusicIsPlaying
    {
        get => _musicIsPlaying;
        private set { _musicIsPlaying = value; Notify(); }
    }

    public bool MusicIsBuffering
    {
        get => _musicIsBuffering;
        private set { _musicIsBuffering = value; Notify(); }
    }

    public double MusicPositionSec
    {
        get => _musicPositionSec;
        private set { _musicPositionSec = value; Notify(); UpdateLrcIndex(); }
    }

    public double MusicDurationSec
    {
        get => _musicDurationSec;
        private set
        {
            if (Math.Abs(_musicDurationSec - value) > 0.01)
                WriteGlobalLog($"[MusicDurationSec] {_musicDurationSec:F2} -> {value:F2}");
            _musicDurationSec = value;
            Notify();
        }
    }

    public double MusicPlaySpeed
    {
        get => _musicPlaySpeed;
        private set { _musicPlaySpeed = value; Notify(); }
    }

    public GlobalRepeatMode MusicRepeatMode
    {
        get => _musicRepeatMode;
        private set { _musicRepeatMode = value; Notify(); }
    }

    public double MusicVolume
    {
        get => _musicVolume;
        private set { _musicVolume = value; Notify(); }
    }

    public TrackMeta? MusicCurrentMeta
    {
        get => _musicCurrentMeta;
        private set { _musicCurrentMeta = value; Notify(); Notify(nameof(MusicCurrentTitle)); Notify(nameof(MusicCurrentArtist)); Notify(nameof(MusicCurrentLrcIdx)); }
    }

    public int MusicCurrentLrcIdx
    {
        get => _musicCurrentLrcIdx;
        private set { if (_musicCurrentLrcIdx != value) { _musicCurrentLrcIdx = value; Notify(); } }
    }

    public string MusicCurrentTitle
    {
        get
        {
            var meta = MusicCurrentMeta;
            if (!string.IsNullOrEmpty(meta?.Title)) return meta!.Title!;
            if (MusicPlaylist.Count == 0 || MusicCurrentIdx < 0 || MusicCurrentIdx >= MusicPlaylist.Count) return "";
            var name = MusicPlaylist[MusicCurrentIdx].TryGetValue("name", out var n) ? n : "";
            return Path.GetFileNameWithoutExtension(name);
        }
    }

    public string MusicCurrentArtist => MusicCurrentMeta?.Artist ?? "";

    /// <summary>启动服务：初始化音乐播放器（不播放任何东西）。</summary>
    public void Start()
    {
        EnsureMusicPlayer();
    }

    private void EnsureMusicPlayer()
    {
        if (_musicInitialized) return;
        _musicInitialized = true;

        WriteGlobalLog("[EnsureMusicPlayer] 开始创建音乐播放器实例");
        var mpv = new MpvPlayer();
        var opts = new[]
        {
            new KeyValuePair<string, string>("vo", "null"),       // 音乐不需要视频输出
            new KeyValuePair<string, string>("hwdec", "no"),      // 纯音频不需要硬解
            // 日志显示系统有 wasapi 和 openal 驱动。wasapi 会路由到 Senary Audio 虚拟设备
            // （无真实扬声器，初始化"成功"但无声）。openal 通常使用系统默认播放设备，
            // 能正确路由到真实扬声器。这里直接用 openal 避免虚拟设备陷阱。
            new KeyValuePair<string, string>("ao", "openal"),
            new KeyValuePair<string, string>("audio-buffer", "0.2"),
            new KeyValuePair<string, string>("gapless-audio", "yes"),
            new KeyValuePair<string, string>("keep-open", "yes"),
            new KeyValuePair<string, string>("idle", "yes"),
        };
        // 纯音频播放不需要硬件解码，强制 hwdec=no 避免任何 GPU 相关初始化开销和冲突
        try
        {
            mpv.Init(opts);
            WriteGlobalLog("[EnsureMusicPlayer] mpv.Init 成功");
        }
        catch (Exception ex)
        {
            WriteGlobalLog($"[EnsureMusicPlayer] mpv.Init 失败: {ex.Message}");
            throw;
        }

        mpv.PlayingChanged += _ => MusicIsPlaying = mpv.IsPlaying;
        mpv.BufferingChanged += b => MusicIsBuffering = b;
        mpv.PositionChanged += p => MusicPositionSec = p;
        mpv.DurationChanged += d => MusicDurationSec = d;
        mpv.VolumeChanged += v => MusicVolume = v;
        mpv.Ended += _ => OnMusicCompleted();
        // 诊断：确认事件确实从 MpvPlayer 传到了 GlobalPlayerService（排查"进度不走"问题）
        mpv.PositionChanged += p =>
        {
            if ((DateTime.Now - _lastPositionLogAt).TotalSeconds < 2) return;
            _lastPositionLogAt = DateTime.Now;
            WriteGlobalLog($"[mpv->Global] PositionChanged p={p:F2}");
        };
        mpv.DurationChanged += d => WriteGlobalLog($"[mpv->Global] DurationChanged d={d:F2}");

        _musicPlayer = mpv;
        WriteGlobalLog("[EnsureMusicPlayer] 音乐播放器创建完成");
    }

    // 【BUG 修复】这里和 MpvPlayer.WriteMpvLog 共用同一个 mpv.log 文件（全局音乐播放器
    // 也是一个独立的 MpvPlayer 实例），此前两边写日志都不带来源标识，导致排查视频播放
    // 页"进度条不走"问题时，误把这里打印的、后台音乐播放器（可能处于暂停状态）的
    // PositionChanged 日志当成了视频播放器卡住的证据。加上 [global] 前缀，
    // 和 MpvPlayer 内部按实例编号打的 [mpv#N] 前缀区分开。
    private static void WriteGlobalLog(string line)
    {
        try
        {
            var dir = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "MEPlayer");
            System.IO.Directory.CreateDirectory(dir);
            var path = System.IO.Path.Combine(dir, "mpv.log");
            var text = $"{DateTime.Now:HH:mm:ss.fff} [global] {line}{Environment.NewLine}";
            System.IO.File.AppendAllText(path, text);
        }
        catch { }
    }

    /// <summary>开始播放一份新的播放列表。</summary>
    public void PlayMusicPlaylist(List<Dictionary<string, string>> playlist, int index)
    {
        if (playlist.Count == 0) return;

        EnsureMusicPlayer();
        StopMusicForOtherPlayback(); // 这里只是把 video/iptv 状态从底部栏挤掉

        MusicPlaylist.Clear();
        foreach (var p in playlist) MusicPlaylist.Add(new Dictionary<string, string>(p));

        var clamped = Math.Clamp(index, 0, playlist.Count - 1);
        BuildShuffleOrder();
        MiniBarKind = MiniBarKind.Music;
        PlayAt(clamped);
    }

    private void PlayAt(int index)
    {
        if (MusicPlaylist.Count == 0 || _musicPlayer == null) return;
        var gen = ++_playGeneration;
        _readyForCompleted = false;
        MusicCurrentIdx = index;
        MusicCurrentMeta = null;
        MusicCurrentLrcIdx = -1;

        var path = MusicPlaylist[index].TryGetValue("path", out var p) ? p : "";
        // 诊断日志：确认 PlayAt 被调用及路径
        try
        {
            var logPath = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "MEPlayer", "mpv.log");
            System.IO.File.AppendAllText(logPath,
                $"{DateTime.Now:HH:mm:ss.fff} [GlobalPlayer] PlayAt idx={index} path={path}{Environment.NewLine}");
        }
        catch { }
        _musicPlayer.Open(path);

        // 加载元数据
        Task.Run(() => LoadMetaFor(path, gen));

        // 500ms 后标记可处理 completed 事件，避免刚 open 就被错当成完成
        Task.Delay(500).ContinueWith(_ =>
        {
            if (gen == _playGeneration) _readyForCompleted = true;
        }, TaskScheduler.Default);
    }

    private void OnMusicCompleted()
    {
        if (!_readyForCompleted) return;
        _readyForCompleted = false;
        switch (MusicRepeatMode)
        {
            case GlobalRepeatMode.One:
                PlayAt(MusicCurrentIdx);
                break;
            case GlobalRepeatMode.Shuffle:
                PlayAt(ShuffleNext(1));
                break;
            default:
                PlayAt((MusicCurrentIdx + 1) % MusicPlaylist.Count);
                break;
        }
    }

    public void MusicPrevious()
    {
        if (MusicPlaylist.Count == 0) return;
        if (MusicRepeatMode == GlobalRepeatMode.Shuffle) PlayAt(ShuffleNext(-1));
        else PlayAt((MusicCurrentIdx - 1 + MusicPlaylist.Count) % MusicPlaylist.Count);
    }

    public void MusicNext()
    {
        if (MusicPlaylist.Count == 0) return;
        if (MusicRepeatMode == GlobalRepeatMode.Shuffle) PlayAt(ShuffleNext(1));
        else PlayAt((MusicCurrentIdx + 1) % MusicPlaylist.Count);
    }

    public void MusicPlayAt(int index) => PlayAt(index);

    public void MusicTogglePlay()
    {
        if (!_musicInitialized || _musicPlayer == null) return;
        _musicPlayer.PlayOrPause();
    }

    public void MusicSeekTo(double seconds)
    {
        if (!_musicInitialized || _musicPlayer == null) return;
        // 边界保护：避免 seek 到 >= 总时长的位置直接触发 EOF（表现为"点一下进度条，
        // 歌曲直接停止播放"）。之前进度条 Value 曾因为一个独立 bug 被跳变到 1（=100%），
        // seek(1 * duration) 相当于 seek 到文件末尾，立刻触发 mpv 的 END_FILE 事件。
        // 那个 bug 本身已经修复，这里再加一层防御，即使将来出现其它导致 Value 越界的
        // 情况，也不会直接把歌曲 seek 没了。
        var dur = MusicDurationSec;
        if (dur > 0) seconds = Math.Clamp(seconds, 0, Math.Max(0, dur - 0.3));
        _musicPlayer.Seek(seconds);
    }

    public void MusicSetVolume(double v)
    {
        if (!_musicInitialized || _musicPlayer == null) return;
        _musicPlayer.SetVolume(v);
        MusicVolume = v;
    }

    public void MusicSetSpeed(double s)
    {
        if (!_musicInitialized || _musicPlayer == null) return;
        _musicPlayer.SetRate(s);
        MusicPlaySpeed = s;
    }

    public void MusicCycleRepeat()
    {
        MusicRepeatMode = (GlobalRepeatMode)(((int)MusicRepeatMode + 1) % 3);
        if (MusicRepeatMode == GlobalRepeatMode.Shuffle) BuildShuffleOrder();
    }

    private void BuildShuffleOrder()
    {
        _shuffleOrder.Clear();
        for (int i = 0; i < MusicPlaylist.Count; i++) _shuffleOrder.Add(i);
        var rng = new Random();
        // Fisher-Yates 洗牌
        for (int i = _shuffleOrder.Count - 1; i > 0; i--)
        {
            int j = rng.Next(i + 1);
            (_shuffleOrder[i], _shuffleOrder[j]) = (_shuffleOrder[j], _shuffleOrder[i]);
        }
    }

    private int ShuffleNext(int dir)
    {
        if (_shuffleOrder.Count == 0) BuildShuffleOrder();
        var pos = _shuffleOrder.IndexOf(MusicCurrentIdx);
        if (pos < 0) return _shuffleOrder[0];
        return _shuffleOrder[(pos + dir + _shuffleOrder.Count) % _shuffleOrder.Count];
    }

    private void LoadMetaFor(string filePath, int gen)
    {
        try
        {
            var meta = AudioMetadataReader.ReadFile(filePath);
            if (_playGeneration != gen) return;

            var lrcLines = !string.IsNullOrEmpty(meta.Lyrics)
                ? (IReadOnlyList<LrcLine>)LrcParser.Parse(meta.Lyrics!)
                : Array.Empty<LrcLine>();

            MusicCurrentMeta = new TrackMeta(meta.Title, meta.Artist, meta.CoverBytes, lrcLines);
            MusicCurrentLrcIdx = -1;
        }
        catch
        {
            // 忽略读取失败
        }
    }

    private void UpdateLrcIndex()
    {
        var lines = MusicCurrentMeta?.LrcLines;
        if (lines == null || lines.Count == 0) return;
        int idx = 0;
        for (int i = 0; i < lines.Count; i++)
        {
            if (lines[i].Time.TotalSeconds <= MusicPositionSec) idx = i;
            else break;
        }
        MusicCurrentLrcIdx = idx;
    }

    /// <summary>停止音乐播放（仅在开始播放视频/IPTV 时调用）。</summary>
    public void StopMusicForOtherPlayback()
    {
        if (!_musicInitialized || _musicPlayer == null) return;
        try { _musicPlayer.Pause(); } catch { }
    }

    public void Dispose()
    {
        try { _musicPlayer?.Dispose(); } catch { }
        _musicPlayer = null;
        _musicInitialized = false;
    }

    private static IEnumerable<KeyValuePair<string, string>> MergeOptions(
        IEnumerable<KeyValuePair<string, string>> a, IEnumerable<KeyValuePair<string, string>> b)
    {
        foreach (var kv in a) yield return kv;
        foreach (var kv in b) yield return kv;
    }

    private void Notify([CallerMemberName] string? name = null)
        => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name ?? ""));
}
