using System.Globalization;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Storage.Pickers;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private async Task ShowFilesAsync(Node node, Server server)
    {
        var path = remotePath;
        var files = (await CallAsync(node, "files", new { id = server.Id, path })).Items()
            .OrderBy(item => item.Field("type").StringValue() != "folder")
            .ThenBy(item => item.Field("name").StringValue(), StringComparer.CurrentCultureIgnoreCase);
        var entries = new StackPanel { Spacing = 8 };
        var search = new TextBox { Header = "筛选当前目录", PlaceholderText = "文件或文件夹名称" };
        var parent = Button("上一级", () => RunAsync(() =>
        {
            var separator = path.LastIndexOf('/');
            return NavigateFilesAsync(node, server, separator < 0 ? "" : path[..separator]);
        }));
        parent.IsEnabled = path.Length > 0;
        SetWorkspace(Heading("文件管理", path.Length == 0 ? "服务器根目录" : "/" + path),
            Actions(parent,
                Button("刷新", () => RunAsync(() => ShowFilesAsync(node, server))),
                Button("新建文件夹", () => RunAsync(() => CreateFolderAsync(node, server, path))),
                Button("上传文件", () => RunAsync(() => UploadFileAsync(node, server, path)))),
            search, entries);

        void RenderEntries()
        {
            entries.Children.Clear();
            foreach (var item in files)
            {
                var name = item.Field("name").StringValue();
                if (!ValidLeaf(name) || !name.Contains(search.Text, StringComparison.OrdinalIgnoreCase))
                    continue;
                var folder = item.Field("type").StringValue() == "folder";
                var itemPath = ChildPath(path, name);
                var menu = new DropDownButton { Content = folder ? "文件夹操作" : "文件操作" };
                var actions = new MenuFlyout();
                if (!folder)
                {
                    actions.Items.Add(MenuItem("下载", () => RunAsync(() => DownloadFileAsync(node, server, itemPath, name))));
                    actions.Items.Add(MenuItem("编辑文本", () => RunAsync(() => EditFileAsync(node, server, itemPath))));
                }
                actions.Items.Add(MenuItem("重命名", () => RunAsync(() => RenameFileAsync(node, server, path, itemPath, name))));
                actions.Items.Add(MenuItem("删除", () => RunAsync(() => DeleteFileAsync(node, server, itemPath, folder))));
                menu.Flyout = actions;
                var detail = folder ? "文件夹" : $"{item.Field("size").LongValue():N0} 字节";
                var modified = item.Field("lastModified").StringValue();
                if (modified.Length > 0)
                    detail += "  ·  " + modified;
                var fileTitle = folder
                    ? (UIElement)Button(name, () => RunAsync(() => NavigateFilesAsync(node, server, itemPath)))
                    : Text(name, "BodyStrongTextBlockStyle");
                var line = new Grid { ColumnSpacing = 10 };
                line.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
                line.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
                line.Children.Add(Stack(fileTitle, Secondary(detail)));
                Grid.SetColumn(menu, 1);
                line.Children.Add(menu);
                entries.Children.Add(Card(line));
            }
            if (entries.Children.Count == 0)
                entries.Children.Add(Card(Secondary(search.Text.Length == 0 ? "目录为空。" : "没有匹配项。")));
        }
        search.TextChanged += (_, _) => RenderEntries();
        RenderEntries();
    }

    private async Task NavigateFilesAsync(Node node, Server server, string target)
    {
        var previous = remotePath;
        remotePath = target;
        try { await ShowFilesAsync(node, server); }
        catch { remotePath = previous; throw; }
    }

    private async Task CreateFolderAsync(Node node, Server server, string path)
    {
        var field = new TextBox { Header = "文件夹名称", MaxLength = 255 };
        if (!await ConfirmAsync("新建文件夹", field, "新建"))
            return;
        var name = CheckedLeaf(field.Text);
        await CallAsync(node, "mkdir", new { id = server.Id, path, name });
        await ShowFilesAsync(node, server);
        Notice("文件夹已创建。");
    }

    private async Task RenameFileAsync(Node node, Server server, string path, string oldPath, string oldName)
    {
        var field = new TextBox { Header = "新名称", Text = oldName, MaxLength = 255 };
        if (!await ConfirmAsync("重命名", field, "保存"))
            return;
        var name = CheckedLeaf(field.Text);
        if (name == oldName)
            return;
        await CallAsync(node, "renameFile", new { id = server.Id, oldPath, newPath = ChildPath(path, name) });
        await ShowFilesAsync(node, server);
        Notice("名称已更新。");
    }

    private async Task DeleteFileAsync(Node node, Server server, string path, bool folder)
    {
        if (!await ConfirmAsync("删除远端文件", Secondary(folder
            ? $"删除 /{path} 及其中的全部文件？此操作无法撤销。"
            : $"删除 /{path}？此操作无法撤销。"), "删除"))
            return;
        var reply = await CallAsync(node, "deleteFiles", new { id = server.Id, paths = new[] { path } });
        await ShowFilesAsync(node, server);
        Notice(reply.StringValue("删除请求已完成，请核对文件列表。"), InfoBarSeverity.Informational);
    }

    private async Task EditFileAsync(Node node, Server server, string path)
    {
        var data = await CallAsync(node, "readFile", new { id = server.Id, path });
        var editor = Multiline("文件内容", data.StringValue(), 300);
        if (await ConfirmAsync("编辑 /" + path, editor, "保存") && editor.Text != data.StringValue())
        {
            await CallAsync(node, "saveFile", new { id = server.Id, path, content = editor.Text });
            Notice("文件已保存。");
        }
    }

    private async Task UploadFileAsync(Node node, Server server, string path)
    {
        var picker = new FileOpenPicker();
        picker.FileTypeFilter.Add("*");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));
        var file = await picker.PickSingleFileAsync();
        if (file is null)
            return;
        var name = CheckedLeaf(file.Name);
        var existing = (await CallAsync(node, "files", new { id = server.Id, path })).Items();
        if (existing.Any(item => string.Equals(item.Field("name").StringValue(), name, StringComparison.OrdinalIgnoreCase)) &&
            !await ConfirmAsync("替换远端文件", Secondary($"当前目录已有 {name}。继续上传可能替换已有内容。"), "上传"))
            return;
        await CallAsync(node, "uploadFile", new { id = server.Id, path, localPath = file.Path, name });
        await ShowFilesAsync(node, server);
        Notice("上传完成。");
    }

    private async Task DownloadFileAsync(Node node, Server server, string path, string name)
    {
        var extension = Path.GetExtension(name);
        var picker = new FileSavePicker { SuggestedFileName = name };
        picker.FileTypeChoices.Add("远端文件", [extension.Length == 0 ? "." : extension]);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));
        var file = await picker.PickSaveFileAsync();
        if (file is null)
            return;
        await CallAsync(node, "downloadFile", new { id = server.Id, path, localPath = file.Path });
        Notice("下载完成。");
    }

    private async Task ShowServerSettingsAsync(Node node, Server server)
    {
        var settings = await CallAsync(node, "settings", new { id = server.Id });
        if (JsonNode.Parse(settings.GetRawText()) is not JsonObject values)
            throw new DaemonException("Daemon 未返回可编辑的实例配置。");

        var fields = new StackPanel { Spacing = 8 };
        var collect = new List<Action<JsonObject>>();
        foreach (var (key, label, number) in new (string, string, bool)[]
        {
            ("name", "实例名称", false), ("java", "Java 路径", false), ("core", "核心文件", false),
            ("minM", "最小内存（MB）", true), ("maxM", "最大内存（MB）", true), ("args", "启动参数", false),
            ("backupMaxCount", "备份数量上限", true), ("backupDelay", "备份间隔（秒）", true),
            ("backupPath", "备份目录", false),
        })
        {
            var initial = settings.Field(key);
            if (initial.ValueKind != (number ? JsonValueKind.Number : JsonValueKind.String))
                continue;
            var input = new TextBox { Header = label, Text = initial.StringValue() };
            fields.Children.Add(input);
            collect.Add(draft =>
            {
                if (input.Text == initial.StringValue())
                    return;
                if (!number)
                    draft[key] = input.Text;
                else if (int.TryParse(input.Text, NumberStyles.None, CultureInfo.InvariantCulture, out var parsed))
                    draft[key] = parsed;
                else
                    throw new DaemonException($"{label}须为非负整数。");
            });
        }
        foreach (var (key, label) in new (string, string)[]
        {
            ("autoRestart", "崩溃后自动重启"), ("runOnStartup", "Daemon 启动时启动服务器"), ("monitorPlayers", "监控玩家"),
        })
        {
            if (settings.Field(key).ValueKind is not (JsonValueKind.True or JsonValueKind.False))
                continue;
            var toggle = new ToggleSwitch { Header = label, IsOn = settings.Flag(key) };
            fields.Children.Add(toggle);
            collect.Add(draft => draft[key] = toggle.IsOn);
        }

        var rawOptions = new JsonSerializerOptions { WriteIndented = true };
        var propertiesPath = settings.Field("serverPropertiesPath").StringValue("server.properties").Replace('\\', '/');
        var properties = Button("编辑 server.properties", () => RunAsync(() => EditFileAsync(node, server, propertiesPath)));
        properties.IsEnabled = propertiesPath.Length > 0 && !propertiesPath.StartsWith('/') && !propertiesPath.Contains(':');
        var general = Button("编辑配置", () => RunAsync(async () =>
        {
            var form = new ScrollViewer { Content = fields, MaxHeight = 420 };
            if (!await ConfirmAsync("编辑实例配置", form, "保存"))
            {
                form.Content = null;
                return;
            }
            form.Content = null;
            var draft = (JsonObject)values.DeepClone();
            foreach (var action in collect)
                action(draft);
            await SaveSettingsAsync(node, server, draft);
        }), accent: true);
        general.IsEnabled = fields.Children.Count > 0;
        var minimum = settings.Field("minM").StringValue("—");
        var maximum = settings.Field("maxM").StringValue("—");
        SetWorkspace(Heading("实例配置", "更改启动参数、内存与备份设置。"),
            Card(Stack(Text(server.Name, "BodyStrongTextBlockStyle"),
                Secondary($"核心  {settings.Field("core").StringValue("—")}"),
                Secondary($"内存  {minimum}–{maximum} MB"))),
            Actions(general, Button("刷新", () => RunAsync(() => ShowServerSettingsAsync(node, server))), properties,
                Button("高级 JSON", () => RunAsync(async () =>
                {
                    var editor = Multiline("配置 JSON", settings.GetRawText(), 300);
                    editor.Text = JsonSerializer.Serialize(settings, rawOptions);
                    if (!await ConfirmAsync("高级配置", editor, "保存"))
                        return;
                    JsonObject json;
                    try { json = JsonNode.Parse(editor.Text) as JsonObject ?? throw new JsonException(); }
                    catch (JsonException) { throw new DaemonException("配置必须为有效的 JSON 对象。"); }
                    await SaveSettingsAsync(node, server, json);
                }))));
    }

    private async Task SaveSettingsAsync(Node node, Server server, JsonObject settings)
    {
        var result = await CallAsync(node, "saveSettings", new { id = server.Id, settings });
        await ShowServerSettingsAsync(node, server);
        Notice(result.StringValue("配置已保存。"));
    }

    private async Task ShowAddonsAsync(Node node, Server server, string mode)
    {
        var choices = Choices(["插件", "模组"], mode == "plugins" ? "插件" : "模组");
        choices.Header = "类型";
        var entries = new StackPanel { Spacing = 8 };
        SetWorkspace(Heading("插件与模组", "查看当前实例中的文件，按需启用、停用或删除。"),
            choices, Button("刷新", () => RunAsync(() => ShowAddonsAsync(node, server, mode))), entries);
        choices.SelectionChanged += async (_, _) => await RunAsync(() =>
            ShowAddonsAsync(node, server, choices.SelectedIndex == 0 ? "plugins" : "mods"));
        var reply = await CallAsync(node, "pluginsMods", new { id = server.Id, mode });
        var groups = new (string Key, string Label, string Action)[]
        {
            ("jarFiles", "已启用", "disable"), ("disableJarFiles", "已停用", "enable"), ("clientJarFiles", "仅客户端", ""),
        };
        foreach (var (key, label, action) in groups)
        {
            foreach (var value in reply.Field(key).Items())
            {
                var name = value.StringValue();
                var buttons = new List<UIElement>();
                if (action.Length > 0)
                    buttons.Add(Button(action == "enable" ? "启用" : "停用",
                        () => RunAsync(() => SetAddonAsync(node, server, mode, action, name))));
                buttons.Add(Button("删除", () => RunAsync(() => SetAddonAsync(node, server, mode, "delete", name))));
                entries.Children.Add(Card(Stack(Text(name, "BodyStrongTextBlockStyle"), Secondary(label), Actions([.. buttons]))));
            }
        }
        if (entries.Children.Count == 0)
            entries.Children.Add(Card(Secondary("未找到插件或模组。")));
    }

    private async Task SetAddonAsync(Node node, Server server, string mode, string action, string name)
    {
        if (action == "delete" && !await ConfirmAsync("删除文件", Secondary($"从当前服务器删除 {name}？"), "删除"))
            return;
        await CallAsync(node, "setPluginsMods", new { id = server.Id, mode, action, targets = new[] { name } });
        await ShowAddonsAsync(node, server, mode);
        Notice("操作已完成。");
    }

    private static MenuFlyoutItem MenuItem(string label, Func<Task> action)
    {
        var item = new MenuFlyoutItem { Text = label };
        item.Click += async (_, _) => await action();
        return item;
    }

    private static TextBox Multiline(string header, string value, double height)
    {
        var box = new TextBox
        {
            Header = header,
            Text = value,
            AcceptsReturn = true,
            Height = height,
            MinWidth = 320,
            FontFamily = new FontFamily("Cascadia Mono, Consolas"),
            TextWrapping = TextWrapping.Wrap,
        };
        ScrollViewer.SetVerticalScrollBarVisibility(box, ScrollBarVisibility.Auto);
        return box;
    }

    private static string ChildPath(string directory, string leaf) =>
        directory.Length == 0 ? leaf : directory + "/" + leaf;

    private static bool ValidLeaf(string value) =>
        value.Length > 0 && value is not ("." or "..") && value.IndexOfAny(['/', '\\', '\0']) < 0;

    private static string CheckedLeaf(string value)
    {
        var name = value.Trim();
        return ValidLeaf(name) ? name : throw new DaemonException("名称不能包含路径分隔符。");
    }
}
