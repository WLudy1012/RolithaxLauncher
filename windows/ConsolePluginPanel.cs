using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private sealed record SchemaCommandProbe(string Id, string Title, string Description);
    private sealed record SchemaCheck(SchemaPreflightCheck Requirement, string Status,
        string? Version = null, SchemaCommandProbe? Command = null)
    {
        public string Label
        {
            get
            {
                var kind = Requirement.Kind switch { "mod" => "模组", "minecraftPlugin" => "Minecraft 插件", _ => "MCDR 插件" };
                var observed = Version is { } version ? $"检测到 {version}" : Status == "incompatible" ? "未找到" : "无法确认";
                return $"{kind} {Requirement.Id}：要求 {Requirement.Version}，{observed}";
            }
        }
    }

    private sealed record ConsolePanelNode(string Id, string Kind, string Label, string? Field, string? Action,
        ConsolePanelNode[]? Children, string? Description = null, string? Tone = null, string? Unit = null,
        string? Placeholder = null, string? Asset = null, string? Value = null);

    private sealed record ConsolePanelSnapshot(long Revision, long PackageRevision, string PluginId, string Title,
        bool Stale, ConsolePanelNode[] Nodes, Dictionary<string, string> Values, Dictionary<string, string> Evidence);

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
        var installed = new MenuFlyoutItem { Text = "选择已安装插件", Icon = new SymbolIcon(Symbol.Library) };
        installed.Click += async (_, _) => await RunAsync(() => SelectInstalledSchemaAsync(run));
        management.Items.Add(installed);
        var restore = new MenuFlyoutItem { Text = "恢复内置 Schema", IsEnabled = run.ImportedPanel };
        restore.Click += async (_, _) => await RunAsync(() => RestoreConsolePanelAsync(run));
        management.Items.Add(restore);
        var manage = new DropDownButton
        {
            Content = run.Panel?.Title ?? "Rolithax Schema", Flyout = management, MinWidth = 180, MaxWidth = 350,
        };
        AutomationProperties.SetName(manage, "选择或管理 Schema 插件");
        var source = (run.ImportedPanel, run.AllowedPanelGrants.Count) switch
        {
            (false, _) => "内置 Schema",
            (true, 0) => "当前插件 · 只读",
            _ => $"当前插件 · 已授权 {run.AllowedPanelGrants.Count} 项操作",
        };
        view.Body.Children.Add(AdaptivePair(CompactStack(manage, Secondary(source)),
            (FrameworkElement)Actions(Button("插件中心", () => Navigate("schema")), status), 560));
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
        if (run.SchemaCompatibility is { Length: > 0 } compatibility)
        {
            var result = new InfoBar
            {
                IsOpen = true, IsClosable = false,
                Severity = run.ImportedPanel && run.SchemaCompatibilityAllowsActions ? InfoBarSeverity.Informational : InfoBarSeverity.Warning,
                Message = compatibility,
            };
            if (run.SchemaChecks.Length > 0)
                result.ActionButton = Button("重新检查", () => RunAsync(async () =>
                {
                    await RestoreSchemaBindingAsync(run, interactive: true);
                    if (IsCurrentConsole(run) && tab == "console-panel") BuildConsolePluginView(run, view);
                }));
            view.Body.Children.Add(result);
            if (run.SchemaChecks.Length > 0)
            {
                var requirements = CompactStack(run.SchemaChecks.Select(check => (UIElement)Secondary(check)).ToArray());
                view.Body.Children.Add(new Expander { Header = "版本要求", Content = requirements,
                    HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch });
            }
        }
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

            case "expander":
                var expanded = Stack((node.Children ?? []).Select(child => BuildPanelNode(run, view, child, revision)).ToArray());
                return Described(new Expander { Header = node.Label, Content = expanded,
                    HorizontalAlignment = HorizontalAlignment.Stretch, HorizontalContentAlignment = HorizontalAlignment.Stretch });

            case "tabs":
                var tabs = new SelectorBar();
                AutomationProperties.SetName(tabs, node.Label);
                var tabContent = new ContentControl { HorizontalContentAlignment = HorizontalAlignment.Stretch };
                foreach (var child in node.Children ?? [])
                    tabs.Items.Add(new SelectorBarItem { Text = child.Label,
                        Tag = Stack((child.Children ?? []).Select(item => BuildPanelNode(run, view, item, revision)).ToArray()) });
                tabs.SelectionChanged += (_, _) => tabContent.Content = tabs.SelectedItem?.Tag;
                tabs.SelectedItem = tabs.Items.FirstOrDefault();
                var tabStrip = new ScrollViewer { Content = tabs, VerticalScrollMode = ScrollMode.Disabled,
                    VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto };
                return Described(Stack(Text(node.Label, "BodyStrongTextBlockStyle"), tabStrip, tabContent));

            case "image":
            case "video":
                return Described(SchemaMedia(run, node));

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

            case "toggle" when node.Field is { } toggleKey:
                var initial = node.Value == "true" ? "true" : "false";
                var toggle = new ToggleSwitch { Header = node.Label,
                    IsOn = run.PanelDrafts.GetValueOrDefault(toggleKey, initial) == "true" };
                run.PanelDrafts[toggleKey] = toggle.IsOn ? "true" : "false";
                toggle.Toggled += (_, _) => { run.PanelDrafts[toggleKey] = toggle.IsOn ? "true" : "false"; view.Refresh(); };
                return Described(toggle);

            case "select" when node.Field is { } choiceKey:
                var select = new ComboBox { Header = node.Label, HorizontalAlignment = HorizontalAlignment.Stretch };
                foreach (var option in node.Children ?? [])
                    if (option.Value is { } choiceValue)
                        select.Items.Add(new ComboBoxItem { Content = option.Label, Tag = choiceValue });
                var selected = run.PanelDrafts.GetValueOrDefault(choiceKey, node.Value ?? "");
                select.SelectedItem = select.Items.OfType<ComboBoxItem>().FirstOrDefault(item => (string)item.Tag == selected);
                if (select.SelectedItem is null) run.PanelDrafts.Remove(choiceKey);
                else run.PanelDrafts[choiceKey] = selected;
                select.SelectionChanged += (_, _) =>
                {
                    if (select.SelectedItem is ComboBoxItem { Tag: string selectedValue }) run.PanelDrafts[choiceKey] = selectedValue;
                    else run.PanelDrafts.Remove(choiceKey);
                    view.Refresh();
                };
                return Described(select);

            case "button" when node.Action is { } action:
                var button = Button(node.Label, () => RunAsync(() => SendPanelActionAsync(run, action, revision)),
                    accent: node.Tone is not ("neutral" or "danger" or "error"));
                button.HorizontalAlignment = HorizontalAlignment.Stretch;
                button.MinHeight = 40;
                if (node.Tone is "danger" or "error") button.Style = Resource<Style>("SchemaCriticalButtonStyle");
                view.Bindings.Add(_ =>
                {
                    var granted = !run.ImportedPanel || run.AllowedPanelGrants.Contains(action);
                    var valid = !run.AllowedPanelCommands.TryGetValue(action, out var template) || CommandParameter.Matches(template)
                        .All(parameter => run.PanelDrafts.GetValueOrDefault(parameter.Groups[1].Value, "").Trim() is { Length: > 0 and <= 128 } draft &&
                            !draft.Any(char.IsWhiteSpace));
                    button.IsEnabled = run.Connected && granted && valid && run.SchemaCompatibilityAllowsActions;
                    ToolTipService.SetToolTip(button, !granted ? "此操作尚未授权。" :
                        !run.SchemaCompatibilityAllowsActions ? "请先完成插件兼容性检查。" : null);
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

    private async Task SelectInstalledSchemaAsync(ConsoleRun run)
    {
        if (!IsCurrentConsole(run)) return;
        var options = schemaLibrary.Installed().OrderBy(item => item.Metadata.Name, StringComparer.CurrentCultureIgnoreCase).ToArray();
        if (options.Length == 0)
        {
            if (await ConfirmAsync("没有已安装插件", Secondary("请先在插件中心安装或导入插件。"), "打开插件中心"))
                Navigate("schema");
            return;
        }
        var picker = new ComboBox { Header = "Schema 插件", HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var item in options)
            picker.Items.Add(new ComboBoxItem { Tag = item, Content = $"{item.Metadata.Name} · {item.Metadata.Version}" });
        picker.SelectedIndex = Math.Max(0, Array.FindIndex(options, option => option.Metadata.Id == run.SchemaPackage?.Metadata.Id));
        if (!await ConfirmAsync("选择 Schema 插件",
            Stack(Secondary($"仅切换 {run.Node.Connection.Name} 的当前实例。"), picker), "配置权限") ||
            !IsCurrentConsole(run) || picker.SelectedItem is not ComboBoxItem { Tag: SchemaInstallation installation }) return;

        var package = await Task.Run(() => schemaLibrary.Load(installation));
        var checks = await CheckSchemaAsync(run, package, offerCommands: true);
        if (!IsCurrentConsole(run) || !await ConfirmSchemaChecksAsync(checks) || !IsCurrentConsole(run)) return;
        var permissions = new Dictionary<string, CheckBox>();
        var current = schemaLibrary.Binding(run.Node.Connection.Id, run.SchemaBindingKey);
        var previousGrants = current?.Installation.Digest == installation.Digest ? current.Value.Binding.Grants : [];
        var content = Stack(Text($"{package.Metadata.Name} · {package.Metadata.Version}", "BodyStrongTextBlockStyle"),
            Secondary("插件可根据当前实例日志绘制界面。每项操作都需要单独授权。"));
        foreach (var (action, command) in package.Commands)
            AddGrant(action, command, "服务器控制台命令");
        foreach (var (action, details) in package.Actions)
        {
            var description = details.Field("kind").StringValue() == "host-task"
                ? $"宿主机任务 · {details.Field("taskId").StringValue()}"
                : $"{FileOperationName(details.Field("operation").StringValue())} · {details.Field("root").StringValue()}/{details.Field("path").StringValue()}";
            AddGrant(action, description, "Daemon 操作");
        }
        if (permissions.Count == 0) content.Children.Add(Secondary("此插件无需操作权限。"));
        if (!await ConfirmAsync("授权并启用", new ScrollViewer { Content = content, MaxHeight = 395 }, "启用") ||
            !IsCurrentConsole(run)) return;
        var grants = permissions.Where(permission => permission.Value.IsChecked == true).Select(permission => permission.Key).ToArray();
        await LoadSchemaAsync(run, package, grants, checks);
        if (!IsCurrentConsole(run)) return;
        await Task.Run(() => schemaLibrary.Bind(run.Node.Connection.Id, run.SchemaBindingKey, installation, grants));
        Notice("已为此实例启用 Schema 插件。");

        void AddGrant(string action, string title, string kind)
        {
            var grant = new CheckBox
            {
                IsChecked = previousGrants.Contains(action),
                Content = CompactStack(Text(title), Secondary(kind)),
            };
            AutomationProperties.SetName(grant, $"允许 {title}");
            permissions.Add(action, grant);
            content.Children.Add(grant);
        }
    }

    private async Task RestoreSchemaBindingAsync(ConsoleRun run, bool interactive = false)
    {
        var binding = schemaLibrary.Binding(run.Node.Connection.Id, run.SchemaBindingKey);
        if (binding is not { } saved || !IsCurrentConsole(run)) return;
        if (run.ImportedPanel) run.SchemaCompatibilityAllowsActions = false;
        try
        {
            var package = await Task.Run(() => schemaLibrary.Load(saved.Installation));
            var checks = await CheckSchemaAsync(run, package, interactive);
            if (!IsCurrentConsole(run)) return;
            var allow = checks.All(check => check.Status == "compatible") ||
                interactive && await ConfirmSchemaChecksAsync(checks);
            if (!IsCurrentConsole(run)) return;
            if (allow)
            {
                await LoadSchemaAsync(run, package, saved.Binding.Grants, checks);
                return;
            }
            if (run.ImportedPanel) await RestoreConsolePanelAsync(run, clearBinding: false);
            if (!IsCurrentConsole(run)) return;
            run.SchemaChecks = checks.Select(check => check.Label).ToArray();
            run.SchemaCompatibility = checks.Any(check => check.Status == "incompatible")
                ? "已绑定的插件版本要求未满足，当前使用内置 Schema。"
                : "请确认已绑定插件的版本要求后再启用。当前使用内置 Schema。";
        }
        catch (Exception error) when (error is InvalidDataException or IOException or UnauthorizedAccessException or JsonException or DaemonException)
        {
            run.SchemaCompatibility = "无法载入已绑定的插件。请从插件中心检查或重新安装。";
        }
    }

    private async Task LoadSchemaAsync(ConsoleRun run, SchemaPackage package, string[] grants, SchemaCheck[] checks)
    {
        if (!IsCurrentConsole(run)) return;
        var snapshot = await CallAsync(run.Node, "consolePanelImport",
            new { id = run.ServerId, streamEpoch = run.Epoch, source = package.Source, allowedCommands = grants });
        if (!IsCurrentConsole(run)) return;
        run.ImportedPanel = true;
        run.SchemaPackage = package;
        run.SchemaChecks = checks.Select(check => check.Label).ToArray();
        run.SchemaCompatibility = checks.Length == 0 ? "" : checks.Any(check => check.Status == "unknown")
            ? "部分版本无法确认，已按你的选择启用。" : "已验证插件所需版本。";
        run.SchemaCompatibilityAllowsActions = true;
        run.AllowedPanelGrants = grants.ToHashSet(StringComparer.Ordinal);
        run.AllowedPanelCommands = package.Commands.Where(command => run.AllowedPanelGrants.Contains(command.Key))
            .ToDictionary(command => command.Key, command => command.Value);
        run.PanelDrafts.Clear();
        ApplyConsolePanel(run, snapshot, rebuild: true);
        if (checks.Length == 0 || run.Panel is not { } panel) return;
        await SendSchemaResultAsync(run, panel.PackageRevision, "preflight", new
        {
            checks = checks.Select(check => new
            {
                kind = check.Requirement.Kind,
                id = check.Requirement.Id,
                expected = check.Requirement.Version,
                version = check.Version,
                status = check.Status,
            }),
        });
    }

    private async Task<SchemaCheck[]> CheckSchemaAsync(ConsoleRun run, SchemaPackage package, bool offerCommands)
    {
        var results = new List<SchemaCheck>();
        foreach (var probe in package.Preflight)
        {
            if (!IsCurrentConsole(run)) return [];
            results.Add(await ReadSchemaCheckAsync(run, probe));
        }
        if (!offerCommands || results.Any(check => check.Status == "incompatible")) return [.. results];
        for (var i = 0; i < results.Count; i++)
        {
            if (!IsCurrentConsole(run)) return [];
            var check = results[i];
            if (check.Command is not { } command) continue;
            var accepted = await ConfirmAsync("检查插件版本", Stack(Secondary(check.Label),
                Text(command.Title, "BodyStrongTextBlockStyle"), Secondary(command.Description),
                Secondary("将在当前实例关联的 Daemon 上运行管理员登记的只读版本探测。")), "运行探测", "跳过");
            if (!IsCurrentConsole(run)) return [];
            if (!accepted) continue;
            results[i] = await ReadSchemaCheckAsync(run, check.Requirement, command.Id);
            if (results[i].Status == "incompatible") break;
        }
        return [.. results];
    }

    private async Task<SchemaCheck> ReadSchemaCheckAsync(ConsoleRun run, SchemaPreflightCheck probe, string? commandProbeId = null)
    {
        try
        {
            var reply = await CallAsync(run.Node, "schemaPreflight",
                new { id = run.ServerId, kind = probe.Kind, target = probe.Id, commandProbeId });
            var version = reply.Field("version").StringValue();
            var status = reply.Field("status").StringValue() switch
            {
                "found" when version.Length > 0 => probe.Version == version ? "compatible" : "incompatible",
                "missing" => "incompatible",
                _ => "unknown",
            };
            var descriptor = reply.Field("commandProbe");
            var command = status == "unknown" && descriptor.ValueKind == JsonValueKind.Object
                ? descriptor.Deserialize<SchemaCommandProbe>(PanelJson) : null;
            return new(probe, status, version.Length == 0 ? null : version, command);
        }
        catch (DaemonException) { return new(probe, "unknown"); }
    }

    private async Task<bool> ConfirmSchemaChecksAsync(SchemaCheck[] checks)
    {
        if (checks.All(check => check.Status == "compatible")) return true;
        var list = new ScrollViewer
        {
            MaxHeight = 320,
            Content = CompactStack(checks.Select(check => (UIElement)Secondary(check.Label)).ToArray()),
        };
        if (!checks.Any(check => check.Status == "incompatible"))
            return await ConfirmAsync("部分版本无法确认", list, "仍然启用");
        var dialog = new ContentDialog
        {
            XamlRoot = Root.XamlRoot,
            RequestedTheme = Root.ActualTheme,
            Title = "版本要求未满足",
            Content = list,
            CloseButtonText = "关闭",
        };
        await dialog.ShowAsync();
        return false;
    }

    private async Task RestoreConsolePanelAsync(ConsoleRun run, bool clearBinding = true)
    {
        if (!IsCurrentConsole(run))
            return;
        var snapshot = await CallAsync(run.Node, "consolePanelRestore", new { id = run.ServerId, streamEpoch = run.Epoch });
        if (!IsCurrentConsole(run))
            return;
        run.ImportedPanel = false;
        run.SchemaPackage = null;
        run.SchemaCompatibility = "";
        run.SchemaChecks = [];
        run.SchemaCompatibilityAllowsActions = true;
        run.AllowedPanelGrants.Clear();
        run.AllowedPanelCommands.Clear();
        run.PanelDrafts.Clear();
        ApplyConsolePanel(run, snapshot, rebuild: true);
        if (clearBinding)
        {
            await Task.Run(() => schemaLibrary.ClearBinding(run.Node.Connection.Id, run.SchemaBindingKey));
            Notice("已恢复内置 Schema。");
        }
    }

    private async Task SendPanelActionAsync(ConsoleRun run, string actionId, long packageRevision)
    {
        if (tab != "console-panel" || !IsCurrentConsole(run) || !run.Connected || run.Panel?.PackageRevision != packageRevision ||
            !run.SchemaCompatibilityAllowsActions || (run.ImportedPanel && !run.AllowedPanelGrants.Contains(actionId)))
            return;
        if (run.SchemaPackage?.Actions.ContainsKey(actionId) == true)
        {
            await ExecuteSchemaIntentAsync(run, packageRevision, actionId);
            return;
        }
        var arguments = new Dictionary<string, string>();
        if (run.AllowedPanelCommands.TryGetValue(actionId, out var template))
        {
            foreach (Match parameter in CommandParameter.Matches(template))
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

}
