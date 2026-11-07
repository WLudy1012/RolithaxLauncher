using System.Globalization;
using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Windows.System;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private sealed class ConsoleRun(Node node, long serverId, long epoch)
    {
        public Node Node { get; } = node;
        public long ServerId { get; } = serverId;
        public long Epoch { get; } = epoch;
        public bool Polling { get; set; }
        public bool Connected { get; set; }
        public long LastSequence { get; set; }
        public List<string> Lines { get; } = [];
        public List<string> CommandHistory { get; } = [];
        public int HistoryIndex { get; set; }
        public string Draft { get; set; } = "";
        public string Filter { get; set; } = "";
        public bool Follow { get; set; } = true;
        public int SelectionStart { get; set; }
        public int SelectionLength { get; set; }
        public bool EulaRequired { get; set; }
        public bool EulaAcknowledged { get; set; }
        public ConsolePanelSnapshot? Panel { get; set; }
        public bool ImportedPanel { get; set; }
        public Dictionary<string, string> AllowedPanelCommands { get; set; } = [];
        public Dictionary<string, string> PanelDrafts { get; } = [];
    }

    private ConsoleRun? consoleRun;
    private Task consoleStop = Task.CompletedTask;
    private bool polling;
    private TextBox? consoleOutput;
    private TextBlock? consoleStatus;
    private Button? consoleSend;
    private Button? consoleEula;

    private Task OpenServer(Node node, Server server, string destination) => RunAsync(async () =>
    {
        SelectNode(node);
        LeaveServer();
        selectedServer = server;
        remotePath = "";
        if (page != "instances")
            Navigate("instances");
        await ChangeTabAsync(node, server, destination);
    });

    private void LeaveServer()
    {
        selectedServer = null;
        StopConsole();
    }

    private void StopConsole()
    {
        if (consoleRun is { } run)
        {
            consoleRun = null;
            consoleStop = StopConsoleAsync(run.Node);
        }
        ResetConsoleViews();
    }

    private void ResetConsoleViews()
    {
        consoleOutput = null;
        consoleStatus = null;
        consoleSend = null;
        consoleEula = null;
        consolePluginView = null;
    }

    private async Task StopConsoleAsync(Node node)
    {
        try { await CallAsync(node, "consoleStop"); }
        catch (Exception) { }
    }

    private async Task ChangeTabAsync(Node node, Server server, string destination)
    {
        tab = destination;
        if ((destination is "console" or "console-panel") && ConsoleFor(node, server) is null)
        {
            try
            {
                await consoleStop;
                var start = await CallAsync(node, "consoleStart", new { id = server.Id });
                if (closed)
                    return;
                var epoch = start.Field("streamEpoch").LongValue();
                consoleRun = new ConsoleRun(node, server.Id, epoch);
            }
            catch
            {
                Render();
                throw;
            }
        }
        Render();
        switch (destination)
        {
            case "files":
                await ShowFilesAsync(node, server);
                break;
            case "configuration":
                await ShowServerSettingsAsync(node, server);
                break;
            case "addons":
                await ShowAddonsAsync(node, server, "plugins");
                break;
            case "resources":
                ShowResources(node, server);
                break;
        }
    }

    private ConsoleRun? ConsoleFor(Node node, Server server) =>
        consoleRun is { } run && run.Node == node && run.ServerId == server.Id ? run : null;

    private bool IsCurrentConsole(ConsoleRun run) =>
        !closed && consoleRun == run && active == run.Node && selectedServer?.Id == run.ServerId;

    private Grid? workspaceBody;

    private Grid ServerWorkspace(Node node, Server server)
    {
        var root = new Grid { Margin = new Thickness(28, 22, 28, 24), RowSpacing = 12 };
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        var primary = Button(server.Status == 0 ? "启动" : "停止",
            () => RunAsync(() => ServerActionAsync(node, server, server.Status == 0 ? "start" : "stop")), accent: server.Status == 0);
        primary.IsEnabled = server.Status is 0 or 2;
        var restart = Button("重启", () => RunAsync(() => ServerActionAsync(node, server, "restart")));
        restart.IsEnabled = server.Status == 2;
        var backup = Button("备份", () => RunAsync(() => ServerActionAsync(node, server, "backup")));
        backup.IsEnabled = server.Status == 2;
        var more = new DropDownButton { Content = "更多" };
        var flyout = new MenuFlyout();
        var force = new MenuFlyoutItem { Text = "强制停止", IsEnabled = server.Status != 0 };
        force.Click += async (_, _) => await RunAsync(() => ServerActionAsync(node, server, "forceExit"));
        var remove = new MenuFlyoutItem { Text = "删除服务器", IsEnabled = server.Status == 0 };
        remove.Click += async (_, _) => await RunAsync(() => DeleteServerAsync(node, server));
        flyout.Items.Add(force);
        flyout.Items.Add(remove);
        more.Flyout = flyout;
        root.Children.Add(Stack(
            Button("‹  返回服务器列表", () => { LeaveServer(); Render(); }),
            Heading(server.Name, $"{node.Connection.Name}  ·  {server.StatusText}  ·  #{server.Id}"),
            Actions(primary, restart, backup, more, Button("刷新状态", () => RunAsync(async () =>
            {
                await RefreshAsync(node);
                if (selectedServer is { } refreshed)
                    await ChangeTabAsync(node, refreshed, tab);
                else
                    Render();
            })))));
        var selector = new SelectorBar();
        foreach (var (key, label) in new (string, string)[]
            { ("console-panel", "面板"), ("console", "原始控制台"), ("files", "文件"), ("configuration", "配置"),
                ("addons", "插件 / 模组"), ("resources", "资源") })
        {
            var item = new SelectorBarItem { Text = label, Tag = key };
            selector.Items.Add(item);
            if (key == tab)
                selector.SelectedItem = item;
        }
        selector.SelectionChanged += async (_, _) =>
        {
            if (selector.SelectedItem?.Tag is string destination && destination != tab)
                await RunAsync(() => ChangeTabAsync(node, server, destination));
        };
        Grid.SetRow(selector, 1);
        root.Children.Add(selector);
        workspaceBody = new Grid();
        Grid.SetRow(workspaceBody, 2);
        root.Children.Add(workspaceBody);
        if (tab is "console" or "console-panel")
            workspaceBody.Children.Add(ConsoleFor(node, server) is { } run
                ? tab == "console" ? ConsolePanel(run, server) : ConsolePluginPanel(run)
                : Card(Stack(Secondary("控制台尚未打开。"), Button("连接控制台",
                    () => RunAsync(() => ChangeTabAsync(node, server, tab)), accent: true))));
        return root;
    }

    private async Task ServerActionAsync(Node node, Server server, string action)
    {
        var label = action switch { "start" => "启动", "stop" => "停止", "restart" => "重启",
            "backup" => "备份", "forceExit" => "强制停止", _ => throw new InvalidOperationException() };
        if (action is "stop" or "restart" or "forceExit")
        {
            var detail = $"{label} {server.Name}？";
            if (action == "forceExit")
                detail += "进程可能来不及保存数据。";
            if (!await ConfirmAsync(label + "服务器", Secondary(detail), label))
                return;
        }
        var reply = await CallAsync(node, "action", new { id = server.Id, action });
        Notice(reply.StringValue($"{label}指令已发送。"));
        if (action == "backup")
            return;
        await RefreshAsync(node);
        if (selectedServer is { } refreshed)
        {
            Render();
            if (tab == "files")
                await ShowFilesAsync(node, refreshed);
            else if (tab == "configuration")
                await ShowServerSettingsAsync(node, refreshed);
            else if (tab == "addons")
                await ShowAddonsAsync(node, refreshed, "plugins");
            else if (tab == "resources")
                ShowResources(node, refreshed);
        }
        else
            Render();
    }

    private async Task DeleteServerAsync(Node node, Server server)
    {
        var files = new CheckBox { Content = "同时删除服务器文件" };
        if (!await ConfirmAsync("删除服务器", Stack(
            Secondary($"删除 {node.Connection.Name} 上的 {server.Name}（#{server.Id}）？此操作无法撤销。"), files), "删除"))
            return;
        var reply = await CallAsync(node, "deleteInstance", new { id = server.Id, deleteFiles = files.IsChecked == true });
        LeaveServer();
        await RefreshAsync(node);
        Render();
        Notice(reply.StringValue("已删除服务器。"));
    }

    private Grid ConsolePanel(ConsoleRun run, Server server)
    {
        var panel = new Grid { RowSpacing = 8 };
        panel.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        panel.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        panel.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var search = new TextBox { PlaceholderText = "筛选日志", Text = run.Filter, MinWidth = 142 };
        AutomationProperties.SetName(search, "筛选控制台日志");
        var follow = new CheckBox { Content = "跟随输出", IsChecked = run.Follow };
        var copy = Button("复制日志", () =>
        {
            if (!IsCurrentConsole(run) || consoleOutput is null || consoleOutput.Text.Length == 0)
                return;
            var package = new global::Windows.ApplicationModel.DataTransfer.DataPackage();
            package.SetText(consoleOutput.Text);
            global::Windows.ApplicationModel.DataTransfer.Clipboard.SetContent(package);
            Notice("已复制当前日志。");
        });
        consoleEula = Button("查看 EULA", () => RunAsync(async () =>
        {
            if (!await ConfirmAsync("同意 Minecraft EULA",
                Stack(Secondary("启动服务器前需要接受 Minecraft 最终用户许可协议。"),
                    new HyperlinkButton { Content = "阅读 Minecraft EULA", NavigateUri = new Uri("https://aka.ms/MinecraftEULA") }),
                "同意并启动"))
                return;
            if (!IsCurrentConsole(run))
                return;
            await CallAsync(run.Node, "action", new { id = server.Id, action = "agreeEula?true" });
            run.EulaAcknowledged = true;
            if (consoleEula is { } button)
                button.Visibility = Visibility.Collapsed;
            Notice("已发送同意和启动指令。");
        }));
        consoleEula.Visibility = run.EulaRequired && !run.EulaAcknowledged ? Visibility.Visible : Visibility.Collapsed;
        consoleStatus = Secondary(ConsoleConnectionStatus(run));
        panel.Children.Add(Stack(Actions(search, follow, copy, consoleEula), consoleStatus));
        consoleOutput = new TextBox
        {
            IsReadOnly = true,
            AcceptsReturn = true,
            FontFamily = new FontFamily("Cascadia Mono, Consolas"),
            FontSize = 14,
            TextWrapping = TextWrapping.Wrap,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            VerticalAlignment = VerticalAlignment.Stretch,
        };
        AutomationProperties.SetName(consoleOutput, "控制台日志");
        var output = consoleOutput;
        output.SelectionChanged += (_, _) =>
        {
            if (!run.Follow)
            {
                run.SelectionStart = output.SelectionStart;
                run.SelectionLength = output.SelectionLength;
            }
        };
        ScrollViewer.SetVerticalScrollBarVisibility(consoleOutput, ScrollBarVisibility.Auto);
        Grid.SetRow(consoleOutput, 1);
        panel.Children.Add(consoleOutput);
        search.TextChanged += (_, _) => { run.Filter = search.Text; UpdateLog(run); };
        follow.Checked += (_, _) => { run.Follow = true; UpdateLog(run); };
        follow.Unchecked += (_, _) => run.Follow = false;
        var input = new TextBox { PlaceholderText = "输入控制台指令", MaxLength = 8192, Text = run.Draft };
        AutomationProperties.SetName(input, "服务器控制台指令");
        input.TextChanged += (_, _) => run.Draft = input.Text;
        async Task Send()
        {
            var command = input.Text.Trim();
            if (tab != "console" || command.Length == 0 || !run.Connected || !IsCurrentConsole(run))
                return;
            await RunAsync(async () =>
            {
                await CallAsync(run.Node, "consoleSend", run.Epoch == 0
                    ? (object)new { command }
                    : new { id = run.ServerId, streamEpoch = run.Epoch, command });
                if (run.CommandHistory.Count == 0 || run.CommandHistory[^1] != command)
                {
                    run.CommandHistory.Add(command);
                    if (run.CommandHistory.Count > 100)
                        run.CommandHistory.RemoveAt(0);
                }
                run.HistoryIndex = run.CommandHistory.Count;
                input.Text = "";
            });
            input.Focus(FocusState.Programmatic);
        }
        consoleSend = Button("发送", Send, accent: true);
        consoleSend.IsEnabled = run.Connected;
        input.KeyDown += async (_, args) =>
        {
            switch (args.Key)
            {
                case VirtualKey.Enter:
                    args.Handled = true;
                    await Send();
                    break;
                case VirtualKey.Up:
                case VirtualKey.Down:
                    if (run.CommandHistory.Count == 0)
                        return;
                    run.HistoryIndex = Math.Clamp(run.HistoryIndex + (args.Key == VirtualKey.Up ? -1 : 1), 0, run.CommandHistory.Count);
                    input.Text = run.HistoryIndex == run.CommandHistory.Count ? "" : run.CommandHistory[run.HistoryIndex];
                    input.SelectionStart = input.Text.Length;
                    args.Handled = true;
                    break;
            }
        };
        var row = new Grid { ColumnSpacing = 8 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.Children.Add(input);
        Grid.SetColumn(consoleSend, 1);
        row.Children.Add(consoleSend);
        Grid.SetRow(row, 2);
        panel.Children.Add(row);
        UpdateLog(run);
        return panel;
    }

    private void UpdateLog(ConsoleRun run)
    {
        if (consoleOutput is null || !IsCurrentConsole(run))
            return;
        var offset = run.SelectionStart;
        var length = run.SelectionLength;
        consoleOutput.Text = string.Join(Environment.NewLine, run.Lines.Where(line =>
            run.Filter.Length == 0 || line.Contains(run.Filter, StringComparison.OrdinalIgnoreCase)));
        if (run.Follow)
            consoleOutput.Select(consoleOutput.Text.Length, 0);
        else
        {
            var start = Math.Min(offset, consoleOutput.Text.Length);
            consoleOutput.Select(start, Math.Min(length, consoleOutput.Text.Length - start));
        }
    }

    private static string ConsoleConnectionStatus(ConsoleRun run) =>
        run.Connected ? "控制台已连接" : "等待控制台连接…";

    private async Task PollConsoleAsync()
    {
        if (consoleRun is not { Polling: false } run || !IsCurrentConsole(run))
            return;
        run.Polling = true;
        try
        {
            var result = await CallAsync(run.Node, "consolePoll", run.Epoch == 0
                ? (object)new { afterSequence = run.LastSequence }
                : new { id = run.ServerId, streamEpoch = run.Epoch, afterSequence = run.LastSequence,
                    afterPanelRevision = run.Panel?.Revision ?? -1, limit = 128 });
            if (!IsCurrentConsole(run))
                return;
            run.Connected = result.Flag("connected");
            run.EulaRequired = result.Flag("eulaRequired");
            var lines = result.Field("lines").Items();
            if (result.Field("firstAvailableSequence").LongValue() > run.LastSequence + 1)
                run.Lines.Add("［部分日志已超出缓冲区］");
            foreach (var line in lines)
            {
                var sequence = line.Field("sequence").LongValue();
                if (sequence <= run.LastSequence)
                    continue;
                run.LastSequence = sequence;
                run.Lines.Add(line.Field("line").StringValue());
            }
            if (run.Lines.Count > 500)
                run.Lines.RemoveRange(0, run.Lines.Count - 500);
            if (lines.Length > 0)
                UpdateLog(run);
            ApplyConsolePanel(run, result.Field("panel"));
            RefreshConsoleStatus(run);
        }
        catch (Exception)
        {
            if (!IsCurrentConsole(run))
                return;
            run.Connected = false;
            RefreshConsoleStatus(run);
            if (consoleStatus is { } status)
                status.Text = "控制台连接暂不可用，正在重试。";
        }
        finally
        {
            run.Polling = false;
        }
    }

    private void RefreshConsoleStatus(ConsoleRun run)
    {
        if (consoleStatus is { } status)
            status.Text = ConsoleConnectionStatus(run);
        if (consoleSend is { } send)
            send.IsEnabled = run.Connected;
        if (consoleEula is { } eula)
            eula.Visibility = run.EulaRequired && !run.EulaAcknowledged ? Visibility.Visible : Visibility.Collapsed;
        consolePluginView?.Refresh();
    }

    private async Task PollAsync()
    {
        if (polling || busy || closed || active is not { } node)
            return;
        polling = true;
        try
        {
            if (page == "overview" && monitorText is { } monitor)
            {
                if (!node.MonitorStarted)
                {
                    await CallAsync(node, "monitorStart");
                    node.MonitorStarted = true;
                }
                var result = await CallAsync(node, "monitorPoll");
                if (!closed && active == node && monitorText == monitor)
                {
                    var cpu = result.Field("cpu").NumberValue();
                    var memory = result.Field("memUsage").NumberValue();
                    monitor.Text = !result.Flag("connected") ? "等待宿主机监控连接…"
                        : $"CPU {Percent(cpu)}  ·  内存 {Percent(memory)}";
                }
            }
            await PollCreationAsync(node);
            await PollResourcesAsync(node);
        }
        catch (Exception)
        {
            if (!closed && active == node && monitorText is { } monitor)
                monitor.Text = "宿主机监控暂不可用。";
        }
        finally
        {
            polling = false;
        }
    }

    private static string Percent(double? value) =>
        value is { } finite && double.IsFinite(finite) ? finite.ToString("0.0", CultureInfo.CurrentCulture) + "%" : "—";

    private void SetWorkspace(params UIElement[] elements)
    {
        if (workspaceBody is null)
            return;
        var body = Stack(elements);
        body.Spacing = 12;
        workspaceBody.Children.Clear();
        workspaceBody.Children.Add(new ScrollViewer { Content = body });
    }
}
