using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Win32.SafeHandles;

namespace Rolithax.Windows;

internal sealed class DaemonException(string message, int status = 0) : Exception(message)
{
    public int Status { get; } = status;
}

internal sealed class DaemonSession : IDisposable
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private readonly SessionHandle handle;

    private DaemonSession(SessionHandle handle) => this.handle = handle;

    public static Task<DaemonSession> OpenAsync(DaemonConnection connection) => Task.Run(() =>
    {
        if (Native.AbiVersion() != 1)
            throw new DaemonException("客户端与 Daemon 核心的版本不匹配。");
        var config = JsonSerializer.Serialize(new
        {
            baseUrl = connection.Address,
            apiKey = connection.ApiKey,
            connection.AllowHttp,
            connection.AllowSelfSigned,
        }, Json);
        var pointer = Native.Open(config, out var error);
        if (pointer == IntPtr.Zero)
        {
            if (error != IntPtr.Zero)
                ReadReply(error, connection.ApiKey);
            throw new DaemonException("无法打开 Daemon 会话。");
        }
        if (error != IntPtr.Zero)
            Native.Free(error);
        return new DaemonSession(new SessionHandle(pointer));
    });

    public Task<JsonElement> CallAsync(string operation, object? args = null, string? secret = null) => Task.Run(() =>
        ReadReply(Native.Call(handle, operation, JsonSerializer.Serialize(args ?? new { }, Json)), secret,
            operation is "pair" or "createUser" or "updateUser" or "updateProfile"));

    private static JsonElement ReadReply(IntPtr reply, string? secret, bool sensitive = false)
    {
        if (reply == IntPtr.Zero)
            throw new DaemonException("Daemon 核心未返回结果。");
        try
        {
            using var document = JsonDocument.Parse(Marshal.PtrToStringUTF8(reply) ?? "{}");
            var root = document.RootElement;
            if (root.Field("ok").ValueKind != JsonValueKind.True)
            {
                var message = sensitive ? "Daemon 拒绝了请求。请检查权限和输入后重试。"
                    : root.Field("error").StringValue("Daemon 请求失败。");
                if (!string.IsNullOrEmpty(secret))
                    message = message.Replace(secret, "[已隐藏]", StringComparison.Ordinal);
                var status = (int)root.Field("httpStatus").LongValue();
                if (status == 0)
                    status = (int)root.Field("businessCode").LongValue();
                throw new DaemonException(message.Length > 400 ? "Daemon 请求失败。" : message, status);
            }
            return root.Field("data").Clone();
        }
        finally
        {
            Native.Free(reply);
        }
    }

    public void Dispose() => handle.Dispose();

    private sealed class SessionHandle : SafeHandleZeroOrMinusOneIsInvalid
    {
        public SessionHandle(IntPtr pointer) : base(true) => SetHandle(pointer);

        protected override bool ReleaseHandle()
        {
            Native.Close(handle);
            return true;
        }
    }

    private static class Native
    {
        private const string Library = "rolithax_core.dll";

        [DllImport(Library, CallingConvention = CallingConvention.Cdecl, EntryPoint = "rolithax_abi_version")]
        public static extern int AbiVersion();

        [DllImport(Library, CallingConvention = CallingConvention.Cdecl, EntryPoint = "rolithax_session_open")]
        public static extern IntPtr Open([MarshalAs(UnmanagedType.LPUTF8Str)] string config, out IntPtr error);

        [DllImport(Library, CallingConvention = CallingConvention.Cdecl, EntryPoint = "rolithax_session_call")]
        public static extern IntPtr Call(SessionHandle session,
            [MarshalAs(UnmanagedType.LPUTF8Str)] string operation,
            [MarshalAs(UnmanagedType.LPUTF8Str)] string args);

        [DllImport(Library, CallingConvention = CallingConvention.Cdecl, EntryPoint = "rolithax_session_close")]
        public static extern void Close(IntPtr session);

        [DllImport(Library, CallingConvention = CallingConvention.Cdecl, EntryPoint = "rolithax_string_free")]
        public static extern void Free(IntPtr value);
    }
}

internal static class JsonFields
{
    public static JsonElement Field(this JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value) ? value : default;

    public static string StringValue(this JsonElement element, string fallback = "") => element.ValueKind switch
    {
        JsonValueKind.String => element.GetString() ?? fallback,
        JsonValueKind.Number => element.GetRawText(),
        _ => fallback,
    };

    public static long LongValue(this JsonElement element) =>
        long.TryParse(element.StringValue(), System.Globalization.CultureInfo.InvariantCulture, out var value) ? value : 0;

    public static double? NumberValue(this JsonElement element) =>
        double.TryParse(element.StringValue(), System.Globalization.CultureInfo.InvariantCulture, out var value) ? value : null;

    public static bool Flag(this JsonElement element, string key) => element.Field(key).ValueKind == JsonValueKind.True;

    public static JsonElement[] Items(this JsonElement element) =>
        element.ValueKind == JsonValueKind.Array ? [.. element.EnumerateArray()] : [];
}
