using System.Net.Http;
using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Storage;
using Windows.Storage.Pickers;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private readonly SchemaLibrary schemaLibrary = new();
    private string schemaCenterPage = "installed";
    private string? schemaSelectedSource;

    private void ShowSchemaCenter(StackPanel body)
    {
        var choices = new SelectorBar();
        foreach (var (key, label) in new[] { ("installed", "已安装"), ("catalog", "浏览仓库"), ("sources", "插件源") })
            choices.Items.Add(new SelectorBarItem { Tag = key, Text = label });
        choices.SelectedItem = choices.Items.First(item => (string)item.Tag == schemaCenterPage);
        AutomationProperties.SetName(choices, "插件中心分区");

        var search = new TextBox { PlaceholderText = "按名称或 ID 搜索", MaxLength = 120, MinWidth = 150 };
        AutomationProperties.SetName(search, "搜索 Schema 插件");
        var import = Button("导入 ZIP", () => RunAsync(ImportSchemaPackageAsync));
        body.Children.Add(PageHeading("Schema 插件中心", "安装插件后，可在实例控制台选择启用和授权。", import));
        body.Children.Add(AdaptivePair(choices, search, 640));

        var content = new StackPanel { Spacing = 12 };
        body.Children.Add(Card(content));
        CancellationTokenSource? viewLifetime = null;
        Action? updateSearch = null;
        bool Match(string name, string id) => name.Contains(search.Text.Trim(), StringComparison.CurrentCultureIgnoreCase) ||
            id.Contains(search.Text.Trim(), StringComparison.OrdinalIgnoreCase);

        void ShowInstalled()
        {
            content.Children.Clear();
            SchemaInstallation[] installed;
            try { installed = schemaLibrary.Installed(); }
            catch (Exception error) when (error is IOException or JsonException or UnauthorizedAccessException)
            {
                content.Children.Add(Secondary("无法读取已安装插件，请检查本地存储。"));
                return;
            }
            foreach (var plugin in installed.Where(item => Match(item.Metadata.Name, item.Metadata.Id))
                         .OrderBy(item => item.Metadata.Name, StringComparer.CurrentCultureIgnoreCase))
            {
                var info = plugin.Metadata;
                var details = CompactStack(Text(info.Name, "BodyStrongTextBlockStyle"),
                    Secondary($"{info.Version} · Schema {info.SdkVersion} · {info.Engine.ToUpperInvariant()} · {info.Id}"));
                if (info.Description.Length > 0) details.Children.Add(Secondary(info.Description));
                if (info.Authors.Length > 0) details.Children.Add(Secondary(string.Join("、", info.Authors) + " · " + info.License));
                if (plugin.Source is { } source) details.Children.Add(Secondary(source));
                AddSchemaHomepage(details, info.Homepage);
                var icon = PluginIcon();
                if (viewLifetime is { } lifetime)
                    _ = LoadInstalledIconAsync(plugin, icon, lifetime.Token);
                var remove = Button("卸载", () => RunAsync(async () =>
                {
                    if (!await ConfirmAsync("卸载 Schema 插件", Secondary($"卸载 {info.Name} 并移除它的实例绑定？"), "卸载")) return;
                    await Task.Run(() => schemaLibrary.Uninstall(plugin));
                    ShowInstalled();
                    Notice("已卸载 Schema 插件。");
                }));
                AutomationProperties.SetName(remove, $"卸载 {info.Name}");
                content.Children.Add(AdaptivePair(IconAndDetails(icon, details), remove, 540));
                content.Children.Add(SchemaDivider());
            }
            if (content.Children.Count == 0) content.Children.Add(Secondary(installed.Length == 0
                ? "还没有已安装的插件。可浏览仓库或导入 ZIP。" : "没有符合搜索条件的插件。"));
        }

        void ShowSources()
        {
            content.Children.Clear();
            var sources = SchemaRepositoryClient.LoadSources();
            foreach (var source in sources)
            {
                var title = source == SchemaRepositoryClient.DefaultSource ? "Rolithax 官方插件源" : new Uri(source).Host;
                var open = Button("浏览", () =>
                {
                    schemaSelectedSource = source;
                    choices.SelectedItem = choices.Items.First(item => (string)item.Tag == "catalog");
                });
                var remove = Button("移除", () => RunAsync(async () =>
                {
                    if (!await ConfirmAsync("移除插件源", Secondary($"移除 {source}？已安装的插件可继续使用。"), "移除")) return;
                    await Task.Run(() => SchemaRepositoryClient.SaveSources(sources.Where(item => item != source)));
                    if (schemaSelectedSource == source) schemaSelectedSource = null;
                    ShowSources();
                }));
                content.Children.Add(Entry(title, source, (FrameworkElement)Actions(open, remove)));
            }
            if (sources.Count == 0) content.Children.Add(Secondary("尚未添加插件源。"));
            var addAddress = new TextBox { Header = "Git 仓库或 README 地址", PlaceholderText = "https://github.com/作者/仓库", MaxLength = 2048 };
            var add = Button("添加插件源", () => RunAsync(async () =>
            {
                var value = SchemaRepositoryClient.NormalizeSource(addAddress.Text);
                var repository = await SchemaRepositoryClient.FetchAsync(value, viewLifetime?.Token ?? CancellationToken.None);
                await Task.Run(() => SchemaRepositoryClient.SaveSources(sources.Append(value)));
                schemaSelectedSource = value;
                ShowSources();
                Notice($"已添加 {repository.Name}。");
            }), accent: true);
            content.Children.Add(AdaptivePair(addAddress, add, 560));
        }

        void ShowCatalog(CancellationToken cancellationToken)
        {
            content.Children.Clear();
            var sources = SchemaRepositoryClient.LoadSources();
            if (sources.Count == 0)
            {
                content.Children.Add(Secondary("还没有插件源。"));
                content.Children.Add(Button("添加插件源", () => choices.SelectedItem = choices.Items.First(item => (string)item.Tag == "sources")));
                return;
            }
            var sourcePicker = new ComboBox { ItemsSource = sources, HorizontalAlignment = HorizontalAlignment.Stretch,
                SelectedItem = sources.Contains(schemaSelectedSource) ? schemaSelectedSource : sources[0] };
            AutomationProperties.SetName(sourcePicker, "当前插件仓库");
            var list = new StackPanel { Spacing = 12 };
            var progress = new ProgressBar { IsIndeterminate = true, Visibility = Visibility.Collapsed };
            var message = new InfoBar { IsClosable = true };
            var revision = 0;
            SchemaRepositoryCatalog? catalog = null;

            void Display()
            {
                list.Children.Clear();
                if (catalog is not { } repository) return;
                var title = CompactStack(Text(repository.Name, "SubtitleTextBlockStyle"), Secondary(repository.Description));
                if (repository.Authors is { Length: > 0 } authors) title.Children.Add(Secondary(string.Join("、", authors)));
                AddSchemaHomepage(title, repository.Homepage);
                var repoIcon = PluginIcon();
                if (repository.Icon is { } icon) _ = LoadIndexIconAsync(repository, icon, repoIcon, cancellationToken);
                list.Children.Add(IconAndDetails(repoIcon, title));
                list.Children.Add(SchemaDivider());
                var matches = repository.Plugins.Where(item => Match(item.Name, item.Id)).ToArray();
                var installed = schemaLibrary.Installed().ToDictionary(item => item.Metadata.Id, StringComparer.Ordinal);
                foreach (var plugin in matches)
                {
                    var engine = plugin.SdkVersion > 0 ? $"Schema {plugin.SdkVersion} · {plugin.Engine.ToUpperInvariant()}" : plugin.Engine.ToUpperInvariant();
                    var details = CompactStack(Text(plugin.Name, "BodyStrongTextBlockStyle"),
                        Secondary($"{plugin.Version} · {engine} · {plugin.Id}"), Secondary(plugin.Description));
                    if (plugin.Authors is { Length: > 0 } pluginAuthors)
                        details.Children.Add(Secondary(string.Join("、", pluginAuthors) + " · " + plugin.License));
                    if (plugin.Compatibility.Length > 0) details.Children.Add(Secondary(plugin.Compatibility));
                    AddSchemaHomepage(details, plugin.Homepage);
                    var pluginIcon = PluginIcon();
                    if (plugin.Icon is { } definition) _ = LoadIndexIconAsync(repository, definition, pluginIcon, cancellationToken);
                    var current = installed.GetValueOrDefault(plugin.Id);
                    var sameVersion = current is not null && current.Metadata.Version == plugin.Version && current.Source == repository.Url &&
                        current.Revision == repository.Revision;
                    var install = Button(sameVersion ? "已安装" : current is null ? "安装" : "更新", () => RunAsync(async () =>
                    {
                        try
                        {
                            progress.Visibility = Visibility.Visible;
                            await schemaLibrary.InstallAsync(repository, plugin, cancellationToken);
                            if (!cancellationToken.IsCancellationRequested) Display();
                            Notice("已安装完整插件，可在实例控制台选择使用。");
                        }
                        catch (Exception error) { if (!cancellationToken.IsCancellationRequested) ShowSchemaError(message, error); }
                        finally { progress.Visibility = Visibility.Collapsed; }
                    }), accent: !sameVersion);
                    install.IsEnabled = !sameVersion;
                    AutomationProperties.SetName(install, $"{install.Content} {plugin.Name}");
                    list.Children.Add(AdaptivePair(IconAndDetails(pluginIcon, details), install, 540));
                    list.Children.Add(SchemaDivider());
                }
                if (matches.Length == 0) list.Children.Add(Secondary("没有符合搜索条件的插件。"));
            }

            async Task Refresh()
            {
                if (sourcePicker.SelectedItem is not string source) return;
                var requested = ++revision;
                schemaSelectedSource = source;
                catalog = null;
                list.Children.Clear();
                message.IsOpen = false;
                progress.Visibility = Visibility.Visible;
                try
                {
                    var result = await SchemaRepositoryClient.FetchAsync(source, cancellationToken);
                    if (requested == revision && !cancellationToken.IsCancellationRequested) { catalog = result; Display(); }
                }
                catch (Exception error)
                {
                    if (requested == revision && !cancellationToken.IsCancellationRequested) ShowSchemaError(message, error);
                }
                finally { if (requested == revision) progress.Visibility = Visibility.Collapsed; }
            }

            sourcePicker.SelectionChanged += async (_, _) => await Refresh();
            var refresh = Button("刷新仓库", Refresh);
            content.Children.Add(AdaptivePair(sourcePicker, refresh, 560));
            content.Children.Add(progress);
            content.Children.Add(message);
            content.Children.Add(list);
            updateSearch = Display;
            _ = Refresh();
        }

        void ShowSelection()
        {
            viewLifetime?.Cancel();
            viewLifetime?.Dispose();
            viewLifetime = new CancellationTokenSource();
            schemaCenterPage = choices.SelectedItem?.Tag as string ?? "installed";
            search.Visibility = schemaCenterPage == "sources" ? Visibility.Collapsed : Visibility.Visible;
            updateSearch = null;
            switch (schemaCenterPage)
            {
                case "catalog": ShowCatalog(viewLifetime.Token); break;
                case "sources": ShowSources(); break;
                default: ShowInstalled(); updateSearch = ShowInstalled; break;
            }
        }

        search.TextChanged += (_, _) => updateSearch?.Invoke();
        choices.SelectionChanged += (_, _) => ShowSelection();
        body.Unloaded += (_, _) =>
        {
            viewLifetime?.Cancel();
            viewLifetime?.Dispose();
            viewLifetime = null;
        };
        ShowSelection();
    }

    private static void AddSchemaHomepage(Panel details, string address)
    {
        if (Uri.TryCreate(address, UriKind.Absolute, out var uri) && uri.Scheme == Uri.UriSchemeHttps && uri.UserInfo.Length == 0)
            details.Children.Add(new HyperlinkButton { Content = "项目主页", NavigateUri = uri, Padding = new Thickness(0, 2, 4, 2) });
    }

    private async Task ImportSchemaPackageAsync()
    {
        var picker = new FileOpenPicker();
        picker.FileTypeFilter.Add(".zip");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));
        if (await picker.PickSingleFileAsync() is not { } file) return;
        try
        {
            var installed = await schemaLibrary.ImportAsync(file.Path, CancellationToken.None);
            schemaCenterPage = "installed";
            Render();
            Notice($"已导入 {installed.Metadata.Name}。");
        }
        catch (Exception error) when (error is InvalidDataException or JsonException or ArgumentException)
        {
            throw new DaemonException("无法导入插件包。" + error.Message);
        }
    }

    private static void ShowSchemaError(InfoBar message, Exception error)
    {
        message.Severity = InfoBarSeverity.Error;
        message.Message = error switch
        {
            OperationCanceledException => "下载超时，请重试。",
            HttpRequestException request => request.StatusCode is { } status ? $"服务器返回 HTTP {(int)status}。" : "无法连接插件仓库，请检查网络。",
            JsonException => "插件索引格式无效。",
            InvalidDataException or ArgumentException => error.Message,
            _ => "无法读取仓库或保存插件，请检查本地存储。",
        };
        message.IsOpen = true;
    }

    private static Grid PluginIcon()
    {
        var box = new Grid { Width = 42, Height = 42, VerticalAlignment = VerticalAlignment.Top };
        box.Children.Add(new SymbolIcon(Symbol.Library));
        return box;
    }

    private static Grid IconAndDetails(FrameworkElement icon, FrameworkElement details)
    {
        var row = new Grid { ColumnSpacing = 12 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.Children.Add(icon);
        Grid.SetColumn(details, 1);
        row.Children.Add(details);
        return row;
    }

    private async Task LoadIndexIconAsync(SchemaRepositoryCatalog repository, SchemaAsset definition, Grid box,
        CancellationToken cancellationToken)
    {
        try
        {
            var path = await schemaLibrary.CacheIndexIconAsync(repository, definition, cancellationToken);
            await SetPluginIconAsync(box, path, cancellationToken);
        }
        catch (Exception error) when (error is IOException or HttpRequestException or OperationCanceledException or UnauthorizedAccessException or System.Runtime.InteropServices.COMException) { }
    }

    private async Task LoadInstalledIconAsync(SchemaInstallation plugin, Grid box, CancellationToken cancellationToken)
    {
        try
        {
            var definition = plugin.CatalogIcon;
            if (definition is null && plugin.Metadata.Icon is { } id)
            {
                var package = await Task.Run(() => schemaLibrary.Load(plugin), cancellationToken);
                definition = package.Assets.GetValueOrDefault(id);
            }
            if (definition is not null && schemaLibrary.AssetPath(definition) is { } path)
                await SetPluginIconAsync(box, path, cancellationToken);
        }
        catch (Exception error) when (error is InvalidDataException or IOException or OperationCanceledException or UnauthorizedAccessException or System.Runtime.InteropServices.COMException) { }
    }

    private static async Task SetPluginIconAsync(Grid box, string path, CancellationToken cancellationToken)
    {
        if (cancellationToken.IsCancellationRequested) return;
        var file = await StorageFile.GetFileFromPathAsync(path);
        using var stream = await file.OpenReadAsync();
        var bitmap = new BitmapImage { DecodePixelWidth = 96 };
        await bitmap.SetSourceAsync(stream);
        if (!cancellationToken.IsCancellationRequested)
        {
            box.Children.Clear();
            box.Children.Add(new Image { Source = bitmap, Stretch = Stretch.Uniform, IsHitTestVisible = false });
        }
    }
}
