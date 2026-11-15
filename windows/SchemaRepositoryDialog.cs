using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private async Task OpenSchemaRepositoryAsync(ConsoleRun run)
    {
        if (!IsCurrentConsole(run)) return;
        using var lifetime = new CancellationTokenSource();
        var sources = SchemaRepositoryClient.LoadSources().ToList();
        var selector = new ComboBox { HorizontalAlignment = HorizontalAlignment.Stretch, PlaceholderText = "选择插件源" };
        AutomationProperties.SetName(selector, "Schema 插件源");
        var address = new TextBox { PlaceholderText = "https://github.com/作者/仓库", MinWidth = 240 };
        AutomationProperties.SetName(address, "新增 Git 仓库或 HTTPS README 地址");
        var feedback = new InfoBar { Severity = InfoBarSeverity.Error, IsClosable = false };
        var progress = new ProgressBar { IsIndeterminate = true, Visibility = Visibility.Collapsed };
        var catalogHeader = new StackPanel { Spacing = 4 };
        var entries = new StackPanel { Spacing = 20 };
        var scroll = new ScrollViewer
        {
            Content = entries, MaxHeight = 360, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
        };
        var body = new StackPanel { Spacing = 16, MinWidth = 420 };
        var dialog = new ContentDialog
        {
            XamlRoot = Root.XamlRoot, Title = "Schema 插件源", CloseButtonText = "关闭", Content = body,
            RequestedTheme = Root.ActualTheme,
            DefaultButton = ContentDialogButton.Close,
        };
        dialog.Resources["ContentDialogMaxWidth"] = 760d;
        SchemaDownloadedPlugin? selected = null;
        var loading = false;
        var add = new Button { Content = "添加" };
        var refresh = new Button { Content = "刷新" };
        var remove = new Button { Content = "移除" };

        void ShowError(Exception error)
        {
            feedback.Message = error switch
            {
                OperationCanceledException => "读取超时，请刷新重试。",
                System.Net.Http.HttpRequestException request => $"无法下载插件源或文件（HTTP {(int?)request.StatusCode}），请检查地址和网络后重试。",
                JsonException or System.Xml.XmlException => "插件源 README 格式无效，请联系插件源维护者修复。",
                _ => error.Message,
            };
            feedback.IsOpen = true;
        }

        void SetLoading(bool value)
        {
            loading = value;
            selector.IsEnabled = add.IsEnabled = address.IsEnabled = !value;
            refresh.IsEnabled = remove.IsEnabled = !value && selector.SelectedItem is string;
            entries.IsHitTestVisible = !value;
            entries.Opacity = value ? 0.6 : 1;
            progress.Visibility = value ? Visibility.Visible : Visibility.Collapsed;
        }

        async Task InstallAsync(SchemaRepositoryCatalog repository, SchemaRepositoryEntry plugin)
        {
            if (loading) return;
            feedback.IsOpen = false;
            SetLoading(true);
            try
            {
                selected = await SchemaRepositoryClient.InstallAsync(repository, plugin, lifetime.Token);
                if (!lifetime.IsCancellationRequested) dialog.Hide();
            }
            catch (Exception error)
            {
                if (!lifetime.IsCancellationRequested) ShowError(error);
            }
            finally { SetLoading(false); }
        }

        async Task ReloadAsync()
        {
            if (loading || selector.SelectedItem is not string source) return;
            feedback.IsOpen = false;
            SetLoading(true);
            entries.Children.Clear();
            catalogHeader.Children.Clear();
            try
            {
                var repository = await SchemaRepositoryClient.FetchAsync(source, lifetime.Token);
                if (lifetime.IsCancellationRequested) return;
                catalogHeader.Children.Add(Text(repository.Name, "SubtitleTextBlockStyle"));
                if (repository.Description.Length > 0) catalogHeader.Children.Add(Secondary(repository.Description));
                catalogHeader.Children.Add(Secondary(repository.Revision == "README" ? "自托管 README 源"
                    : $"仓库提交 {repository.Revision[..Math.Min(12, repository.Revision.Length)]}"));
                foreach (var plugin in repository.Plugins)
                {
                    var install = Button("加载", () => InstallAsync(repository, plugin), accent: true);
                    AutomationProperties.SetName(install, $"加载 {plugin.Name} {plugin.Version}");
                    var description = CompactStack(Text(plugin.Name, "BodyStrongTextBlockStyle"),
                        Secondary($"{plugin.Version} · {(plugin.Engine == "lua" ? "Lua 预构建包" : "YAML 文件包")}"));
                    if (plugin.Description.Length > 0) description.Children.Add(Secondary(plugin.Description));
                    if (plugin.Compatibility.Length > 0) description.Children.Add(Secondary(plugin.Compatibility));
                    entries.Children.Add(AdaptivePair(description, install, 460));
                    entries.Children.Add(SchemaDivider());
                }
                if (repository.Plugins.Count == 0) entries.Children.Add(Secondary("这个插件源还没有可用插件。"));
            }
            catch (Exception error)
            {
                if (!lifetime.IsCancellationRequested) ShowError(error);
            }
            finally { SetLoading(false); }
        }

        void UpdateSources(string? selectedUrl)
        {
            selector.ItemsSource = null;
            selector.ItemsSource = sources.ToArray();
            selector.SelectedItem = selectedUrl;
            if (sources.Count == 0)
            {
                entries.Children.Clear();
                catalogHeader.Children.Clear();
                catalogHeader.Children.Add(Secondary("添加一个 Git 仓库地址，或直接填写 HTTPS README.md 地址。"));
            }
        }

        UpdateSources(sources.FirstOrDefault());
        selector.SelectionChanged += async (_, _) => await ReloadAsync();
        refresh.Click += async (_, _) => await ReloadAsync();
        add.Click += (_, _) =>
        {
            try
            {
                var source = SchemaRepositoryClient.NormalizeSource(address.Text);
                var updated = sources.Append(source).Distinct(StringComparer.Ordinal).ToList();
                SchemaRepositoryClient.SaveSources(updated);
                sources = updated;
                address.Text = "";
                UpdateSources(source);
            }
            catch (Exception error) { ShowError(error); }
        };
        remove.Click += (_, _) =>
        {
            if (selector.SelectedItem is not string source) return;
            try
            {
                var updated = sources.Where(value => value != source).ToList();
                SchemaRepositoryClient.SaveSources(updated);
                sources = updated;
                UpdateSources(sources.FirstOrDefault());
                if (sources.Count == 0) SetLoading(false);
            }
            catch (Exception error) { ShowError(error); }
        };
        body.Children.Add(AdaptivePair(selector, (FrameworkElement)Actions(refresh, remove), 500));
        body.Children.Add(AdaptivePair(address, add, 500));
        body.Children.Add(Secondary("插件会读取当前实例的日志。下载校验通过后，需单独确认命令权限。"));
        body.Children.Add(feedback);
        body.Children.Add(progress);
        body.Children.Add(catalogHeader);
        body.Children.Add(scroll);
        dialog.Opened += async (_, _) => await ReloadAsync();
        dialog.Closed += (_, _) => lifetime.Cancel();
        await dialog.ShowAsync();
        if (selected is not null && IsCurrentConsole(run))
        {
            var source = JsonSerializer.Deserialize<ConsolePluginSource>(selected.SourceJson, PanelJson)
                ?? throw new DaemonException("插件包格式无效。");
            await ImportConsolePanelSourceAsync(run, source);
        }
    }
}
