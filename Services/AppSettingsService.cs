using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace MEPlayer.Services;

/// <summary>全局设置服务（替代 Flutter 端 AppSettingsController + BaseSettingsController）。</summary>
public sealed class AppSettingsService : INotifyPropertyChanged
{
    private readonly StorageService _storage;

    // ── 播放 ──────────────────────────────────────────────────────────────
    public bool HardwareDecode { get; set; } = true;
    public string MpvProfile { get; set; } = "balanced";

    // ── 媒体扫描路径 ──────────────────────────────────────────────────────
    public ObservableCollection<string> VideoScanPaths { get; } = new();
    public ObservableCollection<string> MusicScanPaths { get; } = new();

    // ── IPTV 源 ───────────────────────────────────────────────────────────
    public ObservableCollection<Dictionary<string, string>> IptvSources { get; } = new();

    // ── 最近文件 ──────────────────────────────────────────────────────────
    public ObservableCollection<string> RecentFiles { get; } = new();

    // ── Windows 专属 ──────────────────────────────────────────────────────
    public int ThemeMode { get; set; } = 0;       // 0=system 1=light 2=dark
    public uint SeedColor { get; set; } = 0xFF3498DB;

    public AppSettingsService(StorageService storage)
    {
        _storage = storage;
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public void Load()
    {
        HardwareDecode = _storage.Get(StorageService.KHardwareDecode, true);
        MpvProfile = _storage.Get(StorageService.KMpvProfile, "balanced");

        ThemeMode = _storage.Get(StorageService.KThemeMode, 0);
        SeedColor = _storage.Get(StorageService.KSeedColor, 0xFF3498DBu);

        VideoScanPaths.Clear();
        foreach (var p in _storage.Get(StorageService.KVideoScanPaths, new List<string>()))
            VideoScanPaths.Add(p);

        MusicScanPaths.Clear();
        foreach (var p in _storage.Get(StorageService.KMusicScanPaths, new List<string>()))
            MusicScanPaths.Add(p);

        IptvSources.Clear();
        var rawSources = _storage.Get(StorageService.KIptvSources, new List<Dictionary<string, string>>());
        foreach (var s in rawSources)
            IptvSources.Add(new Dictionary<string, string>(s));

        RecentFiles.Clear();
        foreach (var f in _storage.Get(StorageService.KRecentFiles, new List<string>()))
            RecentFiles.Add(f);
    }

    // ── 设置项 ─────────────────────────────────────────────────────────────
    public void SetHardwareDecode(bool v) { HardwareDecode = v; _storage.Set(StorageService.KHardwareDecode, v); Notify(); }
    public void SetMpvProfile(string v) { MpvProfile = v; _storage.Set(StorageService.KMpvProfile, v); Notify(); }
    public void SetThemeMode(int v) { ThemeMode = v; _storage.Set(StorageService.KThemeMode, v); Notify(); }
    public void SetSeedColor(uint v) { SeedColor = v; _storage.Set(StorageService.KSeedColor, v); Notify(); }

    // ── 扫描路径 ───────────────────────────────────────────────────────────
    public void AddVideoScanPath(string path)
    {
        if (!VideoScanPaths.Contains(path))
        {
            VideoScanPaths.Add(path);
            SaveVideoScanPaths();
        }
    }

    public void RemoveVideoScanPath(string path)
    {
        if (VideoScanPaths.Remove(path)) SaveVideoScanPaths();
    }

    private void SaveVideoScanPaths() => _storage.Set(StorageService.KVideoScanPaths, new List<string>(VideoScanPaths));

    public void AddMusicScanPath(string path)
    {
        if (!MusicScanPaths.Contains(path))
        {
            MusicScanPaths.Add(path);
            SaveMusicScanPaths();
        }
    }

    public void RemoveMusicScanPath(string path)
    {
        if (MusicScanPaths.Remove(path)) SaveMusicScanPaths();
    }

    private void SaveMusicScanPaths() => _storage.Set(StorageService.KMusicScanPaths, new List<string>(MusicScanPaths));

    // ── IPTV 源 ────────────────────────────────────────────────────────────
    public void AddIptvSource(Dictionary<string, string> src) { IptvSources.Add(src); SaveIptvSources(); }
    public void UpdateIptvSource(int idx, Dictionary<string, string> src) { IptvSources[idx] = src; SaveIptvSources(); }
    public void RemoveIptvSource(int idx) { IptvSources.RemoveAt(idx); SaveIptvSources(); }

    private void SaveIptvSources()
    {
        var list = new List<Dictionary<string, string>>();
        foreach (var s in IptvSources) list.Add(new Dictionary<string, string>(s));
        _storage.Set(StorageService.KIptvSources, list);
    }

    // ── 最近文件 ───────────────────────────────────────────────────────────
    public void AddRecentFile(string path)
    {
        RecentFiles.Remove(path);
        RecentFiles.Insert(0, path);
        while (RecentFiles.Count > 50) RecentFiles.RemoveAt(RecentFiles.Count - 1);
        _storage.Set(StorageService.KRecentFiles, new List<string>(RecentFiles));
    }

    private void Notify([CallerMemberName] string? name = null) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name ?? ""));
}
