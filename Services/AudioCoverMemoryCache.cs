using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;

namespace MEPlayer.Services;

/// <summary>
/// 音乐封面的"内存态、分批增量加载"策略（与 Flutter 端 audio_cover_memory_cache.dart 完全一致）。
///
/// 封面直接嵌在音乐文件 ID3/FLAC/OGG 标签里，读取成本很低，没必要落盘缓存。
/// 加载节奏：第一批读取当前列表的前 batchSize(默认 200)首；用户往下滚动到还没
/// 读取封面的位置时，再读取下一批 batchSize 首（200~400，400~600……），
/// 每次都是整批增量，不是逐条懒加载。
///
/// 切换分类/排序方式后"前 200"的定义会跟着变化，因此每次分类或排序变化都
/// 需要调用 Reset() 重新从当前列表顺序的开头开始计算。
/// </summary>
public sealed class AudioCoverMemoryCache<T> : IDisposable
{
    public int BatchSize { get; }
    private readonly Func<T, string> _pathOf;
    private readonly Func<T, bool> _hasCover;
    private readonly Action<T, AudioMetadata> _applyCover;

    private int _loadedBatches;
    private volatile bool _loading;
    private volatile bool _disposed;
    private readonly SemaphoreSlim _lock = new(1, 1);

    public AudioCoverMemoryCache(int batchSize, Func<T, string> pathOf, Func<T, bool> hasCover, Action<T, AudioMetadata> applyCover)
    {
        BatchSize = batchSize;
        _pathOf = pathOf;
        _hasCover = hasCover;
        _applyCover = applyCover;
    }

    public void Dispose()
    {
        _disposed = true;
        _lock.Dispose();
    }

    /// <summary>切换分类/排序方式，或重新扫描后调用：清零批次计数。</summary>
    public void Reset() => _loadedBatches = 0;

    /// <summary>确保 orderedList 的前 batchSize 首封面已加载（或正在加载）。</summary>
    public Task EnsureFirstBatch(IReadOnlyList<T> orderedList, Action? onProgress = null)
        => EnsureBatchesUpTo(1, orderedList, onProgress);

    /// <summary>
    /// 根据滚动位置增量加载：当用户看到了索引 visibleIndex 但该位置还没有
    /// 封面数据时调用。内部按 batchSize 取整，换算出需要覆盖到第几批，
    /// 只在还没加载到那一批时才触发新的加载（200 → 400 → 600…）。
    /// </summary>
    public Task EnsureVisible(int visibleIndex, IReadOnlyList<T> orderedList, Action? onProgress = null)
    {
        var neededBatch = (visibleIndex / BatchSize) + 1;
        return EnsureBatchesUpTo(neededBatch, orderedList, onProgress);
    }

    /// <summary>
    /// 确保已加载到第 targetBatch 批（每批 batchSize 首）。
    /// 例如 targetBatch=1 → 加载前 200 首；targetBatch=2 → 加载 200~400
    /// 范围内还没加载的部分（累计前 400 首），以此类推。
    /// </summary>
    public async Task EnsureBatchesUpTo(int targetBatch, IReadOnlyList<T> orderedList, Action? onProgress = null)
    {
        if (_disposed || orderedList.Count == 0) return;
        if (targetBatch <= _loadedBatches) return;

        await _lock.WaitAsync();
        try
        {
            if (_loading || _disposed) return;
            _loading = true;
        }
        finally
        {
            _lock.Release();
        }

        try
        {
            while (!_disposed && _loadedBatches < targetBatch)
            {
                var start = _loadedBatches * BatchSize;
                var end = Math.Min((_loadedBatches + 1) * BatchSize, orderedList.Count);
                if (start >= orderedList.Count)
                {
                    // 列表本身比目标批次短，直接视为已加载完。
                    _loadedBatches = targetBatch;
                    break;
                }
                for (int i = start; i < end; i++)
                {
                    if (_disposed) break;
                    var item = orderedList[i];
                    if (_hasCover(item)) continue;
                    try
                    {
                        var meta = AudioMetadataReader.ReadFile(_pathOf(item));
                        if (_disposed) break;
                        _applyCover(item, meta);
                        onProgress?.Invoke();
                    }
                    catch
                    {
                        // 单首读取失败不影响其余歌曲继续加载
                    }
                    // 让出 CPU，避免阻塞 UI 线程
                    await Task.Yield();
                }
                _loadedBatches++;
            }
        }
        finally
        {
            _loading = false;
        }
    }
}
