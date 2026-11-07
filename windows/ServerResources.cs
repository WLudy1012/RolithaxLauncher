using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private sealed record ResourceQuery(string Query, string Type, string GameVersion, string Loader);

    private sealed class ResourceState
    {
        public ResourceQuery? Search { get; set; }
        public JsonElement[] Projects { get; set; } = [];
        public JsonElement Task { get; set; }
        public long NextPoll { get; set; }
        public long TaskRevision { get; set; }
        public static bool Finished(JsonElement task) =>
            task.Field("status").StringValue() is "completed" or "failed" or "cancelled";
    }

    private Action<JsonElement>? resourceTaskView;

    private void ShowResources(Node node, Server server)
    {
        if (!node.Resources.TryGetValue(server.Id, out var state))
            node.Resources[server.Id] = state = new ResourceState();
        var criteria = state.Search;
        var search = new TextBox { Header = "搜索 Modrinth", PlaceholderText = "项目名称或关键词", Text = criteria?.Query ?? "" };
        var type = Choices(["插件", "模组"], criteria?.Type == "mod" ? "模组" : "插件");
        type.Header = "类型";
        var version = new TextBox { Header = "Minecraft 版本", PlaceholderText = "例如 1.21.8", Text = criteria?.GameVersion ?? "" };
        var loader = new TextBox { Header = "服务端或加载器", PlaceholderText = "例如 paper、fabric", Text = criteria?.Loader ?? "" };
        var form = new Grid { ColumnSpacing = 12, RowSpacing = 8 };
        form.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        form.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        form.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        form.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        form.Children.Add(search);
        Grid.SetColumn(type, 1);
        form.Children.Add(type);
        Grid.SetRow(version, 1);
        form.Children.Add(version);
        Grid.SetRow(loader, 1);
        Grid.SetColumn(loader, 1);
        form.Children.Add(loader);

        var results = new StackPanel { Spacing = 8 };
        var taskPanel = new StackPanel { Spacing = 8 };
        var searchButton = Button("搜索资源", () => RunAsync(async () =>
        {
            var request = new ResourceQuery(search.Text.Trim(), type.SelectedIndex == 1 ? "mod" : "plugin",
                version.Text.Trim(), loader.Text.Trim().ToLowerInvariant());
            if (request.GameVersion.Length == 0 || request.Loader.Length == 0)
                throw new DaemonException("请输入 Minecraft 版本和服务端或加载器，以筛选兼容资源。");
            state.Projects = (await CallAsync(node, "resourceSearch", request)).Items();
            state.Search = request;
            ShowResources(node, server);
        }), accent: true);
        SetWorkspace(Heading("资源", $"搜索 Modrinth，将兼容插件或模组安装到 {server.Name}。"),
            Card(Stack(form, searchButton)), taskPanel, results);
        if (criteria is not null && state.Projects.Length == 0)
            results.Children.Add(Card(Secondary("没有找到兼容资源。请核对 Minecraft 版本和加载器。")));
        foreach (var item in state.Projects)
        {
            var title = item.Field("title").StringValue();
            var author = item.Field("author").StringValue();
            results.Children.Add(Card(Stack(Text(title, "BodyStrongTextBlockStyle"),
                Secondary(author.Length == 0 ? item.Field("description").StringValue()
                    : $"{author}  ·  {item.Field("description").StringValue()}"),
                Button("查看兼容版本", () => RunAsync(() => SelectResourceVersionAsync(node, server, state, item))))));
        }
        resourceTaskView = task =>
        {
            taskPanel.Children.Clear();
            var id = task.Field("id").StringValue();
            if (id.Length == 0)
                return;
            var status = task.Field("status").StringValue() switch
            {
                "queued" => "等待安装",
                "running" => "安装中",
                "completed" => "安装完成",
                "failed" => "安装失败",
                "cancelled" => "已取消",
                _ => "等待安装状态",
            };
            var files = task.Field("installedFiles").Items();
            var cancel = Button("取消安装", () => RunAsync(async () =>
            {
                if (!await ConfirmAsync("取消安装", Secondary($"取消 {server.Name} 上的当前资源安装？"), "取消安装", "返回"))
                    return;
                await CallAsync(node, "resourceCancel", new { taskId = id });
                await UpdateResourceTaskAsync(node, server, state, id);
            }));
            cancel.IsEnabled = !ResourceState.Finished(task);
            var detail = Stack(Text(status, "BodyStrongTextBlockStyle"), Secondary(task.Field("message").StringValue()),
                new ProgressBar { Minimum = 0, Maximum = 100, Value = Math.Clamp(task.Field("progress").LongValue(), 0, 100) },
                Actions(Button("刷新进度", () => RunAsync(() => UpdateResourceTaskAsync(node, server, state, id))), cancel));
            if (files.Length > 0)
                detail.Children.Add(Secondary("已安装文件  " + string.Join("、", files.Select(file => file.StringValue()))));
            taskPanel.Children.Add(Card(detail));
        };
        resourceTaskView(state.Task);
    }

    private async Task SelectResourceVersionAsync(Node node, Server server, ResourceState state, JsonElement project)
    {
        if (state.Search is not { } criteria)
            return;
        var projectId = project.Field("id").StringValue();
        var title = project.Field("title").StringValue();
        var reply = await CallAsync(node, "resourceVersions", new { projectId, criteria.GameVersion, criteria.Loader });
        var available = new ComboBox { Header = "兼容版本", HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var value in reply.Items())
        {
            var id = value.Field("id").StringValue();
            if (id.Length > 0)
                available.Items.Add(new ComboBoxItem { Tag = id, Content = value.Field("number").StringValue(value.Field("name").StringValue(id)) });
        }
        if (available.Items.Count == 0)
            throw new DaemonException("没有符合当前 Minecraft 版本和加载器的可安装版本。");
        if (state.Task.Field("id").StringValue().Length > 0 && !ResourceState.Finished(state.Task))
            throw new DaemonException("当前服务器有正在执行的安装任务。");
        available.SelectedIndex = 0;
        if (!await ConfirmAsync(title, Stack(Secondary($"安装到 {node.Connection.Name} 上的 {server.Name}。"),
            available, Secondary("必需依赖将随资源安装。Daemon 会校验下载文件。")), "安装"))
            return;
        if (available.SelectedItem is not ComboBoxItem { Tag: string versionId })
            return;
        state.Task = await CallAsync(node, "resourceInstall", new
        {
            id = server.Id, projectId, versionId, criteria.Type, criteria.GameVersion, criteria.Loader,
        });
        resourceTaskView?.Invoke(state.Task);
        Notice("资源安装任务已提交。");
    }

    private async Task UpdateResourceTaskAsync(Node node, Server server, ResourceState state, string taskId)
    {
        state.NextPoll = Environment.TickCount64 + 2500;
        var revision = ++state.TaskRevision;
        var task = await CallAsync(node, "resourceTask", new { taskId });
        if (revision != state.TaskRevision)
            return;
        state.Task = task;
        if (!closed && active == node && selectedServer?.Id == server.Id && tab == "resources")
            resourceTaskView?.Invoke(state.Task);
    }

    private Task PollResourcesAsync(Node node)
    {
        if (tab != "resources" || selectedServer is not { } server ||
            !node.Resources.TryGetValue(server.Id, out var state) || Environment.TickCount64 < state.NextPoll ||
            ResourceState.Finished(state.Task) || state.Task.Field("id").StringValue() is not { Length: > 0 } id)
            return Task.CompletedTask;
        return UpdateResourceTaskAsync(node, server, state, id);
    }
}
