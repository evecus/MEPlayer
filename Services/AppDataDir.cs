using System;
using System.IO;

namespace MEPlayer.Services;

/// <summary>
/// 程序所在目录下的 appdata 文件夹路径管理。
/// 与 Flutter 端 data_dir.dart 完全一致：appdata 放在 exe 同级，
/// 避免与 Flutter 打包的 data 目录冲突。
/// </summary>
public static class AppDataDir
{
    private static string? _root;

    /// <summary>程序同级的 appdata 文件夹。</summary>
    public static string RootPath => _root ??= Path.Combine(
        AppContext.BaseDirectory, "appdata");

    /// <summary>appdata/thumbnails 子文件夹。</summary>
    public static string ThumbsDir => Path.Combine(RootPath, "thumbnails");

    /// <summary>appdata/thumbnails 子文件夹的绝对路径别名（与 ThumbsDir 等价）。</summary>
    public static string ThumbsPath => ThumbsDir;

    /// <summary>appdata/covers 子文件夹（历史遗留，新代码不写入）。</summary>
    public static string CoversDir => Path.Combine(RootPath, "covers");

    public static void EnsureCreated()
    {
        try
        {
            if (!Directory.Exists(RootPath)) Directory.CreateDirectory(RootPath);
            if (!Directory.Exists(ThumbsDir)) Directory.CreateDirectory(ThumbsDir);
            if (!Directory.Exists(CoversDir)) Directory.CreateDirectory(CoversDir);
        }
        catch { }
    }

    /// <summary>异步版本的 EnsureCreated（占位，等价同步实现）。</summary>
    public static System.Threading.Tasks.Task EnsureCreatedAsync()
    {
        EnsureCreated();
        return System.Threading.Tasks.Task.CompletedTask;
    }

    /// <summary>绝对路径 → 相对于 appdata 根目录的相对路径（用于持久化）。</summary>
    public static string ToRelative(string absolutePath)
    {
        try
        {
            var rel = Path.GetRelativePath(RootPath, absolutePath);
            if (Path.IsPathRooted(rel)) return absolutePath;
            return rel;
        }
        catch { return absolutePath; }
    }

    /// <summary>相对路径 → 当前环境可用的绝对路径。</summary>
    public static string ToAbsolute(string stored)
    {
        if (string.IsNullOrEmpty(stored)) return stored;
        if (Path.IsPathRooted(stored)) return stored;
        return Path.Combine(RootPath, stored);
    }
}
