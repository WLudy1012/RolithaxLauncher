using System.Security.Cryptography;
using System.Text.Json;

namespace Rolithax.Windows;

internal sealed record SchemaInstallation(SchemaPluginMetadata Metadata, string Digest, string? Source, string Revision,
    SchemaAsset? CatalogIcon = null);
internal sealed record SchemaBinding(Guid ConnectionId, string ServerId, string PluginId, string Digest, string[] Grants);

internal sealed class SchemaLibrary
{
    private const int IndexLimit = 1024 * 1024;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private readonly object sync = new();
    private readonly string root;
    private readonly string packages;
    private readonly string media;
    private readonly string index;
    private LibraryState? state;

    private sealed record LibraryState(SchemaInstallation[] Installations, SchemaBinding[] Bindings);

    public SchemaLibrary(string? storage = null)
    {
        root = storage ?? SchemaRepositoryClient.Storage;
        packages = Path.Combine(root, "packages");
        media = Path.Combine(root, "media");
        index = Path.Combine(root, "library.json");
    }

    public SchemaInstallation[] Installed()
    {
        lock (sync) return [.. Current().Installations];
    }

    public (SchemaInstallation Installation, SchemaBinding Binding)? Binding(Guid connectionId, string serverId)
    {
        lock (sync)
        {
            var current = Current();
            var binding = current.Bindings.FirstOrDefault(item => item.ConnectionId == connectionId && item.ServerId == serverId);
            var install = current.Installations.FirstOrDefault(item => item.Metadata.Id == binding?.PluginId && item.Digest == binding.Digest);
            return install is not null && binding is not null ? (install, binding) : null;
        }
    }

    public void Bind(Guid connectionId, string serverId, SchemaInstallation installation, IEnumerable<string> grants)
    {
        lock (sync)
        {
            var current = Current();
            if (!current.Installations.Any(item => item.Metadata.Id == installation.Metadata.Id && item.Digest == installation.Digest))
                throw new InvalidDataException("Schema 插件已更新或卸载，请重新选择。");
            var next = current.Bindings.Where(item => item.ConnectionId != connectionId || item.ServerId != serverId)
                .Append(new SchemaBinding(connectionId, serverId, installation.Metadata.Id, installation.Digest, grants.Distinct().ToArray())).ToArray();
            Save(current with { Bindings = next });
        }
    }

    public void ClearBinding(Guid connectionId, string serverId)
    {
        lock (sync)
        {
            var current = Current();
            var bindings = current.Bindings.Where(item => item.ConnectionId != connectionId || item.ServerId != serverId).ToArray();
            if (bindings.Length != current.Bindings.Length) Save(current with { Bindings = bindings });
        }
    }

    public void Uninstall(SchemaInstallation installation)
    {
        lock (sync)
        {
            var current = Current();
            if (!current.Installations.Any(item => item.Metadata.Id == installation.Metadata.Id && item.Digest == installation.Digest))
                return;
            Save(new LibraryState(current.Installations.Where(item => item.Metadata.Id != installation.Metadata.Id).ToArray(),
                current.Bindings.Where(item => item.PluginId != installation.Metadata.Id).ToArray()));
            TryDelete(PackagePath(installation.Digest));
        }
    }

    public SchemaPackage Load(SchemaInstallation installation)
    {
        var path = PackagePath(installation.Digest);
        using (var input = File.OpenRead(path))
        {
            if (input.Length > SchemaPackageReader.BundleLimit ||
                Convert.ToHexStringLower(SHA256.HashData(input)) != installation.Digest)
                throw new InvalidDataException("已安装插件的完整性校验失败，请重新下载。");
        }
        var package = SchemaPackageReader.Read(path);
        if (package.Metadata.Id != installation.Metadata.Id || package.Metadata.Version != installation.Metadata.Version)
            throw new InvalidDataException("已安装插件的元数据与索引不符。");
        return package;
    }

    public async Task<SchemaInstallation> InstallAsync(SchemaRepositoryCatalog catalog, SchemaRepositoryEntry entry,
        CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(packages);
        var staging = Path.Combine(packages, Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            await SchemaRepositoryClient.DownloadPackageAsync(catalog, entry, staging, cancellationToken).ConfigureAwait(false);
            return await FinishInstallAsync(staging, catalog, entry, cancellationToken).ConfigureAwait(false);
        }
        finally { TryDelete(staging); }
    }

    public async Task<SchemaInstallation> ImportAsync(string path, CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(packages);
        var staging = Path.Combine(packages, Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            await using (var input = File.OpenRead(path))
            await using (var output = new FileStream(staging, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65_536, true))
            {
                if (input.Length > SchemaPackageReader.BundleLimit) throw new InvalidDataException("Schema 包超过大小限制。");
                var buffer = new byte[65536];
                long size = 0;
                int length;
                while ((length = await input.ReadAsync(buffer, cancellationToken).ConfigureAwait(false)) > 0)
                {
                    size += length;
                    if (size > SchemaPackageReader.BundleLimit) throw new InvalidDataException("Schema 包超过大小限制。");
                    await output.WriteAsync(buffer.AsMemory(0, length), cancellationToken).ConfigureAwait(false);
                }
            }
            return await FinishInstallAsync(staging, null, null, cancellationToken).ConfigureAwait(false);
        }
        finally { TryDelete(staging); }
    }

    private async Task<SchemaInstallation> FinishInstallAsync(string staging, SchemaRepositoryCatalog? catalog,
        SchemaRepositoryEntry? entry, CancellationToken cancellationToken)
    {
        var package = await Task.Run(() => SchemaPackageReader.Read(staging, (asset, stream) =>
        {
            cancellationToken.ThrowIfCancellationRequested();
            StoreAsset(asset, stream);
        }), cancellationToken).ConfigureAwait(false);
        var metadata = package.Metadata;
        if (entry is not null && (entry.Id != metadata.Id || entry.Version != metadata.Version || entry.Engine != metadata.Engine ||
            (entry.SdkVersion != 0 && entry.SdkVersion != metadata.SdkVersion)))
            throw new InvalidDataException("Schema 包元数据与插件源声明不符。");
        foreach (var asset in package.Assets.Values.Where(asset => asset.Url is not null))
            await CacheMediaAsync(asset, asset.Url!, cancellationToken).ConfigureAwait(false);
        if (catalog is not null && entry?.Icon is { } icon)
            await CacheMediaAsync(icon, IconUri(catalog, icon), cancellationToken).ConfigureAwait(false);
        cancellationToken.ThrowIfCancellationRequested();
        await using var input = File.OpenRead(staging);
        var digest = Convert.ToHexStringLower(await SHA256.HashDataAsync(input, cancellationToken).ConfigureAwait(false));
        await input.DisposeAsync();
        var installation = new SchemaInstallation(metadata, digest, catalog?.Url, catalog?.Revision ?? "本地", entry?.Icon);
        lock (sync)
        {
            var current = Current();
            var previous = current.Installations.FirstOrDefault(item => item.Metadata.Id == metadata.Id);
            if (previous is not null && previous.Source != installation.Source)
                throw new InvalidDataException("已安装同 ID 的其他来源插件。请先移除旧插件。");
            if (previous is null && current.Installations.Length >= 128)
                throw new InvalidDataException("最多安装 128 个 Schema 插件。");
            if (!File.Exists(PackagePath(digest))) File.Move(staging, PackagePath(digest));
            Save(new LibraryState(current.Installations.Where(item => item.Metadata.Id != metadata.Id).Append(installation).ToArray(),
                current.Bindings.Where(binding => binding.PluginId != metadata.Id || binding.Digest == digest).ToArray()));
            if (previous is not null && previous.Digest != digest) TryDelete(PackagePath(previous.Digest));
        }
        return installation;
    }

    public async Task<string> CacheIndexIconAsync(SchemaRepositoryCatalog catalog, SchemaAsset asset, CancellationToken cancellationToken) =>
        await CacheMediaAsync(asset, IconUri(catalog, asset), cancellationToken).ConfigureAwait(false);

    private static Uri IconUri(SchemaRepositoryCatalog catalog, SchemaAsset asset) =>
        asset.Url ?? new Uri(catalog.FilesBase, asset.Path!);

    public string? AssetPath(SchemaAsset asset)
    {
        var path = MediaPath(asset);
        return File.Exists(path) && new FileInfo(path).Length == asset.Size ? path : null;
    }

    private async Task<string> CacheMediaAsync(SchemaAsset asset, Uri uri, CancellationToken cancellationToken)
    {
        if (AssetPath(asset) is { } existing)
        {
            try
            {
                await Task.Run(() =>
                {
                    using var input = File.OpenRead(existing);
                    SchemaPackageReader.VerifyAsset(asset, input, Stream.Null);
                }, cancellationToken).ConfigureAwait(false);
                return existing;
            }
            catch (Exception error) when (error is InvalidDataException or FileNotFoundException) { }
        }
        Directory.CreateDirectory(media);
        var staging = Path.Combine(media, Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            await using (var output = new FileStream(staging, FileMode.CreateNew, FileAccess.Write, FileShare.None, 65_536, true))
                await SchemaRepositoryClient.DownloadVerifiedAsync(uri, asset.Sha256, asset.Size, output, cancellationToken).ConfigureAwait(false);
            await Task.Run(() =>
            {
                using var input = File.OpenRead(staging);
                SchemaPackageReader.VerifyAsset(asset, input, Stream.Null);
            }, cancellationToken).ConfigureAwait(false);
            var path = MediaPath(asset);
            File.Move(staging, path, overwrite: true);
            return path;
        }
        finally { TryDelete(staging); }
    }

    private void StoreAsset(SchemaAsset asset, Stream input)
    {
        Directory.CreateDirectory(media);
        var staging = Path.Combine(media, Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            using (var output = File.Open(staging, FileMode.CreateNew, FileAccess.Write))
                SchemaPackageReader.VerifyAsset(asset, input, output);
            File.Move(staging, MediaPath(asset), overwrite: true);
        }
        finally { TryDelete(staging); }
    }

    private static void TryDelete(string file)
    {
        try { File.Delete(file); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    private string PackagePath(string digest) => Path.Combine(packages, CheckedDigest(digest) + ".zip");

    private string MediaPath(SchemaAsset asset) => Path.Combine(media, CheckedDigest(asset.Sha256) + (asset.MediaType switch
    {
        "image/png" => ".png", "image/jpeg" => ".jpg", "image/webp" => ".webp", "video/mp4" => ".mp4",
        _ => throw new InvalidDataException("未知媒体类型。"),
    }));

    private static string CheckedDigest(string digest) => digest.Length == 64 && digest.All(Uri.IsHexDigit)
        ? digest.ToLowerInvariant() : throw new InvalidDataException("Schema 安装目录索引无效。");

    private LibraryState Current()
    {
        if (state is not null) return state;
        if (!File.Exists(index)) return state = new LibraryState([], []);
        using var input = File.OpenRead(index);
        if (input.Length > IndexLimit) throw new InvalidDataException("Schema 安装索引过大。");
        return state = JsonSerializer.Deserialize<LibraryState>(input, Json) ?? throw new InvalidDataException("Schema 安装索引无效。");
    }

    private void Save(LibraryState next)
    {
        Directory.CreateDirectory(root);
        var data = JsonSerializer.SerializeToUtf8Bytes(next, Json);
        if (data.Length > IndexLimit) throw new InvalidDataException("Schema 安装索引过大。");
        var staging = Path.Combine(root, Guid.NewGuid().ToString("N") + ".tmp");
        try
        {
            File.WriteAllBytes(staging, data);
            File.Move(staging, index, overwrite: true);
            state = next;
        }
        finally { TryDelete(staging); }
    }
}
