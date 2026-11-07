using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private void ShowConnections(StackPanel body)
    {
        body.Children.Add(Heading("连接管理", "添加 Daemon，或在多个已连接节点之间切换。"));
        body.Children.Add(Actions(
            Button("添加连接", () => RunAsync(() => EditConnectionAsync(null)), accent: true),
            Button("粘贴配对码", () => RunAsync(PairAsync))));
        if (ConnectionStore.HasLegacyConnections && savedAvailable)
            body.Children.Add(Card(Entry("已有桌面版配置", "可以导入旧版保存的兼容连接。",
                Button("导入连接", () => RunAsync(ImportLegacyAsync)))));

        body.Children.Add(Text("已连接", "SubtitleTextBlockStyle"));
        if (nodes.Count == 0)
            body.Children.Add(Card(Secondary("尚未连接任何 Daemon。")));
        foreach (var node in nodes)
        {
            var open = Button(active == node ? "当前节点" : "设为当前", () => { SelectNode(node); Render(); });
            open.IsEnabled = active != node;
            body.Children.Add(Card(Stack(
                Text(node.Connection.Name, "BodyStrongTextBlockStyle"),
                Secondary($"{node.Connection.Address}  ·  {node.Servers.Length} 台服务器"),
                Actions(open, Button("断开", () => RunAsync(() => DisconnectAsync(node)))))));
        }

        body.Children.Add(Text("已保存", "SubtitleTextBlockStyle"));
        if (saved.Count == 0)
            body.Children.Add(Card(Secondary(savedAvailable ? "保存后，下次可以直接连接。" : "无法读取连接配置。")));
        foreach (var connection in saved)
        {
            var connect = Button("连接", () => RunAsync(async () =>
            {
                await ConnectAsync(connection);
                Render();
            }));
            connect.IsEnabled = nodes.All(item => item.Connection.Id != connection.Id);
            body.Children.Add(Card(Stack(
                Text(connection.Name, "BodyStrongTextBlockStyle"),
                Secondary(connection.Address),
                Actions(connect,
                    Button("编辑", () => RunAsync(() => EditConnectionAsync(connection))),
                    Button("移除", () => RunAsync(() => RemoveConnectionAsync(connection)))))));
        }
    }

    private async Task EditConnectionAsync(DaemonConnection? previous, string? pairedAddress = null, string? pairedKey = null,
        bool pairedHttp = false, bool pairedSelfSigned = false)
    {
        var name = new TextBox { Header = "连接名称", PlaceholderText = "例如 家用服务器", Text = previous?.Name ?? "", MaxLength = 120 };
        var address = new TextBox { Header = "Daemon 地址", PlaceholderText = "https://daemon.example.com:1027", MaxLength = 2048,
            Text = pairedAddress ?? previous?.Address ?? "", IsReadOnly = pairedAddress is not null };
        var key = new PasswordBox { Header = "API Key", Password = pairedKey ?? previous?.ApiKey ?? "", MaxLength = 4096 };
        AutomationProperties.SetName(name, "连接名称");
        AutomationProperties.SetName(address, "Daemon 地址");
        AutomationProperties.SetName(key, "API Key");
        var allowHttp = new CheckBox { Content = "允许 HTTP 明文连接", IsChecked = pairedHttp || previous?.AllowHttp == true };
        var selfSigned = new CheckBox { Content = "跳过 HTTPS 证书验证", IsChecked = pairedSelfSigned || previous?.AllowSelfSigned == true };
        var persist = new CheckBox { Content = "保存到此 Windows 账户", IsChecked = savedAvailable, IsEnabled = savedAvailable && previous is null };
        var content = Stack(name, address, key, allowHttp, selfSigned, persist);
        string? Validate()
        {
            if (string.IsNullOrWhiteSpace(key.Password))
                return "请输入 API Key。";
            try
            {
                _ = NormalizeAddress(address.Text, allowHttp.IsChecked == true);
                return null;
            }
            catch (DaemonException error)
            {
                return error.Message;
            }
        }
        if (!await ConfirmAsync(previous is null ? "添加 Daemon" : "编辑 Daemon", content, "验证并连接", validate: Validate))
            return;
        var normalized = NormalizeAddress(address.Text, allowHttp.IsChecked == true);
        var pinDisabled = selfSigned.IsChecked == true && normalized.Scheme == Uri.UriSchemeHttps;
        var plainHttp = normalized.Scheme == Uri.UriSchemeHttp;
        if ((plainHttp || pinDisabled) && !await ConfirmAsync("确认连接安全设置",
            Secondary(plainHttp ? "HTTP 会明文发送凭据和管理指令。请确认 Daemon 位于可信网络。"
                : "跳过证书验证后，应用无法确认 Daemon 身份。请确认地址与网络可信。"), "继续连接"))
            return;
        var connection = new DaemonConnection(previous?.Id ?? Guid.NewGuid(),
            string.IsNullOrWhiteSpace(name.Text) ? normalized.Host : name.Text.Trim(),
            normalized.AbsoluteUri.TrimEnd('/'), key.Password.Trim(), plainHttp, pinDisabled);
        var duplicate = saved.FirstOrDefault(item => item.Id != connection.Id && SameConnection(item, connection));
        if (duplicate is not null)
            throw new DaemonException("这个地址和 API Key 已经保存。请从连接列表打开。");

        var replacement = await OpenNodeAsync(connection);
        if (closed)
        {
            await Task.Run(replacement.Session.Dispose);
            return;
        }
        var replaced = nodes.FirstOrDefault(item => item.Connection.Id == connection.Id || SameConnection(item.Connection, connection));
        if (replaced is not null)
            await DisconnectAsync(replaced);
        nodes.Add(replacement);
        SelectNode(replacement);
        if (persist.IsChecked == true)
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

    private async Task PairAsync()
    {
        var input = new TextBox { Header = "配对码", PlaceholderText = "mslxp1:…", AcceptsReturn = false, MaxLength = 8192 };
        var allowHttp = new CheckBox { Content = "允许 HTTP 明文连接" };
        var selfSigned = new CheckBox { Content = "跳过 HTTPS 证书验证" };
        var explanation = Secondary("在 Daemon 的 Rolithax 中心生成配对码，再粘贴到这里。");
        if (!await ConfirmAsync("配对设备", Stack(explanation, input, allowHttp, selfSigned), "兑换"))
            return;
        var payload = input.Text.Trim();
        var address = ParsePairingAddress(payload);
        var originalUri = NormalizeAddress(address, true);
        var plainHttp = originalUri.Scheme == Uri.UriSchemeHttp;
        if (plainHttp && allowHttp.IsChecked != true)
            throw new DaemonException("配对地址使用 HTTP。确认网络可信后，请勾选允许 HTTP 连接。");
        var skipTls = originalUri.Scheme == Uri.UriSchemeHttps && selfSigned.IsChecked == true;
        if ((plainHttp || skipTls) && !await ConfirmAsync("确认配对连接",
            Secondary(plainHttp ? "配对码会通过 HTTP 明文发送。请确认网络可信。"
                : "应用将跳过 Daemon 的 HTTPS 证书验证。请确认网络可信。"), "继续配对"))
            return;
        var draft = new DaemonConnection(Guid.Empty, "", originalUri.AbsoluteUri, "", plainHttp, skipTls);
        var reply = await RedeemPairAsync(draft, payload);
        var target = reply.Field("daemonUrl").StringValue(originalUri.AbsoluteUri);
        var key = reply.Field("apiKey").StringValue();
        if (key.Length == 0)
            throw new DaemonException("配对成功响应缺少 API Key，请重新生成配对码。");
        var normalized = NormalizeAddress(target, true);
        if (!string.Equals(originalUri.GetLeftPart(UriPartial.Authority), normalized.GetLeftPart(UriPartial.Authority),
            StringComparison.OrdinalIgnoreCase))
            throw new DaemonException("返回的 Daemon 地址与配对码不同。请确认服务器地址后使用 API Key 连接。");
        await EditConnectionAsync(null, normalized.AbsoluteUri, key, plainHttp, skipTls);
    }

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

    private static async Task<Node> OpenNodeAsync(DaemonConnection connection)
    {
        var session = await DaemonSession.OpenAsync(connection);
        try
        {
            var list = await session.CallAsync("instances", secret: connection.ApiKey);
            if (list.ValueKind != JsonValueKind.Array)
                throw new DaemonException("Daemon 未返回服务器列表。");
            return new Node(connection, session, list.Items().Select(Server.From).ToArray());
        }
        catch
        {
            await Task.Run(session.Dispose);
            throw;
        }
    }

    private async Task DisconnectAsync(Node node)
    {
        if (active == node)
        {
            LeaveServer();
            active = nodes.FirstOrDefault(item => item != node);
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
        string.Equals(left.Address.TrimEnd('/'), right.Address.TrimEnd('/'), StringComparison.Ordinal) &&
        string.Equals(left.ApiKey, right.ApiKey, StringComparison.Ordinal);

    private static Uri NormalizeAddress(string value, bool allowHttp)
    {
        var input = value.Trim();
        if (input.Length == 0)
            throw new DaemonException("请输入 Daemon 地址。");
        if (!input.Contains("://", StringComparison.Ordinal))
            input = "https://" + input;
        if (!Uri.TryCreate(input, UriKind.Absolute, out var address) || string.IsNullOrEmpty(address.Host) ||
            address.Scheme is not ("https" or "http") || address.UserInfo.Length > 0 ||
            address.Query.Length > 0 || address.Fragment.Length > 0)
            throw new DaemonException("请输入有效的 HTTP 或 HTTPS 地址，地址中不能包含凭据、查询参数或片段。");
        if (address.Scheme == Uri.UriSchemeHttp && !allowHttp)
            throw new DaemonException("该地址使用 HTTP。确认网络可信后，请勾选允许 HTTP 连接。");
        return address;
    }
}
