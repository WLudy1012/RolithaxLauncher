using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private TextBlock? creationText;

    private async Task CreateServerAsync(Node node)
    {
        if (node.CreationId is not null)
            throw new DaemonException("当前节点有正在跟踪的创建任务。");
        var name = new TextBox { Header = "服务器名称" };
        var core = new TextBox { Header = "核心文件名", Text = "server.jar" };
        var min = new TextBox { Header = "最小内存（MB）", Text = "1024" };
        var max = new TextBox { Header = "最大内存（MB）", Text = "4096" };
        var java = new TextBox { Header = "Java 路径", PlaceholderText = "留空使用 Daemon 默认环境" };
        var args = new TextBox { Header = "启动参数" };
        var path = new TextBox { Header = "远端目录", PlaceholderText = "留空使用默认目录" };
        var editor = Multiline("高级请求 JSON", "", 220);
        editor.PlaceholderText = "支持 coreUrl / coreSha256、packageUrl / packageSha256 等创建参数。";
        var javaPaths = new ComboBox { Header = "已检测到的 Java", PlaceholderText = "选择环境", Visibility = Visibility.Collapsed };
        javaPaths.SelectionChanged += (_, _) =>
        {
            if (javaPaths.SelectedItem is ComboBoxItem { Tag: string location })
                java.Text = location;
        };
        var javaList = new Button { Content = "读取 Java 列表" };
        javaList.Click += async (_, _) =>
        {
            javaList.IsEnabled = false;
            try
            {
                var reply = await CallAsync(node, "javaList", new { refresh = false });
                javaPaths.Items.Clear();
                foreach (var item in reply.Items())
                {
                    var location = item.Field("path").StringValue();
                    if (location.Length > 0)
                        javaPaths.Items.Add(new ComboBoxItem
                            { Content = $"{item.Field("version").StringValue()} · {location}", Tag = location });
                }
                javaPaths.Visibility = Visibility.Visible;
                javaPaths.PlaceholderText = javaPaths.Items.Count == 0 ? "Daemon 未检测到 Java" : "选择环境";
            }
            catch (Exception)
            {
                javaPaths.Visibility = Visibility.Visible;
                javaPaths.PlaceholderText = "无法读取 Java 列表";
            }
            finally { javaList.IsEnabled = true; }
        };
        var form = Stack(name, core, min, max, java, javaList, javaPaths, args, path);
        var advanced = new ToggleSwitch { Header = "使用高级 JSON" };
        editor.Visibility = Visibility.Collapsed;
        advanced.Toggled += (_, _) =>
        {
            form.Visibility = advanced.IsOn ? Visibility.Collapsed : Visibility.Visible;
            editor.Visibility = advanced.IsOn ? Visibility.Visible : Visibility.Collapsed;
        };
        var content = new ScrollViewer { MaxHeight = 450, Content = Stack(Secondary($"创建目标为 {node.Connection.Name}。"), advanced, form, editor) };
        if (!await ConfirmAsync("新建服务器", content, "创建"))
            return;

        object request;
        if (advanced.IsOn)
        {
            try { request = JsonNode.Parse(editor.Text) as JsonObject ?? throw new JsonException(); }
            catch (JsonException) { throw new DaemonException("创建参数必须为有效的 JSON 对象。"); }
        }
        else
        {
            if (string.IsNullOrWhiteSpace(name.Text) || string.IsNullOrWhiteSpace(core.Text) ||
                !int.TryParse(min.Text, NumberStyles.None, CultureInfo.InvariantCulture, out var minM) ||
                !int.TryParse(max.Text, NumberStyles.None, CultureInfo.InvariantCulture, out var maxM) || minM <= 0 || maxM < minM)
                throw new DaemonException("请填写名称、核心文件名与有效的内存范围。");
            request = new { name = name.Text.Trim(), core = core.Text.Trim(), minM, maxM,
                java = BlankAsNull(java.Text), args = BlankAsNull(args.Text), path = BlankAsNull(path.Text) };
        }
        var result = await CallAsync(node, "createInstance", request);
        var id = result.StringValue();
        if (id.Length == 0)
            throw new DaemonException("Daemon 未返回创建任务 ID。");
        node.CreationId = id;
        node.CreationStatus = "正在等待创建进度…";
        Render();
        await CallAsync(node, "creationStart", new { serverId = id });
        Notice("创建任务已提交。");
    }

    private UIElement CreationPanel(Node node, string id)
    {
        creationText = Secondary(node.CreationStatus ?? "正在等待创建进度…");
        return Card(Stack(Text("创建服务器", "BodyStrongTextBlockStyle"), creationText,
            Actions(Button("取消任务", () => RunAsync(async () =>
            {
                if (!await ConfirmAsync("取消创建", Secondary("向 Daemon 发送取消请求？"), "取消任务", "返回"))
                    return;
                await CallAsync(node, "cancelCreation", new { serverId = id, cleanupFiles = false });
                await FinishCreationAsync(node);
                Notice("取消请求已发送。");
            })),
            Button("关闭进度", () => RunAsync(async () =>
            {
                if (!await ConfirmAsync("关闭进度", Secondary("关闭进度后，Daemon 会继续执行创建任务。"), "关闭"))
                    return;
                await FinishCreationAsync(node);
            })))));
    }

    private async Task PollCreationAsync(Node node)
    {
        if (node.CreationId is not { } id)
            return;
        var result = await CallAsync(node, "creationPoll");
        if (closed || node.CreationId != id)
            return;
        var progress = result.Field("progress").NumberValue();
        var message = result.Field("message").StringValue();
        node.CreationStatus = progress is { } value ? $"{value:0}%  ·  {message}"
            : result.Flag("connected") ? "已订阅创建进度。" : "等待创建进度连接…";
        if (active == node && creationText is { } text)
            text.Text = node.CreationStatus;
        if (progress >= 100 && !busy && active == node && selectedServer is null)
            await RunAsync(async () =>
            {
                await FinishCreationAsync(node);
                await RefreshAsync(node);
                Render();
                Notice("创建任务已完成。");
            });
    }

    private async Task FinishCreationAsync(Node node)
    {
        await CallAsync(node, "creationStop");
        node.CreationId = null;
        node.CreationStatus = null;
        Render();
    }

    private StackPanel? userResults;

    private void ShowUserShell(StackPanel body)
    {
        body.Children.Add(Heading("用户管理", "在当前 Daemon 上管理账户和实例访问权限。"));
        if (active is not { } node)
        {
            body.Children.Add(Card(Stack(Secondary("连接 Daemon 后可管理账户。"),
                Button("管理连接", () => Navigate("connections"), accent: true))));
            return;
        }
        var picker = NodePicker(node);
        picker.SelectionChanged += (_, _) =>
        {
            if (active is { } selected && selected != node)
                _ = RunAsync(() => ShowUsersAsync(selected));
        };
        body.Children.Add(picker);
        body.Children.Add(Actions(Button("添加用户", () => RunAsync(() => EditUserAsync(node, null)), accent: true),
            Button("刷新", () => RunAsync(() => ShowUsersAsync(node)))));
        userResults = new StackPanel { Spacing = 10 };
        body.Children.Add(userResults);
    }

    private async Task ShowUsersAsync(Node node)
    {
        var target = userResults;
        var users = (await CallAsync(node, "users")).Items();
        if (closed || active != node || page != "users" || target is null || userResults != target)
            return;
        target.Children.Clear();
        if (users.Length == 0)
            target.Children.Add(Card(Secondary("Daemon 没有返回用户。")));
        foreach (var user in users)
        {
            var username = user.Field("username").StringValue();
            var name = user.Field("name").StringValue(username);
            var role = user.Field("role").StringValue();
            target.Children.Add(Card(Stack(Text(name, "BodyStrongTextBlockStyle"),
                Secondary($"{username}  ·  {(role == "admin" ? "管理员" : "普通用户")}"),
                Actions(Button("编辑", () => RunAsync(() => EditUserAsync(node, user))),
                    Button("删除", () => RunAsync(() => DeleteUserAsync(node, user)))))));
        }
    }

    private async Task EditUserAsync(Node node, JsonElement? previous)
    {
        var existing = previous ?? default;
        var username = new TextBox { Header = "用户名", Text = existing.Field("username").StringValue(), IsReadOnly = previous.HasValue };
        var name = new TextBox { Header = "显示名称", Text = existing.Field("name").StringValue() };
        var password = new PasswordBox { Header = previous.HasValue ? "新密码（可留空）" : "密码" };
        var role = Choices(["普通用户", "管理员"], existing.Field("role").StringValue() == "admin" ? "管理员" : "普通用户");
        role.Header = "权限";
        var reset = new CheckBox { Content = "重置 API Key", Visibility = previous.HasValue ? Visibility.Visible : Visibility.Collapsed };
        var currentResources = existing.Field("resources").Items().Select(item => item.StringValue()).ToHashSet(StringComparer.Ordinal);
        var knownResources = new List<(string Name, CheckBox Control)>();
        var resourceFields = Stack(Secondary("允许访问的服务器"));
        foreach (var server in node.Servers)
        {
            var key = "server:" + server.Id.ToString(CultureInfo.InvariantCulture);
            var field = new CheckBox { Content = server.Name, IsChecked = currentResources.Contains(key) };
            knownResources.Add((key, field));
            resourceFields.Children.Add(field);
        }
        try
        {
            var frps = (await CallAsync(node, "frps")).Items();
            if (frps.Length > 0)
                resourceFields.Children.Add(Secondary("允许访问的 FRP 隧道"));
            foreach (var frp in frps)
            {
                var id = frp.Field("id").StringValue();
                if (id.Length == 0)
                    continue;
                var key = "frp:" + id;
                var field = new CheckBox
                {
                    Content = frp.Field("name").StringValue($"FRP #{id}"),
                    IsChecked = currentResources.Contains(key),
                };
                knownResources.Add((key, field));
                resourceFields.Children.Add(field);
            }
        }
        catch (DaemonException)
        {
            resourceFields.Children.Add(Secondary("无法读取 FRP 隧道，已保留现有 FRP 权限。"));
        }
        var content = new ScrollViewer { MaxHeight = 450, Content = Stack(username, name, password, role, reset, resourceFields) };
        if (!await ConfirmAsync(previous.HasValue ? "编辑用户" : "添加用户", content, "保存"))
            return;
        var resources = currentResources.Except(knownResources.Select(item => item.Name))
            .Concat(knownResources.Where(item => item.Control.IsChecked == true).Select(item => item.Name)).ToArray();
        var roleName = role.SelectedIndex == 1 ? "admin" : "user";
        if (previous.HasValue)
        {
            if (reset.IsChecked == true && !await ConfirmAsync("重置 API Key",
                Secondary("此用户原有 API Key 将失效，使用该密钥的客户端需要重新连接。"), "重置"))
                return;
            await CallAsync(node, "updateUser", new { id = existing.Field("id").StringValue(), name = BlankAsNull(name.Text),
                password = BlankAsNull(password.Password), role = roleName, resources, resetApiKey = reset.IsChecked == true });
        }
        else
        {
            if (string.IsNullOrWhiteSpace(username.Text) || string.IsNullOrWhiteSpace(password.Password))
                throw new DaemonException("请填写用户名和密码。");
            await CallAsync(node, "createUser", new { username = username.Text.Trim(), password = password.Password,
                name = BlankAsNull(name.Text), role = roleName, resources });
        }
        await ShowUsersAsync(node);
        Notice("用户信息已保存。");
    }

    private async Task DeleteUserAsync(Node node, JsonElement user)
    {
        if (!await ConfirmAsync("删除用户", Secondary($"从 {node.Connection.Name} 删除账户 {user.Field("username").StringValue()}？"), "删除"))
            return;
        await CallAsync(node, "deleteUser", new { id = user.Field("id").StringValue() });
        await ShowUsersAsync(node);
        Notice("用户已删除。");
    }

    private async Task ShowProfileAsync(Node node)
    {
        var profile = await CallAsync(node, "profile");
        var username = new TextBox { Header = "用户名", Text = profile.Field("username").StringValue() };
        var name = new TextBox { Header = "显示名称", Text = profile.Field("name").StringValue() };
        var avatar = new TextBox { Header = "头像地址", Text = profile.Field("avatar").StringValue() };
        var oldPassword = new PasswordBox { Header = "当前密码" };
        var newPassword = new PasswordBox { Header = "新密码（可留空）" };
        if (!await ConfirmAsync("当前账户", Stack(username, name, avatar, oldPassword, newPassword), "保存"))
            return;
        if (newPassword.Password.Length > 0 && oldPassword.Password.Length == 0)
            throw new DaemonException("修改密码时需填写当前密码。");
        await CallAsync(node, "updateProfile", new { username = BlankAsNull(username.Text), name = BlankAsNull(name.Text),
            avatar = BlankAsNull(avatar.Text), oldPassword = BlankAsNull(oldPassword.Password), password = BlankAsNull(newPassword.Password) });
        Notice("账户资料已更新。");
    }

    private static string? BlankAsNull(string value) => string.IsNullOrWhiteSpace(value) ? null : value;
}
