using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Graphics;

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

    private sealed class Node(DaemonConnection connection, DaemonSession session, Server[] servers)
    {
        public DaemonConnection Connection { get; } = connection;
        public DaemonSession Session { get; } = session;
        public Server[] Servers { get; set; } = servers;
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

    public MainWindow()
    {
        InitializeComponent();
        Title = "Rolithax · 远端管理";
        SystemBackdrop = new MicaBackdrop();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        AppWindow.TitleBar.PreferredTheme = TitleBarTheme.UseDefaultAppMode;
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
        var width = Math.Min(area.Width - 32, (int)(1240 * scale));
        var height = Math.Min(area.Height - 32, (int)(820 * scale));
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

        var body = new StackPanel { Spacing = 20, Margin = new Thickness(28, 24, 28, 32),
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

    private async Task RefreshAsync(Node node)
    {
        node.Servers = (await CallAsync(node, "instances")).Items().Select(Server.From).ToArray();
        if (active == node && selectedServer is { } server)
        {
            selectedServer = node.Servers.FirstOrDefault(item => item.Id == server.Id);
            if (selectedServer is null)
                LeaveServer();
        }
    }

    private void SelectNode(Node node)
    {
        if (active == node)
            return;
        LeaveServer();
        active = node;
        filter = "";
        statusFilter = "全部状态";
    }

    private void ShowOverview(StackPanel body)
    {
        body.Children.Add(Heading("控制中心", "查看 Daemon 连接、服务器与宿主机负载。"));
        var metrics = new Grid { ColumnSpacing = 12 };
        var values = new (string Label, string Value)[]
        {
            ("已连接", nodes.Count.ToString(CultureInfo.InvariantCulture)),
            ("当前节点实例", active?.Servers.Length.ToString(CultureInfo.InvariantCulture) ?? "—"),
            ("运行中", active?.Servers.Count(item => item.Status == 2).ToString(CultureInfo.InvariantCulture) ?? "—"),
        };
        for (var index = 0; index < values.Length; index++)
        {
            metrics.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var (label, value) = values[index];
            var metric = Card(Stack(Text(label, "CaptionTextBlockStyle"), Text(value, "TitleTextBlockStyle")));
            Grid.SetColumn(metric, index);
            metrics.Children.Add(metric);
        }
        body.Children.Add(metrics);
        body.Children.Add(Text("当前节点", "SubtitleTextBlockStyle"));
        if (active is not { } node)
        {
            body.Children.Add(Card(Stack(
                Text("还没有连接的 Daemon", "BodyStrongTextBlockStyle"),
                Secondary("添加一台远端 Daemon，即可查看服务器并使用控制台。"),
                Button("管理连接", () => Navigate("connections"), accent: true))));
            return;
        }

        monitorText = Secondary("等待宿主机监控数据…");
        body.Children.Add(Card(Stack(NodePicker(node), Secondary(node.Connection.Address), monitorText,
            Actions(Button("查看服务器", () => Navigate("instances"), accent: true),
                Button("刷新", () => RunAsync(async () => { await RefreshAsync(node); Render(); }))))));
        var running = node.Servers.Where(server => server.Status == 2).Take(4).ToArray();
        if (running.Length == 0)
            return;
        body.Children.Add(Text("运行中的服务器", "SubtitleTextBlockStyle"));
        foreach (var server in running)
            body.Children.Add(Card(Entry(server.Name, server.Core,
                Button("打开控制台", () => OpenServer(node, server, "console-panel")))));
    }

    private void ShowInstances(StackPanel body)
    {
        body.Children.Add(Heading("服务器", "查找实例，查看控制台、文件和配置。"));
        if (active is not { } node)
        {
            body.Children.Add(Card(Stack(Text("请先连接 Daemon", "BodyStrongTextBlockStyle"),
                Button("管理连接", () => Navigate("connections"), accent: true))));
            return;
        }

        var filters = new Grid { ColumnSpacing = 12, RowSpacing = 8 };
        filters.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        filters.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        filters.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        filters.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        var search = new TextBox { Text = filter, PlaceholderText = "按名称、核心或 ID 搜索" };
        AutomationProperties.SetName(search, "搜索服务器");
        filters.Children.Add(search);
        var status = Choices(["全部状态", "运行中", "已停止", "切换中"], statusFilter);
        AutomationProperties.SetName(status, "按运行状态筛选");
        Grid.SetColumn(status, 1);
        filters.Children.Add(status);
        var switcher = NodePicker(node);
        Grid.SetRow(switcher, 1);
        filters.Children.Add(switcher);
        var order = Choices(["名称", "ID"], sort);
        AutomationProperties.SetName(order, "服务器排序");
        Grid.SetColumn(order, 1);
        Grid.SetRow(order, 1);
        filters.Children.Add(order);
        body.Children.Add(filters);
        body.Children.Add(Actions(
            Button("新建服务器", () => RunAsync(() => CreateServerAsync(node)), accent: true),
            Button("刷新列表", () => RunAsync(async () => { await RefreshAsync(node); Render(); }))));
        if (node.CreationId is { } creationId)
            body.Children.Add(CreationPanel(node, creationId));
        var list = new StackPanel { Spacing = 10 };
        body.Children.Add(list);
        void UpdateList()
        {
            list.Children.Clear();
            var matching = node.Servers.Where(server =>
                (filter.Length == 0 || server.Name.Contains(filter, StringComparison.OrdinalIgnoreCase) ||
                    server.Core.Contains(filter, StringComparison.OrdinalIgnoreCase) || server.Id.ToString(CultureInfo.InvariantCulture).Contains(filter)) &&
                (statusFilter switch { "运行中" => server.Status == 2, "已停止" => server.Status == 0, "切换中" => server.Status is 1 or 3 or 4, _ => true }));
            var sorted = (sort == "ID" ? matching.OrderBy(server => server.Id)
                : matching.OrderBy(server => server.Name, StringComparer.CurrentCultureIgnoreCase)).ToArray();
            list.Children.Add(Secondary($"{sorted.Length} 台服务器"));
            foreach (var server in sorted)
            {
                var detail = $"{server.StatusText}  ·  #{server.Id}" + (server.Core.Length > 0 ? $"  ·  {server.Core}" : "");
                var lifecycle = Button(server.Status == 0 ? "启动" : "停止", () => RunAsync(() =>
                    ServerActionAsync(node, server, server.Status == 0 ? "start" : "stop")));
                lifecycle.IsEnabled = server.Status is 0 or 2;
                list.Children.Add(Card(Stack(Text(server.Name, "BodyStrongTextBlockStyle"), Secondary(detail),
                    Actions(Button("控制台", () => OpenServer(node, server, "console-panel")),
                        lifecycle, Button("文件", () => OpenServer(node, server, "files"))))));
            }
            if (sorted.Length == 0)
                list.Children.Add(Card(Secondary(node.Servers.Length == 0 ? "当前节点还没有服务器。" : "没有符合筛选条件的服务器。")));
        }
        search.TextChanged += (_, _) => { filter = search.Text.Trim(); UpdateList(); };
        status.SelectionChanged += (_, _) => { statusFilter = (string)status.SelectedItem; UpdateList(); };
        order.SelectionChanged += (_, _) => { sort = (string)order.SelectedItem; UpdateList(); };
        UpdateList();
    }

    private ComboBox NodePicker(Node current)
    {
        var select = new ComboBox { Header = "当前 Daemon", HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var node in nodes)
            select.Items.Add(new ComboBoxItem { Content = $"{node.Connection.Name} · {node.Connection.Address}", Tag = node });
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
        body.Children.Add(Heading("设置", "调整界面外观，查看当前连接的账户。"));
        var theme = Choices(["跟随系统", "浅色", "深色"], Root.RequestedTheme switch
        {
            ElementTheme.Light => "浅色",
            ElementTheme.Dark => "深色",
            _ => "跟随系统",
        });
        theme.Header = "应用主题";
        theme.SelectionChanged += (_, _) =>
        {
            Root.RequestedTheme = theme.SelectedIndex switch
            {
                1 => ElementTheme.Light,
                2 => ElementTheme.Dark,
                _ => ElementTheme.Default,
            };
            AppWindow.TitleBar.PreferredTheme = Root.RequestedTheme switch
            {
                ElementTheme.Light => TitleBarTheme.Light,
                ElementTheme.Dark => TitleBarTheme.Dark,
                _ => TitleBarTheme.UseDefaultAppMode,
            };
        };
        body.Children.Add(Card(Stack(theme, Secondary("当前窗口使用此设置。"))));
        if (active is { } node)
            body.Children.Add(Card(Entry("当前账户", node.Connection.Name,
                Button("查看资料", () => RunAsync(() => ShowProfileAsync(node))))));
        body.Children.Add(Card(Stack(Text("Rolithax Launcher", "BodyStrongTextBlockStyle"),
            Secondary("Windows 远端管理 · WinUI 3 · .NET 10"))));
    }

    private static StackPanel Heading(string title, string description) =>
        Stack(Text(title, "TitleLargeTextBlockStyle"), Secondary(description));

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

    private static ItemsControl Actions(params UIElement[] items)
    {
        var result = new ItemsControl();
        result.ItemsPanel = (ItemsPanelTemplate)Application.Current.Resources["WrappingActions"];
        foreach (var item in items)
            result.Items.Add(item);
        return result;
    }

    private static Border Card(UIElement child) => new()
    {
        Style = Resource<Style>("RolithaxCardStyle"),
        Child = child,
    };

    private static Grid Entry(string title, string subtitle, FrameworkElement action)
    {
        var row = new Grid { ColumnSpacing = 16 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.Children.Add(Stack(Text(title, "BodyStrongTextBlockStyle"), Secondary(subtitle)));
        Grid.SetColumn(action, 1);
        row.Children.Add(action);
        return row;
    }

    private static Button Button(string title, Action action, bool accent = false)
    {
        var button = new Button { Content = title, MinHeight = 32, Margin = new Thickness(0, 0, 8, 2) };
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
