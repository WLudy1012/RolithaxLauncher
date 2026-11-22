using System.IO.Compression;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Xml;

namespace Rolithax.Windows;

internal sealed record SchemaRepositoryFile(string Path, string Sha256, long Size);
internal sealed record SchemaRepositoryEntry(string Id, string Name, string Version, string Description,
    string Engine, string SourceType, string Path, Uri? PackageUrl, string Sha256, long Size,
    IReadOnlyList<SchemaRepositoryFile> Files, string Compatibility = "", int SdkVersion = 0,
    string[]? Authors = null, string License = "", string Homepage = "", SchemaAsset? Icon = null);
internal sealed record SchemaRepositoryCatalog(string Url, string Name, string Description, string Revision,
    Uri FilesBase, IReadOnlyList<SchemaRepositoryEntry> Plugins, string Id = "", string[]? Authors = null,
    string Homepage = "", SchemaAsset? Icon = null);

internal static class SchemaRepositoryClient
{
    public const string DefaultSource = "https://github.com/WLudy1012/rolithax-schema-plugins";
    private const int PackageLimit = 512 * 1024;
    private static readonly UTF8Encoding Utf8 = new(false, true);
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private static readonly Regex Hash = new("^[a-fA-F0-9]{64}$", RegexOptions.CultureInvariant);
    private static readonly Regex Commit = new("^[a-fA-F0-9]{40,64}$", RegexOptions.CultureInvariant);
    private static readonly Regex Identifier = new("^[a-z][a-z0-9._-]{0,127}$", RegexOptions.CultureInvariant);
    internal static readonly string Storage = System.IO.Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "RolithaxLauncher", "schema");
    private static readonly HttpClient Client = CreateClient();

    private static HttpClient CreateClient()
    {
        var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false })
            { Timeout = TimeSpan.FromSeconds(45) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Rolithax-Schema/3.0");
        return client;
    }

    public static IReadOnlyList<string> LoadSources()
    {
        var path = System.IO.Path.Combine(Storage, "sources.json");
        if (!File.Exists(path))
            return [DefaultSource];
        try
        {
            if (new FileInfo(path).Length > 65_536)
                return [DefaultSource];
            return (JsonSerializer.Deserialize<string[]>(File.ReadAllBytes(path), Json) ?? [])
                .Where(value => !string.IsNullOrWhiteSpace(value)).Select(NormalizeSource)
                .Distinct(StringComparer.Ordinal).Take(32).ToArray();
        }
        catch (Exception error) when (error is JsonException or IOException or ArgumentException)
        {
            return [DefaultSource];
        }
    }

    public static void SaveSources(IEnumerable<string> sources)
    {
        var urls = sources.Select(NormalizeSource).Distinct(StringComparer.Ordinal).ToArray();
        if (urls.Length > 32)
            throw new InvalidDataException("最多添加 32 个 Schema 插件源。");
        Directory.CreateDirectory(Storage);
        var destination = System.IO.Path.Combine(Storage, "sources.json");
        var temporary = destination + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            File.WriteAllBytes(temporary, JsonSerializer.SerializeToUtf8Bytes(urls, Json));
            File.Move(temporary, destination, true);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    public static string NormalizeSource(string value)
    {
        var uri = Https(value.Trim());
        if (uri.Query.Length > 0 || uri.Fragment.Length > 0)
            throw new ArgumentException("插件源地址不能包含查询参数或锚点。");
        if (uri.Host is "github.com" or "gitlab.com")
        {
            var parts = uri.AbsolutePath.Trim('/').Split('/');
            if (parts.Length < 2 || (uri.Host == "github.com" && parts.Length != 2) || parts.Any(part => !SafeSegment(part)))
                throw new ArgumentException("请输入 GitHub 或 GitLab 仓库主页地址。");
            return uri.GetLeftPart(UriPartial.Path).TrimEnd('/').RemovesSuffix(".git");
        }
        if (!uri.AbsolutePath.EndsWith("/README.md", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("自托管源请填写可直接下载的 HTTPS README.md 地址。");
        return uri.AbsoluteUri;
    }

    public static async Task<SchemaRepositoryCatalog> FetchAsync(string source, CancellationToken cancellationToken = default)
    {
        source = NormalizeSource(source);
        var uri = new Uri(source);
        Uri readme;
        Uri filesBase;
        var revision = "README";
        if (uri.Host == "github.com")
        {
            var project = uri.AbsolutePath.Trim('/');
            revision = await GitHubCommitAsync(project, cancellationToken);
            filesBase = new Uri($"https://raw.githubusercontent.com/{project}/{revision}/");
            readme = new Uri(filesBase, "README.md");
        }
        else if (uri.Host == "gitlab.com")
        {
            var project = uri.AbsolutePath.Trim('/');
            using var commit = JsonDocument.Parse(await DownloadAsync(new Uri(
                $"https://gitlab.com/api/v4/projects/{Uri.EscapeDataString(project)}/repository/commits?per_page=1"),
                262_144, cancellationToken));
            revision = Required(commit.RootElement[0], "id");
            if (!Commit.IsMatch(revision)) throw new InvalidDataException("仓库提交信息无效。");
            filesBase = new Uri($"https://gitlab.com/{project}/-/raw/{revision}/");
            readme = new Uri(filesBase, "README.md");
        }
        else
        {
            readme = uri;
            filesBase = new Uri(uri, ".");
        }
        var readmeData = await DownloadAsync(readme, 262_144, cancellationToken);
        if (revision == "README") revision = Convert.ToHexStringLower(SHA256.HashData(readmeData));
        var text = Utf8.GetString(readmeData);
        var blocks = Regex.Matches(text, @"(?m)^```rolithax-schema-repository(-v2)?[ \t]*\r?\n([\s\S]*?)^```[ \t]*\r?$",
            RegexOptions.CultureInvariant, TimeSpan.FromSeconds(1));
        var newer = blocks.Where(match => match.Groups[1].Success).ToArray();
        var legacy = blocks.Where(match => !match.Groups[1].Success).ToArray();
        if (newer.Length > 1 || legacy.Length > 1 || blocks.Count == 0)
            throw new InvalidDataException("README 缺少唯一的 Rolithax Schema 源索引。");
        var modern = newer.Length == 1;
        using var document = JsonDocument.Parse((modern ? newer[0] : legacy[0]).Groups[2].Value);
        var root = document.RootElement;
        if (!root.TryGetProperty("schemaVersion", out var version) || version.GetInt32() != (modern ? 2 : 1))
            throw new InvalidDataException("不支持此插件源格式版本。");
        var id = Required(root, "id");
        if (!Identifier.IsMatch(id))
            throw new InvalidDataException("插件源 ID 无效。");
        var plugins = root.GetProperty("plugins");
        if (plugins.ValueKind != JsonValueKind.Array || plugins.GetArrayLength() > 256)
            throw new InvalidDataException("插件源条目过多或格式错误。");
        var entries = plugins.EnumerateArray().Select(item => ParseEntry(item, modern)).ToArray();
        if (entries.Select(entry => entry.Id).Distinct(StringComparer.Ordinal).Count() != entries.Length)
            throw new InvalidDataException("插件源包含重复的插件 ID。");
        return new SchemaRepositoryCatalog(source, modern ? Required(root, "name") : Optional(root, "name", uri.Host),
            modern ? Required(root, "description") : Optional(root, "description"), revision, filesBase, entries, id,
            modern ? Authors(root) : null, modern ? Https(Required(root, "homepage")).AbsoluteUri : "",
            modern ? IndexIcon(root) : null);
    }

    private static SchemaRepositoryEntry ParseEntry(JsonElement item, bool modern)
    {
        var id = Required(item, "id");
        if (!Identifier.IsMatch(id)) throw new InvalidDataException("插件 ID 无效。");
        var engine = Required(item, "engine");
        var source = item.GetProperty("source");
        var type = Required(source, "type");
        string path = "", sha = "";
        long size = 0;
        Uri? url = null;
        SchemaRepositoryFile[] files = [];
        if (type == "files" && engine == "yaml")
        {
            path = RelativePath(Required(source, "path"));
            files = source.GetProperty("files").EnumerateArray().Select(file => new SchemaRepositoryFile(
                RelativePath(Required(file, "path")), Digest(file), Size(file, FileLimit(Required(file, "path"))))).ToArray();
            var names = files.Select(file => file.Path).ToHashSet(StringComparer.OrdinalIgnoreCase);
            if (files.Length > 131 || files.Sum(file => file.Size) > SchemaPackageReader.BundleLimit || names.Count != files.Length ||
                !new[] { "plugin.json", "rules.yaml", "ui.xml" }.All(name => names.Contains(name)) ||
                (!modern && files.Length != 3))
                throw new InvalidDataException("文件型源缺少业务定义文件，或声明的文件超出限制。");
        }
        else if (type == "package" && engine is "yaml" or "lua")
        {
            url = Https(Required(source, "url"));
            sha = Digest(source);
            size = Size(source, modern ? SchemaPackageReader.BundleLimit : PackageLimit);
        }
        else throw new InvalidDataException("Lua 插件必须提供预构建 ZIP 包；文件型源仅支持 YAML。");
        var compatibility = "";
        if (item.TryGetProperty("compatibility", out var supported) && supported.ValueKind == JsonValueKind.Object &&
            supported.TryGetProperty("versions", out var versions) && versions.ValueKind == JsonValueKind.Array)
        {
            var declared = versions.EnumerateArray().Where(value => value.ValueKind == JsonValueKind.String)
                .Take(12).Select(value => value.GetString() ?? "").Select(value => value[..Math.Min(64, value.Length)]).ToArray();
            if (declared.Length > 0) compatibility = "声明版本：" + string.Join("、", declared);
        }
        var sdk = checked((int)item.Field("sdkVersion").LongValue());
        if (modern && sdk is < 1 or > 3) throw new InvalidDataException("插件源缺少有效的 SDK 版本。");
        return new SchemaRepositoryEntry(id, modern ? Required(item, "name") : Optional(item, "name", id), Required(item, "version"),
            modern ? Required(item, "description") : Optional(item, "description"), engine, type, path, url, sha, size, files, compatibility,
            sdk, modern ? Authors(item) : null, modern ? Required(item, "license") : "",
            modern ? Https(Required(item, "homepage")).AbsoluteUri : "", modern ? IndexIcon(item) : null);
    }

    private static string[] Authors(JsonElement item)
    {
        var authors = item.Field("authors").Items().Select(value => value.StringValue()).ToArray();
        if (authors.Length is < 1 or > 16 || authors.Any(value => string.IsNullOrWhiteSpace(value) || value.Length > 128))
            throw new InvalidDataException("插件源缺少有效的作者列表。");
        return authors;
    }

    private static SchemaAsset IndexIcon(JsonElement item)
    {
        var icon = SchemaPackageReader.ReadAsset(item.Field("icon"), "icon", requireAssetDirectory: false);
        if (!icon.MediaType.StartsWith("image/", StringComparison.Ordinal)) throw new InvalidDataException("图标必须是图片。");
        return icon;
    }

    private static async Task<string> GitHubCommitAsync(string project, CancellationToken cancellationToken)
    {
        try
        {
            using var commit = JsonDocument.Parse(await DownloadAsync(
                new Uri($"https://api.github.com/repos/{project}/commits?per_page=1"), 262_144, cancellationToken));
            var revision = Required(commit.RootElement[0], "sha");
            return Commit.IsMatch(revision) ? revision : throw new InvalidDataException("仓库提交信息无效。");
        }
        catch (HttpRequestException error) when ((int?)error.StatusCode is 403 or 429)
        {
            var atom = await DownloadAsync(new Uri($"https://github.com/{project}/commits/HEAD.atom"), 262_144, cancellationToken);
            using var stream = new MemoryStream(atom, false);
            using var reader = XmlReader.Create(stream, new XmlReaderSettings
            {
                DtdProcessing = DtdProcessing.Prohibit, XmlResolver = null, MaxCharactersInDocument = 262_144,
                IgnoreComments = true, IgnoreProcessingInstructions = true,
            });
            var inEntry = false;
            while (reader.Read())
            {
                if (reader.NodeType == XmlNodeType.Element && reader.LocalName == "entry" && reader.NamespaceURI == "http://www.w3.org/2005/Atom")
                    inEntry = true;
                else if (inEntry && reader.NodeType == XmlNodeType.Element && reader.LocalName == "id" && reader.NamespaceURI == "http://www.w3.org/2005/Atom")
                {
                    var id = reader.ReadElementContentAsString().Trim();
                    const string prefix = "tag:github.com,2008:Grit::Commit/";
                    var revision = id.StartsWith(prefix, StringComparison.Ordinal) ? id[prefix.Length..] : "";
                    if (revision.Length == 40 && revision.All(character => character is >= '0' and <= '9' or >= 'a' and <= 'f'))
                        return revision;
                    throw new InvalidDataException("仓库提交订阅中的 SHA 无效。");
                }
                else if (inEntry && reader.NodeType == XmlNodeType.EndElement && reader.LocalName == "entry")
                    break;
            }
            throw new InvalidDataException("仓库提交订阅没有有效的提交记录。");
        }
    }

    public static async Task<string> DownloadPackageAsync(SchemaRepositoryCatalog repository,
        SchemaRepositoryEntry entry, string destination, CancellationToken cancellationToken = default)
    {
        await using var output = new FileStream(destination, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65536, true);
        if (entry.SourceType == "files")
        {
            using var zip = new ZipArchive(output, ZipArchiveMode.Create, true);
            foreach (var file in entry.Files)
            {
                var target = zip.CreateEntry(file.Path, CompressionLevel.Optimal);
                target.LastWriteTime = new DateTimeOffset(1980, 1, 1, 0, 0, 0, TimeSpan.Zero);
                await using var stream = target.Open();
                await DownloadVerifiedAsync(new Uri(repository.FilesBase, entry.Path + "/" + file.Path),
                    file.Sha256, file.Size, stream, cancellationToken);
            }
        }
        else
        {
            await DownloadVerifiedAsync(entry.PackageUrl!, entry.Sha256, entry.Size, output, cancellationToken);
        }
        return destination;
    }

    private static async Task<byte[]> DownloadAsync(Uri uri, int limit, CancellationToken cancellationToken)
    {
        using var stream = new MemoryStream();
        await DownloadToAsync(uri, limit, stream, null, cancellationToken);
        return stream.ToArray();
    }

    internal static async Task DownloadVerifiedAsync(Uri uri, string digest, long size, Stream output, CancellationToken cancellationToken)
    {
        using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        if (await DownloadToAsync(uri, size, output, hash, cancellationToken) != size ||
            !Convert.ToHexString(hash.GetHashAndReset()).Equals(digest, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("文件大小或 SHA-256 校验失败，请刷新插件源后重试。");
    }

    private static async Task<long> DownloadToAsync(Uri uri, long limit, Stream output, IncrementalHash? hash, CancellationToken cancellationToken)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(45));
        cancellationToken = deadline.Token;
        for (var redirects = 0; redirects <= 5; redirects++)
        {
            Https(uri.AbsoluteUri);
            using var response = await Client.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            if ((int)response.StatusCode is >= 300 and < 400 && response.Headers.Location is { } location)
            {
                uri = location.IsAbsoluteUri ? location : new Uri(uri, location);
                continue;
            }
            response.EnsureSuccessStatusCode();
            if (response.Content.Headers.ContentLength > limit) throw new InvalidDataException("下载内容超过大小限制。");
            await using var input = await response.Content.ReadAsStreamAsync(cancellationToken);
            var buffer = new byte[8192];
            var size = 0L;
            int read;
            while ((read = await input.ReadAsync(buffer, cancellationToken)) > 0)
            {
                size += read;
                if (size > limit) throw new InvalidDataException("下载内容超过大小限制。");
                hash?.AppendData(buffer.AsSpan(0, read));
                await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
            }
            return size;
        }
        throw new InvalidDataException("下载重定向次数过多。");
    }

    internal static Uri Https(string value)
    {
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri) || uri.Scheme != "https" || uri.UserInfo.Length > 0 || uri.Fragment.Length > 0)
            throw new ArgumentException("插件源与下载地址必须使用 HTTPS，且不能包含账号密码。");
        return uri;
    }

    private static string RelativePath(string value)
    {
        if (value.Length is < 1 or > 256 || value.Split('/').Any(part => !SafeSegment(part)))
            throw new InvalidDataException("插件源路径必须是安全的相对路径。");
        return value;
    }

    private static bool SafeSegment(string value) => value.Length > 0 && value is not "." and not ".." &&
        value.All(character => char.IsAsciiLetterOrDigit(character) || character is '.' or '_' or '-');

    private static int FileLimit(string path) => path switch
    {
        "plugin.json" => 16_384, "ui.xml" or "rules.yaml" => 65_536, "business.lua" => 131_072,
        _ when path.StartsWith("assets/", StringComparison.Ordinal) => SchemaPackageReader.VideoLimit,
        _ => throw new InvalidDataException("Schema 包含未知文件或目录。"),
    };

    private static string Required(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String && value.GetString() is { Length: > 0 } text && text.Length <= 2048
            ? text : throw new InvalidDataException($"插件源缺少有效的 {name}。");

    private static string Optional(JsonElement element, string name, string fallback = "")
    {
        if (!element.TryGetProperty(name, out var value) || value.ValueKind != JsonValueKind.String)
            return fallback;
        var text = value.GetString() ?? fallback;
        return text[..Math.Min(text.Length, 2048)];
    }

    private static string Digest(JsonElement element)
    {
        var value = Required(element, "sha256");
        return Hash.IsMatch(value) ? value : throw new InvalidDataException("SHA-256 格式无效。");
    }

    private static long Size(JsonElement element, int maximum) =>
        element.TryGetProperty("size", out var value) && value.TryGetInt64(out var size) && size > 0 && size <= maximum
            ? size : throw new InvalidDataException("插件源文件大小超出限制。");

    private static string RemovesSuffix(this string value, string suffix) =>
        value.EndsWith(suffix, StringComparison.Ordinal) ? value[..^suffix.Length] : value;
}
