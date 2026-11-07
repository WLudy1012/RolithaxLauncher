using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json;

namespace Rolithax.Windows;

internal sealed record DaemonConnection(Guid Id, string Name, string Address, string ApiKey, bool AllowHttp, bool AllowSelfSigned);

internal sealed class ConnectionStore
{
    private const int MaxConfigSize = 1024 * 1024;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private static readonly string Folder = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "RolithaxLauncher");
    private static readonly string FilePath = Path.Combine(Folder, "windows-connections.dat");
    private static readonly string LegacyPath = Path.Combine(Folder, "daemons.json");

    public static bool HasLegacyConnections => File.Exists(LegacyPath);

    public static List<DaemonConnection> Load()
    {
        if (!File.Exists(FilePath))
            return [];
        var data = ProtectedData.Unprotect(ReadBoundedFile(FilePath), null, DataProtectionScope.CurrentUser);
        try
        {
            return JsonSerializer.Deserialize<List<DaemonConnection>>(data, Json)
                ?? throw new InvalidDataException("连接配置为空。");
        }
        finally
        {
            CryptographicOperations.ZeroMemory(data);
        }
    }

    public static void Save(IReadOnlyList<DaemonConnection> connections)
    {
        Directory.CreateDirectory(Folder);
        var data = JsonSerializer.SerializeToUtf8Bytes(connections, Json);
        var temporary = FilePath + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            var encrypted = ProtectedData.Protect(data, null, DataProtectionScope.CurrentUser);
            if (encrypted.Length > MaxConfigSize)
                throw new InvalidDataException("连接配置过大。");
            File.WriteAllBytes(temporary, encrypted);
            File.Move(temporary, FilePath, overwrite: true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(data);
            File.Delete(temporary);
        }
    }

    public static (List<DaemonConnection> Connections, int Skipped) ReadLegacy()
    {
        using var document = JsonDocument.Parse(ReadBoundedFile(LegacyPath));
        var result = new List<DaemonConnection>();
        var skipped = 0;
        foreach (var item in document.RootElement.EnumerateArray())
        {
            var id = item.Field("id").StringValue();
            if (!Guid.TryParse(id, out var guid) || !string.IsNullOrEmpty(item.Field("fingerprint").StringValue()) ||
                !CredRead($"RolithaxLauncher.Daemon|{id}", 1, 0, out var credential))
            {
                skipped++;
                continue;
            }
            try
            {
                var record = Marshal.PtrToStructure<Credential>(credential);
                var apiKey = Marshal.PtrToStringUni(record.Blob, checked((int)record.BlobSize / 2));
                if (string.IsNullOrWhiteSpace(apiKey))
                {
                    skipped++;
                    continue;
                }
                result.Add(new DaemonConnection(guid, item.Field("name").StringValue(),
                    item.Field("address").StringValue(), apiKey, item.Flag("allowHttp"), false));
            }
            finally
            {
                CredFree(credential);
            }
        }
        return (result, skipped);
    }

    private static byte[] ReadBoundedFile(string path)
    {
        using var stream = File.OpenRead(path);
        if (stream.Length > MaxConfigSize)
            throw new InvalidDataException("连接配置过大。");
        var bytes = new byte[stream.Length];
        stream.ReadExactly(bytes);
        return bytes;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct Credential
    {
        public uint Flags;
        public uint Type;
        public IntPtr TargetName;
        public IntPtr Comment;
        public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;
        public uint BlobSize;
        public IntPtr Blob;
    }

    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CredRead(string target, uint type, uint flags, out IntPtr credential);

    [DllImport("advapi32.dll")]
    private static extern void CredFree(IntPtr credential);
}
