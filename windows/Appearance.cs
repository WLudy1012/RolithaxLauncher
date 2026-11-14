using System.Text.Json;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private const string SystemTheme = "system";
    private const string LightTheme = "light";
    private const string DarkTheme = "dark";
    private const string MicaMaterial = "mica";
    private const string MicaAltMaterial = "mica-alt";
    private const string SolidMaterial = "solid";

    private static readonly JsonSerializerOptions AppearanceJson = new(JsonSerializerDefaults.Web);
    private static readonly string AppearancePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "RolithaxLauncher", "windows-appearance.json");

    private AppearancePreferences appearance = new(SystemTheme, MicaMaterial);
    private double windowLimitScale;

    private void InitializeAppearance()
    {
        if (OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000))
            AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
        appearance = ReadAppearance();
        ApplyAppearance();
        UpdateNavigationChrome(Navigation.DisplayMode);
        Navigation.DisplayModeChanged += (_, args) => UpdateNavigationChrome(args.DisplayMode);
        AppTitleBar.PaneToggleRequested += (_, _) =>
        {
            if (Navigation.IsEnabled)
                Navigation.IsPaneOpen = !Navigation.IsPaneOpen;
        };
        Root.Loaded += InitializeWindowLimits;
    }

    private void InitializeWindowLimits(object sender, RoutedEventArgs args)
    {
        Root.Loaded -= InitializeWindowLimits;
        var xamlRoot = Root.XamlRoot;
        UpdateWindowLimits(xamlRoot);
        xamlRoot.Changed += WindowScaleChanged;
        Closed += (_, _) => xamlRoot.Changed -= WindowScaleChanged;
    }

    private void WindowScaleChanged(XamlRoot root, XamlRootChangedEventArgs args) => UpdateWindowLimits(root);

    private void UpdateWindowLimits(XamlRoot root)
    {
        var scale = root.RasterizationScale;
        if (windowLimitScale == scale || AppWindow.Presenter is not OverlappedPresenter presenter)
            return;
        windowLimitScale = scale;
        var workArea = DisplayArea.GetFromWindowId(AppWindow.Id, DisplayAreaFallback.Primary).WorkArea;
        presenter.PreferredMinimumWidth = Math.Min(workArea.Width, (int)Math.Ceiling(480 * scale));
        presenter.PreferredMinimumHeight = Math.Min(workArea.Height, (int)Math.Ceiling(500 * scale));
    }

    private void UpdateNavigationChrome(NavigationViewDisplayMode mode)
    {
        NavigationContext.Visibility = mode == NavigationViewDisplayMode.Compact
            ? Visibility.Collapsed : Visibility.Visible;
        AppTitleBar.IsPaneToggleButtonVisible = mode == NavigationViewDisplayMode.Minimal;
        WorkspaceSurface.Margin = mode == NavigationViewDisplayMode.Minimal
            ? new Thickness(6, 2, 6, 6) : new Thickness(10, 2, 10, 10);
    }

    private void AddAppearanceSettings(StackPanel body)
    {
        var theme = AppearanceChoice("应用主题",
            [(SystemTheme, "跟随系统"), (LightTheme, "浅色"), (DarkTheme, "深色")],
            appearance.Theme, value => ChangeAppearance(appearance with { Theme = value }));
        var material = AppearanceChoice("窗口背景",
            [(MicaMaterial, "云母（默认）"), (MicaAltMaterial, "云母 Alt"), (SolidMaterial, "纯色")],
            appearance.Material, value => ChangeAppearance(appearance with { Material = value }));

        var settings = Section("外观",
            AppearanceRow("应用主题", "切换系统、浅色或深色主题。", theme),
            AppearanceRow("窗口背景", "云母会随 Windows 的透明效果设置调整。", material));
        settings.Spacing = 14;
        body.Children.Add(Card(settings));
    }

    private static Grid AppearanceRow(string title, string description, ComboBox choice) =>
        AdaptivePair(CompactStack(Text(title, "BodyStrongTextBlockStyle"), Secondary(description)), choice, 390);

    private static ComboBox AppearanceChoice(string name, (string Value, string Label)[] options,
        string selected, Action<string> change)
    {
        var choice = new ComboBox { MinWidth = 132, VerticalAlignment = VerticalAlignment.Center };
        AutomationProperties.SetName(choice, name);
        foreach (var (value, label) in options)
        {
            var item = new ComboBoxItem { Tag = value, Content = label };
            choice.Items.Add(item);
            if (value == selected)
                choice.SelectedItem = item;
        }
        choice.SelectionChanged += (_, _) =>
        {
            if (choice.SelectedItem is ComboBoxItem { Tag: string value })
                change(value);
        };
        return choice;
    }

    private void ChangeAppearance(AppearancePreferences preferences)
    {
        if (preferences == appearance)
            return;
        appearance = preferences;
        ApplyAppearance();
        try
        {
            SaveAppearance(preferences);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            Notice("无法保存外观设置。当前窗口已应用所选外观。", InfoBarSeverity.Warning);
        }
    }

    private void ApplyAppearance()
    {
        Root.RequestedTheme = appearance.Theme switch
        {
            LightTheme => ElementTheme.Light,
            DarkTheme => ElementTheme.Dark,
            _ => ElementTheme.Default,
        };
        AppWindow.TitleBar.PreferredTheme = Root.RequestedTheme switch
        {
            ElementTheme.Light => TitleBarTheme.Light,
            ElementTheme.Dark => TitleBarTheme.Dark,
            _ => TitleBarTheme.UseDefaultAppMode,
        };

        var useMica = appearance.Material != SolidMaterial && MicaController.IsSupported();
        var useAlt = useMica && appearance.Material == MicaAltMaterial;
        SolidWindowBackground.Visibility = useMica ? Visibility.Collapsed : Visibility.Visible;
        MicaAltNavigationLayer.Visibility = useAlt ? Visibility.Visible : Visibility.Collapsed;
        if (!useMica)
        {
            SystemBackdrop = null;
            return;
        }
        var kind = useAlt ? MicaKind.BaseAlt : MicaKind.Base;
        if (SystemBackdrop is not MicaBackdrop mica || mica.Kind != kind)
            SystemBackdrop = new MicaBackdrop { Kind = kind };
    }

    private static AppearancePreferences ReadAppearance()
    {
        try
        {
            using var stream = File.OpenRead(AppearancePath);
            if (stream.Length <= 4096 && JsonSerializer.Deserialize<AppearancePreferences>(stream, AppearanceJson) is { } saved)
                return new AppearancePreferences(
                    saved.Theme is LightTheme or DarkTheme ? saved.Theme : SystemTheme,
                    saved.Material is MicaAltMaterial or SolidMaterial ? saved.Material : MicaMaterial);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        {
        }
        return new AppearancePreferences(SystemTheme, MicaMaterial);
    }

    private static void SaveAppearance(AppearancePreferences preferences)
    {
        var folder = Path.GetDirectoryName(AppearancePath)!;
        Directory.CreateDirectory(folder);
        var temporary = Path.Combine(folder, Path.GetRandomFileName());
        try
        {
            using (var stream = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write))
                JsonSerializer.Serialize(stream, preferences, AppearanceJson);
            File.Move(temporary, AppearancePath, overwrite: true);
        }
        finally
        {
            File.Delete(temporary);
        }
    }

    private sealed record AppearancePreferences(string Theme, string Material);
}
