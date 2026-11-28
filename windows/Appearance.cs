using Microsoft.UI.Composition.SystemBackdrops;
using Microsoft.UI.System;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Windows.UI.ViewManagement;

namespace Rolithax.Windows;

public sealed partial class MainWindow
{
    private ThemeSettings? systemThemeSettings;
    private readonly UISettings systemUi = new();
    private double windowLimitScale;

    private void InitializeAppearance()
    {
        if (OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000))
            AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
        systemThemeSettings = ThemeSettings.CreateForWindowId(AppWindow.Id);
        systemThemeSettings.Changed += OnHighContrastChanged;
        Closed += (_, _) => systemThemeSettings.Changed -= OnHighContrastChanged;
        UpdateWindowBackdrop();
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
        var minimal = mode == NavigationViewDisplayMode.Minimal;
        WorkspaceSurface.CornerRadius = minimal ? new CornerRadius(0) : new CornerRadius(8, 0, 0, 0);
        WorkspaceSurface.BorderThickness = new Thickness(minimal ? 0 : 1, 1, 0, 0);
    }

    private void UpdateWindowBackdrop()
    {
        var useMica = systemThemeSettings?.HighContrast != true && MicaController.IsSupported();
        if (useMica)
            SystemBackdrop ??= new MicaBackdrop { Kind = MicaKind.Base };
        else
            SystemBackdrop = null;
        SolidWindowBackground.Visibility = useMica ? Visibility.Collapsed : Visibility.Visible;
    }

    private void OnHighContrastChanged(ThemeSettings sender, object args) =>
        DispatcherQueue.TryEnqueue(() => { if (!closed) UpdateWindowBackdrop(); });

    private void AnimateEntrance(UIElement element)
    {
        if (!systemUi.AnimationsEnabled) return;
        element.Transitions = new TransitionCollection { new EntranceThemeTransition { FromVerticalOffset = 10 } };
    }
}
