using System.IO.Compression;
using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Storage.Pickers;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private sealed record ConsolePanelNode(string Id, string Kind, string Label, string? Field, string? Action,
        ConsolePanelNode[]? Children, string? Description = null, string? Tone = null, string? Unit = null,
        string? Placeholder = null);

    private sealed record ConsolePanelSnapshot(long Revision, long PackageRevision, string PluginId, string Title,
        bool Stale, ConsolePanelNode[] Nodes, Dictionary<string, string> Values, Dictionary<string, string> Evidence);

    private sealed record ConsolePluginSource(string Manifest, string Ui, string Rules, string Lua = "");

    private sealed class ConsolePluginView(ConsoleRun run)
    {
        public StackPanel Body { get; } = new() { Spacing = 24, Padding = new Thickness(0, 8, 0, 16) };
        public List<Action<ConsolePanelSnapshot>> Bindings { get; } = [];
        public long? PackageRevision { get; set; }
        public TextBlock? Status { get; set; }

        public void Refresh()
        {
            if (Status is { } status)
                status.Text = ConsoleConnectionStatus(run);
            if (run.Panel is not { } snapshot)
                return;
            foreach (var apply in Bindings)
                apply(snapshot);
        }
    }

    private static readonly JsonSerializerOptions PanelJson = new(JsonSerializerDefaults.Web);
    private static readonly Regex CommandParameter = new(@"\{([a-z][a-z0-9-]{0,63})\}", RegexOptions.CultureInvariant);
    private ConsolePluginView? consolePluginView;

    private ScrollViewer ConsolePluginPanel(ConsoleRun run)
    {
        var view = new ConsolePluginView(run);
        consolePluginView = view;
        BuildConsolePluginView(run, view);
        return new ScrollViewer { Content = view.Body, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled };
    }

    private void BuildConsolePluginView(ConsoleRun run, ConsolePluginView view)
    {
        view.Body.Children.Clear();
        view.Bindings.Clear();
        view.Status = null;
        view.PackageRevision = run.Panel?.PackageRevision;
        if (run.Epoch == 0)
        {
            view.Body.Children.Add(Card(Secondary("当前核心版本未提供控制台面板。请使用同一发布包中的应用与 DLL。")));
            return;
        }

        var status = view.Status = Secondary(ConsoleConnectionStatus(run));
        var management = new MenuFlyout();
        var import = new MenuFlyoutItem { Text = "导入本地 ZIP", Icon = new SymbolIcon(Symbol.OpenFile) };
        import.Click += async (_, _) => await RunAsync(() => ImportConsolePanelAsync(run));
        management.Items.Add(import);
        var restore = new MenuFlyoutItem { Text = "恢复内置 Schema", IsEnabled = run.ImportedPanel };
        restore.Click += async (_, _) => await RunAsync(() => RestoreConsolePanelAsync(run));
        management.Items.Add(restore);
        var manage = new DropDownButton
        {
            Content = run.Panel?.Title ?? "Rolithax Schema", Flyout = management, MinWidth = 180, MaxWidth = 350,
        };
        AutomationProperties.SetName(manage, "选择或管理 Schema 插件");
        var source = (run.ImportedPanel, run.AllowedPanelCommands.Count) switch
        {
            (false, _) => "内置 Schema",
            (true, 0) => "当前插件 · 只读",
            _ => $"当前插件 · 已授权 {run.AllowedPanelCommands.Count} 项操作",
        };
        view.Body.Children.Add(AdaptivePair(CompactStack(manage, Secondary(source)),
            (FrameworkElement)Actions(Button("插件源", () => RunAsync(() => OpenSchemaRepositoryAsync(run))), status), 560));
        view.Body.Children.Add(SchemaDivider());
        if (run.Panel is not { } snapshot)
        {
            view.Body.Children.Add(CompactStack(Secondary("正在读取 Rolithax Schema…"), new ProgressBar { IsIndeterminate = true }));
            return;
        }

        var stale = new InfoBar
        {
            Severity = InfoBarSeverity.Warning,
            Message = "部分日志缺失，以下结果可能不完整。可展开日志依据核对。",
            IsClosable = false,
        };
        view.Bindings.Add(panel => stale.IsOpen = panel.Stale);
        view.Body.Children.Add(stale);
        var elements = snapshot.Nodes.Select(node => BuildPanelNode(run, view, node, snapshot.PackageRevision)).ToArray();
        if (snapshot.Nodes.All(node => node.Kind == "section"))
            view.Body.Children.Add(SchemaColumns(elements, 350, 2));
        else foreach (var element in elements) view.Body.Children.Add(element);
        view.Body.Children.Add(SchemaDivider());
        view.Body.Children.Add(BuildPanelEvidence(view, snapshot.Nodes));
        view.Refresh();
    }

    private static Expander BuildPanelEvidence(ConsolePluginView view, ConsolePanelNode[] nodes)
    {
        var labels = new Dictionary<string, string>();
        void Collect(ConsolePanelNode node)
        {
            if (node.Field is { } field && node.Kind != "field")
                labels.TryAdd(field, node.Label);
            foreach (var child in node.Children ?? [])
                Collect(child);
        }
        foreach (var node in nodes)
            Collect(node);

        var content = new StackPanel { Spacing = 8 };
        long? displayedRevision = null;
        view.Bindings.Add(snapshot =>
        {
            if (displayedRevision == snapshot.Revision)
                return;
            displayedRevision = snapshot.Revision;
            content.Children.Clear();
            if (snapshot.Evidence.Count == 0)
                content.Children.Add(Secondary("暂无匹配的控制台日志。"));
            foreach (var (field, line) in snapshot.Evidence)
            {
                var evidence = Secondary(line);
                evidence.IsTextSelectionEnabled = true;
                evidence.FontFamily = new FontFamily("Cascadia Mono, Consolas");
                content.Children.Add(CompactStack(Text(labels.GetValueOrDefault(field, field), "BodyStrongTextBlockStyle"), evidence));
            }
        });
        return new Expander
        {
            Header = "日志依据",
            Content = content,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
        };
    }

    private UIElement BuildPanelNode(ConsoleRun run, ConsolePluginView view, ConsolePanelNode node, long revision)
    {
        string Display(ConsolePanelSnapshot snapshot) => node.Field is { } field &&
            snapshot.Values.TryGetValue(field, out var text) && text.Length > 0 ? text :
            node.Field is { } emptyField && snapshot.Evidence.ContainsKey(emptyField) ? "无" : "等待日志";
        StackPanel Described(UIElement content)
        {
            var result = CompactStack(content);
            if (!string.IsNullOrWhiteSpace(node.Description)) result.Children.Add(Secondary(node.Description));
            return result;
        }
        switch (node.Kind)
        {
            case "section":
                var section = Section(node.Label);
                section.Spacing = 16;
                ((TextBlock)section.Children[0]).FontSize = 22;
                if (!string.IsNullOrWhiteSpace(node.Description)) section.Children.Add(Secondary(node.Description));
                foreach (var child in node.Children ?? [])
                    section.Children.Add(BuildPanelNode(run, view, child, revision));
                return section;

            case "row":
                return SchemaColumns((node.Children ?? []).Select(child => BuildPanelNode(run, view, child, revision)).ToArray(), 180, 3);

            case "divider":
                return SchemaDivider();

            case "metric":
                var metric = Text("等待日志", SchemaToneStyle(node.Tone, "Metric"));
                view.Bindings.Add(snapshot => metric.Text = Display(snapshot) +
                    (node.Field is { } metricField && snapshot.Values.GetValueOrDefault(metricField, "").Length > 0 && node.Unit is { } unit ? " " + unit : ""));
                return Described(CompactStack(Secondary(node.Label), metric));

            case "progress":
                var progressText = Text("等待日志", "BodyStrongTextBlockStyle");
                var progress = new ProgressBar { Minimum = 0, Maximum = 100, Style = Resource<Style>(SchemaToneStyle(node.Tone, "Progress")) };
                AutomationProperties.SetName(progress, node.Label);
                view.Bindings.Add(snapshot =>
                {
                    var raw = node.Field is { } progressField ? snapshot.Values.GetValueOrDefault(progressField, "").Trim().TrimEnd('%') : "";
                    var valid = double.TryParse(raw, NumberStyles.Float, CultureInfo.InvariantCulture, out var number) && double.IsFinite(number);
                    progress.Visibility = valid ? Visibility.Visible : Visibility.Collapsed;
                    progress.Value = valid ? Math.Clamp(number, 0, 100) : 0;
                    progressText.Text = valid ? progress.Value.ToString("0.##", CultureInfo.InvariantCulture) + (node.Unit ?? "%") : "等待日志";
                });
                return Described(Stack(AdaptivePair(Text(node.Label, "BodyStrongTextBlockStyle"), progressText, 250), progress));

            case "badge":
            case "status":
                var badgeValue = Text("等待日志", SchemaToneStyle(node.Tone, "Text"));
                view.Bindings.Add(snapshot => badgeValue.Text = Display(snapshot));
                var badge = new Border
                {
                    Style = Resource<Style>(node.Tone == "success" ? "RolithaxSuccessPillStyle" : "RolithaxNeutralPillStyle"),
                    Child = badgeValue, MaxWidth = 280,
                };
                return Described(AdaptivePair(Text(node.Label), badge, 300));

            case "text":
                var value = Text("等待日志");
                view.Bindings.Add(snapshot =>
                {
                    value.Text = Display(snapshot) +
                        (node.Field is { } field && snapshot.Values.GetValueOrDefault(field, "").Length > 0 && node.Unit is { } unit ? " " + unit : "");
                });
                return Described(node.Field is null ? Text(node.Label) : AdaptivePair(Secondary(node.Label), value, 320, equalColumns: true));

            case "list":
                var list = new ListView
                {
                    SelectionMode = ListViewSelectionMode.None,
                    MaxHeight = 210,
                    IsItemClickEnabled = false,
                };
                AutomationProperties.SetName(list, node.Label);
                var empty = Secondary("等待日志");
                string? previous = null;
                view.Bindings.Add(snapshot =>
                {
                    var current = node.Field is { } field ? snapshot.Values.GetValueOrDefault(field, "") : "";
                    if (previous == current)
                        return;
                    previous = current;
                    var rows = current.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
                    list.ItemsSource = rows;
                    list.Visibility = rows.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
                    empty.Visibility = rows.Length == 0 ? Visibility.Visible : Visibility.Collapsed;
                    empty.Text = Display(snapshot);
                });
                return Described(Stack(Text(node.Label, "BodyStrongTextBlockStyle"), list, empty));

            case "field" when node.Field is { } key:
                var input = new TextBox
                {
                    Header = node.Label,
                    Text = run.PanelDrafts.GetValueOrDefault(key, ""),
                    MaxLength = 128,
                    PlaceholderText = node.Placeholder ?? "",
                };
                AutomationProperties.SetName(input, node.Label);
                input.TextChanged += (_, _) => { run.PanelDrafts[key] = input.Text; view.Refresh(); };
                return Described(input);

            case "button" when node.Action is { } action:
                var button = Button(node.Label, () => RunAsync(() => SendPanelActionAsync(run, action, revision)),
                    accent: node.Tone is not ("neutral" or "danger" or "error"));
                button.HorizontalAlignment = HorizontalAlignment.Stretch;
                button.MinHeight = 40;
                if (node.Tone is "danger" or "error") button.Style = Resource<Style>("SchemaCriticalButtonStyle");
                view.Bindings.Add(_ =>
                {
                    var granted = !run.ImportedPanel || run.AllowedPanelCommands.ContainsKey(action);
                    var valid = !run.ImportedPanel || !granted || CommandParameter.Matches(run.AllowedPanelCommands[action])
                        .All(parameter => run.PanelDrafts.GetValueOrDefault(parameter.Groups[1].Value, "").Trim() is { Length: > 0 and <= 128 } draft &&
                            !draft.Any(char.IsWhiteSpace));
                    button.IsEnabled = run.Connected && granted && valid;
                    ToolTipService.SetToolTip(button, !granted ? "导入时未授予此命令权限。" : null);
                });
                return Described(button);

            default:
                return Secondary("此面板控件无法显示。");
        }
    }

    private static Border SchemaDivider() => new()
    {
        Style = Resource<Style>("SchemaDividerStyle"),
    };

    private static string SchemaToneStyle(string? tone, string kind) => "Schema" + (tone switch
    {
        "danger" or "error" => "Critical",
        "warning" => "Warning",
        "success" => "Success",
        "neutral" => "Neutral",
        _ => "Primary",
    }) + kind + "Style";

    private static Grid SchemaColumns(IReadOnlyList<UIElement> elements, double minimumWidth, int maximumColumns)
    {
        var grid = new Grid { ColumnSpacing = 28, RowSpacing = 28 };
        foreach (var element in elements) grid.Children.Add(element);
        var current = 0;
        void Layout(double width)
        {
            var columns = Math.Clamp((int)((width + grid.ColumnSpacing) / (minimumWidth + grid.ColumnSpacing)), 1, maximumColumns);
            if (current == columns) return;
            current = columns;
            grid.ColumnDefinitions.Clear();
            grid.RowDefinitions.Clear();
            for (var column = 0; column < columns; column++) grid.ColumnDefinitions.Add(new ColumnDefinition());
            for (var row = 0; row < (elements.Count + columns - 1) / columns; row++)
                grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            for (var index = 0; index < elements.Count; index++)
            {
                if (elements[index] is not FrameworkElement element) continue;
                Grid.SetRow(element, index / columns);
                Grid.SetColumn(element, index % columns);
            }
        }
        Layout(0);
        grid.SizeChanged += (_, args) => Layout(args.NewSize.Width);
        return grid;
    }

    private void ApplyConsolePanel(ConsoleRun run, JsonElement payload, bool rebuild = false)
    {
        if (payload.ValueKind != JsonValueKind.Object || !IsCurrentConsole(run))
            return;
        var snapshot = payload.Deserialize<ConsolePanelSnapshot>(PanelJson)
            ?? throw new DaemonException("控制台面板数据格式无效。");
        if (run.Panel is { } current && current.Revision >= snapshot.Revision && !rebuild)
            return;
        if (run.Panel is null || run.Panel.Revision < snapshot.Revision)
            run.Panel = snapshot;
        if (consolePluginView is not { } view || tab != "console-panel")
            return;
        if (rebuild || view.PackageRevision != run.Panel.PackageRevision)
            BuildConsolePluginView(run, view);
        else
            view.Refresh();
    }

    private async Task ImportConsolePanelAsync(ConsoleRun run)
    {
        if (!IsCurrentConsole(run))
            return;
        var picker = new FileOpenPicker();
        picker.FileTypeFilter.Add(".zip");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));
        var file = await picker.PickSingleFileAsync();
        if (file is null || !IsCurrentConsole(run))
            return;
        var source = await Task.Run(() => ReadConsolePlugin(file.Path));
        await ImportConsolePanelSourceAsync(run, source);
    }

    private async Task ImportConsolePanelSourceAsync(ConsoleRun run, ConsolePluginSource source)
    {
        if (!IsCurrentConsole(run))
            return;
        var result = await CallAsync(run.Node, "consolePanelInspect", new { source });
        if (result.ValueKind != JsonValueKind.Object)
            throw new DaemonException("无法读取控制台插件的命令声明。");
        var commands = result.EnumerateObject().ToArray();
        var grants = new Dictionary<string, CheckBox>();
        using var manifest = JsonDocument.Parse(source.Manifest);
        var content = Stack(Text($"{manifest.RootElement.Field("id").StringValue()} · {manifest.RootElement.Field("version").StringValue()}",
                "BodyStrongTextBlockStyle"),
            Secondary("Schema 插件将读取当前实例会话的日志。仅授权需要的操作，每次发送命令前仍需确认。"));
        foreach (var command in commands)
        {
            var grant = new CheckBox { Content = CompactStack(Text(command.Value.StringValue()), Secondary(command.Name)) };
            AutomationProperties.SetName(grant, $"允许 {command.Value.StringValue()}");
            grants[command.Name] = grant;
            content.Children.Add(grant);
        }
        if (commands.Length == 0) content.Children.Add(Secondary("此插件仅解析日志，不包含命令操作。"));
        if (!await ConfirmAsync("加载 Schema 插件", new ScrollViewer { Content = content, MaxHeight = 360 }, "加载") || !IsCurrentConsole(run))
            return;
        var allowedCommands = grants.Where(pair => pair.Value.IsChecked == true).Select(pair => pair.Key).ToArray();
        var snapshot = await CallAsync(run.Node, "consolePanelImport",
            new { id = run.ServerId, streamEpoch = run.Epoch, source, allowedCommands });
        if (!IsCurrentConsole(run))
            return;
        run.ImportedPanel = true;
        run.AllowedPanelCommands = commands.Where(command => allowedCommands.Contains(command.Name))
            .ToDictionary(command => command.Name, command => command.Value.StringValue());
        run.PanelDrafts.Clear();
        ApplyConsolePanel(run, snapshot, rebuild: true);
        Notice("Schema 插件已载入。");
    }

    private async Task RestoreConsolePanelAsync(ConsoleRun run)
    {
        if (!IsCurrentConsole(run))
            return;
        var snapshot = await CallAsync(run.Node, "consolePanelRestore", new { id = run.ServerId, streamEpoch = run.Epoch });
        if (!IsCurrentConsole(run))
            return;
        run.ImportedPanel = false;
        run.AllowedPanelCommands.Clear();
        run.PanelDrafts.Clear();
        ApplyConsolePanel(run, snapshot, rebuild: true);
        Notice("已恢复内置 Schema。");
    }

    private async Task SendPanelActionAsync(ConsoleRun run, string actionId, long packageRevision)
    {
        if (tab != "console-panel" || !IsCurrentConsole(run) || !run.Connected || run.Panel?.PackageRevision != packageRevision ||
            (run.ImportedPanel && !run.AllowedPanelCommands.ContainsKey(actionId)))
            return;
        var arguments = new Dictionary<string, string>();
        if (run.ImportedPanel)
        {
            foreach (Match parameter in CommandParameter.Matches(run.AllowedPanelCommands[actionId]))
            {
                var key = parameter.Groups[1].Value;
                arguments[key] = run.PanelDrafts.GetValueOrDefault(key, "").Trim();
            }
        }
        var target = new { id = run.ServerId, streamEpoch = run.Epoch, packageRevision, actionId, arguments };
        var result = await CallAsync(run.Node, "consolePanelPrepareAction", target);
        var confirmedCommand = result.StringValue();
        if (confirmedCommand.Length == 0)
            throw new DaemonException("面板未生成有效命令。");
        if (run.ImportedPanel && !await ConfirmAsync("执行 Schema 命令",
            Stack(Secondary($"目标为 {run.Node.Connection.Name} 上的服务器 #{run.ServerId}。"),
                new TextBox { Text = confirmedCommand, IsReadOnly = true, TextWrapping = TextWrapping.Wrap }), "执行"))
            return;
        if (!IsCurrentConsole(run) || run.Panel?.PackageRevision != packageRevision)
            return;
        await CallAsync(run.Node, "consolePanelAction", new
        {
            target.id, target.streamEpoch, target.packageRevision, target.actionId, target.arguments, confirmedCommand,
        });
        Notice("面板命令已发送。");
    }

    private static ConsolePluginSource ReadConsolePlugin(string path)
    {
        try
        {
            using var file = File.OpenRead(path);
            if (file.Length > 512 * 1024)
                throw new DaemonException("控制台插件压缩包过大。");
            using var zip = new ZipArchive(file);
            var allowed = new HashSet<string>(StringComparer.Ordinal)
                { "plugin.json", "ui.xml", "rules.yaml", "business.lua" };
            var names = new HashSet<string>(StringComparer.Ordinal);
            if (zip.Entries.Count is < 3 or > 4 || zip.Entries.Any(entry =>
                    !allowed.Contains(entry.FullName) || !names.Add(entry.FullName) ||
                    ((entry.ExternalAttributes >> 16) & 0xF000) == 0xA000) ||
                !names.Contains("plugin.json") || !names.Contains("ui.xml") ||
                (!names.Contains("rules.yaml") && !names.Contains("business.lua")))
                throw new DaemonException("Schema 包根目录必须包含 plugin.json、ui.xml，以及 rules.yaml 或 business.lua，不能包含重复或其他文件。");

            string ReadEntry(string name, int maxFileBytes)
            {
                var entry = zip.GetEntry(name) ?? throw new InvalidDataException();
                if (entry.Length < (name == "rules.yaml" ? 0 : 1) || entry.Length > maxFileBytes)
                    throw new DaemonException("控制台插件文件为空或过大。");
                using var stream = entry.Open();
                using var bytes = new MemoryStream();
                var buffer = new byte[4096];
                int length;
                while ((length = stream.Read(buffer, 0, buffer.Length)) > 0)
                {
                    if (bytes.Length + length > maxFileBytes)
                        throw new DaemonException("控制台插件文件过大。");
                    bytes.Write(buffer, 0, length);
                }
                if (bytes.Length != entry.Length)
                    throw new InvalidDataException();
                return new UTF8Encoding(false, true).GetString(bytes.GetBuffer(), 0, (int)bytes.Length).TrimStart('\uFEFF');
            }

            return new ConsolePluginSource(ReadEntry("plugin.json", 16_384), ReadEntry("ui.xml", 65_536),
                names.Contains("rules.yaml") ? ReadEntry("rules.yaml", 65_536) : "",
                names.Contains("business.lua") ? ReadEntry("business.lua", 131_072) : "");
        }
        catch (Exception error) when (error is InvalidDataException or DecoderFallbackException)
        {
            throw new DaemonException("无法读取控制台插件。请使用有效的 UTF-8 ZIP 包。");
        }
    }
}
