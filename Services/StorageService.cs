using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace MEPlayer.Services;

/// <summary>
/// 基于 SQLite 的键值存储，替代 Flutter 端的 Hive。
///
/// 实现说明：
/// - 所有键值对存放在 SQLite 数据库的 kv 表（key TEXT PRIMARY KEY, value TEXT）。
/// - value 字段保存 value 的 JSON 序列化字符串（基本类型也以 JSON 文本存储，
///   反序列化时按目标 T 类型还原）。
/// - 单一 SqliteConnection 贯穿 App 生命周期，所有操作加锁串行化，
///   并启用 WAL 模式以兼顾读性能与崩溃恢复。
/// - 保留与原 JSON 版本完全相同的公开接口（Init / Get / Set / Delete），
///   调用方无需任何改动。
/// </summary>
public sealed class StorageService : IDisposable
{
    private string _dbPath = "";
    private readonly object _lock = new();
    private SqliteConnection? _conn;
    private bool _disposed;

    private static readonly JsonSerializerOptions _opts = new()
    {
        WriteIndented = false,
    };

    public void Init(string path)
    {
        // 兼容旧调用：原 path 形如 .../settings.json，这里改为同目录下 settings.db。
        // 如果传入的已经是 .db 路径则直接使用。
        _dbPath = Path.GetExtension(path).Equals(".json", StringComparison.OrdinalIgnoreCase)
            ? Path.ChangeExtension(path, ".db")
            : path;

        lock (_lock)
        {
            try
            {
                var dir = Path.GetDirectoryName(_dbPath);
                if (!string.IsNullOrEmpty(dir) && !Directory.Exists(dir))
                    Directory.CreateDirectory(dir);

                _conn = new SqliteConnection($"Data Source={_dbPath}");
                _conn.Open();

                using (var cmd = _conn.CreateCommand())
                {
                    cmd.CommandText =
                        "PRAGMA journal_mode=WAL;" +
                        "PRAGMA synchronous=NORMAL;" +
                        "CREATE TABLE IF NOT EXISTS kv (" +
                        "  key TEXT PRIMARY KEY NOT NULL," +
                        "  value TEXT NOT NULL" +
                        ");" +
                        "CREATE INDEX IF NOT EXISTS idx_kv_key ON kv(key);";
                    cmd.ExecuteNonQuery();
                }
            }
            catch
            {
                // 初始化失败时保留 _conn=null，后续 Get/Set 会走默认值/无操作兜底，
                // 避免存储故障导致整个 App 无法启动。
                _conn = null;
            }
        }
    }

    public T Get<T>(string key, T defaultValue)
    {
        lock (_lock)
        {
            if (_conn == null) return defaultValue;
            try
            {
                using var cmd = _conn.CreateCommand();
                cmd.CommandText = "SELECT value FROM kv WHERE key = @k";
                cmd.Parameters.AddWithValue("@k", key);
                var raw = cmd.ExecuteScalar() as string;
                if (string.IsNullOrEmpty(raw)) return defaultValue;
                return JsonSerializer.Deserialize<T>(raw, _opts) ?? defaultValue;
            }
            catch
            {
                return defaultValue;
            }
        }
    }

    public void Set<T>(string key, T value)
    {
        lock (_lock)
        {
            if (_conn == null) return;
            try
            {
                var json = JsonSerializer.Serialize(value, value?.GetType() ?? typeof(T), _opts);
                using var cmd = _conn.CreateCommand();
                cmd.CommandText =
                    "INSERT INTO kv(key, value) VALUES(@k, @v) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value";
                cmd.Parameters.AddWithValue("@k", key);
                cmd.Parameters.AddWithValue("@v", json);
                cmd.ExecuteNonQuery();
            }
            catch
            {
                // 写入失败不影响运行
            }
        }
    }

    public void Delete(string key)
    {
        lock (_lock)
        {
            if (_conn == null) return;
            try
            {
                using var cmd = _conn.CreateCommand();
                cmd.CommandText = "DELETE FROM kv WHERE key = @k";
                cmd.Parameters.AddWithValue("@k", key);
                cmd.ExecuteNonQuery();
            }
            catch
            {
                // 忽略
            }
        }
    }

    public bool HasKey(string key)
    {
        lock (_lock)
        {
            if (_conn == null) return false;
            try
            {
                using var cmd = _conn.CreateCommand();
                cmd.CommandText = "SELECT 1 FROM kv WHERE key = @k LIMIT 1";
                cmd.Parameters.AddWithValue("@k", key);
                return cmd.ExecuteScalar() != null;
            }
            catch
            {
                return false;
            }
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        lock (_lock)
        {
            try { _conn?.Dispose(); } catch { }
            _conn = null;
        }
    }

    // ── 共享 key 常量（与 Flutter 端一一对应） ──────────────────────────────
    public const string KHardwareDecode = "hw_decode";
    public const string KMpvProfile = "mpv_profile";
    public const string KCompatMode = "compat_mode";
    public const string KIptvSources = "iptv_sources";
    public const string KVideoScanPaths = "video_scan_paths";
    public const string KMusicScanPaths = "music_scan_paths";
    public const string KRecentFiles = "recent_files";
    public const string KPlayerVolume = "player_volume";

    public const string KThemeMode = "theme_mode";
    public const string KSeedColor = "seed_color";

    public const string KVideoSortMode = "video_sort_mode";
    public const string KVideoThumbCache = "video_thumb_cache";

    public const string KMusicCategory = "music_category";
    public const string KMusicSongSort = "music_song_sort";
    public const string KMusicGroupSort = "music_group_sort";
    public const string KMusicLibraryCache = "music_library_cache";
}
