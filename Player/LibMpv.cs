using System;
using System.Runtime.InteropServices;
using System.Text;

namespace MEPlayer.Player;

/// <summary>
/// 官方 libmpv C API 的 P/Invoke 封装（libmpv-2.dll）。
/// 直接使用 mpv_handle，不做任何中间抽象，与 mpv 官方文档一一对应。
/// </summary>
internal static class LibMpv
{
    private const string Lib = "libmpv-2.dll";

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr mpv_create();

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern int mpv_initialize(IntPtr mpv);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern void mpv_terminate_destroy(IntPtr mpv);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern int mpv_request_log_messages(IntPtr mpv, [MarshalAs(UnmanagedType.LPUTF8Str)] string min_level);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern int mpv_set_option_string(IntPtr mpv, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.LPUTF8Str)] string data);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern int mpv_set_property(IntPtr mpv, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, MpvFormat format, ref long data);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern int mpv_set_property(IntPtr mpv, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, MpvFormat format, ref double data);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern int mpv_set_property_string(IntPtr mpv, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.LPUTF8Str)] string data);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern int mpv_get_property(IntPtr mpv, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, MpvFormat format, ref double data);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern int mpv_get_property(IntPtr mpv, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, MpvFormat format, ref long data);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr mpv_get_property_string(IntPtr mpv, [MarshalAs(UnmanagedType.LPUTF8Str)] string name);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern void mpv_free(IntPtr data);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern int mpv_command(IntPtr mpv, IntPtr[] args);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern int mpv_command_string(IntPtr mpv, IntPtr unused, [MarshalAs(UnmanagedType.LPUTF8Str)] string args);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern ulong mpv_client_api_version();

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern int mpv_observe_property(IntPtr mpv, ulong reply_userdata, [MarshalAs(UnmanagedType.LPUTF8Str)] string name, MpvFormat format);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr mpv_wait_event(IntPtr mpv, double timeout);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern void mpv_wakeup(IntPtr mpv);

    [DllImport(Lib, CallingConvention = CallingConvention.Cdecl)]
    public static extern int mpv_set_wakeup_callback(IntPtr mpv, MpvWakeupCallback cb, IntPtr d);

    public delegate void MpvWakeupCallback(IntPtr ctx);

    // ── 事件结构 ──────────────────────────────────────────────────────────
    public const int MPV_EVENT_NONE = 0;
    public const int MPV_EVENT_SHUTDOWN = 1;
    public const int MPV_EVENT_LOG_MESSAGE = 2;
    public const int MPV_EVENT_GET_PROPERTY_REPLY = 3;
    public const int MPV_EVENT_SET_PROPERTY_REPLY = 4;
    public const int MPV_EVENT_COMMAND_REPLY = 5;
    public const int MPV_EVENT_START_FILE = 6;
    public const int MPV_EVENT_END_FILE = 7;
    public const int MPV_EVENT_FILE_LOADED = 8;
    public const int MPV_EVENT_IDLE = 11;
    public const int MPV_EVENT_PROPERTY_CHANGE = 21;

    public const int MPV_END_FILE_REASON_EOF = 0;
    public const int MPV_END_FILE_REASON_STOP = 2;
    public const int MPV_END_FILE_REASON_ERROR = 4;
    public const int MPV_END_FILE_REASON_REDIRECT = 5;

    [StructLayout(LayoutKind.Sequential)]
    public struct MpvEvent
    {
        public int event_id;
        public int error;
        public ulong reply_userdata;
        public IntPtr data;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct MpvEventProperty
    {
        public IntPtr name;
        public MpvFormat format;
        public IntPtr data;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct MpvEventEndFile
    {
        public int reason;
        public int error;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct MpvEventLogMessage
    {
        public IntPtr prefix;   // UTF-8 字符串，如 "cplayer"
        public IntPtr level;    // UTF-8 字符串，如 "warn"
        public IntPtr text;     // UTF-8 字符串，日志正文（含换行）
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct MpvNode
    {
        public MpvNodeFormat format;
        public IntPtr data;
        public IntPtr list;
        public IntPtr keys;
    }

    public enum MpvNodeFormat : int
    {
        None = 0,
        String = 1,
        OsdString = 2,
        Flag = 3,
        Int64 = 4,
        Double = 5,
        NodeMap = 6,
        NodeArray = 7,
    }

    public const MpvFormat MPV_FORMAT_NONE = MpvFormat.None;
    public const MpvFormat MPV_FORMAT_STRING = MpvFormat.String;
    public const MpvFormat MPV_FORMAT_OSD_STRING = MpvFormat.OsdString;
    public const MpvFormat MPV_FORMAT_FLAG = MpvFormat.Flag;
    public const MpvFormat MPV_FORMAT_INT64 = MpvFormat.Int64;
    public const MpvFormat MPV_FORMAT_DOUBLE = MpvFormat.Double;
    public const MpvFormat MPV_FORMAT_NODE = MpvFormat.Node;

    public static string? PtrToStringAnsiSafe(IntPtr p)
    {
        if (p == IntPtr.Zero) return null;
        // mpv 返回的字符串是 UTF-8 编码，不能直接用 PtrToStringAnsi（会按 ANSI 代码页解码导致中文乱码）
        // 手动按 UTF-8 解码
        int len = 0;
        while (Marshal.ReadByte(p, len) != 0) len++;
        var bytes = new byte[len];
        Marshal.Copy(p, bytes, 0, len);
        mpv_free(p);
        return Encoding.UTF8.GetString(bytes);
    }
}

public enum MpvFormat : int
{
    None = 0,
    String = 1,
    OsdString = 2,
    Flag = 3,
    Int64 = 4,
    Double = 5,
    Node = 6,
    NodeList = 7,
}
