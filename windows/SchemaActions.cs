using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Storage;
using Windows.Storage.Pickers;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private UIElement SchemaMedia(ConsoleRun run, ConsolePanelNode node)
    {
        var caption = Text(node.Label, "BodyStrongTextBlockStyle");
        var frame = new Grid { MaxHeight = 420, MinHeight = 120, HorizontalAlignment = HorizontalAlignment.Stretch };
        var fallback = Secondary("媒体文件不可用，可在插件中心重新安装插件。");
        frame.Children.Add(fallback);
        if (run.SchemaPackage?.Assets.GetValueOrDefault(node.Asset ?? "") is not { } asset || schemaLibrary.AssetPath(asset) is not { } path)
            return Stack(caption, frame);
        if (node.Kind == "image" && asset.MediaType.StartsWith("image/", StringComparison.Ordinal))
        {
            var image = new Image { Stretch = Stretch.Uniform, MaxHeight = 420 };
            AutomationProperties.SetName(image, node.Label);
            frame.Children.Add(image);
            image.Loaded += async (_, _) =>
            {
                try
                {
                    var file = await StorageFile.GetFileFromPathAsync(path);
                    using var stream = await file.OpenReadAsync();
                    var bitmap = new BitmapImage { DecodePixelWidth = 1200 };
                    await bitmap.SetSourceAsync(stream);
                    if (!image.IsLoaded) return;
                    image.Source = bitmap;
                    fallback.Visibility = Visibility.Collapsed;
                }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Runtime.InteropServices.COMException) { }
            };
        }
        else if (node.Kind == "video" && asset.MediaType == "video/mp4")
        {
            var player = new MediaPlayerElement { AutoPlay = false, AreTransportControlsEnabled = true, MaxHeight = 420 };
            AutomationProperties.SetName(player, node.Label);
            frame.Children.Add(player);
            MediaSource? source = null;
            MediaPlayer? playback = null;
            player.Loaded += async (_, _) =>
            {
                try
                {
                    var file = await StorageFile.GetFileFromPathAsync(path);
                    if (!player.IsLoaded || playback is not null) return;
                    source = MediaSource.CreateFromStorageFile(file);
                    playback = new MediaPlayer();
                    playback.MediaFailed += (_, _) => DispatcherQueue.TryEnqueue(() =>
                    {
                        if (player.IsLoaded) fallback.Visibility = Visibility.Visible;
                    });
                    player.SetMediaPlayer(playback);
                    player.Source = source;
                    fallback.Visibility = Visibility.Collapsed;
                }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Runtime.InteropServices.COMException) { }
            };
            player.Unloaded += (_, _) =>
            {
                playback?.Pause();
                player.Source = null;
                player.SetMediaPlayer(null);
                playback?.Dispose();
                playback = null;
                source?.Dispose();
                source = null;
            };
        }
        return Stack(caption, frame);
    }

    private async Task ExecuteSchemaIntentAsync(ConsoleRun run, long revision, string actionId)
    {
        var target = new { id = run.ServerId, streamEpoch = run.Epoch, packageRevision = revision, actionId };
        var intent = await CallAsync(run.Node, "consolePanelPrepareIntent", target);
        var kind = intent.Field("kind").StringValue();
        async Task<bool> StillGranted()
        {
            if (!IsCurrentConsole(run) || run.Panel?.PackageRevision != revision || !run.SchemaCompatibilityAllowsActions) return false;
            var verified = await CallAsync(run.Node, "consolePanelPrepareIntent", target);
            return verified.ValueKind == JsonValueKind.Object && JsonElement.DeepEquals(intent, verified);
        }

        if (kind == "host-task")
        {
            var taskId = intent.Field("taskId").StringValue();
            var allowed = (await CallAsync(run.Node, "schemaTasks", new { id = run.ServerId })).Items()
                .FirstOrDefault(task => task.Field("id").StringValue() == taskId);
            if (allowed.ValueKind == JsonValueKind.Undefined) throw new DaemonException("此宿主机任务未对当前账号和实例授权。");
            if (!await ConfirmAsync("运行宿主机任务", Stack(Text(allowed.Field("title").StringValue(taskId), "BodyStrongTextBlockStyle"),
                Secondary(allowed.Field("description").StringValue()), Secondary($"目标为 {run.Node.Connection.Name} 的实例 #{run.ServerId}。")), "运行") ||
                !await StillGranted()) return;
            var result = await CallAsync(run.Node, "schemaRunTask", new { target.id, target.streamEpoch, target.packageRevision, target.actionId, taskId });
            var status = result.Field("status").StringValue("failed");
            var summary = status switch { "completed" => "任务已完成。", "timedOut" => "任务已超时。", _ => "任务失败。" };
            if (result.Flag("truncated")) summary += " 输出已截断。";
            var output = result.Field("output").StringValue();
            var exitCode = result.Field("exitCode").StringValue();
            var details = Stack(Secondary(exitCode.Length == 0 ? summary : $"{summary} 退出码 {exitCode}。"));
            if (output.Length > 0) details.Children.Add(new TextBox { Text = output[..Math.Min(32_768, output.Length)], IsReadOnly = true,
                TextWrapping = TextWrapping.Wrap, MaxHeight = 320, FontFamily = new FontFamily("Cascadia Mono, Consolas") });
            await ConfirmAsync("宿主机任务结果", details, "完成");
            await SendSchemaResultAsync(run, revision, "action", new { actionId, status, summary });
            return;
        }

        if (kind != "file") throw new DaemonException("此操作尚未授权。");
        var operation = intent.Field("operation").StringValue();
        var root = await ResolveSchemaRootAsync(run, intent.Field("root").StringValue());
        if (root is null) return;
        var relativePath = intent.Field("path").StringValue();
        var directory = operation is "list" or "upload" or "mkdir";
        if (relativePath.Length == 0 && operation != "list")
        {
            relativePath = await PickSchemaPathAsync(run, root, directory, operation is "rename" or "delete");
            if (relativePath is null) return;
        }
        var path = JoinSchemaPath(root, relativePath);
        if (path == root && !directory) throw new DaemonException("请先选择实例目录内的条目。");
        var label = FileOperationName(operation);
        var success = await RunSchemaFileActionAsync(run, operation, path, StillGranted);
        if (!success) return;
        var message = $"{label}已完成。";
        await SendSchemaResultAsync(run, revision, "action", new { actionId, status = "completed", summary = message });
        Notice(message);
    }

    private async Task<string?> ResolveSchemaRootAsync(ConsoleRun run, string root)
    {
        if (root == "instance") return "";
        var layout = await CallAsync(run.Node, "schemaLayout", new { id = run.ServerId });
        if (root == "minecraft" && layout.Field("gameDirectory").ValueKind == JsonValueKind.String)
            return CheckedSchemaPath(layout.Field("gameDirectory").StringValue());
        if (root == "minecraft") throw new DaemonException("Daemon 未返回实例的 Minecraft 目录。");
        if (layout.Field("kind").StringValue() != "mcdr") throw new DaemonException("当前实例未识别到 MCDR 目录。");
        if (root == "mcdr-config") return "config";
        if (root != "mcdr-plugins") throw new DaemonException("未知的 Schema 文件目录。");
        var directories = layout.Field("pluginDirectories").Items().Select(value => CheckedSchemaPath(value.StringValue()))
            .Where(value => value.Length > 0).Distinct(StringComparer.Ordinal).ToArray();
        if (directories.Length == 0) throw new DaemonException("MCDR 未返回可用的插件目录。");
        if (directories.Length == 1) return directories[0];
        var picker = new ComboBox { Header = "MCDR 插件目录", ItemsSource = directories, SelectedIndex = 0 };
        return await ConfirmAsync("选择插件目录", picker, "选择") ? picker.SelectedItem as string : null;
    }

    private async Task<string?> PickSchemaPathAsync(ConsoleRun run, string root, bool directory, bool allowFolderEntry)
    {
        var selected = "";
        var current = Text("/" + root);
        var info = Secondary("");
        var entries = new ListView { MaxHeight = 260, SelectionMode = ListViewSelectionMode.Single };
        var back = new Button { Content = "上一级", IsEnabled = false };
        var open = new Button { Content = "进入文件夹", IsEnabled = false };
        var links = (FrameworkElement)Actions(back, open);
        bool loading = false;
        async Task ReadDirectoryAsync(string path)
        {
            if (loading) return;
            loading = true;
            back.IsEnabled = open.IsEnabled = entries.IsEnabled = false;
            try
            {
                var data = await CallAsync(run.Node, "files", new { id = run.ServerId, path = JoinSchemaPath(root, path) });
                if (!IsCurrentConsole(run)) return;
                selected = path;
                current.Text = "/" + JoinSchemaPath(root, path);
                info.Text = "";
                entries.Items.Clear();
                foreach (var item in data.Items())
                {
                    var name = item.Field("name").StringValue();
                    if (!IsSchemaLeaf(name)) continue;
                    var folder = item.Field("type").StringValue() == "folder";
                    entries.Items.Add(new ListViewItem { Content = name + (folder ? "/" : ""), Tag = (name, folder) });
                }
            }
            catch (DaemonException) { info.Text = "无法打开此目录，请检查连接权限。"; }
            finally
            {
                loading = false;
                entries.IsEnabled = true;
                back.IsEnabled = selected.Length > 0;
            }
        }
        await ReadDirectoryAsync("");
        back.Click += async (_, _) => await ReadDirectoryAsync(selected.Contains('/') ? selected[..selected.LastIndexOf('/')] : "");
        open.Click += async (_, _) =>
        {
            if (entries.SelectedItem is ListViewItem { Tag: ValueTuple<string, bool> item } && item.Item2)
                await ReadDirectoryAsync(JoinSchemaPath(selected, item.Item1));
        };
        entries.SelectionChanged += (_, _) => open.IsEnabled = !loading && entries.SelectedItem is ListViewItem { Tag: ValueTuple<string, bool> item } && item.Item2;
        var target = allowFolderEntry ? "条目" : "文件";
        if (!await ConfirmAsync(directory ? "选择目录" : "选择远端" + target, Stack(current, links, entries, info),
            directory ? "使用当前目录" : "选择" + target, validate: () => loading ? "目录正在读取。" :
                !directory && (entries.SelectedItem is not ListViewItem { Tag: ValueTuple<string, bool> item } || item.Item2 && !allowFolderEntry)
                    ? $"请选择一个{target}。" : null)) return null;
        return directory ? selected : entries.SelectedItem is ListViewItem { Tag: ValueTuple<string, bool> leaf }
            ? JoinSchemaPath(selected, leaf.Item1) : null;
    }

    private async Task<bool> RunSchemaFileActionAsync(ConsoleRun run, string operation, string path, Func<Task<bool>> stillGranted)
    {
        var id = run.ServerId;
        var target = Secondary($"目标为 {run.Node.Connection.Name} 上的实例目录 /{path}。");
        switch (operation)
        {
            case "list":
                if (!await ConfirmAsync("查看远端目录", target, "查看") || !await stillGranted()) return false;
                var list = await CallAsync(run.Node, "files", new { id, path });
                var names = list.Items().Take(512).Select(item => item.Field("name").StringValue());
                await ConfirmAsync("目录内容", SchemaFileText(string.Join("\n", names)), "完成");
                return true;
            case "read":
            case "write":
                var label = operation == "read" ? "查看文件" : "编辑文件";
                if (!await ConfirmAsync(label, target, "打开") || !await stillGranted()) return false;
                var original = (await CallAsync(run.Node, "readFile", new { id, path })).StringValue();
                if (original.Length > 256 * 1024) throw new DaemonException("文件太大，无法在 Schema 面板中打开。");
                var editor = SchemaFileText(original, operation == "read");
                if (operation == "read")
                {
                    await ConfirmAsync("文件内容", editor, "完成");
                    return true;
                }
                if (!await ConfirmAsync("保存 /" + path, editor, "保存") || editor.Text == original || !await stillGranted()) return false;
                var fresh = (await CallAsync(run.Node, "readFile", new { id, path })).StringValue();
                if (fresh != original) throw new DaemonException("文件在编辑期间被修改，请重新打开后保存。");
                await CallAsync(run.Node, "saveFile", new { id, path, content = editor.Text });
                return true;
            case "rename":
            case "mkdir":
                var input = new TextBox { Header = "名称", MaxLength = 128 };
                var rename = operation == "rename";
                if (!await ConfirmAsync(FileOperationName(operation), Stack(target, input), rename ? "保存" : "新建",
                    validate: () => IsSchemaLeaf(input.Text.Trim()) ? null : "请输入单一名称，不使用路径分隔符。") || !await stillGranted()) return false;
                var name = input.Text.Trim();
                if (rename)
                {
                    var parent = path.Contains('/') ? path[..path.LastIndexOf('/')] : "";
                    await CallAsync(run.Node, "renameFile", new { id, oldPath = path, newPath = JoinSchemaPath(parent, name) });
                }
                else await CallAsync(run.Node, "mkdir", new { id, path, name });
                return true;
            case "delete":
                if (!await ConfirmAsync("删除远端条目", Stack(target, Secondary("目录及其内容可能同时被删除。")), "删除") || !await stillGranted()) return false;
                var result = await CallAsync(run.Node, "deleteFiles", new { id, paths = new[] { path } });
                if (result.StringValue() is { Length: > 0 } report)
                    await ConfirmAsync("删除结果", Secondary(report), "完成");
                return true;
            case "upload":
                var filePicker = new FileOpenPicker();
                filePicker.FileTypeFilter.Add("*");
                WinRT.Interop.InitializeWithWindow.Initialize(filePicker, WinRT.Interop.WindowNative.GetWindowHandle(this));
                if (await filePicker.PickSingleFileAsync() is not { } source) return false;
                if (!IsSchemaLeaf(source.Name)) throw new DaemonException("本地文件名不适用于这个远端目录。");
                if (!await ConfirmAsync("上传文件", Stack(target, Secondary($"上传 {source.Name}，可能覆盖已有同名文件。")), "上传") || !await stillGranted()) return false;
                await CallAsync(run.Node, "uploadFile", new { id, path, localPath = source.Path, name = source.Name });
                return true;
            case "download":
                if (!await ConfirmAsync("下载文件", target, "选择保存位置") || !await stillGranted()) return false;
                var savePicker = new FileSavePicker { SuggestedFileName = path.Split('/')[^1] };
                var extension = Path.GetExtension(savePicker.SuggestedFileName);
                savePicker.FileTypeChoices.Add("远端文件", [extension.Length > 0 ? extension : "."]);
                WinRT.Interop.InitializeWithWindow.Initialize(savePicker, WinRT.Interop.WindowNative.GetWindowHandle(this));
                if (await savePicker.PickSaveFileAsync() is not { } file || !await stillGranted()) return false;
                await CallAsync(run.Node, "downloadFile", new { id, path, localPath = file.Path });
                return true;
            default:
                throw new DaemonException("未知的 Schema 文件操作。");
        }
    }

    private async Task SendSchemaResultAsync(ConsoleRun run, long revision, string eventType, object result)
    {
        if (!IsCurrentConsole(run) || run.Panel?.PackageRevision != revision) return;
        try
        {
            var snapshot = await CallAsync(run.Node, "consolePanelResult", new { id = run.ServerId, streamEpoch = run.Epoch,
                packageRevision = revision, eventType, result });
            ApplyConsolePanel(run, snapshot);
        }
        catch (DaemonException error)
        {
            Notice("Schema 未能读取操作结果。" + error.Message, InfoBarSeverity.Warning);
        }
    }

    private static TextBox SchemaFileText(string value, bool readOnly = true) => new()
    {
        Text = value, IsReadOnly = readOnly, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap,
        MaxHeight = 380, MaxLength = 256 * 1024, FontFamily = new FontFamily("Cascadia Mono, Consolas"),
    };

    private static string FileOperationName(string operation) => operation switch
    {
        "list" => "查看目录", "read" => "查看文件", "write" => "编辑文件", "upload" => "上传文件",
        "download" => "下载文件", "rename" => "重命名", "delete" => "删除", "mkdir" => "新建目录", _ => "文件操作",
    };

    private static string JoinSchemaPath(string root, string path) => string.Join('/',
        new[] { CheckedSchemaPath(root), CheckedSchemaPath(path) }.Where(value => value.Length > 0));

    private static string CheckedSchemaPath(string path)
    {
        if (path.Length > 512 || (path.Length > 0 && path.Split('/').Any(segment => !IsSchemaLeaf(segment))))
            throw new DaemonException("Schema 文件路径超出了允许的目录。");
        return path;
    }

    private static bool IsSchemaLeaf(string name) => name.Length is > 0 and <= 128 && name is not ("." or "..") &&
        name == name.Trim() && !name.EndsWith('.') && name.All(value => !char.IsControl(value) && !"/\\:<>\"|?*%".Contains(value));
}
