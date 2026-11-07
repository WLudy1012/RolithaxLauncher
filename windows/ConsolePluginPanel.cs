using System.IO.Compression;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Windows.Storage.Pickers;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private sealed record ConsolePanelNode(string Id, string Kind, string Label, string? Field, string? Action,
        ConsolePanelNode[]? Children);

    private sealed record ConsolePanelSnapshot(long Revision, long PackageRevision, string PluginId, string Title,
        bool Stale, ConsolePanelNode[] Nodes, Dictionary<string, string> Values, Dictionary<string, string> Evidence);

    private sealed record ConsolePluginSource(string Manifest, string Ui, string Rules);

    private sealed class ConsolePluginView(ConsoleRun run)
    {
        public StackPanel Body { get; } = new() { Spacing = 12 };
        public List<Action<ConsolePanelSnapshot>> Bindings { get; } = [];
        public long? PackageRevision { get; set; }

        public void Refresh()
        {
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
        return new ScrollViewer { Content = view.Body };
    }

    private void BuildConsolePluginView(ConsoleRun run, ConsolePluginView view)
    {
        view.Body.Children.Clear();
        view.Bindings.Clear();
        view.PackageRevision = run.Panel?.PackageRevision;
        if (run.Epoch == 0)
        {
            view.Body.Children.Add(Card(Secondary("当前核心版本未提供控制台面板。请使用同一发布包中的应用与 DLL。")));
            return;
        }

        var status = Secondary(ConsoleConnectionStatus(run));
        var import = Button("导入面板插件", () => RunAsync(() => ImportConsolePanelAsync(run)));
        var restore = Button("恢复内置面板", () => RunAsync(() => RestoreConsolePanelAsync(run)));
        restore.IsEnabled = run.ImportedPanel;
        var description = (run.ImportedPanel, run.AllowedPanelCommands.Count) switch
        {
            (false, _) => "内置 Paper 面板从控制台日志提取信息。",
            (true, 0) => "此实例已加载只读插件。面板数据来自控制台日志。",
            _ => "此实例已加载本地插件。面板数据来自控制台日志。",
        };
        view.Body.Children.Add(Stack(
            Heading(run.Panel?.Title ?? "控制台面板", description),
            status, Actions(import, restore)));
        view.Bindings.Add(_ => status.Text = ConsoleConnectionStatus(run));
        if (run.Panel is not { } snapshot)
        {
            view.Body.Children.Add(Card(Secondary("正在读取面板…")));
            return;
        }

        var stale = new InfoBar
        {
            Severity = InfoBarSeverity.Warning,
            Message = "控制台日志可能有缺失，面板中的历史数据可能已过时。",
            IsClosable = false,
        };
        view.Bindings.Add(panel => stale.IsOpen = panel.Stale);
        view.Body.Children.Add(stale);
        foreach (var node in snapshot.Nodes)
            view.Body.Children.Add(BuildPanelNode(run, view, node, snapshot.PackageRevision));
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
                content.Children.Add(Stack(Text(labels.GetValueOrDefault(field, field), "BodyStrongTextBlockStyle"), evidence));
            }
        });
        return new Expander
        {
            Header = "查看日志依据",
            Content = content,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Stretch,
        };
    }

    private UIElement BuildPanelNode(ConsoleRun run, ConsolePluginView view, ConsolePanelNode node, long revision)
    {
        switch (node.Kind)
        {
            case "section":
                var section = Stack(Text(node.Label, "SubtitleTextBlockStyle"));
                foreach (var child in node.Children ?? [])
                    section.Children.Add(BuildPanelNode(run, view, child, revision));
                return Card(section);

            case "text":
            case "status":
                var value = Secondary("—");
                view.Bindings.Add(snapshot =>
                {
                    value.Text = node.Field is { } field && snapshot.Values.TryGetValue(field, out var text) && text.Length > 0 ? text : "—";
                });
                return node.Field is null ? Text(node.Label) : Stack(Text(node.Label, "BodyStrongTextBlockStyle"), value);

            case "list":
                var list = new ListView
                {
                    SelectionMode = ListViewSelectionMode.None,
                    MaxHeight = 210,
                    IsItemClickEnabled = false,
                };
                AutomationProperties.SetName(list, node.Label);
                var empty = Secondary("暂无记录");
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
                });
                return Stack(Text(node.Label, "BodyStrongTextBlockStyle"), list, empty);

            case "field" when node.Field is { } key:
                var input = new TextBox
                {
                    Header = node.Label,
                    Text = run.PanelDrafts.GetValueOrDefault(key, ""),
                    MaxLength = 128,
                };
                AutomationProperties.SetName(input, node.Label);
                input.TextChanged += (_, _) => run.PanelDrafts[key] = input.Text;
                return input;

            case "button" when node.Action is { } action:
                var button = Button(node.Label, () => RunAsync(() => SendPanelActionAsync(run, action, revision)));
                view.Bindings.Add(_ =>
                {
                    var granted = !run.ImportedPanel || run.AllowedPanelCommands.ContainsKey(action);
                    button.IsEnabled = run.Connected && granted;
                    ToolTipService.SetToolTip(button, !granted ? "导入时未授予此命令权限。" : null);
                });
                return button;

            default:
                return Secondary("此面板控件无法显示。");
        }
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
        var result = await CallAsync(run.Node, "consolePanelInspect", new { source });
        if (result.ValueKind != JsonValueKind.Object)
            throw new DaemonException("无法读取控制台插件的命令声明。");
        var commands = result.EnumerateObject().ToArray();
        var grant = new CheckBox { Content = "允许执行下列命令（每次执行前仍需确认）" };
        var content = Stack(Secondary("插件只在此实例的当前控制台会话生效。它将读取此会话的日志。"));
        if (commands.Length > 0)
        {
            content.Children.Add(grant);
            foreach (var command in commands)
                content.Children.Add(Secondary($"{command.Name}  ·  {command.Value.StringValue()}"));
        }
        if (!await ConfirmAsync("导入控制台插件", new ScrollViewer { Content = content, MaxHeight = 360 }, "导入") || !IsCurrentConsole(run))
            return;
        var allowedCommands = grant.IsChecked == true ? commands.Select(command => command.Name).ToArray() : [];
        var snapshot = await CallAsync(run.Node, "consolePanelImport",
            new { id = run.ServerId, streamEpoch = run.Epoch, source, allowedCommands });
        if (!IsCurrentConsole(run))
            return;
        run.ImportedPanel = true;
        run.AllowedPanelCommands = grant.IsChecked == true
            ? commands.ToDictionary(command => command.Name, command => command.Value.StringValue())
            : [];
        run.PanelDrafts.Clear();
        ApplyConsolePanel(run, snapshot, rebuild: true);
        Notice("控制台插件已载入。");
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
        Notice("已恢复内置面板。");
    }

    private async Task SendPanelActionAsync(ConsoleRun run, string actionId, long packageRevision)
    {
        if (tab != "console-panel" || !IsCurrentConsole(run) || !run.Connected || run.Panel?.PackageRevision != packageRevision ||
            (run.ImportedPanel && !run.AllowedPanelCommands.ContainsKey(actionId)))
            return;
        var arguments = new Dictionary<string, string>();
        if (run.ImportedPanel)
        {
            var parameter = CommandParameter.Match(run.AllowedPanelCommands[actionId]);
            if (parameter.Success)
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
        if (run.ImportedPanel && !await ConfirmAsync("执行面板命令",
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
            var expected = new[] { "plugin.json", "ui.xml", "rules.yaml" };
            if (zip.Entries.Count != expected.Length ||
                expected.Any(name => zip.Entries.Count(entry => entry.FullName == name) != 1))
                throw new DaemonException("压缩包根目录需要且只允许 plugin.json、ui.xml 和 rules.yaml。");

            string ReadEntry(string name, int maxFileBytes)
            {
                var entry = zip.GetEntry(name) ?? throw new InvalidDataException();
                if (entry.Length < 1 || entry.Length > maxFileBytes)
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

            return new ConsolePluginSource(ReadEntry(expected[0], 8_192), ReadEntry(expected[1], 65_536),
                ReadEntry(expected[2], 65_536));
        }
        catch (Exception error) when (error is InvalidDataException or DecoderFallbackException)
        {
            throw new DaemonException("无法读取控制台插件。请使用有效的 UTF-8 ZIP 包。");
        }
    }
}
