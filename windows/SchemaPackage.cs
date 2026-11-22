using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Rolithax.Windows;

internal sealed record ConsolePluginSource(string Manifest, string Ui, string Rules = "", string Lua = "");
internal sealed record SchemaAsset(string Id, string MediaType, long Size, string Sha256, string? Path, Uri? Url);
internal sealed record SchemaPreflightCheck(string Kind, string Id, string Version);
internal sealed record SchemaPluginMetadata(string Id, string Name, string Version, string Description,
    string Engine, int SdkVersion, string[] Authors, string License, string Homepage, string? Icon);
internal sealed record SchemaPackage(ConsolePluginSource Source, SchemaPluginMetadata Metadata,
    IReadOnlyDictionary<string, SchemaAsset> Assets, SchemaPreflightCheck[] Preflight,
    IReadOnlyDictionary<string, string> Commands, IReadOnlyDictionary<string, JsonElement> Actions);

internal static class SchemaPackageReader
{
    internal const int ImageLimit = 8 * 1024 * 1024;
    internal const int VideoLimit = 64 * 1024 * 1024;
    internal const int BundleLimit = 128 * 1024 * 1024;
    private const int LegacyLimit = 512 * 1024;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private static readonly UTF8Encoding Utf8 = new(false, true);
    private static readonly Regex Id = new("^[a-z][a-z0-9._-]{0,127}$", RegexOptions.CultureInvariant);
    private static readonly Regex Hash = new("^[a-fA-F0-9]{64}$", RegexOptions.CultureInvariant);

    public static SchemaPackage Read(string filePath, Action<SchemaAsset, Stream>? materialize = null)
    {
        using var file = File.OpenRead(filePath);
        if (file.Length is < 1 or > BundleLimit) throw new InvalidDataException("Schema 包超过大小限制。");
        using var zip = new ZipArchive(file);
        if (zip.Entries.Count is < 2 or > 132) throw new InvalidDataException("Schema 包文件数量无效。");
        var entries = new Dictionary<string, ZipArchiveEntry>(StringComparer.OrdinalIgnoreCase);
        foreach (var entry in zip.Entries)
        {
            if (((entry.ExternalAttributes >> 16) & 0xF000) == 0xA000 ||
                !entries.TryAdd(SafePath(entry.FullName), entry))
                throw new InvalidDataException("Schema 包包含重复文件或符号链接。");
        }

        var manifest = ReadText("plugin.json", 16_384);
        using var document = JsonDocument.Parse(manifest);
        var root = document.RootElement;
        var sdk = checked((int)root.Field("sdkVersion").LongValue());
        var engine = root.Field("engine").StringValue("yaml");
        var id = root.Field("id").StringValue();
        var version = root.Field("version").StringValue();
        if (sdk is < 1 or > 3 || !Id.IsMatch(id) || id == "rolithax.paper" || string.IsNullOrEmpty(version) || engine is not ("yaml" or "lua"))
            throw new InvalidDataException("Schema 包元数据、SDK 版本或插件 ID 无效。");
        if (sdk < 3 && file.Length > LegacyLimit) throw new InvalidDataException("旧版 Schema 包超过 512 KiB。");

        var ui = ReadText("ui.xml", 65_536);
        var rules = engine == "yaml" || entries.ContainsKey("rules.yaml") ? ReadText("rules.yaml", 65_536, allowEmpty: true) : "";
        var lua = engine == "lua" ? ReadText("business.lua", 131_072) : "";
        if (engine == "lua" && !string.IsNullOrWhiteSpace(rules)) throw new InvalidDataException("Lua 包只能包含空的 rules.yaml。");

        var assets = new Dictionary<string, SchemaAsset>(StringComparer.OrdinalIgnoreCase);
        var embedded = new Dictionary<string, SchemaAsset>(StringComparer.OrdinalIgnoreCase);
        if (sdk == 3)
        {
            foreach (var value in root.Field("assets").Items())
            {
                var asset = ReadAsset(value, value.Field("id").StringValue(), requireAssetDirectory: true);
                if (!Id.IsMatch(asset.Id) || !assets.TryAdd(asset.Id, asset) ||
                    (asset.Path is { } path && !embedded.TryAdd(path, asset)))
                    throw new InvalidDataException("Schema 包存在重复的资源 ID 或资源路径。");
            }
            if (assets.Count > 128 || assets.Values.Sum(item => item.Size) > BundleLimit)
                throw new InvalidDataException("Schema 媒体资源超出大小或数量限制。");
        }
        foreach (var name in entries.Keys)
            if (name is not ("plugin.json" or "ui.xml" or "rules.yaml") &&
                (engine != "lua" || name != "business.lua") && !embedded.ContainsKey(name))
                throw new InvalidDataException("Schema 包包含未声明的文件。");

        var source = new ConsolePluginSource(manifest, ui, rules, lua);
        var inspection = Inspect(source);
        foreach (var (path, asset) in embedded)
        {
            if (!entries.TryGetValue(path, out var entry) || entry.FullName != path || entry.Length != asset.Size)
                throw new InvalidDataException("Schema 包缺少声明的媒体文件，或文件长度不符。");
            using var input = entry.Open();
            if (materialize is null) VerifyAsset(asset, input, Stream.Null);
            else materialize(asset, input);
        }

        var icon = root.Field("icon").StringValue();
        var authors = root.Field("authors").Items().Select(value => value.StringValue()).ToArray();
        var metadata = new SchemaPluginMetadata(id, root.Field("name").StringValue(inspection.Field("title").StringValue(id)),
            version, root.Field("description").StringValue(), engine, sdk, authors, root.Field("license").StringValue(),
            root.Field("homepage").StringValue(), icon.Length == 0 ? null : icon);
        var preflight = root.Field("preflight").Items().Select(item => new SchemaPreflightCheck(item.Field("kind").StringValue(),
            item.Field("id").StringValue(), item.Field("version").StringValue())).ToArray();
        var actions = inspection.Field("actions");
        return new SchemaPackage(source, metadata, assets, preflight,
            inspection.Field("commands").EnumerateObject().ToDictionary(pair => pair.Name, pair => pair.Value.StringValue()),
            actions.ValueKind == JsonValueKind.Object ? actions.EnumerateObject().ToDictionary(pair => pair.Name, pair => pair.Value.Clone()) : []);

        string ReadText(string name, int limit, bool allowEmpty = false)
        {
            if (!entries.TryGetValue(name, out var entry) || entry.FullName != name ||
                entry.Length < (allowEmpty ? 0 : 1) || entry.Length > limit)
                throw new InvalidDataException($"Schema 包内的 {name} 缺失或超出大小限制。");
            using var input = entry.Open();
            var bytes = new byte[entry.Length];
            input.ReadExactly(bytes);
            if (input.ReadByte() != -1) throw new InvalidDataException("Schema 文件长度不符。");
            return Utf8.GetString(bytes).TrimStart('\uFEFF');
        }
    }

    internal static SchemaAsset ReadAsset(JsonElement value, string id, bool requireAssetDirectory)
    {
        var mediaType = value.Field("mediaType").StringValue();
        var size = value.Field("size").LongValue();
        var digest = value.Field("sha256").StringValue();
        var path = value.Field("path").StringValue();
        var url = value.Field("url").StringValue();
        var limit = mediaType switch
        {
            "image/png" or "image/jpeg" or "image/webp" => ImageLimit,
            "video/mp4" => VideoLimit,
            _ => throw new InvalidDataException("媒体类型必须为 PNG、JPEG、WebP 或 MP4。"),
        };
        if (size <= 0 || size > limit || !Hash.IsMatch(digest) || (path.Length == 0) == (url.Length == 0))
            throw new InvalidDataException("媒体文件必须声明唯一来源、有效的大小与 SHA-256。");
        var relative = path.Length == 0 ? null : SafePath(path);
        if (requireAssetDirectory && relative is not null && !relative.StartsWith("assets/", StringComparison.Ordinal))
            throw new InvalidDataException("包内媒体文件只能放在 assets/ 目录。");
        var address = url.Length == 0 ? null : SchemaRepositoryClient.Https(url);
        return new SchemaAsset(id, mediaType, size, digest.ToLowerInvariant(), relative, address);
    }

    internal static string SafePath(string path)
    {
        if (path.Length is < 1 or > 256 || path.Split('/').Any(segment => segment.Length == 0 || segment is "." or ".." ||
            !segment.All(character => char.IsAsciiLetterOrDigit(character) || character is '.' or '_' or '-')))
            throw new InvalidDataException("资源路径必须为安全的相对路径。");
        return path;
    }

    internal static void VerifyAsset(SchemaAsset asset, Stream input, Stream destination)
    {
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        var size = 0L;
        var buffer = new byte[64 * 1024];
        var header = new byte[16];
        var headerLength = 0;
        int length;
        while ((length = input.Read(buffer)) > 0)
        {
            size += length;
            if (size > asset.Size) throw new InvalidDataException("媒体文件超过声明的大小。");
            var count = Math.Min(length, header.Length - headerLength);
            buffer.AsSpan(0, count).CopyTo(header.AsSpan(headerLength));
            headerLength += count;
            hash.AppendData(buffer.AsSpan(0, length));
            destination.Write(buffer, 0, length);
        }
        if (size != asset.Size || !Convert.ToHexStringLower(hash.GetHashAndReset()).Equals(asset.Sha256, StringComparison.Ordinal))
            throw new InvalidDataException("媒体文件大小或 SHA-256 校验失败。");
        var validType = asset.MediaType switch
        {
            "image/png" => header.AsSpan(0, 8).SequenceEqual(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 }),
            "image/jpeg" => header[0] == 255 && header[1] == 216 && header[2] == 255,
            "image/webp" => header.AsSpan(0, 4).SequenceEqual("RIFF"u8) && header.AsSpan(8, 4).SequenceEqual("WEBP"u8),
            "video/mp4" => header.AsSpan(4, 4).SequenceEqual("ftyp"u8),
            _ => false,
        };
        if (!validType) throw new InvalidDataException("媒体文件格式与声明不一致。");
    }

    private static JsonElement Inspect(ConsolePluginSource source)
    {
        var session = Native.Create();
        if (session == 0) throw new InvalidDataException("无法打开 Schema 校验会话。");
        try
        {
            var pointer = Native.Request(session, JsonSerializer.Serialize(new { op = "inspect", source }, Json));
            if (pointer == IntPtr.Zero) throw new InvalidDataException("Schema 校验没有返回结果。");
            try
            {
                using var response = JsonDocument.Parse(Marshal.PtrToStringUTF8(pointer) ?? "{}");
                if (!response.RootElement.Flag("ok"))
                    throw new InvalidDataException(response.RootElement.Field("error").Field("message").StringValue("Schema 插件校验失败。"));
                return response.RootElement.Field("data").Clone();
            }
            finally { Native.Free(pointer); }
        }
        finally { Native.Destroy(session); }
    }

    private static class Native
    {
        private const string Library = "rolithax_schema.dll";

        [DllImport(Library, CallingConvention = CallingConvention.Cdecl, EntryPoint = "rolithax_schema_create")]
        public static extern ulong Create();
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl, EntryPoint = "rolithax_schema_destroy")]
        public static extern void Destroy(ulong session);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl, EntryPoint = "rolithax_schema_request")]
        public static extern IntPtr Request(ulong session, [MarshalAs(UnmanagedType.LPUTF8Str)] string request);
        [DllImport(Library, CallingConvention = CallingConvention.Cdecl, EntryPoint = "rolithax_schema_free")]
        public static extern void Free(IntPtr pointer);
    }
}
