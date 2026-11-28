using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private void ShowConnections(StackPanel body)
    {
        var firstConnection = nodes.Count == 0 && saved.Count == 0 && savedAvailable;
        body.Children.Add(PageHeading("连接管理", firstConnection ? "选择要连接的面板类型。" : "管理已保存的面板和当前连接。",
            firstConnection ? Button("粘贴配对码", () => RunAsync(PairAsync)) : Actions(
            Button("添加连接", () => RunAsync(() => EditConnectionAsync(null)), accent: true),
            Button("粘贴配对码", () => RunAsync(PairAsync)))));
        if (ConnectionStore.HasLegacyConnections && savedAvailable)
            body.Children.Add(Card(Entry("已有桌面版配置", "可以导入旧版保存的兼容连接。",
                Button("导入连接", () => RunAsync(ImportLegacyAsync)))));

        if (firstConnection)
        {
            body.Children.Add(ProviderList());
            return;
        }

        var connected = Section($"已连接 · {nodes.Count}");
        if (nodes.Count > 0) body.Children.Add(Card(connected));
        foreach (var node in nodes)
        {
            var tools = Actions(Button("服务器", () =>
            {
                SelectNode(node);
                Navigate("instances");
            }), Button("断开", () => RunAsync(() => DisconnectAsync(node))));
            if (active != node)
                tools.Children.Insert(0, Button("设为当前", () => { SelectNode(node); Render(); }));
            var serverSummary = node.ServerListWarning is null ? $"{node.Servers.Length} 台服务器" : "服务器列表不可用";
            var status = active == node ? $"当前节点 · {serverSummary}" : serverSummary;
            var detail = $"{ConnectionProviderName(node.Connection)} · {status}\n{node.Connection.Address}";
            connected.Children.Add(ConnectionRow(node.Connection, detail, tools));
        }

        var stored = Section($"已保存 · {saved.Count}");
        if (saved.Count > 0 || !savedAvailable) body.Children.Add(Card(stored));
        if (!savedAvailable) stored.Children.Add(Secondary("无法读取连接配置。"));
        foreach (var connection in saved)
        {
            var connect = Button("连接", () => RunAsync(async () =>
            {
                await ConnectAsync(connection);
                Render();
            }));
            connect.IsEnabled = nodes.All(item => item.Connection.Id != connection.Id);
            stored.Children.Add(ConnectionRow(connection, $"{ConnectionProviderName(connection)} · {connection.Address}",
                Actions(connect,
                    Button("编辑", () => RunAsync(() => EditConnectionAsync(connection))),
                    Button("移除", () => RunAsync(() => RemoveConnectionAsync(connection))))));
        }
    }

    private static readonly (string Key, string Name)[] ConnectionProviders =
    [
        ("mslx", "MSLX"), ("mcsl", "MCSL Future"),
        ("mcsm", "MCSManager"), ("pterodactyl", "Pterodactyl"),
    ];

    private static (string Key, string Name) ProviderFor(DaemonConnection connection) =>
        ConnectionProviders.FirstOrDefault(provider => provider.Key == connection.Kind, ConnectionProviders[0]);

    private static string ConnectionProviderName(DaemonConnection connection) => ProviderFor(connection).Name;

    private static Image ProviderIcon(string key, double size)
    {
        var image = new Image
        {
            Source = new BitmapImage(new Uri($"ms-appx:///Assets/Providers/{key}.png")),
            Width = size, Height = size, Stretch = Stretch.Uniform,
            VerticalAlignment = VerticalAlignment.Center,
        };
        AutomationProperties.SetAccessibilityView(image, Microsoft.UI.Xaml.Automation.Peers.AccessibilityView.Raw);
        return image;
    }

    private static Grid ConnectionRow(DaemonConnection connection, string description, FrameworkElement action)
    {
        var label = CompactStack(Text(connection.Name, "BodyStrongTextBlockStyle"), Secondary(description));
        return AdaptivePair(IconAndDetails(ProviderIcon(ProviderFor(connection).Key, 32), label),
            action, action is Button ? 280 : 530);
    }

    private Border ProviderList()
    {
        var list = new ListView { SelectionMode = ListViewSelectionMode.None, IsItemClickEnabled = true };
        AutomationProperties.SetName(list, "选择面板类型");
        foreach (var provider in ConnectionProviders)
        {
            var detail = provider.Key switch
            {
                "mslx" => "Daemon 地址或 Rolithax 配对码",
                "mcsl" => "服务地址与远程访问 Token",
                "mcsm" => "MCSManager 10 面板地址与 API Key",
                _ => "面板地址与 Client API Key",
            };
            var row = new Grid { Tag = provider.Key, ColumnSpacing = 16, Padding = new Thickness(8, 10, 8, 10) };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition());
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.Children.Add(ProviderIcon(provider.Key, 32));
            var labels = CompactStack(Text(provider.Name, "BodyStrongTextBlockStyle"), Secondary(detail));
            Grid.SetColumn(labels, 1);
            row.Children.Add(labels);
            var next = new FontIcon { Glyph = "\uE76C", FontSize = 12 };
            Grid.SetColumn(next, 2);
            row.Children.Add(next);
            var item = new ListViewItem { Tag = provider.Key, Content = row, HorizontalContentAlignment = HorizontalAlignment.Stretch };
            AutomationProperties.SetName(item, $"{provider.Name}，{detail}");
            list.Items.Add(item);
        }
        list.ItemClick += (_, args) =>
        {
            if (args.ClickedItem is FrameworkElement { Tag: string kind })
                _ = RunAsync(() => EditConnectionAsync(null, initialKind: kind));
        };
        var surface = Card(list);
        surface.Padding = new Thickness(4);
        return surface;
    }

    private async Task EditConnectionAsync(DaemonConnection? previous, bool startPairing = false, string initialKind = "mslx")
    {
        var activeKind = previous?.Kind ?? initialKind;
        var providerGroup = new RadioButtons { MaxColumns = 4 };
        AutomationProperties.SetName(providerGroup, "连接类型");
        foreach (var provider in ConnectionProviders)
        {
            var label = Text(provider.Name);
            label.TextAlignment = TextAlignment.Center;
            var card = CompactStack(ProviderIcon(provider.Key, 40), label);
            card.HorizontalAlignment = HorizontalAlignment.Center;
            var button = new RadioButton { Content = card, MinWidth = 132, MinHeight = 80, IsEnabled = previous is null || activeKind == provider.Key };
            AutomationProperties.SetName(button, provider.Name);
            button.GotFocus += (_, _) => button.StartBringIntoView(new BringIntoViewOptions { AnimationDesired = systemUi.AnimationsEnabled });
            providerGroup.Items.Add(button);
        }
        providerGroup.SelectedIndex = Math.Max(0, Array.FindIndex(ConnectionProviders, item => item.Key == activeKind));
        var carousel = new ScrollViewer { Content = providerGroup, VerticalScrollMode = ScrollMode.Disabled,
            VerticalScrollBarVisibility = ScrollBarVisibility.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Auto,
            HorizontalScrollMode = ScrollMode.Enabled };
        var name = new TextBox { Header = "连接名称", PlaceholderText = "例如 家用服务器", Text = previous?.Name ?? "", MaxLength = 120 };
        var address = new TextBox { Header = "Daemon 地址", MaxLength = 2048, Text = previous?.Address ?? "" };
        var key = new PasswordBox { Header = "API Key", Password = previous?.ApiKey ?? "", MaxLength = 4096 };
        var nodeId = new TextBox { Header = "节点 ID（可选）", Text = previous?.NodeId ?? "", MaxLength = 256 };
        var usePairCode = new CheckBox { Content = "使用 Rolithax 配对码", IsChecked = startPairing };
        var pairCode = new TextBox { Header = "配对码", PlaceholderText = "mslxp1:…", MaxLength = 8192 };
        var allowHttp = new CheckBox { Content = "允许 HTTP 明文连接", IsChecked = previous?.AllowHttp == true };
        var selfSigned = new CheckBox { Content = "跳过 HTTPS 证书验证", IsChecked = previous?.AllowSelfSigned == true };
        var persist = new CheckBox { Content = "保存到此 Windows 账户", IsChecked = savedAvailable, IsEnabled = savedAvailable && previous is null };
        var help = Secondary("");
        string Kind() => ConnectionProviders[Math.Clamp(providerGroup.SelectedIndex, 0, ConnectionProviders.Length - 1)].Key;
        bool Pairing() => previous is null && Kind() == "mslx" && usePairCode.IsChecked == true;
        void UpdateForm()
        {
            var kind = Kind();
            var pairing = Pairing();
            usePairCode.Visibility = previous is null && kind == "mslx" ? Visibility.Visible : Visibility.Collapsed;
            pairCode.Visibility = pairing ? Visibility.Visible : Visibility.Collapsed;
            address.Visibility = key.Visibility = pairing ? Visibility.Collapsed : Visibility.Visible;
            nodeId.Visibility = kind == "mcsm" ? Visibility.Visible : Visibility.Collapsed;
            selfSigned.Visibility = kind == "mslx" ? Visibility.Visible : Visibility.Collapsed;
            allowHttp.Content = kind == "mcsl" ? "允许明文 HTTP / WebSocket" : "允许 HTTP 明文连接";
            address.Header = kind == "mslx" ? "Daemon 地址" : kind == "mcsl" ? "守护进程地址" : "面板地址";
            address.PlaceholderText = kind switch
            {
                "mcsl" => "wss://mcsl.example.com/api/v2", "mslx" => "https://daemon.example.com:1027",
                _ => "https://panel.example.com",
            };
            key.Header = kind == "mcsl" ? "远程访问 Token" : kind == "pterodactyl" ? "Client API Key" : "API Key";
            help.Text = kind switch
            {
                "mslx" when pairing => "从 MSLX 的 Rolithax 中心复制一次性配对码。",
                "mslx" => "使用 Daemon 地址和账号的 API Key。",
                "mcsl" => "使用 MCSL Future /api/v2 地址和远程访问 Token。",
                "mcsm" => "使用 MCSManager 10 的面板地址和 API Key。",
                _ => "使用 Pterodactyl 面板地址和 Client API Key。",
            };
            AutomationProperties.SetName(address, (string)address.Header);
            AutomationProperties.SetName(key, (string)key.Header);
        }
        providerGroup.SelectionChanged += (_, _) => UpdateForm();
        usePairCode.Checked += (_, _) => UpdateForm();
        usePairCode.Unchecked += (_, _) => UpdateForm();
        UpdateForm();
        var form = Stack(carousel, help, name, usePairCode, pairCode, address, key, nodeId, allowHttp, selfSigned, persist);
        var content = new ScrollViewer { Content = form, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            MaxHeight = Math.Clamp(Root.ActualHeight - 150, 215, 565) };
        string? Validate()
        {
            try
            {
                if (Pairing())
                {
                    var endpoint = ParsePairingAddress(pairCode.Text.Trim());
                    _ = NormalizeAddress(endpoint, allowHttp.IsChecked == true);
                }
                else
                {
                    if (string.IsNullOrWhiteSpace(key.Password)) return $"请输入 {key.Header}。";
                    _ = NormalizeAddress(address.Text, allowHttp.IsChecked == true, Kind() == "mcsl");
                }
                return null;
            }
            catch (DaemonException error) { return error.Message; }
        }
        if (!await ConfirmAsync(previous is null ? "添加连接" : "编辑连接", content, "验证并连接", validate: Validate, maxWidth: 760)) return;

        var kind = Kind();
        var payload = Pairing() ? pairCode.Text.Trim() : null;
        var normalized = NormalizeAddress(payload is null ? address.Text : ParsePairingAddress(payload),
            allowHttp.IsChecked == true, kind == "mcsl");
        var insecureTls = kind == "mslx" && selfSigned.IsChecked == true && normalized.Scheme == Uri.UriSchemeHttps;
        var plainHttp = normalized.Scheme is "http" or "ws";
        if ((plainHttp || insecureTls) && !await ConfirmAsync("确认连接安全设置",
            Secondary(plainHttp ? "明文连接会发送可被读取的凭据和管理指令。请确认目标位于可信网络。"
                : "跳过证书验证后无法确认 Daemon 身份。请确认地址与网络可信。"), "继续连接")) return;

        var apiKey = key.Password.Trim();
        if (payload is not null)
        {
            var draft = new DaemonConnection(Guid.Empty, "", normalized.AbsoluteUri, "", plainHttp, insecureTls);
            var reply = await RedeemPairAsync(draft, payload);
            var protocol = reply.Field("protocolVersion");
            if (protocol.ValueKind != JsonValueKind.Undefined && protocol.LongValue() != 1)
                throw new DaemonException("配对协议与当前客户端不兼容，请更新 Rolithax 插件和客户端。");
            var target = NormalizeAddress(reply.Field("daemonUrl").StringValue(normalized.AbsoluteUri), allowHttp.IsChecked == true);
            if (!string.Equals(normalized.GetLeftPart(UriPartial.Authority), target.GetLeftPart(UriPartial.Authority), StringComparison.OrdinalIgnoreCase) ||
                !string.Equals(normalized.AbsolutePath.TrimEnd('/'), target.AbsolutePath.TrimEnd('/'), StringComparison.Ordinal))
                throw new DaemonException("配对返回了不同的地址，请在 Daemon 上重新生成配对码。");
            apiKey = reply.Field("apiKey").StringValue();
            if (apiKey.Length == 0) throw new DaemonException("配对成功响应缺少 API Key，请重新生成配对码。");
        }

        var connection = new DaemonConnection(previous?.Id ?? Guid.NewGuid(),
            string.IsNullOrWhiteSpace(name.Text) ? normalized.Host : name.Text.Trim(),
            normalized.AbsoluteUri.TrimEnd('/'), apiKey, plainHttp, insecureTls, kind, kind == "mcsm" ? nodeId.Text.Trim() : "");
        await SaveConnectedAsync(connection, persist.IsChecked == true);
    }

    private async Task SaveConnectedAsync(DaemonConnection connection, bool persist)
    {
        if (saved.Any(item => item.Id != connection.Id && SameConnection(item, connection)))
            throw new DaemonException("此连接和凭据已保存，请从连接列表打开。");
        var replacement = await OpenNodeAsync(connection);
        if (closed)
        {
            await Task.Run(replacement.Session.Dispose);
            return;
        }
        var replaced = nodes.FirstOrDefault(item => item.Connection.Id == connection.Id || SameConnection(item.Connection, connection));
        if (replaced is not null) await DisconnectAsync(replaced);
        nodes.Add(replacement);
        SelectNode(replacement);
        if (persist)
        {
            var update = saved.Where(item => item.Id != connection.Id).Append(connection).ToList();
            try
            {
                await Task.Run(() => ConnectionStore.Save(update));
                saved = update;
            }
            catch
            {
                Render();
                Notice("已连接。保存配置失败，这次连接仅在当前会话有效。", InfoBarSeverity.Warning);
                return;
            }
        }
        Render();
        Notice("已连接到 " + connection.Name);
    }

    private Task PairAsync() => EditConnectionAsync(null, startPairing: true);

    private static async Task<JsonElement> RedeemPairAsync(DaemonConnection draft, string payload)
    {
        var session = await DaemonSession.OpenAsync(draft);
        try
        {
            return await session.CallAsync("pair", new { payload, deviceName = "Rolithax Windows" }, payload);
        }
        finally
        {
            await Task.Run(session.Dispose);
        }
    }

    private static string ParsePairingAddress(string payload)
    {
        try
        {
            const string prefix = "mslxp1:";
            if (!payload.StartsWith(prefix, StringComparison.Ordinal))
                throw new FormatException();
            var input = payload[prefix.Length..].Replace('-', '+').Replace('_', '/');
            input = input.PadRight((input.Length + 3) / 4 * 4, '=');
            using var document = JsonDocument.Parse(Convert.FromBase64String(input));
            var root = document.RootElement;
            var address = root.Field("u").StringValue();
            if (root.Field("v").LongValue() != 1 || string.IsNullOrWhiteSpace(address) || root.Field("c").StringValue().Length == 0)
                throw new FormatException();
            if (root.Field("e").LongValue() < DateTimeOffset.UtcNow.ToUnixTimeSeconds())
                throw new DaemonException("配对码已过期。请在 Daemon 上生成新的配对码。");
            return address;
        }
        catch (Exception error) when (error is FormatException or JsonException)
        {
            throw new DaemonException("无法识别配对码。请复制 Rolithax 中心生成的完整内容。");
        }
    }

    private async Task ConnectAsync(DaemonConnection connection)
    {
        var existing = nodes.FirstOrDefault(item => item.Connection.Id == connection.Id || SameConnection(item.Connection, connection));
        if (existing is not null)
        {
            SelectNode(existing);
            return;
        }
        var node = await OpenNodeAsync(connection);
        if (closed)
        {
            await Task.Run(node.Session.Dispose);
            return;
        }
        nodes.Add(node);
        SelectNode(node);
        Notice("已连接到 " + connection.Name);
    }

    private async Task<Node> OpenNodeAsync(DaemonConnection connection)
    {
        var session = await DaemonSession.OpenAsync(connection);
        try
        {
            await CheckNodeStatusAsync(connection, session);
            var node = new Node(connection, session);
            if (!connection.IsThirdParty)
            {
                try
                {
                    var capability = await session.CallAsync("schemaCapabilities", secret: connection.ApiKey);
                    var version = capability.Field("pluginVersion").StringValue();
                    node.SchemaExtensionStatus = version.Length == 0 ? "未检测到支持 Schema 的 Rolithax 扩展" :
                        capability.Field("protocolVersion").LongValue() == 1 ? $"Rolithax 扩展 {version}" : $"Rolithax 扩展 {version} 的 Schema 协议不兼容";
                }
                catch (DaemonException) { node.SchemaExtensionStatus = "无法确认 Rolithax 扩展版本"; }
            }
            if (!closed)
                await RefreshAsync(node, allowUnavailable: true);
            return node;
        }
        catch
        {
            await Task.Run(session.Dispose);
            throw;
        }
    }

    private static async Task CheckNodeStatusAsync(DaemonConnection connection, DaemonSession session)
    {
        try
        {
            var status = await session.CallAsync("status", secret: connection.ApiKey);
            if (status.ValueKind != JsonValueKind.Object)
                throw new DaemonException("Daemon 未返回有效的状态数据。");
        }
        catch (DaemonException error)
        {
            var detail = error.Status switch
            {
                401 => "Daemon 拒绝了连接认证。",
                403 => "Daemon 拒绝了状态查询。",
                _ => "无法读取 Daemon 状态。",
            };
            throw new DaemonException($"{detail} {error.Message}", error.Status);
        }
    }

    private async Task DisconnectAsync(Node node)
    {
        if (active == node)
        {
            LeaveServer();
            active = nodes.FirstOrDefault(item => item != node);
            filter = "";
            statusFilter = "全部状态";
        }
        nodes.Remove(node);
        await Task.Run(node.Session.Dispose);
        Render();
    }

    private async Task RemoveConnectionAsync(DaemonConnection connection)
    {
        if (!await ConfirmAsync("移除已保存的连接", Secondary($"从此 Windows 账户移除 {connection.Name} 及其 API Key？"), "移除"))
            return;
        var update = saved.Where(item => item.Id != connection.Id).ToList();
        await Task.Run(() => ConnectionStore.Save(update));
        saved = update;
        Render();
        Notice("已移除保存的连接。");
    }

    private async Task ImportLegacyAsync()
    {
        var (connections, skipped) = await Task.Run(ConnectionStore.ReadLegacy);
        var update = saved.ToList();
        foreach (var connection in connections)
        {
            if (update.All(item => item.Id != connection.Id && !SameConnection(item, connection)))
                update.Add(connection);
        }
        var imported = update.Count - saved.Count;
        if (imported > 0)
        {
            await Task.Run(() => ConnectionStore.Save(update));
            saved = update;
        }
        Render();
        var result = $"已导入 {imported} 个连接。";
        if (skipped > 0)
            result += $" {skipped} 个旧连接使用证书指纹或缺少系统凭据，请手动添加。";
        Notice(result, skipped > 0 ? InfoBarSeverity.Warning : InfoBarSeverity.Informational);
    }

    private static bool SameConnection(DaemonConnection left, DaemonConnection right) =>
        left.Kind == right.Kind && left.NodeId == right.NodeId &&
        string.Equals(left.Address.TrimEnd('/'), right.Address.TrimEnd('/'), StringComparison.Ordinal) &&
        string.Equals(left.ApiKey, right.ApiKey, StringComparison.Ordinal);

    private static Uri NormalizeAddress(string value, bool allowHttp, bool allowWebSocket = false)
    {
        var input = value.Trim();
        if (input.Length == 0)
            throw new DaemonException("请输入 Daemon 地址。");
        if (!input.Contains("://", StringComparison.Ordinal))
            input = "https://" + input;
        if (!Uri.TryCreate(input, UriKind.Absolute, out var address) || string.IsNullOrEmpty(address.Host) ||
            (address.Scheme is not ("https" or "http") && (!allowWebSocket || address.Scheme is not ("ws" or "wss"))) || address.UserInfo.Length > 0 ||
            address.Query.Length > 0 || address.Fragment.Length > 0)
            throw new DaemonException("请输入有效的面板地址。地址不能包含凭据、查询参数或片段。");
        if ((address.Scheme is "http" or "ws") && !allowHttp)
            throw new DaemonException("该地址使用明文连接。确认网络可信后，请勾选允许明文连接。");
        return address;
    }
}
