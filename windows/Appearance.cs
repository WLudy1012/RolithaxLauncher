using System.Text.Json;
using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.System;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics.Imaging;
using Windows.Storage;
using Windows.Storage.Pickers;
using Windows.UI.ViewManagement;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private const string SystemTheme = "system";
    private const string LightTheme = "light";
    private const string DarkTheme = "dark";
    private const string MicaMaterial = "mica";
    private const string MicaAltMaterial = "mica-alt";
    private const string AcrylicMaterial = "acrylic";
    private const string SolidMaterial = "solid";

    private static readonly JsonSerializerOptions AppearanceJson = new(JsonSerializerDefaults.Web);
    private static readonly string AppearancePath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "RolithaxLauncher", "windows-appearance.json");

    private AppearancePreferences appearance = new(SystemTheme, MicaMaterial);
    private ThemeSettings? systemThemeSettings;
    private readonly UISettings systemUi = new();
    private double windowLimitScale;

    private void InitializeAppearance()
    {
        if (OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000))
            AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
        appearance = ReadAppearance();
        systemThemeSettings = ThemeSettings.CreateForWindowId(AppWindow.Id);
        systemThemeSettings.Changed += OnHighContrastChanged;
        Closed += (_, _) => systemThemeSettings.Changed -= OnHighContrastChanged;
        ApplyAppearance();
        Root.Loaded += RestoreWindowBackground;
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
            [(MicaMaterial, "云母（默认）"), (MicaAltMaterial, "云母 Alt"), (AcrylicMaterial, "应用内亚克力"), (SolidMaterial, "纯色")],
            appearance.Material, value => ChangeAppearance(appearance with { Material = value }));

        var remove = Button("移除图片", () =>
        {
            var previous = appearance.Background;
            CustomWindowBackground.Source = null;
            if (ChangeAppearance(appearance with { Background = null })) ForgetWindowBackground(previous);
            Render();
        });
        remove.IsEnabled = appearance.Background is not null;
        var settings = Section("外观",
            AppearanceRow("应用主题", "切换系统、浅色或深色主题。", theme),
            AppearanceRow("窗口材质", "跟随 Windows 的透明效果设置。", material),
            AppearanceRow("自定义背景", appearance.Background is null ? "选择图片后以应用内亚克力覆盖。" : "当前已设置背景图片。",
                Actions(Button("选择图片", () => RunAsync(ChooseWindowBackgroundAsync)), remove)));
        settings.Spacing = 14;
        body.Children.Add(Card(settings));
    }

    private static Grid AppearanceRow(string title, string description, FrameworkElement choice) =>
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

    private bool ChangeAppearance(AppearancePreferences preferences)
    {
        if (preferences == appearance)
            return true;
        appearance = preferences;
        ApplyAppearance();
        try
        {
            SaveAppearance(preferences);
            return true;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            Notice("无法保存外观设置。当前窗口已应用所选外观。", InfoBarSeverity.Warning);
            return false;
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

        var highContrast = systemThemeSettings?.HighContrast == true;
        var hasBackground = !highContrast && CustomWindowBackground.Source is not null;
        var useAcrylic = !highContrast && (hasBackground || appearance.Material == AcrylicMaterial);
        var useMica = !highContrast && !useAcrylic && (appearance.Material is MicaMaterial or MicaAltMaterial) && MicaController.IsSupported();
        var useAlt = useMica && appearance.Material == MicaAltMaterial;
        CustomWindowBackground.Visibility = hasBackground ? Visibility.Visible : Visibility.Collapsed;
        AcrylicWindowLayer.Visibility = useAcrylic ? Visibility.Visible : Visibility.Collapsed;
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

    private void OnHighContrastChanged(ThemeSettings sender, object args) =>
        DispatcherQueue.TryEnqueue(() => { if (!closed) ApplyAppearance(); });

    private Border AcrylicCard(UIElement child) => AcrylicWindowLayer.Visibility == Visibility.Visible
        ? Card(child) : new Border { Style = Resource<Style>("RolithaxAcrylicCardStyle"), Child = child };

    private void AnimateEntrance(UIElement element)
    {
        if (!systemUi.AnimationsEnabled) return;
        element.Transitions = new TransitionCollection { new EntranceThemeTransition { FromVerticalOffset = 10 } };
    }

    private async void RestoreWindowBackground(object sender, RoutedEventArgs args)
    {
        Root.Loaded -= RestoreWindowBackground;
        if (WindowBackgroundPath(appearance.Background) is not { } path) return;
        var saved = appearance.Background;
        try
        {
            var file = await StorageFile.GetFileFromPathAsync(path);
            var bitmap = await ReadWindowBackgroundAsync(file);
            if (closed || appearance.Background != saved) return;
            CustomWindowBackground.Source = bitmap;
            ApplyAppearance();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Runtime.InteropServices.COMException) { }
    }

    private async Task ChooseWindowBackgroundAsync()
    {
        var picker = new FileOpenPicker { SuggestedStartLocation = PickerLocationId.PicturesLibrary };
        foreach (var extension in new[] { ".png", ".jpg", ".jpeg", ".webp" }) picker.FileTypeFilter.Add(extension);
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(this));
        if (await picker.PickSingleFileAsync() is not { } file) return;
        var id = Guid.NewGuid().ToString("N");
        var path = WindowBackgroundPath(id)!;
        BitmapImage bitmap;
        try
        {
            if ((await file.GetBasicPropertiesAsync()).Size > 32 * 1024 * 1024)
                throw new DaemonException("请选择 32 MB 以内的图片。");
            var folder = Path.GetDirectoryName(path)!;
            Directory.CreateDirectory(folder);
            var storage = await StorageFolder.GetFolderFromPathAsync(folder);
            var copy = await file.CopyAsync(storage, Path.GetFileName(path), NameCollisionOption.FailIfExists);
            bitmap = await ReadWindowBackgroundAsync(copy);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Runtime.InteropServices.COMException)
        {
            ForgetWindowBackground(id);
            throw new DaemonException("无法读取图片。请选择有效的 PNG、JPEG 或 WebP 图片。");
        }
        var previous = appearance.Background;
        CustomWindowBackground.Source = bitmap;
        if (ChangeAppearance(appearance with { Background = id })) ForgetWindowBackground(previous);
        Render();
    }

    private static async Task<BitmapImage> ReadWindowBackgroundAsync(StorageFile file)
    {
        using var stream = await file.OpenReadAsync();
        if (stream.Size > 32 * 1024 * 1024) throw new IOException();
        var decoder = await BitmapDecoder.CreateAsync(stream);
        if (decoder.PixelWidth > 8192 || decoder.PixelHeight > 8192) throw new IOException();
        stream.Seek(0);
        var bitmap = new BitmapImage();
        if (decoder.OrientedPixelHeight > decoder.OrientedPixelWidth) bitmap.DecodePixelHeight = 2560;
        else bitmap.DecodePixelWidth = 2560;
        await bitmap.SetSourceAsync(stream);
        return bitmap;
    }

    private static string? WindowBackgroundPath(string? id) => Guid.TryParseExact(id, "N", out var parsed)
        ? Path.Combine(Path.GetDirectoryName(AppearancePath)!, "backgrounds", parsed.ToString("N")) : null;

    private static void ForgetWindowBackground(string? id)
    {
        if (WindowBackgroundPath(id) is not { } path) return;
        try { File.Delete(path); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
    }

    private static AppearancePreferences ReadAppearance()
    {
        try
        {
            using var stream = File.OpenRead(AppearancePath);
            if (stream.Length <= 4096 && JsonSerializer.Deserialize<AppearancePreferences>(stream, AppearanceJson) is { } saved)
                return new AppearancePreferences(
                    saved.Theme is LightTheme or DarkTheme ? saved.Theme : SystemTheme,
                    saved.Material is MicaAltMaterial or AcrylicMaterial or SolidMaterial ? saved.Material : MicaMaterial,
                    WindowBackgroundPath(saved.Background) is null ? null : saved.Background);
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

    private sealed record AppearancePreferences(string Theme, string Material, string? Background = null);
}
