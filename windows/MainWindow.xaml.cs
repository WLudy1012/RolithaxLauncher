using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Windows.Foundation;
using Windows.Graphics;
using VirtualKey = Windows.System.VirtualKey;
using VirtualKeyModifiers = Windows.System.VirtualKeyModifiers;

namespace Rolithax.Windows;

public sealed partial class MainWindow : Window
{
    private sealed record Server(long Id, string Name, string Core, int Status, string StatusText, long Players)
    {
        public static Server From(JsonElement value)
        {
            var id = value.Field("id").LongValue();
            var status = (int)value.Field("status").LongValue();
            return new Server(id, value.Field("name").StringValue($"服务器 #{id}"),
                value.Field("core").StringValue(), status, value.Field("statusText").StringValue(status switch
                {
                    0 => "已停止",
                    1 => "启动中",
                    2 => "运行中",
                    3 => "停止中",
                    4 => "重启中",
                    _ => "未知",
                }), value.Field("extra").Field("onlinePlayers").LongValue());
        }
    }

    private sealed class Node(DaemonConnection connection, DaemonSession session)
    {
        public DaemonConnection Connection { get; } = connection;
        public DaemonSession Session { get; } = session;
        public Server[] Servers { get; set; } = [];
        public string? ServerListWarning { get; set; }
        public bool MonitorStarted { get; set; }
        public string? CreationId { get; set; }
        public string? CreationStatus { get; set; }
        public Dictionary<long, ResourceState> Resources { get; } = [];
    }

    private readonly List<Node> nodes = [];
    private List<DaemonConnection> saved = [];
    private bool savedAvailable = true;
    private Node? active;
    private Server? selectedServer;
    private string page = "overview";
    private string filter = "";
    private string statusFilter = "全部状态";
    private string sort = "名称";
    private string tab = "console-panel";
    private string remotePath = "";
    private bool busy;
    private bool closed;
    private readonly DispatcherQueueTimer pollTimer;
    private TextBlock? monitorText;
    private Meter? cpuMeter;
    private Meter? memoryMeter;
    private TextBox? serverSearch;

    public MainWindow()
    {
        InitializeComponent();
        Title = "Rolithax · 远端管理";
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        InitializeAppearance();
        AddWindowShortcuts();
        AppWindow.SetIcon(Path.Combine(AppContext.BaseDirectory, "Assets", "Rolithax.ico"));
        ResizeForDisplay();
        pollTimer = DispatcherQueue.CreateTimer();
        pollTimer.Interval = TimeSpan.FromMilliseconds(700);
        pollTimer.Tick += (_, _) =>
        {
            _ = PollConsoleAsync();
            _ = PollAsync();
        };
        pollTimer.Start();
        Navigation.SelectedItem = OverviewNav;
        Navigation.SelectionChanged += NavigationChanged;
        Root.Loaded += Initialize;
        Closed += (_, _) =>
        {
            closed = true;
            pollTimer.Stop();
            foreach (var node in nodes)
                _ = Task.Run(node.Session.Dispose);
        };
        Render();
    }

    private void ResizeForDisplay()
    {
        var area = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        var scale = Math.Max(96, GetDpiForWindow(WinRT.Interop.WindowNative.GetWindowHandle(this))) / 96.0;
        var width = Math.Min(area.Width - 32, (int)(1360 * scale));
        var height = Math.Min(area.Height - 32, (int)(840 * scale));
        AppWindow.MoveAndResize(new RectInt32(area.X + (area.Width - width) / 2,
            area.Y + (area.Height - height) / 2, width, height));
    }

    [DllImport("user32.dll")]
    private static extern uint GetDpiForWindow(IntPtr window);

    private async void Initialize(object sender, RoutedEventArgs args)
    {
        Root.Loaded -= Initialize;
        await RunAsync(async () =>
        {
            try
            {
                saved = await Task.Run(ConnectionStore.Load);
            }
            catch
            {
                savedAvailable = false;
                throw new DaemonException("无法读取已保存的连接。请检查当前 Windows 用户及连接配置。");
            }
            Render();
        });
    }

    private void NavigationChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.SelectedItem is not NavigationViewItem { Tag: string target })
            return;
        if (target != "instances")
            LeaveServer();
        Feedback.IsOpen = false;
        page = target;
        Render();
        if (page == "users" && active is { } node)
            _ = RunAsync(() => ShowUsersAsync(node));
    }

    private void Navigate(string target)
    {
        if (page == target)
            return;
        foreach (var item in Navigation.MenuItems.OfType<NavigationViewItem>().Concat(Navigation.FooterMenuItems.OfType<NavigationViewItem>()))
        {
            if (item.Tag as string != target)
                continue;
            Navigation.SelectedItem = item;
            return;
        }
    }

    private void Render()
    {
        ResetConsoleViews();
        monitorText = null;
        cpuMeter = null;
        memoryMeter = null;
        serverSearch = null;
        creationText = null;
        resourceTaskView = null;
        PaneStatus.Text = active?.Connection.Name ?? "尚未连接";
        PageHost.Children.Clear();
        workspaceBody = null;
        userResults = null;
        if (page == "instances" && selectedServer is { } server && active is { } node)
        {
            PageHost.Children.Add(ServerWorkspace(node, server));
            return;
        }

        var body = new StackPanel { Spacing = 16, Margin = new Thickness(24, 24, 24, 28),
            MaxWidth = 1180, HorizontalAlignment = HorizontalAlignment.Stretch };
        PageHost.Children.Add(new ScrollViewer { Content = body, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled });
        switch (page)
        {
            case "connections": ShowConnections(body); break;
            case "instances": ShowInstances(body); break;
            case "users": ShowUserShell(body); break;
            case "settings": ShowSettings(body); break;
            default: ShowOverview(body); break;
        }
    }

    private async Task RunAsync(Func<Task> operation)
    {
        if (busy || closed)
            return;
        busy = true;
        Navigation.IsEnabled = false;
        Feedback.IsOpen = false;
        BusyIndicator.IsActive = true;
        try
        {
            await operation();
        }
        catch (DaemonException error)
        {
            Notice(error.Message, InfoBarSeverity.Error);
        }
        catch (DllNotFoundException)
        {
            Notice("找不到 Daemon 核心。请完整解压 Windows 应用文件夹。", InfoBarSeverity.Error);
        }
        catch (EntryPointNotFoundException)
        {
            Notice("Daemon 核心版本不匹配。请使用同一个发布包中的应用与 DLL。", InfoBarSeverity.Error);
        }
        catch (Exception)
        {
            Notice("操作未完成。请检查连接和本地文件访问权限后重试。", InfoBarSeverity.Error);
        }
        finally
        {
            busy = false;
            if (!closed)
            {
                Navigation.IsEnabled = true;
                BusyIndicator.IsActive = false;
            }
        }
    }

    private void Notice(string message, InfoBarSeverity severity = InfoBarSeverity.Success)
    {
        if (closed)
            return;
        Feedback.Severity = severity;
        Feedback.Message = message;
        Feedback.IsOpen = true;
    }

    private Task<JsonElement> CallAsync(Node node, string operation, object? args = null) =>
        node.Session.CallAsync(operation, args, node.Connection.ApiKey);

    private async Task RefreshAsync(Node node, bool allowUnavailable = false)
    {
        try
        {
            var list = await CallAsync(node, "instances");
            if (list.ValueKind != JsonValueKind.Array)
                throw new DaemonException("Daemon 未返回服务器列表。");
            node.Servers = list.Items().Select(Server.From).ToArray();
        }
        catch (DaemonException error)
        {
            node.ServerListWarning = error.Status switch
            {
                401 => "服务器列表请求未通过认证，请重试。",
                403 => "当前账户没有读取服务器列表的权限。",
                _ => "暂时无法读取服务器列表，请重试。",
            };
            node.Servers = [];
            if (!allowUnavailable)
                throw new DaemonException(node.ServerListWarning, error.Status);
            return;
        }
        node.ServerListWarning = null;
        if (active == node && selectedServer is { } server)
        {
            selectedServer = node.Servers.FirstOrDefault(item => item.Id == server.Id);
            if (selectedServer is null)
                LeaveServer();
        }
    }

    private Task RefreshDisplayedListAsync(Node node) => RunAsync(async () =>
    {
        await RefreshAsync(node, allowUnavailable: true);
        if (!closed)
            Render();
    });

    private void SelectNode(Node node)
    {
        if (active == node)
            return;
        LeaveServer();
        active = node;
        Feedback.IsOpen = false;
        filter = "";
        statusFilter = "全部状态";
    }

    private void AddWindowShortcuts()
    {
        var pages = new[] { "overview", "instances", "connections", "users", "settings" };
        for (var index = 0; index < pages.Length; index++)
        {
            var target = pages[index];
            AddShortcut((VirtualKey)((int)VirtualKey.Number1 + index), VirtualKeyModifiers.Control, () =>
            {
                Navigate(target);
                return true;
            });
        }
        AddShortcut(VirtualKey.F, VirtualKeyModifiers.Control,
            () => serverSearch?.Focus(FocusState.Programmatic) == true);
        AddShortcut(VirtualKey.F5, VirtualKeyModifiers.None, () =>
        {
            if (active is not { } node || selectedServer is not null || page is not ("overview" or "instances"))
                return false;
            _ = RefreshDisplayedListAsync(node);
            return true;
        });
    }

    private void AddShortcut(VirtualKey key, VirtualKeyModifiers modifiers, Func<bool> action)
    {
        var shortcut = new KeyboardAccelerator { Key = key, Modifiers = modifiers };
        shortcut.Invoked += (_, args) => args.Handled = !busy && !closed && action();
        Root.KeyboardAccelerators.Add(shortcut);
    }

    private void ShowOverview(StackPanel body)
    {
        body.Children.Add(PageHeading("控制中心", "查看节点负载和运行中的服务器。",
            Button("添加连接", () => RunAsync(() => EditConnectionAsync(null)), accent: true)));
        var metrics = new Grid { ColumnSpacing = 12 };
        var values = new (string Label, string Value)[]
        {
            ("已连接节点", nodes.Count.ToString(CultureInfo.InvariantCulture)),
            ("当前节点实例", active is { ServerListWarning: null } loaded
                ? loaded.Servers.Length.ToString(CultureInfo.InvariantCulture) : "—"),
            ("运行中", active is { ServerListWarning: null } current
                ? current.Servers.Count(item => item.Status == 2).ToString(CultureInfo.InvariantCulture) : "—"),
        };
        for (var index = 0; index < values.Length; index++)
        {
            metrics.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var (label, value) = values[index];
            var metric = Card(CompactStack(Text(label, "CaptionTextBlockStyle"), Text(value, "TitleTextBlockStyle")));
            metric.Padding = new Thickness(12, 10, 12, 10);
            Grid.SetColumn(metric, index);
            metrics.Children.Add(metric);
        }
        body.Children.Add(metrics);
        if (active is not { } node)
        {
            body.Children.Add(Card(Stack(
                Text("还没有连接的 Daemon", "SubtitleTextBlockStyle"),
                Secondary("连接远端 Daemon 后，可以在这里查看服务器状态和宿主机负载。"),
                Actions(Button("管理连接", () => Navigate("connections")),
                    Button("粘贴配对码", () => RunAsync(PairAsync))))));
            return;
        }

        monitorText = Secondary("等待宿主机监控数据…");
        cpuMeter = new Meter("CPU");
        memoryMeter = new Meter("内存");
        var nodeCard = Card(Stack(
            Text("当前 Daemon", "BodyStrongTextBlockStyle"), NodePicker(node, showHeader: false),
            Secondary(node.Connection.Address), Actions(
                Button("查看服务器", () => Navigate("instances"), accent: true),
                Button("刷新", () => RefreshDisplayedListAsync(node)))));
        var monitorCard = Card(Stack(Text("宿主机负载", "BodyStrongTextBlockStyle"),
            cpuMeter.View, memoryMeter.View, monitorText));
        body.Children.Add(AdaptivePair(nodeCard, monitorCard, 630, equalColumns: true));

        var runningRows = Section("运行中的服务器");
        if (node.ServerListWarning is { } warning)
            runningRows.Children.Add(Secondary(warning));
        else
        {
            var running = node.Servers.Where(server => server.Status == 2).Take(4).ToArray();
            if (running.Length == 0)
                runningRows.Children.Add(Secondary("当前节点没有运行中的服务器。"));
            foreach (var server in running)
                runningRows.Children.Add(ServerRow(server, Button("打开控制台", () => OpenServer(node, server, "console-panel"))));
        }
        body.Children.Add(Card(runningRows));
    }

    private sealed class Meter
    {
        private readonly TextBlock label = Text("—", "CaptionTextBlockStyle");
        private readonly ProgressBar bar = new() { Maximum = 100 };
        public StackPanel View { get; }

        public Meter(string name)
        {
            var row = new Grid();
            row.Children.Add(Text(name, "CaptionTextBlockStyle"));
            label.HorizontalAlignment = HorizontalAlignment.Right;
            row.Children.Add(label);
            AutomationProperties.SetName(bar, name + "使用率");
            View = CompactStack(row, bar);
        }

        public void Set(double? value)
        {
            var percentage = value is { } number && double.IsFinite(number)
                ? Math.Clamp(number, 0, 100) : (double?)null;
            bar.Value = percentage ?? 0;
            label.Text = percentage is { } shown ? $"{shown:0.#}%" : "—";
        }
    }

    private void UpdateNodeMonitor(JsonElement result)
    {
        if (!result.Flag("connected"))
        {
            ResetNodeMonitor("等待宿主机监控连接…");
            return;
        }
        cpuMeter?.Set(result.Field("cpu").NumberValue());
        memoryMeter?.Set(result.Field("memUsage").NumberValue());
        if (monitorText is { } notice)
            notice.Visibility = Visibility.Collapsed;
    }

    private void ResetNodeMonitor(string message)
    {
        cpuMeter?.Set(null);
        memoryMeter?.Set(null);
        if (monitorText is { } notice)
        {
            notice.Text = message;
            notice.Visibility = Visibility.Visible;
        }
    }

    private void ShowInstances(StackPanel body)
    {
        var node = active;
        body.Children.Add(PageHeading("服务器", "查找实例，查看控制台、文件和配置。",
            node is null ? null : Button("新建服务器", () => RunAsync(() => CreateServerAsync(node)), accent: true)));
        if (node is null)
        {
            body.Children.Add(Card(Stack(Text("请先连接 Daemon", "BodyStrongTextBlockStyle"),
                Button("管理连接", () => Navigate("connections"), accent: true))));
            return;
        }

        var search = new TextBox { Text = filter, PlaceholderText = "按名称、核心或 ID 搜索" };
        serverSearch = search;
        AutomationProperties.SetName(search, "搜索服务器");
        ToolTipService.SetToolTip(search, "搜索服务器（Ctrl+F）");
        var status = Choices(["全部状态", "运行中", "已停止", "切换中"], statusFilter);
        AutomationProperties.SetName(status, "按运行状态筛选");
        var order = Choices(["名称", "ID"], sort);
        AutomationProperties.SetName(order, "服务器排序");
        var refresh = Button("刷新列表", () => RefreshDisplayedListAsync(node));
        ToolTipService.SetToolTip(refresh, "刷新列表（F5）");
        body.Children.Add(Card(ServerToolbar(NodePicker(node, showHeader: false), search, Actions(status, order, refresh))));
        if (node.CreationId is { } creationId)
            body.Children.Add(CreationPanel(node, creationId));

        var list = new StackPanel { Spacing = 10 };
        body.Children.Add(Card(list));
        void UpdateList()
        {
            list.Children.Clear();
            if (node.ServerListWarning is { } warning)
            {
                list.Children.Add(Secondary(warning));
                return;
            }
            var matching = node.Servers.Where(server =>
                (filter.Length == 0 || server.Name.Contains(filter, StringComparison.OrdinalIgnoreCase) ||
                    server.Core.Contains(filter, StringComparison.OrdinalIgnoreCase) || server.Id.ToString(CultureInfo.InvariantCulture).Contains(filter)) &&
                (statusFilter switch { "运行中" => server.Status == 2, "已停止" => server.Status == 0, "切换中" => server.Status is 1 or 3 or 4, _ => true }));
            var sorted = (sort == "ID" ? matching.OrderBy(server => server.Id)
                : matching.OrderBy(server => server.Name, StringComparer.CurrentCultureIgnoreCase)).ToArray();
            list.Children.Add(Text($"{sorted.Length} 台服务器", "BodyStrongTextBlockStyle"));
            foreach (var server in sorted)
            {
                var lifecycle = Button(server.Status == 0 ? "启动" : "停止", () => RunAsync(() =>
                    ServerActionAsync(node, server, server.Status == 0 ? "start" : "stop")));
                lifecycle.IsEnabled = server.Status is 0 or 2;
                list.Children.Add(ServerRow(server,
                    Button("控制台", () => OpenServer(node, server, "console-panel")),
                    Button("文件", () => OpenServer(node, server, "files")), lifecycle));
            }
            if (sorted.Length == 0)
                list.Children.Add(Secondary(node.Servers.Length == 0 ? "当前节点还没有服务器。" : "没有符合筛选条件的服务器。"));
        }
        search.TextChanged += (_, _) => { filter = search.Text.Trim(); UpdateList(); };
        status.SelectionChanged += (_, _) => { statusFilter = (string)status.SelectedItem; UpdateList(); };
        order.SelectionChanged += (_, _) => { sort = (string)order.SelectedItem; UpdateList(); };
        UpdateList();
    }

    private static Grid ServerToolbar(ComboBox node, TextBox search, Panel actions)
    {
        var grid = new Grid { ColumnSpacing = 8, RowSpacing = 8 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        for (var index = 0; index < 3; index++)
            grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.Children.Add(node);
        grid.Children.Add(search);
        grid.Children.Add(actions);

        void Arrange(double width)
        {
            var compact = width < 750;
            var narrow = width < 510;
            Grid.SetColumnSpan(node, compact ? 3 : 1);
            Grid.SetRow(search, compact ? 1 : 0);
            Grid.SetColumn(search, compact ? 0 : 1);
            Grid.SetColumnSpan(search, narrow ? 3 : compact ? 2 : 1);
            Grid.SetRow(actions, narrow ? 2 : compact ? 1 : 0);
            Grid.SetColumn(actions, narrow ? 0 : 2);
            Grid.SetColumnSpan(actions, narrow ? 3 : 1);
        }
        Arrange(0);
        grid.SizeChanged += (_, args) => Arrange(args.NewSize.Width);
        return grid;
    }

    private static Grid ServerRow(Server server, params UIElement[] commands) =>
        AdaptivePair(CompactStack(Text(server.Name, "BodyStrongTextBlockStyle"), Secondary(ServerDetail(server))),
            Actions([ServerState(server), ..commands]), 530);

    private static string ServerDetail(Server server)
    {
        var id = $"#{server.Id}";
        var prefix = server.Core.Length == 0 ? id : $"{server.Core}  ·  {id}";
        return server.Status == 2 ? $"{prefix}  ·  {server.Players} 位玩家" : prefix;
    }

    private static Border ServerState(Server server) => new()
    {
        Style = Resource<Style>(server.Status == 2 ? "RolithaxSuccessPillStyle" : "RolithaxNeutralPillStyle"),
        Child = Text(server.StatusText, "CaptionTextBlockStyle"),
        VerticalAlignment = VerticalAlignment.Center,
    };

    private ComboBox NodePicker(Node current, bool showHeader = true)
    {
        var select = new ComboBox { Header = showHeader ? "当前 Daemon" : null };
        AutomationProperties.SetName(select, "当前 Daemon");
        foreach (var node in nodes)
        {
            var label = Text(node.Connection.Name);
            label.TextWrapping = TextWrapping.NoWrap;
            label.TextTrimming = TextTrimming.CharacterEllipsis;
            label.MaxWidth = 250;
            var option = new ComboBoxItem { Content = label, Tag = node };
            AutomationProperties.SetName(option, node.Connection.Name);
            ToolTipService.SetToolTip(option, node.Connection.Address);
            select.Items.Add(option);
        }
        select.SelectedIndex = nodes.IndexOf(current);
        select.SelectionChanged += (_, _) =>
        {
            if (select.SelectedItem is ComboBoxItem { Tag: Node node } && active != node)
            {
                SelectNode(node);
                Render();
            }
        };
        return select;
    }

    private void ShowSettings(StackPanel body)
    {
        body.Children.Add(Heading("设置", "调整界面外观，查看账户和版本信息。"));
        AddAppearanceSettings(body);
        if (active is { } node)
            body.Children.Add(Card(Section("账户", Entry("当前账户", node.Connection.Name,
                Button("查看资料", () => RunAsync(() => ShowProfileAsync(node)))))));
        body.Children.Add(Card(Section("关于", CompactStack(
            Text("Rolithax Launcher", "BodyStrongTextBlockStyle"), Secondary("Windows 远端管理  ·  WinUI 3")))));
    }

    private static Grid PageHeading(string title, string description, FrameworkElement? action) =>
        AdaptivePair(Heading(title, description), action, 530);

    private static StackPanel Heading(string title, string description)
    {
        var heading = Text(title, "TitleTextBlockStyle");
        heading.FontSize = 30;
        AutomationProperties.SetHeadingLevel(heading, AutomationHeadingLevel.Level1);
        return CompactStack(heading, Secondary(description));
    }

    private static StackPanel Section(string title, params UIElement[] items)
    {
        var heading = Text(title, "SubtitleTextBlockStyle");
        heading.FontSize = 16;
        AutomationProperties.SetHeadingLevel(heading, AutomationHeadingLevel.Level2);
        return Stack([heading, ..items]);
    }

    private static StackPanel CompactStack(params UIElement[] items)
    {
        var panel = Stack(items);
        panel.Spacing = 4;
        return panel;
    }

    private static Grid AdaptivePair(FrameworkElement first, FrameworkElement? second, double breakpoint,
        bool equalColumns = false)
    {
        var grid = new Grid { ColumnSpacing = 12, RowSpacing = 8 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        grid.Children.Add(first);
        if (second is null)
            return grid;
        var side = new ColumnDefinition { Width = new GridLength(0) };
        grid.ColumnDefinitions.Add(side);
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        grid.Children.Add(second);
        bool? compactLayout = null;
        void Layout(bool compact)
        {
            if (compactLayout == compact)
                return;
            compactLayout = compact;
            side.Width = compact ? new GridLength(0) : equalColumns ? new GridLength(1, GridUnitType.Star) : GridLength.Auto;
            Grid.SetRow(second, compact ? 1 : 0);
            Grid.SetColumn(second, compact ? 0 : 1);
            second.HorizontalAlignment = equalColumns || compact ? HorizontalAlignment.Stretch : HorizontalAlignment.Right;
            second.VerticalAlignment = equalColumns ? VerticalAlignment.Stretch : VerticalAlignment.Center;
        }
        Layout(true);
        grid.SizeChanged += (_, args) => Layout(args.NewSize.Width < breakpoint);
        return grid;
    }

    private static TextBlock Text(string value, string style = "BodyTextBlockStyle") =>
        new() { Text = value, Style = Resource<Style>(style), TextWrapping = TextWrapping.Wrap };

    private static TextBlock Secondary(string value) => Text(value, "RolithaxSecondaryTextStyle");

    private static T Resource<T>(string key) => (T)Application.Current.Resources[key];

    private static StackPanel Stack(params UIElement[] items)
    {
        var result = new StackPanel { Spacing = 8 };
        foreach (var item in items)
            result.Children.Add(item);
        return result;
    }

    private static Panel Actions(params UIElement[] items)
    {
        var result = new ActionPanel();
        foreach (var item in items)
            result.Children.Add(item);
        return result;
    }

    private sealed class ActionPanel : Panel
    {
        protected override Size MeasureOverride(Size availableSize) => Layout(availableSize, arrange: false);

        protected override Size ArrangeOverride(Size finalSize)
        {
            Layout(finalSize, arrange: true);
            return finalSize;
        }

        private Size Layout(Size available, bool arrange)
        {
            const double gap = 8;
            double x = 0, y = 0, lineHeight = 0, width = 0;
            foreach (var child in Children)
            {
                if (child.Visibility == Visibility.Collapsed)
                    continue;
                if (!arrange)
                    child.Measure(new Size(available.Width, double.PositiveInfinity));
                var desired = child.DesiredSize;
                if (x > 0 && x + desired.Width > available.Width)
                {
                    y += lineHeight + gap;
                    x = lineHeight = 0;
                }
                if (arrange)
                    child.Arrange(new Rect(x, y, desired.Width, desired.Height));
                width = Math.Max(width, x + desired.Width);
                lineHeight = Math.Max(lineHeight, desired.Height);
                x += desired.Width + gap;
            }
            return new Size(width, y + lineHeight);
        }
    }

    private static Border Card(UIElement child) => new()
    {
        Style = Resource<Style>("RolithaxCardStyle"),
        Child = child,
    };

    private static Grid Entry(string title, string subtitle, FrameworkElement action) =>
        AdaptivePair(CompactStack(Text(title, "BodyStrongTextBlockStyle"), Secondary(subtitle)), action, 530);

    private static Button Button(string title, Action action, bool accent = false)
    {
        var button = new Button { Content = title, MinHeight = 32 };
        if (accent)
            button.Style = Resource<Style>("AccentButtonStyle");
        button.Click += (_, _) => action();
        return button;
    }

    private static Button Button(string title, Func<Task> action, bool accent = false) =>
        Button(title, (Action)(async () => await action()), accent);

    private static ComboBox Choices(string[] choices, string selected) =>
        new() { ItemsSource = choices, SelectedItem = selected, HorizontalAlignment = HorizontalAlignment.Stretch };

    private async Task<bool> ConfirmAsync(string title, object content, string action, string cancel = "取消",
        Func<string?>? validate = null)
    {
        var dialog = new ContentDialog
        {
            XamlRoot = Root.XamlRoot,
            Title = title,
            Content = content,
            PrimaryButtonText = action,
            CloseButtonText = cancel,
            DefaultButton = ContentDialogButton.Close,
        };
        if (validate is not null && content is UIElement form)
        {
            var validation = new InfoBar { Severity = InfoBarSeverity.Error, IsClosable = false };
            dialog.Content = Stack(form, validation);
            dialog.PrimaryButtonClick += (_, args) =>
            {
                var error = validate();
                validation.IsOpen = error is not null;
                if (error is null)
                    return;
                args.Cancel = true;
                validation.Message = error;
            };
        }
        return await dialog.ShowAsync() == ContentDialogResult.Primary;
    }
}
