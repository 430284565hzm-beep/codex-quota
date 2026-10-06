param([switch]$Verify, [switch]$VerifyLive, [switch]$VerifyDock, [switch]$VerifyMotion, [switch]$VerifyBank, [switch]$VerifyResetAnimation, [switch]$Preview, [string]$PreviewDirectory)
if ($VerifyResetAnimation) { $VerifyBank=$true }
if ($VerifyLive) { $Verify = $true }
if ($VerifyDock) { $Verify = $true }
if ($VerifyMotion -or $VerifyBank) { $Verify = $true }
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName PresentationFramework, PresentationCore, WindowsBase, System.Xaml, System.Web.Extensions, System.Windows.Forms, System.Drawing
Add-Type -Path (Join-Path $PSScriptRoot 'UsageClient.cs') -ReferencedAssemblies System.Web.Extensions, System.Drawing, PresentationCore, PresentationFramework, WindowsBase, System.Xaml
if (!$Verify -and !$Preview) {
    $script:instance = New-Object System.Threading.Mutex($false, 'Local\CodexUsageWPF')
    if (!$instance.WaitOne(0, $false)) {
        $hwnd = [WidgetWindow]::FindWidgetWindow()
        if ($hwnd -ne [IntPtr]::Zero) { [void][WidgetWindow]::ShowWindow($hwnd, 9); [void][WidgetWindow]::SetForegroundWindow($hwnd) }
        $client = $null
        exit
    }
}
$script:settingsPath = Join-Path $PSScriptRoot 'wpf-settings.json'
$script:settings = @{}
if (Test-Path $settingsPath) { try { $script:settings = Get-Content $settingsPath -Raw | ConvertFrom-Json } catch {} }
$codexCommand = Get-Command codex.exe -ErrorAction SilentlyContinue
if ($codexCommand) { $codexPath = $codexCommand.Source } else {
    $codexPath = (Get-ChildItem "$env:LOCALAPPDATA\OpenAI\Codex\bin\*\codex.exe" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName
}
if (!$codexPath) { throw '未找到 Codex。请先在此账户安装并登录 Codex。' }
$script:client = New-Object UsageClient($codexPath)
[xml]$xaml = [xml](Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Widget.xaml') -Raw -Encoding UTF8)

$script:window = [Windows.Markup.XamlReader]::Load((New-Object System.Xml.XmlNodeReader $xaml))
$script:ui = @{}
foreach ($name in 'MotionCanvas','GlassShadow','GlassBackdrop','GlassSurface','GlassDepth','GlassRim','PeekGroupA','PeekDivider','PeekGroupB','PeekLabelA','PeekLabelB','Header','Outer','FullContent','DockPeek','PeekA','PeekB','Plan','Pin','Refresh','More','Metrics','MetricA','MetricB','CompactA','CompactB','DetailA','DetailB','LabelA','LabelB','ValueA','ValueB','BarA','BarB','ResetA','ResetB','DetailLabelA','DetailLabelB','DetailValueA','DetailValueB','DetailResetA','DetailResetB','RingA','RingB','ArcA','ArcB','Divider','Status','StatusDot') { $ui[$name] = $window.FindName($name) }
$script:backdropBrush = New-Object Windows.Media.ImageBrush
foreach($name in 'Footer','Bank','BankGlyph','BankCaption','BankSheet','BankCount','BankMessage','BankCancel','BankConfirm'){$ui[$name]=$window.FindName($name)}
$script:celebration=[ResetCelebration]::new();[void]$window.FindName('FxHost').Children.Add($script:celebration)
$script:resetSounds=[ResetSounds]::new($PSScriptRoot)
$script:resetSoundEnabled=if($null -ne $settings.resetSound){[bool]$settings.resetSound}else{$true}
$script:resetConfettiEnabled=if($null -ne $settings.resetConfetti){[bool]$settings.resetConfetti}else{$true}
$script:backdropBrush.Stretch = 'Fill'
$script:backdropBrush.ViewboxUnits = 'Absolute'
$script:backdropBrush.ViewportUnits = 'Absolute'
$ui.GlassBackdrop.Background = $script:backdropBrush
$window.Left = [System.Windows.SystemParameters]::WorkArea.Right - $window.Width - 28
$window.Top = [System.Windows.SystemParameters]::WorkArea.Top + 70
if ($settings.width) {
    $window.Width = [Math]::Max(260, [double]$settings.width); $window.Height = [Math]::Max(184, [double]$settings.height)
    # Keep negative desktop coordinates for monitors to the left or above the primary.
    $window.Left = [double]$settings.left
    $window.Top = [double]$settings.top
    $window.Topmost = [bool]$settings.pin
}
$window.Add_SourceInitialized({
    $script:glassHandle = (New-Object Windows.Interop.WindowInteropHelper($window)).Handle
    [Windows.Interop.HwndSource]::FromHwnd($script:glassHandle).CompositionTarget.BackgroundColor = [Windows.Media.Colors]::Transparent
    $script:glassResult = [WidgetWindow]::ConfigureGlass($script:glassHandle, $script:dark)
    [WidgetWindow]::AttachHitTest($window,$ui.Outer)
    Update-GlassShape
})
$script:pending = $null
$script:lastUpdate = $null
$script:theme = if ($settings.theme) { [string]$settings.theme } elseif ([bool]$settings.dark) { 'dark' } else { 'light' }
function Set-BrushColor($key, $value) { $brush = New-Object Windows.Media.SolidColorBrush; $brush.Color = [Windows.Media.ColorConverter]::ConvertFromString($value); $window.Resources.set_Item($key, $brush) }
function Set-Theme {
    $script:dark = $script:theme -eq 'dark'
    if ($script:theme -eq 'auto') {
        try { $script:dark = ((Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize' -Name AppsUseLightTheme -ErrorAction Stop).AppsUseLightTheme -eq 0) } catch { $script:dark = $false }
    }
    if ($script:dark) {
        $colors = @{TextBrush='#F5F5F7';MutedBrush='#C0C0C5';FaintBrush='#ABABB2';SurfaceBrush='#4523262D';EdgeBrush='#40FFFFFF';TrackBrush='#20FFFFFF';MenuBrush='#F52C2C2E';HoverBrush='#18FFFFFF';PressedBrush='#29FFFFFF';SeparatorBrush='#1EFFFFFF';AccentBrush='#66C9A8';WarningBrush='#E2AB60'}
    } else {
        $colors = @{TextBrush='#232326';MutedBrush='#535E69';FaintBrush='#6A7480';SurfaceBrush='#42FFFFFF';EdgeBrush='#95FFFFFF';TrackBrush='#183E4955';MenuBrush='#FAF3F3F5';HoverBrush='#12000000';PressedBrush='#20000000';SeparatorBrush='#18000000';AccentBrush='#398A78';WarningBrush='#B47D36'}
    }
    foreach ($key in $colors.Keys) { Set-BrushColor $key $colors[$key] }
    $surface = [Windows.Media.LinearGradientBrush]::new()
    $surface.StartPoint = [Windows.Point]::new(0,0); $surface.EndPoint = [Windows.Point]::new(0.8,1)
    $stops = if ($script:dark) { @('#B5343944','#98252C38','#AF202731') } else { @('#B8FFFFFF','#97F6F9FE','#A8EAF2FB') }
    for ($i=0; $i -lt 3; $i++) { $surface.GradientStops.Add([Windows.Media.GradientStop]::new([Windows.Media.ColorConverter]::ConvertFromString($stops[$i]),$i/2.0)) }
    $window.Resources.set_Item('SurfaceBrush',$surface)
    $rim = [Windows.Media.LinearGradientBrush]::new()
    $rim.StartPoint = [Windows.Point]::new(0,0); $rim.EndPoint = [Windows.Point]::new(0.85,1)
    $stops = if ($script:dark) { @('#80FFFFFF','#18FFFFFF','#40FFFFFF') } else { @('#E0FFFFFF','#25FFFFFF','#85FFFFFF') }
    for ($i=0; $i -lt 3; $i++) { $rim.GradientStops.Add([Windows.Media.GradientStop]::new([Windows.Media.ColorConverter]::ConvertFromString($stops[$i]),$i/2.0)) }
    $window.Resources.set_Item('RimBrush',$rim)
    $depth = [Windows.Media.LinearGradientBrush]::new()
    $depth.StartPoint = [Windows.Point]::new(0,0); $depth.EndPoint = [Windows.Point]::new(0.7,1)
    $stops = if ($script:dark) { @('#5AFFFFFF','#00FFFFFF','#56000610') } else { @('#7AFFFFFF','#00FFFFFF','#330C1725') }
    for ($i=0; $i -lt 3; $i++) { $depth.GradientStops.Add([Windows.Media.GradientStop]::new([Windows.Media.ColorConverter]::ConvertFromString($stops[$i]),$i/2.0)) }
    $window.Resources.set_Item('DepthBrush',$depth)
    if ($script:glassHandle -ne [IntPtr]::Zero) { $script:glassResult = [WidgetWindow]::ConfigureGlass($script:glassHandle, $script:dark) }
    $ui.Pin.SetResourceReference([Windows.Controls.Control]::ForegroundProperty, $(if ($window.Topmost) { 'AccentBrush' } else { 'MutedBrush' }))
    $ui.Pin.ToolTip = if ($window.Topmost) { '取消置顶' } else { '置顶' }
}
$script:normalWidth = $window.Width
$script:normalHeight = $window.Height
$script:dockSide = $null
$script:dockCenterX = 0.0
$script:dockCenterY = 0.0
$script:dockCollapsed = $false
$script:dockTransition = $null
$script:glassHandle = [IntPtr]::Zero
$script:dockAllowed = !$Verify -and !$Preview
$script:glassRadius = 22.0
$script:isDragging = $false
$script:isResizing = $false
$script:layoutKey = ''
$script:appReady = $false
$script:dockGap = 12.0
$script:shapeKey = ''
function Set-Ring($suffix, $remain) {
    $ring = $ui["Ring$suffix"]; $path = $ui["Arc$suffix"]
    if (!$ring) { return }
    $path.Visibility = if ($remain -gt 0) { 'Visible' } else { 'Collapsed' }
    $center = $ring.Width / 2; $radius = $center - 5.5
    $start = -90 * [Math]::PI / 180; $angle = [Math]::Max(0.1, [Math]::Min(359.9, [double]$remain * 3.6)) * [Math]::PI / 180
    $end = $start + $angle
    $figure = New-Object Windows.Media.PathFigure
    $figure.StartPoint = [Windows.Point]::new(($center + $radius * [Math]::Cos($start)), ($center + $radius * [Math]::Sin($start)))
    $arc = New-Object Windows.Media.ArcSegment
    $arc.Point = [Windows.Point]::new(($center + $radius * [Math]::Cos($end)), ($center + $radius * [Math]::Sin($end))); $arc.Size = New-Object Windows.Size($radius,$radius); $arc.SweepDirection = 'Clockwise'; $arc.IsLargeArc = $angle -gt [Math]::PI
    $figure.Segments.Add($arc); $geometry = New-Object Windows.Media.PathGeometry; $geometry.Figures.Add($figure); $path.Data = $geometry
}
function Update-Layout {
    if ($script:dockSide -and ($script:dockCollapsed -or $script:dockTransition)) { return }
    $wide = $window.ActualWidth -gt 500
    $detail = ($wide -and $window.ActualHeight -ge 290) -or (!$wide -and $window.ActualHeight -ge 460)
    $showReset=($wide -or $window.ActualHeight -ge 240) -and !$detail
    $key="$wide/$detail/$showReset/$($window.ActualWidth -ge 285)"
    if ($key -eq $script:layoutKey) { return }
    $script:layoutKey=$key
    $ui.MetricA.SetValue([Windows.Controls.Grid]::ColumnProperty, 0); $ui.MetricB.SetValue([Windows.Controls.Grid]::ColumnProperty, $(if ($wide) { 1 } else { 0 }))
    $ui.MetricA.SetValue([Windows.Controls.Grid]::RowProperty, 0); $ui.MetricB.SetValue([Windows.Controls.Grid]::RowProperty, $(if ($wide) { 0 } else { 1 }))
    $ui.Metrics.ColumnDefinitions[1].Width = if ($wide) { New-Object Windows.GridLength(1,'Star') } else { New-Object Windows.GridLength(0) }
    $ui.Metrics.RowDefinitions[1].Height = if ($wide) { New-Object Windows.GridLength(0) } else { New-Object Windows.GridLength(1,'Star') }
    $ui.MetricA.Margin = if ($wide) { '0,0,18,0' } elseif ($detail) { '0,0,0,14' } else { '0' }; $ui.MetricB.Margin = if ($wide) { '18,0,0,0' } elseif ($detail) { '0,14,0,0' } else { '0' }
    $ui.Divider.Visibility = if ($wide) { 'Visible' } else { 'Collapsed' }
    foreach ($suffix in 'A','B') {
        $ui["Compact$suffix"].Visibility = if ($detail) { 'Collapsed' } else { 'Visible' }; $ui["Detail$suffix"].Visibility = if ($detail) { 'Visible' } else { 'Collapsed' }
        $ui["Reset$suffix"].Visibility = if (($wide -or $window.ActualHeight -ge 240) -and !$detail) { 'Visible' } else { 'Collapsed' }
        if ($detail) { Set-Ring $suffix $ui["Bar$suffix"].GetAnimationBaseValue([Windows.Controls.Primitives.RangeBase]::ValueProperty) }
    }
    $ui.Metrics.MaxHeight = if ($detail) { if ($wide) { 260 } else { 430 } } else { 160 }
    $ui.Metrics.VerticalAlignment = 'Center'
    $ui.Plan.Visibility = if ($window.ActualWidth -lt 285) { 'Collapsed' } else { 'Visible' }
}
function Get-WorkArea {
    if ($script:glassHandle -eq [IntPtr]::Zero) { return [Windows.SystemParameters]::WorkArea }
    $bounds = [Windows.Forms.Screen]::FromHandle($script:glassHandle).WorkingArea
    $source = [Windows.Interop.HwndSource]::FromHwnd($script:glassHandle)
    if (!$source -or !$source.CompositionTarget) { return [Windows.SystemParameters]::WorkArea }
    $matrix = $source.CompositionTarget.TransformFromDevice
    $origin = $matrix.Transform([Windows.Point]::new($bounds.Left, $bounds.Top))
    return [Windows.Rect]::new($origin.X, $origin.Y, $bounds.Width * $matrix.M11, $bounds.Height * $matrix.M22)
}
function Update-GlassShape {
    if ($script:dockTransition) { return }
    $width = $ui.Outer.Width; $height = $ui.Outer.Height
    if ($width -le 0 -or $height -le 0) { return }
    $radius = [Math]::Min($script:glassRadius, $height / 2)
    foreach ($name in 'Outer','GlassShadow','GlassBackdrop','GlassSurface','GlassRim') { $ui[$name].CornerRadius = [Windows.CornerRadius]::new($radius) }
    $ui.GlassDepth.CornerRadius = [Windows.CornerRadius]::new([Math]::Max(0,$radius-1))
    # Border is the sole antialiased silhouette; no GDI region or second
    # rounded clip may quantize/clip its partially covered edge pixels.
    $ui.Outer.Clip = $null

}
function Sync-OuterBounds {
    if ($script:dockTransition -or ($script:dockSide -and $script:dockCollapsed) -or $window.ActualWidth -le 0 -or $window.ActualHeight -le 0) { return }
    $ui.Outer.Width=[Math]::Max(1.0,$window.ActualWidth-10)
    $ui.Outer.Height=[Math]::Max(1.0,$window.ActualHeight-10)
    [Windows.Controls.Canvas]::SetLeft($ui.Outer,5)
    [Windows.Controls.Canvas]::SetTop($ui.Outer,5)
}
$script:glassScreen=$null
$script:glassTask=$null
$script:glassNextCapture=[DateTime]::MinValue
function Update-GlassViewport([switch]$Force) {
    if ($script:dockTransition -and !$Force) { return }
    if (!$script:glassScreen -or $script:glassHandle -eq [IntPtr]::Zero) { return }
    $sourceInfo=[Windows.Interop.HwndSource]::FromHwnd($script:glassHandle)
    if (!$sourceInfo -or !$sourceInfo.CompositionTarget) { return }
    $matrix=$sourceInfo.CompositionTarget.TransformToDevice
    $viewport=[GlassScreenCapture]::Viewport($script:glassScreen,$script:glassHandle,
        [Windows.Controls.Canvas]::GetLeft($ui.Outer),[Windows.Controls.Canvas]::GetTop($ui.Outer),$matrix.M11,$matrix.M22)
    if ($viewport) {
        $script:backdropBrush.Viewport=[Windows.Rect]::new($viewport[0],$viewport[1],$viewport[2],$viewport[3])
    }
}
function Update-GlassFrame {
    if ($Verify -or !$script:appReady -or !$window.IsVisible -or $window.WindowState -eq 'Minimized' -or $script:glassHandle -eq [IntPtr]::Zero -or $script:isDragging -or $script:isResizing -or $script:dockTransition) { return }
    try {
        if ($script:glassTask) {
            if (!$script:glassTask.IsCompleted) { return }
            $frame=$script:glassTask.GetAwaiter().GetResult()
            $script:glassTask=$null
            if ($frame) {
                $source=[Windows.Media.Imaging.BitmapSource]::Create($frame.Width,$frame.Height,96,96,[Windows.Media.PixelFormats]::Bgra32,$null,$frame.Pixels,$frame.Width*4)
                $source.Freeze()
                $script:glassScreen=$frame
                $script:backdropBrush.Viewbox=[Windows.Rect]::new(0,0,$frame.Width,$frame.Height)
                $script:backdropBrush.ImageSource=$source
                Update-GlassViewport
            }
        }
        if (!$script:glassTask -and [DateTime]::UtcNow -ge $script:glassNextCapture) {
            $bounds=[Windows.Forms.SystemInformation]::VirtualScreen
            $script:glassTask=[GlassScreenCapture]::CaptureAsync($script:glassHandle,$bounds.Left,$bounds.Top,$bounds.Width,$bounds.Height)
            $script:glassNextCapture=[DateTime]::UtcNow.AddMilliseconds(100)
        }
    } catch { $script:glassTask=$null; $script:glassCaptureError=$_.Exception.GetBaseException().Message }
}
function Update-GlassFrameNow { Update-GlassFrame }
function Update-DockPeekLayout {
    $horizontal = $script:dockSide -eq 'top'
    $ui.DockPeek.Width=if($horizontal){158.0}else{54.0}
    $ui.DockPeek.Height=if($horizontal){42.0}else{102.0}
    $ui.DockPeek.Margin='0'
    $ui.DockPeek.RowDefinitions.Clear(); $ui.DockPeek.ColumnDefinitions.Clear()
    if ($horizontal) {
        $row=New-Object Windows.Controls.RowDefinition; $ui.DockPeek.RowDefinitions.Add($row)
        foreach ($width in '*','12','*') {
            $col=New-Object Windows.Controls.ColumnDefinition
            $col.Width=[Windows.GridLengthConverter]::new().ConvertFromString($width)
            $ui.DockPeek.ColumnDefinitions.Add($col)
        }
        $ui.PeekDivider.Width=1; $ui.PeekDivider.Height=24
        $ui.PeekGroupA.SetValue([Windows.Controls.Grid]::RowProperty,0); $ui.PeekGroupA.SetValue([Windows.Controls.Grid]::ColumnProperty,0)
        $ui.PeekDivider.SetValue([Windows.Controls.Grid]::RowProperty,0); $ui.PeekDivider.SetValue([Windows.Controls.Grid]::ColumnProperty,1)
        $ui.PeekGroupB.SetValue([Windows.Controls.Grid]::RowProperty,0); $ui.PeekGroupB.SetValue([Windows.Controls.Grid]::ColumnProperty,2)
    } else {
        foreach ($height in '*','17','*') {
            $row=New-Object Windows.Controls.RowDefinition
            $row.Height=[Windows.GridLengthConverter]::new().ConvertFromString($height)
            $ui.DockPeek.RowDefinitions.Add($row)
        }
        $col=New-Object Windows.Controls.ColumnDefinition; $ui.DockPeek.ColumnDefinitions.Add($col)
        $ui.PeekDivider.Width=26; $ui.PeekDivider.Height=1
        $ui.PeekGroupA.SetValue([Windows.Controls.Grid]::RowProperty,0); $ui.PeekGroupA.SetValue([Windows.Controls.Grid]::ColumnProperty,0)
        $ui.PeekDivider.SetValue([Windows.Controls.Grid]::RowProperty,1); $ui.PeekDivider.SetValue([Windows.Controls.Grid]::ColumnProperty,0)
        $ui.PeekGroupB.SetValue([Windows.Controls.Grid]::RowProperty,2); $ui.PeekGroupB.SetValue([Windows.Controls.Grid]::ColumnProperty,0)
    }
}
function Get-DockRect([bool]$compact) {
    $area = Get-WorkArea
    $gap = $script:dockGap
    $width = if ($compact -and $script:dockSide -eq 'top') { 196.0 } elseif ($compact) { 80.0 } else { [Math]::Min([double]$script:normalWidth, $area.Width - 2 * $gap) }
    $height = if ($compact -and $script:dockSide -eq 'top') { 64.0 } elseif ($compact) { 144.0 } else { [Math]::Min([double]$script:normalHeight, $area.Height - 2 * $gap) }
    $left = [Math]::Max($area.Left + $gap, [Math]::Min($script:dockCenterX - $width / 2, $area.Right - $width - $gap))
    $top = [Math]::Max($area.Top + $gap, [Math]::Min($script:dockCenterY - $height / 2, $area.Bottom - $height - $gap))
    if ($script:dockSide -eq 'left') { $left = $area.Left + $gap }
    if ($script:dockSide -eq 'right') { $left = $area.Right - $width - $gap }
    if ($script:dockSide -eq 'top') { $top = $area.Top + $gap }
    return @{ Left=$left; Top=$top; Width=$width; Height=$height }
}
function Set-WindowRect($rect) {
    if ($script:glassHandle -eq [IntPtr]::Zero) { return }
    $source = [Windows.Interop.HwndSource]::FromHwnd($script:glassHandle)
    if (!$source -or !$source.CompositionTarget) { return }
    $matrix = $source.CompositionTarget.TransformToDevice
    $point = $matrix.Transform([Windows.Point]::new($rect.Left,$rect.Top))
    [WidgetWindow]::Place($script:glassHandle, [int][Math]::Round($point.X), [int][Math]::Round($point.Y),
        [int][Math]::Round($rect.Width * $matrix.M11), [int][Math]::Round($rect.Height * $matrix.M22))
    Sync-OuterBounds
    Update-GlassShape
    Update-GlassViewport
}
function Get-GlassRect {
    return @{Left=$window.Left+[Windows.Controls.Canvas]::GetLeft($ui.Outer)-5
        Top=$window.Top+[Windows.Controls.Canvas]::GetTop($ui.Outer)-5
        Width=$ui.Outer.Width+10;Height=$ui.Outer.Height+10;Radius=$ui.Outer.CornerRadius.TopLeft}
}
function Set-GlassRect($rect) {
    $ui.Outer.Width=[Math]::Max(1.0,$rect.Width-10)
    $ui.Outer.Height=[Math]::Max(1.0,$rect.Height-10)
    [Windows.Controls.Canvas]::SetLeft($ui.Outer,$rect.Left-$window.Left+5)
    [Windows.Controls.Canvas]::SetTop($ui.Outer,$rect.Top-$window.Top+5)
}
function Sync-CapturePause {
    $paused=[bool]($script:isDragging -or $script:isResizing -or $script:dockTransition)
    [GlassScreenCapture]::SetPaused($paused)
    if ($script:glassTimer) {
        if ($paused) { $script:glassTimer.Stop() }
        elseif ($script:appReady -and !$Verify -and !$Preview) { $script:glassTimer.Start() }
    }
}
function Complete-Dock([bool]$compact) {
    $script:dockCollapsed=$compact
    $script:glassRadius=if($compact){if($script:dockSide -eq 'top'){27.0}else{35.0}}else{22.0}
    if ($script:dockSide) { Set-GlassRect (Get-DockRect $compact) }
    $script:dockTransition=$null
    if (!$script:dockSide) { Sync-OuterBounds }
    $ui.GlassSurface.Opacity=if($compact){0.9}else{1.0}
    $ui.GlassDepth.Opacity=if($compact){1.0}else{0.72}
    $ui.GlassRim.Opacity=if($compact){1.0}else{0.86}
    $ui.DockPeek.Opacity=1; $ui.FullContent.Opacity=1
    $ui.DockPeek.Visibility=if($compact){'Visible'}else{'Collapsed'}
    $ui.FullContent.Visibility=if($compact){'Collapsed'}else{'Visible'}
    $window.ResizeMode=if($compact){'NoResize'}else{'CanResize'}
    if (!$compact) {
        $ui.FullContent.Width=[double]::NaN; $ui.FullContent.Height=[double]::NaN
        $ui.FullContent.HorizontalAlignment='Stretch';$ui.FullContent.VerticalAlignment='Stretch'
        $window.MinWidth=260; $window.MinHeight=184
        $script:layoutKey=''; Update-Layout
    }
    Update-GlassShape; Update-GlassViewport
    Sync-CapturePause
    if(!$compact -and $script:bankOpenAfterDock){$script:bankOpenAfterDock=$false;Show-BankSheet}
}
function Stop-DockMotion {
    $script:activeWidthClock=$null
    if($script:dockFallback){$script:dockFallback.Stop()}
    foreach($property in @([Windows.FrameworkElement]::WidthProperty,[Windows.FrameworkElement]::HeightProperty,
            [Windows.Controls.Canvas]::LeftProperty,[Windows.Controls.Canvas]::TopProperty)) {
        $value=$ui.Outer.GetValue($property)
        $ui.Outer.SetValue($property,$value)
        $ui.Outer.BeginAnimation($property,$null)
    }
    foreach($name in 'Outer','GlassShadow','GlassBackdrop','GlassSurface','GlassDepth','GlassRim') {
        $property=[Windows.Controls.Border]::CornerRadiusProperty
        $value=$ui[$name].GetValue($property)
        $ui[$name].SetValue($property,$value)
        $ui[$name].BeginAnimation($property,$null)
    }
    foreach($name in 'GlassSurface','GlassDepth','GlassRim','DockPeek','FullContent') {
        $value=$ui[$name].Opacity
        $ui[$name].Opacity=$value
        $ui[$name].BeginAnimation([Windows.UIElement]::OpacityProperty,$null)
    }
    $value=$script:backdropBrush.Viewport
    $script:backdropBrush.Viewport=$value
    $script:backdropBrush.BeginAnimation([Windows.Media.TileBrush]::ViewportProperty,$null)
}
function New-MorphDouble([double]$from,[double]$to,$duration,$ease) {
    $animation=[Windows.Media.Animation.DoubleAnimation]::new($from,$to,$duration)
    $animation.EasingFunction=$ease
    return $animation
}
function Finish-DockMotion {
    $move=$script:dockTransition
    if(!$move){return}
    Stop-DockMotion
    Set-GlassRect $move.Target
    Complete-Dock $move.Compact
    if(!$move.Compact -and !$ui.Outer.IsMouseOver -and !$script:bankSheetOpen){$script:dockLeave.Start()}
}
function Start-DockMotion($move) {
    $duration=[Windows.Duration]::new([TimeSpan]::FromMilliseconds(260))
    $ease=[Windows.Media.Animation.CubicEase]::new();$ease.EasingMode='EaseInOut'
    $sourceView=if($move.Compact){$ui.FullContent}else{$ui.DockPeek}
    $targetView=if($move.Compact){$ui.DockPeek}else{$ui.FullContent}
    $sourceView.Visibility='Visible'; $targetView.Visibility='Visible'
    $sourceView.BeginAnimation([Windows.UIElement]::OpacityProperty,
        (New-MorphDouble $sourceView.Opacity 0 ([Windows.Duration]::new([TimeSpan]::FromMilliseconds(180))) $ease))
    $fade=New-MorphDouble $targetView.Opacity 1 ([Windows.Duration]::new([TimeSpan]::FromMilliseconds(180))) $ease
    if($targetView.Opacity -lt 0.01){$fade.BeginTime=[TimeSpan]::FromMilliseconds(40)}
    $targetView.BeginAnimation([Windows.UIElement]::OpacityProperty,$fade)
    $endX=$move.Target.Left-$window.Left+5; $endY=$move.Target.Top-$window.Top+5
    $startX=[Windows.Controls.Canvas]::GetLeft($ui.Outer); $startY=[Windows.Controls.Canvas]::GetTop($ui.Outer)
    $widthClock=(New-MorphDouble $ui.Outer.Width ($move.Target.Width-10) $duration $ease).CreateClock()
    $script:activeWidthClock=$widthClock
    $widthClock.Add_Completed({
        param($sender,$args)
        if($script:dockTransition -and [object]::ReferenceEquals($sender,$script:activeWidthClock)){Finish-DockMotion}
    })
    $ui.Outer.ApplyAnimationClock([Windows.FrameworkElement]::WidthProperty,$widthClock)
    $ui.Outer.BeginAnimation([Windows.FrameworkElement]::HeightProperty,
        (New-MorphDouble $ui.Outer.Height ($move.Target.Height-10) $duration $ease))
    $ui.Outer.BeginAnimation([Windows.Controls.Canvas]::LeftProperty,(New-MorphDouble $startX $endX $duration $ease))
    $ui.Outer.BeginAnimation([Windows.Controls.Canvas]::TopProperty,(New-MorphDouble $startY $endY $duration $ease))
    $radius=if($move.Compact){if($script:dockSide -eq 'top'){27.0}else{35.0}}else{22.0}
    foreach($name in 'Outer','GlassShadow','GlassBackdrop','GlassSurface','GlassRim','GlassDepth') {
        $corner=[GlassCornerAnimation]::new()
        $corner.From=$ui[$name].CornerRadius
        $corner.To=[Windows.CornerRadius]::new($(if($name -eq 'GlassDepth'){$radius-1}else{$radius}))
        $corner.Duration=$duration
        $ui[$name].BeginAnimation([Windows.Controls.Border]::CornerRadiusProperty,$corner)
    }
    foreach($entry in @(@('GlassSurface',$(if($move.Compact){0.9}else{1.0})),
            @('GlassDepth',$(if($move.Compact){1.0}else{0.72})),@('GlassRim',$(if($move.Compact){1.0}else{0.86})))) {
        $element=$ui[$entry[0]]
        $element.BeginAnimation([Windows.UIElement]::OpacityProperty,(New-MorphDouble $element.Opacity $entry[1] $duration $ease))
    }
    if($script:glassScreen) {
        $start=$script:backdropBrush.Viewport
        $end=[Windows.Rect]::new($start.X-($endX-$startX),$start.Y-($endY-$startY),$start.Width,$start.Height)
        $motion=[Windows.Media.Animation.RectAnimation]::new($start,$end,$duration);$motion.EasingFunction=$ease
        $script:backdropBrush.BeginAnimation([Windows.Media.TileBrush]::ViewportProperty,$motion)
    }
    $script:dockFallback.Interval=[TimeSpan]::FromMilliseconds(340);$script:dockFallback.Start()
}
function Show-Dock([bool]$compact,[bool]$instant=$false) {
    if(!$script:dockSide -or $script:isDragging){return}
    if($script:dockTransition -and $script:dockTransition.Compact -eq $compact){return}
    if(!$instant -and !$script:dockTransition -and $script:dockCollapsed -eq $compact){return}
    $script:dockEnter.Stop();$script:dockLeave.Stop()
    $current=Get-GlassRect
    Stop-DockMotion
    $target=Get-DockRect $compact; $hostRect=Get-DockRect $false
    $script:dockTransition=@{Target=$target;Host=$hostRect;Compact=$compact}
    Sync-CapturePause
    $script:dockCollapsed=$compact
    $window.MinWidth=0;$window.MinHeight=0;$window.ResizeMode='NoResize'
    $ui.FullContent.Width=[Math]::Max(1.0,$hostRect.Width-46)
    $ui.FullContent.Height=[Math]::Max(1.0,$hostRect.Height-34)
    $ui.FullContent.HorizontalAlignment='Center';$ui.FullContent.VerticalAlignment='Center'
    # The native HWND stays at the expanded bounds in both states. Only its
    # glass surface morphs; transparent unused space passes pointer input through.
    Set-WindowRect $hostRect
    Set-GlassRect $current
    Update-GlassViewport -Force
    Update-DockPeekLayout
    if($instant -or ![Windows.SystemParameters]::ClientAreaAnimation){
        Set-GlassRect $target;Complete-Dock $compact;return
    }
    $targetView=if($compact){$ui.DockPeek}else{$ui.FullContent}
    if($targetView.Visibility -ne 'Visible'){$targetView.Opacity=0}
    Start-DockMotion $script:dockTransition
}

function Find-DockSide {
    $area = Get-WorkArea
    $rect=Get-GlassRect
    $distance = @{
        left=[Math]::Abs($rect.Left - $area.Left)
        right=[Math]::Abs($area.Right - ($rect.Left + $rect.Width))
        top=[Math]::Abs($rect.Top - $area.Top)
    }
    $nearest = $distance.GetEnumerator() | Sort-Object Value | Select-Object -First 1
    if ($nearest.Value -le 36) { return $nearest.Key }
    return $null
}
function Snap-After-Drag {
    if (!$script:dockAllowed -or $script:dockTransition) { return }
    $side = Find-DockSide
    if ($side) {
        $script:normalWidth = [Math]::Max(260, $window.Width)
        $script:normalHeight = [Math]::Max(184, $window.Height)
        $script:dockSide = $side
        $script:dockCenterX = $window.Left + $window.Width / 2
        $script:dockCenterY = $window.Top + $window.Height / 2
        Show-Dock $false $true
        $script:dockLeave.Start()
    } else {
        $script:dockSide = $null
        $script:dockCollapsed = $false
        $script:dockTransition = $null
        Complete-Dock $false
        $script:normalWidth = $window.Width; $script:normalHeight = $window.Height
    }
}
$script:bankAvailable=$null
$script:bankCredits=@()
$script:bankPending=$null
$script:bankSheetOpen=$false
$script:bankOpenAfterDock=$false
$script:bankSuccess=$false
$script:bankAnimationActive=$false
$script:bankProgressEnd=$null
$script:bankRestoreAwaiting=$false
$script:refillBindings=@()
$script:bankMessageOverride=$null
$script:bankAttemptKey=$null
$script:bankAttemptCredit=$null
$script:bankAttemptPath=Join-Path $PSScriptRoot 'banked-attempt.json'
if (!$Verify -and !$Preview -and (Test-Path -LiteralPath $script:bankAttemptPath)) {
    try {
        $attempt=Get-Content -LiteralPath $script:bankAttemptPath -Raw | ConvertFrom-Json
        $parsedKey=[Guid]::Empty
        if ([Guid]::TryParse([string]$attempt.key,[ref]$parsedKey)) {
            $script:bankAttemptKey=[string]$attempt.key
            $script:bankAttemptCredit=[string]$attempt.creditId
        }
    } catch {}
}
function Update-BankUI {
    $known=$null -ne $script:bankAvailable
    $ui.BankCaption.Text=if($known){"储备 $script:bankAvailable"}else{'储备 —'}
    $ui.BankCount.Text=if($known){"$script:bankAvailable 次"}else{'— 次'}
    $ui.Bank.SetResourceReference([Windows.Controls.Control]::ForegroundProperty,$(if($known -and $script:bankAvailable -gt 0){'AccentBrush'}else{'MutedBrush'}))
    $ui.BankConfirm.IsEnabled=(!$script:bankPending -and !$script:pending -and !$script:bankSuccess -and (($known -and $script:bankAvailable -gt 0) -or $script:bankAttemptKey))
    $ui.BankConfirm.Content=if($script:bankSuccess){'已完成'}elseif($script:bankAttemptKey){'重试上次'}else{'使用 1 次'}
    $ui.BankCancel.IsEnabled=(!$script:bankPending -and !$script:bankAnimationActive)
    $ui.BankCancel.Content=if($script:bankSuccess){'完成'}else{'返回'}
    $description=if($script:bankMessageOverride){$script:bankMessageOverride}
        elseif($script:bankAttemptKey){'上次请求结果尚未确定。重试会复用原请求标识，避免重复扣除。'}
        elseif(!$known){'当前未返回储备次数，请先刷新。旧版 Codex 可能需要更新。'}
        elseif($script:bankAvailable -le 0){'当前账户暂无可用的储备重置。'}
        else{'消耗 1 次储备，重置符合条件的额度窗口。'}
    $credit=@($script:bankCredits | Where-Object {$_.status -eq 'available'} | Sort-Object @{Expression={if($_.expiresAt){[long]$_.expiresAt}else{[long]::MaxValue}}} | Select-Object -First 1)
    if(!$script:bankMessageOverride -and !$script:bankAttemptKey -and $credit.Count -and $credit[0].expiresAt){
        $description+="`n最早到期 $([DateTimeOffset]::FromUnixTimeSeconds([long]$credit[0].expiresAt).LocalDateTime.ToString('MM/dd'))"
    }
    $ui.BankMessage.Text=$description
    $ui.Bank.ToolTip=if($known){"Banked reset · $script:bankAvailable 次可用"}else{'Banked reset · 当前未返回数据'}
}
function Update-BankSnapshot($summary) {
    $script:bankAvailable=if($null -ne $summary -and $null -ne $summary.availableCount){[Math]::Max(0,[int]$summary.availableCount)}else{$null}
    $script:bankCredits=if($summary -and $summary.credits){@($summary.credits)}else{@()}
    Update-BankUI
}
function Show-BankSheet {
    if($script:bankAnimationActive){return}
    if($script:dockCollapsed -or $script:dockTransition){$script:bankOpenAfterDock=$true;Show-Dock $false;return}
    $script:dockEnter.Stop();$script:dockLeave.Stop()
    $script:bankSheetOpen=$true
    $ui.Metrics.Visibility='Hidden';$ui.Footer.Visibility='Hidden'
    Update-BankUI
    $ui.BankSheet.Visibility='Visible'
    $ease=[Windows.Media.Animation.CubicEase]::new();$ease.EasingMode='EaseOut'
    if([Windows.SystemParameters]::ClientAreaAnimation -and !$Verify){
        $duration=[Windows.Duration]::new([TimeSpan]::FromMilliseconds(150))
        $ui.BankSheet.BeginAnimation([Windows.UIElement]::OpacityProperty,(New-MorphDouble 0 1 $duration $ease))
        $ui.BankSheet.RenderTransform.BeginAnimation([Windows.Media.TranslateTransform]::YProperty,(New-MorphDouble 5 0 $duration $ease))
    } else {$ui.BankSheet.Opacity=1}
}
function Close-BankSheet {
    if($script:bankPending -or $script:bankAnimationActive){return}
    $script:bankSheetOpen=$false;$script:bankSuccess=$false;$script:bankMessageOverride=$null
    $ui.Metrics.Visibility='Visible';$ui.Footer.Visibility='Visible'
    $ui.BankSheet.BeginAnimation([Windows.UIElement]::OpacityProperty,$null)
    $ui.BankSheet.Visibility='Collapsed'
    Clear-QuotaRestore
    Update-BankUI
    if($script:dockSide -and !$ui.Outer.IsMouseOver){$script:dockLeave.Start()}
}
function Set-BankBusy([bool]$busy) {
    $spin=$ui.BankGlyph.RenderTransform
    $spin.BeginAnimation([Windows.Media.RotateTransform]::AngleProperty,$null)
    $spin.Angle=0
    Update-BankUI
}
function Clear-BankAttempt {
    $script:bankAttemptKey=$null;$script:bankAttemptCredit=$null
    if(!$Verify -and !$Preview -and (Test-Path -LiteralPath $script:bankAttemptPath)){
        Remove-Item -LiteralPath $script:bankAttemptPath -Force
    }
}
function Clear-QuotaRestore {
    if($script:bankProgressEnd){$script:bankProgressEnd.Stop()}
    $script:bankAnimationActive=$false
    foreach($binding in $script:refillBindings){$binding.Dispose()};$script:refillBindings=@()
    foreach($suffix in 'A','B'){$ui["Bar$suffix"].Tag=$null}
}
function Show-ResetCompletion {
    $script:bankRestoreAwaiting=$false;$script:bankSheetOpen=$false
    $ui.BankSheet.Visibility='Collapsed';$ui.Metrics.Visibility='Visible';$ui.Footer.Visibility='Visible'
    if($script:resetSoundEnabled -and !$Verify -and !$Preview){$script:resetSounds.Success()}
    if(![Windows.SystemParameters]::ClientAreaAnimation -or ($Verify -and !$VerifyResetAnimation) -or $Preview){$script:bankSuccess=$false;return}
    $script:bankAnimationActive=$true;$ui.Refresh.IsEnabled=$false;$ui.Status.Text='额度正在恢复…'
    $point=$ui.Metrics.TranslatePoint([Windows.Point]::new(($ui.Metrics.ActualWidth/2),($ui.Metrics.ActualHeight/2)),$script:celebration)
    $script:celebration.Play($point.X,$point.Y,$window.Resources['AccentBrush'].Color,$script:resetConfettiEnabled)
    $pulse=[Windows.Media.Animation.DoubleAnimationUsingKeyFrames]::new()
    [void]$pulse.KeyFrames.Add([Windows.Media.Animation.EasingDoubleKeyFrame]::new(1,[Windows.Media.Animation.KeyTime]::FromTimeSpan([TimeSpan]::Zero)))
    [void]$pulse.KeyFrames.Add([Windows.Media.Animation.EasingDoubleKeyFrame]::new(1.08,[Windows.Media.Animation.KeyTime]::FromTimeSpan([TimeSpan]::FromMilliseconds(100))))
    [void]$pulse.KeyFrames.Add([Windows.Media.Animation.EasingDoubleKeyFrame]::new(1,[Windows.Media.Animation.KeyTime]::FromTimeSpan([TimeSpan]::FromMilliseconds(320))))
    $ui.Bank.RenderTransform.BeginAnimation([Windows.Media.ScaleTransform]::ScaleXProperty,$pulse)
    $ui.Bank.RenderTransform.BeginAnimation([Windows.Media.ScaleTransform]::ScaleYProperty,$pulse)
    $script:bankProgressEnd=[Windows.Threading.DispatcherTimer]::new()
    $script:bankProgressEnd.Interval=[TimeSpan]::FromMilliseconds(1280)
    $script:bankProgressEnd.Add_Tick({
        $script:bankProgressEnd.Stop();$script:bankAnimationActive=$false
        foreach($suffix in 'A','B'){$ui["Bar$suffix"].BeginAnimation([Windows.Controls.Primitives.RangeBase]::ValueProperty,$null);$ui["Bar$suffix"].Tag=$null}
        foreach($binding in $script:refillBindings){$binding.Dispose()};$script:refillBindings=@()
        $script:bankSuccess=$false;$ui.Status.Text='额度已恢复';$ui.Refresh.IsEnabled=!$script:pending;Update-BankUI
        if($script:dockSide -and !$ui.Outer.IsMouseOver){$script:dockLeave.Start()}
    })
    $script:bankProgressEnd.Start();Update-BankUI
}
function Start-BankUse {
    if($script:bankPending -or $script:pending -or $script:bankSuccess){return}
    if(!$script:bankAttemptKey -and ($null -eq $script:bankAvailable -or $script:bankAvailable -le 0)){return}
    try {
        Clear-QuotaRestore
        if($script:resetSoundEnabled -and !$Verify -and !$Preview){$script:resetSounds.Click()}
        if(!$script:bankAttemptKey){
            $script:bankAttemptKey=[Guid]::NewGuid().ToString()
            $credit=@($script:bankCredits | Where-Object {$_.status -eq 'available'} | Sort-Object @{Expression={if($_.expiresAt){[long]$_.expiresAt}else{[long]::MaxValue}}} | Select-Object -First 1)
            $script:bankAttemptCredit=if($credit.Count){[string]$credit[0].id}else{$null}
        }
        if(!$Verify -and !$Preview){
            @{key=$script:bankAttemptKey;creditId=$script:bankAttemptCredit} | ConvertTo-Json | Set-Content -LiteralPath $script:bankAttemptPath -Encoding UTF8
        }
        $script:bankPending=$client.ConsumeResetAsync($script:bankAttemptKey,$script:bankAttemptCredit)
        $script:bankMessageOverride='正在确认储备重置…'
        $ui.Refresh.IsEnabled=$false
        Set-BankBusy $true
    } catch {
        $script:bankMessageOverride='暂时无法确认结果，请重试同一次请求。'
        Clear-QuotaRestore
        $ui.BankMessage.ToolTip=$_.Exception.GetBaseException().Message
        Update-BankUI
    }
}
function Complete-BankUse {
    if(!$script:bankPending -or !$script:bankPending.IsCompleted){return}
    try {
        $result=$script:bankPending.GetAwaiter().GetResult() | ConvertFrom-Json
        $script:bankPending=$null
        switch([string]$result.outcome){
            'reset' {$script:bankSuccess=$true;$script:bankRestoreAwaiting=$true;$script:bankMessageOverride='已确认重置，正在读取最新额度。';Clear-BankAttempt}
            'alreadyRedeemed' {$script:bankSuccess=$true;$script:bankRestoreAwaiting=$true;$script:bankMessageOverride='上次重置已完成，正在读取最新额度。';Clear-BankAttempt}
            'nothingToReset' {$script:bankMessageOverride='当前没有符合重置条件的额度窗口。';Clear-BankAttempt;Clear-QuotaRestore}
            'noCredit' {$script:bankMessageOverride='当前账户已无可用储备，正在更新次数。';Clear-BankAttempt;Clear-QuotaRestore}
            default {throw '服务器返回了未知的兑换结果'}
        }
        Start-Refresh
    } catch {
        $script:bankPending=$null
        Clear-QuotaRestore
        $errorDetail=$_.Exception.GetBaseException()
        $script:bankMessageOverride=if($errorDetail -is [UsageProtocolException] -and $errorDetail.Code -eq -32601){'当前 Codex 版本不支持储备重置，请更新 Codex。'}else{'结果尚未确认。点击重试将复用同一次请求，不会重复扣除。'}
        $ui.BankMessage.ToolTip=$errorDetail.Message
    } finally {
        $script:bankPending=$null
        Set-BankBusy $false
        $ui.Refresh.IsEnabled=!$script:pending
    }
}

function Start-Refresh {
    if ($script:pending -or $script:bankPending -or $script:bankAnimationActive) { return }
    $ui.Status.Text = '正在更新…'; $ui.Refresh.IsEnabled = $false
    $script:pending = $client.FetchAsync()
    Update-BankUI
}
function Update-Window($data, $suffix) {
    $value = $ui["Value$suffix"]; $bar = $ui["Bar$suffix"]
    if (!$data -or $null -eq $data.usedPercent) {
        $value.Text = '—'; $ui["DetailValue$suffix"].Text = '—'
        $ui["Peek$suffix"].Text = '—'
        $ui["Peek$suffix"].ToolTip = '暂无额度数据'
        $bar.BeginAnimation([Windows.Controls.Primitives.RangeBase]::ValueProperty, $null); $bar.Value = 0
        $ui["Reset$suffix"].Text = ''; $ui["DetailReset$suffix"].Text = '暂无额度数据'; $ui["Metric$suffix"].ToolTip = '暂无额度数据'
        Set-Ring $suffix 0; return
    }
    $remain = [Math]::Max(0.0, [Math]::Min(100.0, 100.0 - [double]$data.usedPercent))
    $value.Text = "$($remain.ToString('0.#'))%"
    $ui["DetailValue$suffix"].Text = $value.Text
    $ui["Peek$suffix"].Text = $value.Text
    $from = $bar.Value
    $bar.BeginAnimation([Windows.Controls.Primitives.RangeBase]::ValueProperty, $null)
    $bar.Value = $remain
    if ([Windows.SystemParameters]::ClientAreaAnimation -and (!$Verify -or ($VerifyResetAnimation -and $script:bankRestoreAwaiting)) -and !$Preview) {
        if($script:bankRestoreAwaiting){
            $animation=[Windows.Media.Animation.DoubleAnimationUsingKeyFrames]::new()
            [void]$animation.KeyFrames.Add([Windows.Media.Animation.LinearDoubleKeyFrame]::new($from,[Windows.Media.Animation.KeyTime]::FromTimeSpan([TimeSpan]::Zero)))
            $spline=[Windows.Media.Animation.KeySpline]::new(.20,.65,.30,1)
            [void]$animation.KeyFrames.Add([Windows.Media.Animation.SplineDoubleKeyFrame]::new($remain,[Windows.Media.Animation.KeyTime]::FromTimeSpan([TimeSpan]::FromMilliseconds(1200)),$spline))
        }else{
            $animation = New-Object Windows.Media.Animation.DoubleAnimation($from,$remain,[Windows.Duration]::new([TimeSpan]::FromMilliseconds(480)))
            $animation.EasingFunction = New-Object Windows.Media.Animation.CubicEase;$animation.EasingFunction.EasingMode='EaseOut'
        }
        $bar.BeginAnimation([Windows.Controls.Primitives.RangeBase]::ValueProperty, $animation)
        if($script:bankRestoreAwaiting){$bar.Tag='restoring';$script:refillBindings += [QuotaRefillText]::new($bar,$value,$ui["DetailValue$suffix"],$ui["Peek$suffix"],$ui["Arc$suffix"],$ui["Ring$suffix"].Width/2)}
    }
    $colorKey = if ($remain -le 20) { 'WarningBrush' } else { 'AccentBrush' }
    $bar.SetResourceReference([Windows.Controls.Control]::ForegroundProperty, $colorKey)
    $ui["Arc$suffix"].SetResourceReference([Windows.Shapes.Shape]::StrokeProperty, $colorKey)
    if ($data.windowDurationMins -eq 10080) { $label = '每周' }
    elseif ($data.windowDurationMins) { $label = "$([Math]::Round($data.windowDurationMins / 60, 1)) 小时" } else { $label = '用量' }
    $ui["PeekLabel$suffix"].Text = $label
    $ui["Label$suffix"].Text = $label; $ui["DetailLabel$suffix"].Text = $label
    $reset = if ($data.resetsAt) { [DateTimeOffset]::FromUnixTimeSeconds([long]$data.resetsAt).LocalDateTime.ToString('MM/dd HH:mm') } else { '重置时间暂不可用' }
    $ui["Reset$suffix"].Text = "重置 $reset"; $ui["DetailReset$suffix"].Text = "重置 $reset"
    $ui["Metric$suffix"].ToolTip = "重置时间：$reset"
    $ui["Peek$suffix"].ToolTip = "$label 剩余 $($value.Text) · 重置 $reset"
    Set-Ring $suffix $(if($script:bankRestoreAwaiting){$bar.Value}else{$remain})
}
function Complete-Refresh {
    if (!$script:pending -or !$script:pending.IsCompleted) { return }
    try {
        $result = $script:pending.GetAwaiter().GetResult() | ConvertFrom-Json
        $limits = if ($result.rateLimitsByLimitId.codex) { $result.rateLimitsByLimitId.codex } else { $result.rateLimits }
        if (!$limits) { throw '当前账号无可读取的配额' }
        Update-Window $limits.primary 'A'; Update-Window $limits.secondary 'B'
        $ui.Plan.Text = "$($limits.planType)".ToUpperInvariant()
        Update-BankSnapshot $result.rateLimitResetCredits
        $restoring=$script:bankRestoreAwaiting
        $script:lastUpdate = Get-Date
        $ui.Status.Text = "已更新 $($lastUpdate.ToString('HH:mm'))"
        $ui.Status.ToolTip = $null; $ui.StatusDot.SetResourceReference([Windows.Shapes.Shape]::FillProperty, 'AccentBrush')
        if($restoring){Show-ResetCompletion}
    } catch {
        $resetConfirmed=$script:bankSuccess
        if($resetConfirmed){$script:bankRestoreAwaiting=$false;$script:bankSuccess=$false;Close-BankSheet}
        $ui.Status.Text = if($resetConfirmed){'已重置 · 额度待同步'}elseif ($lastUpdate) { "更新失败 · 数据来自 $($lastUpdate.ToString('HH:mm'))" } else { '暂时无法连接' }
        $ui.StatusDot.SetResourceReference([Windows.Shapes.Shape]::FillProperty, 'WarningBrush')
        $ui.Status.ToolTip = $_.Exception.GetBaseException().Message
    } finally { $script:pending = $null; $ui.Refresh.IsEnabled = !$script:bankPending -and !$script:bankAnimationActive;Update-BankUI }
}
$window.Add_PreviewMouseLeftButtonDown({
    if ($script:dockCollapsed -or $script:dockTransition -or $window.ResizeMode -eq 'NoResize') { return }
    $point=$_.GetPosition($window); $edge=0; $grip=7
    $left=$point.X -le $grip; $right=$point.X -ge $window.ActualWidth-$grip
    $top=$point.Y -le $grip; $bottom=$point.Y -ge $window.ActualHeight-$grip
    if ($top -and $left) {$edge=13} elseif ($top -and $right) {$edge=14}
    elseif ($bottom -and $left) {$edge=16} elseif ($bottom -and $right) {$edge=17}
    elseif ($left) {$edge=10} elseif ($right) {$edge=11} elseif ($top) {$edge=12} elseif ($bottom) {$edge=15}
    if ($edge) {
        $_.Handled=$true;$script:isResizing=$true;Sync-CapturePause
        try { [WidgetWindow]::Resize($script:glassHandle,$edge) }
        finally {$script:isResizing=$false;$script:glassNextCapture=[DateTime]::MinValue;Sync-CapturePause}
    }
})
$ui.Outer.Add_MouseLeftButtonDown({
    $source = $_.OriginalSource
    while ($source -and $source -ne $ui.Outer) {
        if ($source -is [Windows.Controls.Primitives.ButtonBase]) { return }
        $source = [Windows.Media.VisualTreeHelper]::GetParent($source)
    }
    if ($script:dockTransition) { return }
    if ($_.ClickCount -eq 2 -and $script:dockSide) { Show-Dock $false; $_.Handled=$true; return }
    if ([Windows.Input.Mouse]::LeftButton -eq 'Pressed') {
        $script:dockEnter.Stop(); $script:dockLeave.Stop()
        $wasCompact=$script:dockCollapsed; $oldLeft=$window.Left; $oldTop=$window.Top
        try { $script:isDragging=$true; Sync-CapturePause; $window.DragMove(); $_.Handled=$true } catch { }
        finally {
            $script:isDragging=$false; $script:glassNextCapture=[DateTime]::MinValue
            Sync-CapturePause
        }
        if ($wasCompact) {
            if ([Math]::Abs($oldLeft-$window.Left)+[Math]::Abs($oldTop-$window.Top) -lt 4) { Show-Dock $false; return }
            $visual=Get-GlassRect
            $script:dockCenterX=$visual.Left+$visual.Width/2; $script:dockCenterY=$visual.Top+$visual.Height/2
            $side=Find-DockSide
            if ($side) { $script:dockSide=$side; Show-Dock $true $true }
            else {
                $area=Get-WorkArea
                $rect=@{ Width=$script:normalWidth; Height=$script:normalHeight
                    Left=[Math]::Max($area.Left+12,[Math]::Min($script:dockCenterX-$script:normalWidth/2,$area.Right-$script:normalWidth-12))
                    Top=[Math]::Max($area.Top+12,[Math]::Min($script:dockCenterY-$script:normalHeight/2,$area.Bottom-$script:normalHeight-12)) }
                $script:dockSide=$null
                $ui.DockPeek.Visibility='Collapsed'
                Set-WindowRect $rect; Complete-Dock $false
            }
        } else { Snap-After-Drag }
    }
})
$ui.Outer.Add_MouseEnter({
    if (!$script:appReady -or $script:isDragging) { return }
    $script:dockLeave.Stop()
    if ($script:dockSide -and $script:dockCollapsed) { $script:dockEnter.Start() }
})
$ui.Outer.Add_MouseLeave({
    $script:dockEnter.Stop()
    if ($script:appReady -and $script:dockSide -and !$script:isDragging) { $script:dockLeave.Stop(); $script:dockLeave.Start() }
})
$ui.Pin.Add_Click({ $window.Topmost = !$window.Topmost; Set-Theme })
$ui.Refresh.Add_Click({ Start-Refresh })
$ui.Bank.Add_Click({ Show-BankSheet })
$ui.BankCancel.Add_Click({ Close-BankSheet })
$ui.BankConfirm.Add_Click({ Start-BankUse })
$menu = New-Object System.Windows.Controls.ContextMenu
try { $menu.Resources.MergedDictionaries.Add($window.Resources) } catch { }
function Add-Menu($label, [scriptblock]$action, $glyph) {
    $item = New-Object System.Windows.Controls.MenuItem; $item.Header = $label; $item.Add_Click($action)
    if ($glyph) { $icon = New-Object System.Windows.Controls.TextBlock; $icon.Text = [System.Web.HttpUtility]::HtmlDecode([string]$glyph); $icon.FontFamily = 'Segoe Fluent Icons, Segoe MDL2 Assets'; $icon.FontSize = 13; $item.Icon = $icon }
    [void]$menu.Items.Add($item); return $item
}
$refreshItem = Add-Menu '刷新' { Start-Refresh } '&#xE72C;'
$refreshItem.InputGestureText = 'F5'
$bankMenu=Add-Menu '储备重置' {Show-BankSheet} '&#xE777;'
$bankMenu.InputGestureText='F9'
function Add-Separator { $separator = New-Object Windows.Controls.Separator; $separator.Style = $window.Resources.get_Item([Windows.Controls.Separator]); [void]$menu.Items.Add($separator) }
Add-Separator
$themeLight = Add-Menu '浅色' { $script:theme = 'light'; Set-Theme; Sync-ThemeMenu } '&#xE793;'
$themeDark = Add-Menu '深色' { $script:theme = 'dark'; Set-Theme; Sync-ThemeMenu } '&#xE708;'
$themeAuto = Add-Menu '跟随系统' { $script:theme = 'auto'; Set-Theme; Sync-ThemeMenu } '&#xE706;'
foreach ($item in $themeLight,$themeDark,$themeAuto) { $item.IsCheckable = $true }
function Sync-ThemeMenu { $themeLight.IsChecked = $script:theme -eq 'light'; $themeDark.IsChecked = $script:theme -eq 'dark'; $themeAuto.IsChecked = $script:theme -eq 'auto' }
Add-Separator
$soundItem=Add-Menu '重置音效' { $script:resetSoundEnabled=[bool]$soundItem.IsChecked;if(!$script:resetSoundEnabled){$script:resetSounds.Stop()} } '&#xE767;'
$soundItem.IsCheckable=$true;$soundItem.IsChecked=$script:resetSoundEnabled
$confettiItem=Add-Menu '彩纸礼炮' { $script:resetConfettiEnabled=[bool]$confettiItem.IsChecked } '&#xE734;'
$confettiItem.IsCheckable=$true;$confettiItem.IsChecked=$script:resetConfettiEnabled
Add-Separator
$null = Add-Menu '恢复默认大小' {
    $script:normalWidth = 300; $script:normalHeight = 208
    if ($script:dockSide) { Show-Dock $false } else { $window.Width = 300; $window.Height = 208; Update-Layout }
} '&#xE74A;'
$null = Add-Menu '最小化' { $window.WindowState = 'Minimized' } '&#xE921;'
Add-Separator
$null = Add-Menu '关闭用量栏' { $window.Close() } '&#xE7E8;'
$ui.Outer.ContextMenu = $menu
$menu.Add_Closed({
    if ($script:dockSide -and !$ui.Outer.IsMouseOver -and !$script:dockCollapsed) { $script:dockLeave.Stop(); $script:dockLeave.Start() }
})
$ui.Outer.Add_ContextMenuOpening({ $menu.Placement = 'MousePoint'; $menu.HorizontalOffset = 0; $menu.VerticalOffset = 0 })
$ui.More.Add_Click({ $menu.PlacementTarget = $ui.More; $menu.Placement = 'Bottom'; $menu.HorizontalOffset = 0; $menu.VerticalOffset = -3; $menu.IsOpen = $true })
$window.Add_SizeChanged({
    Sync-OuterBounds
    Update-GlassShape
    Update-GlassViewport
    if ($script:dockSide -and !$script:dockTransition -and !$script:dockCollapsed -and $ui.FullContent.Visibility -eq 'Visible') {
        $script:normalWidth = $window.Width; $script:normalHeight = $window.Height
        $script:dockCenterX=$window.Left+$window.Width/2;$script:dockCenterY=$window.Top+$window.Height/2
    }
    Update-Layout
})
$window.Add_LocationChanged({ Update-GlassViewport })
$window.Add_KeyDown({ if ($_.Key -eq 'F5') { Start-Refresh };if($_.Key -eq 'F9'){Show-BankSheet;$_.Handled=$true};if($_.Key -eq 'Escape' -and $script:bankSheetOpen){Close-BankSheet;$_.Handled=$true} })
$poll = New-Object System.Windows.Threading.DispatcherTimer
$poll.Interval = [TimeSpan]::FromMilliseconds(80); $poll.Add_Tick({ Complete-BankUse;Complete-Refresh })
$script:glassTimer = New-Object System.Windows.Threading.DispatcherTimer
$glassTimer.Interval = [TimeSpan]::FromMilliseconds(16); $glassTimer.Add_Tick({ Update-GlassFrameNow })
$refreshTimer = New-Object System.Windows.Threading.DispatcherTimer
    $refreshTimer.Interval = [TimeSpan]::FromSeconds(60); $refreshTimer.Add_Tick({ if ($script:theme -eq 'auto') { Set-Theme }; Start-Refresh })
$script:dockFallback=New-Object System.Windows.Threading.DispatcherTimer
$dockFallback.Add_Tick({$script:dockFallback.Stop();if($script:dockTransition){Finish-DockMotion}})
$script:dockEnter = New-Object System.Windows.Threading.DispatcherTimer
$dockEnter.Interval = [TimeSpan]::FromMilliseconds(1)
$dockEnter.Add_Tick({
    $script:dockEnter.Stop()
    if ($script:dockSide -and $ui.Outer.IsMouseOver -and !$script:isDragging) { Show-Dock $false }
})
$script:dockLeave = New-Object System.Windows.Threading.DispatcherTimer
$dockLeave.Interval = [TimeSpan]::FromMilliseconds(850)
$dockLeave.Add_Tick({
    $script:dockLeave.Stop()
    if ($script:dockSide -and !$ui.Outer.IsMouseOver -and !$menu.IsOpen -and !$script:isDragging -and !$script:dockCollapsed -and !$script:bankSheetOpen -and !$script:bankAnimationActive) { Show-Dock $true }
})
$window.Add_Closed({
    $poll.Stop(); $glassTimer.Stop(); $refreshTimer.Stop(); Stop-DockMotion; $dockEnter.Stop(); $dockLeave.Stop(); $client.Dispose()
    Clear-QuotaRestore;$script:celebration.Stop();$script:resetSounds.Stop();$script:resetSounds.Dispose()
    if (!$Verify -and !$Preview) {
        $width = if ($script:dockSide) { $script:normalWidth } else { $window.Width }
        $height = if ($script:dockSide) { $script:normalHeight } else { $window.Height }
        @{ width=$width; height=$height; left=$window.Left; top=$window.Top; pin=$window.Topmost; dark=$script:dark; theme=$script:theme; dockSide=$script:dockSide; dockCenterX=$script:dockCenterX; dockCenterY=$script:dockCenterY; resetSound=$script:resetSoundEnabled; resetConfetti=$script:resetConfettiEnabled } | ConvertTo-Json | Set-Content -LiteralPath $settingsPath -Encoding UTF8
    }
})
Set-Theme
Update-BankUI
Sync-ThemeMenu
Update-Layout
if ($Verify -or $Preview) {
    if ($VerifyLive) {
        Start-Refresh
        try { [void]$script:pending.Wait(45000) } catch { }
        Complete-Refresh
        if (!$lastUpdate) { throw $ui.Status.ToolTip }
    } else {
        Update-Window ([pscustomobject]@{usedPercent=28.6;windowDurationMins=300;resetsAt=1790000000}) 'A'
        Update-Window ([pscustomobject]@{usedPercent=85.2;windowDurationMins=10080;resetsAt=1790100000}) 'B'
        $ui.Plan.Text = 'PLUS'; $ui.Status.Text = '布局验证'; $ui.StatusDot.Fill = '#38836C'
    }
    if ($Preview) { $window.Title = 'Codex 余量 · 外观预览'; $window.Width=300; $window.Height=208; $window.Add_Loaded({ $poll.Start() }); [void]$window.ShowDialog(); return }
    if (!$PreviewDirectory) { $PreviewDirectory = Join-Path $PSScriptRoot 'previews' }
    [void](New-Item -ItemType Directory -Path $PreviewDirectory -Force)
    $window.Show()
    if ($script:glassResult -ne 0 -or [WidgetWindow]::Backdrop($script:glassHandle) -ne 1) { throw 'Transparent surface initialization failed' }
    function Save-Preview($visual, $fileName) {
        $bitmap = New-Object Windows.Media.Imaging.RenderTargetBitmap([int][Math]::Ceiling($visual.ActualWidth),[int][Math]::Ceiling($visual.ActualHeight),96,96,[Windows.Media.PixelFormats]::Pbgra32)
        $bitmap.Render($visual)
        $encoder = New-Object Windows.Media.Imaging.PngBitmapEncoder; $encoder.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($bitmap))
        $stream = [IO.File]::Create((Join-Path $PreviewDirectory $fileName)); try { $encoder.Save($stream) } finally { $stream.Dispose() }
    }
    function Pump-VerifyUI([int]$milliseconds) {
        $script:verifyFrame=[Windows.Threading.DispatcherFrame]::new()
        $timer=[Windows.Threading.DispatcherTimer]::new()
        $timer.Interval=[TimeSpan]::FromMilliseconds($milliseconds)
        $timer.Add_Tick({$script:verifyFrame.Continue=$false})
        $timer.Start()
        try {[Windows.Threading.Dispatcher]::PushFrame($script:verifyFrame)}finally{$timer.Stop()}
    }
    if($VerifyMotion) {
        $script:normalWidth=300;$script:normalHeight=208
        $script:motionSamples=[Collections.Generic.List[object]]::new()
        $render=[EventHandler]{
            $hostBounds=[WidgetWindow]::Bounds($script:glassHandle)
            $script:motionSamples.Add([pscustomobject]@{
                width=[double]$ui.Outer.Width;height=[double]$ui.Outer.Height
                x=[Windows.Controls.Canvas]::GetLeft($ui.Outer);y=[Windows.Controls.Canvas]::GetTop($ui.Outer)
                full=$(if($ui.FullContent.Visibility -eq 'Visible'){$ui.FullContent.Opacity}else{0.0})
                peek=$(if($ui.DockPeek.Visibility -eq 'Visible'){$ui.DockPeek.Opacity}else{0.0})
                host=($hostBounds -join ',');active=[bool]$script:dockTransition
            })
        }
        $report=[Collections.Generic.List[object]]::new()
        foreach($side in 'left','right','top') {
            $script:dockSide=$side;$script:dockCenterX=$window.Left+150;$script:dockCenterY=$window.Top+104
            Show-Dock $true $true;Pump-VerifyUI 40
            $hostKey=([WidgetWindow]::Bounds($script:glassHandle) -join ',')
            foreach($compact in $false,$true) {
                $script:motionSamples.Clear()
                [Windows.Media.CompositionTarget]::add_Rendering($render)
                try {Show-Dock $compact;Pump-VerifyUI 410}finally{[Windows.Media.CompositionTarget]::remove_Rendering($render)}
                if($script:dockTransition){throw "Motion did not settle: $side $compact"}
                if($script:motionSamples.Count -lt 6){throw 'Too few rendered animation frames'}
                $previous=$null;$minimumOpacity=2.0
                foreach($sample in $script:motionSamples) {
                    if($sample.host -ne $hostKey){throw "Native host moved during morph: $side"}
                    if($sample.full+$sample.peek -lt 0.2){throw "Blank content frame: $side"}
                    $minimumOpacity=[Math]::Min($minimumOpacity,$sample.full+$sample.peek)
                    if($previous) {
                        if(!$compact -and $sample.width -lt $previous.width-0.15){throw "Expansion reversed: $side"}
                        if($compact -and $sample.width -gt $previous.width+0.15){throw "Collapse reversed: $side"}
                    }
                    $previous=$sample
                }
                $target=Get-DockRect $compact
                if([Math]::Abs($ui.Outer.Width-($target.Width-10)) -gt 0.15){throw 'Final geometry snapped'}
                $report.Add([pscustomobject]@{side=$side;compact=$compact;frames=$script:motionSamples.Count;minimumContentOpacity=$minimumOpacity;nativeHostStable=$true})
            }
            Show-Dock $false;Pump-VerifyUI 90
            $before=$ui.Outer.Width;Show-Dock $true
            if([Math]::Abs($ui.Outer.Width-$before) -gt 0.15){throw "Interrupted motion snapped: $side from=$before to=$($ui.Outer.Width)"}
            Pump-VerifyUI 410
            $bounds=[WidgetWindow]::Bounds($script:glassHandle)
            $matrix=[Windows.Interop.HwndSource]::FromHwnd($script:glassHandle).CompositionTarget.TransformToDevice
            $hiddenPoint=if($side -eq 'right'){@(10,10)}elseif($side -eq 'left'){@(280,10)}else{@(10,180)}
            $nativePoint=$matrix.Transform([Windows.Point]::new($hiddenPoint[0],$hiddenPoint[1]))
            if([WidgetWindow]::HitTest($script:glassHandle,($bounds[0]+[int]$nativePoint.X),($bounds[1]+[int]$nativePoint.Y)) -ne -1){throw "Transparent host blocked input: $side"}
        }
        $report | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $PreviewDirectory 'motion-report.json') -Encoding UTF8
        Show-Dock $false $true;$script:dockSide=$null;$script:dockCollapsed=$false
        $script:isResizing=$true;Sync-CapturePause
        try {
            foreach($size in @(@(300,208),@(540,300),@(300,470),@(501,208),@(260,184),@(700,400),@(300,208))) {
                Set-WindowRect @{Left=$window.Left;Top=$window.Top;Width=$size[0];Height=$size[1]}
                Pump-VerifyUI 25
                if([Math]::Abs($ui.Outer.ActualWidth-($window.ActualWidth-10)) -gt 1 -or $window.Opacity -lt 1){throw 'Native resize produced an empty or mismatched surface'}
            }
            $screen=[Windows.Forms.SystemInformation]::VirtualScreen
            $pausedCapture=[GlassScreenCapture]::CaptureAsync($script:glassHandle,$screen.Left,$screen.Top,$screen.Width,$screen.Height).GetAwaiter().GetResult()
            if($pausedCapture){throw 'Capture-exclusion changes overlapped native resize'}
        }finally{$script:isResizing=$false;Sync-CapturePause}
        Write-Output 'PASS: rendered collapse/expansion frames, stable native host, no geometry reversal or blank content frame, mid-animation reversal, transparent input passthrough'
    }
    if($VerifyBank) {
        Add-Type @'
using System;
using System.Collections.Generic;
using System.Threading.Tasks;
public sealed class BankFixtureClient {
    public List<string> Keys=new List<string>();
    public string Outcome="reset",Snapshot;
    public bool FailNext,FetchFailNext;
    public Task<string> FetchAsync() { if(FetchFailNext){FetchFailNext=false;var failed=new TaskCompletionSource<string>();failed.SetException(new TimeoutException("Simulated quota read timeout"));return failed.Task;}return Task.FromResult(Snapshot); }
    public Task<string> ConsumeResetAsync(string key,string credit) {
        Keys.Add(key);
        if(FailNext){FailNext=false;var failure=new TaskCompletionSource<string>();failure.SetException(new TimeoutException("Simulated disconnected response"));return failure.Task;}
        return Task.FromResult("{\"outcome\":\""+Outcome+"\"}");
    }
}
'@
        $realClient=$script:client;$fixture=[BankFixtureClient]::new()
        $fixture.Snapshot='{"rateLimits":{"primary":{"usedPercent":0,"windowDurationMins":300},"secondary":{"usedPercent":0,"windowDurationMins":10080}},"rateLimitResetCredits":{"availableCount":1,"credits":[]}}'
        $script:client=$fixture
        try {
            if(!$script:resetSounds.Ready){throw 'Bundled reset sounds could not be loaded'}
            if(!$soundItem.IsCheckable -or !$confettiItem.IsCheckable -or $script:celebration.IsHitTestVisible){throw 'Reset feedback controls or input passthrough failed'}
            $script:resetConfettiEnabled=$true
            Update-BankSnapshot $null
            if($ui.BankConfirm.IsEnabled -or $ui.BankCaption.Text -ne '储备 —'){throw 'Unknown bank balance was treated as available'}
            Update-BankSnapshot ([pscustomobject]@{availableCount=2;credits=@()})
            if(!$ui.BankConfirm.IsEnabled){throw 'Authoritative availableCount was ignored'}
            Update-Window ([pscustomobject]@{usedPercent=26;windowDurationMins=300}) 'A'
            Update-Window ([pscustomobject]@{usedPercent=62;windowDurationMins=10080}) 'B'
            Show-BankSheet;$window.UpdateLayout();Save-Preview $window 'bank-confirmation.png'
            if($fixture.Keys.Count){throw 'Opening bank sheet redeemed a credit'}
            Start-BankUse;Start-BankUse
            if($fixture.Keys.Count -ne 1 -or $ui.BankConfirm.IsEnabled -or $script:bankAvailable -ne 2){throw 'Double submission or premature local balance update'}
            if($VerifyResetAnimation){
                $script:resetSamples=[Collections.Generic.List[double]]::new();$script:resetSamplesB=[Collections.Generic.List[double]]::new()
                $resetRender=[EventHandler]{$script:resetSamples.Add([double]$ui.BarA.Value);$script:resetSamplesB.Add([double]$ui.BarB.Value)}
                [Windows.Media.CompositionTarget]::add_Rendering($resetRender)
            }
            Complete-BankUse;Complete-Refresh
            if($VerifyResetAnimation){
                try {
                    Pump-VerifyUI 240;Save-Preview $window 'reset-start.png'
                    if($script:celebration.Progress -le 0 -or $script:celebration.Progress -ge 1 -or !$script:celebration.Confetti -or $script:celebration.Plays -ne 1){throw 'Classic halo and confetti did not start with the confirmed reset'}
                    Save-Preview $script:celebration 'celebration-only.png'
                    Pump-VerifyUI 560;Save-Preview $window 'reset-middle.png'
                    if($ui.BankSheet.Visibility -ne 'Collapsed' -or $ui.Metrics.Visibility -ne 'Visible' -or $ui.BarA.Value -le 74 -or $ui.BarA.Value -ge 100 -or $ui.BarB.Value -le 38 -or $ui.BarB.Value -ge 100){throw 'The actual quota bars were not visibly restoring'}
                    if($ui.ValueA.Text -ne ([Math]::Floor($ui.BarA.Value+.5).ToString('0',[Globalization.CultureInfo]::InvariantCulture)+'%')){throw 'Stable animated percentages did not follow their quota bar'}
                    Pump-VerifyUI 1000;Save-Preview $window 'reset-complete.png'
                } finally {[Windows.Media.CompositionTarget]::remove_Rendering($resetRender)}
                if($script:resetSamples.Count -lt 6 -or !@($script:resetSamples | Where-Object {$_ -gt 0 -and $_ -lt 100}).Count){throw 'No intermediate reset fill frames'}
                foreach($samples in @($script:resetSamples,$script:resetSamplesB)){$previous=0.0;foreach($sample in $samples){if($sample -lt $previous-0.05){throw 'Quota fill reversed'};$previous=$sample}}
                if($script:bankAnimationActive -or $ui.BarA.Value -ne 100 -or $ui.BarB.Value -ne 100 -or !$ui.Refresh.IsEnabled -or $script:celebration.Progress -ne 1){throw 'Quota refill and celebration did not finish cleanly'}
                [pscustomobject]@{frames=$script:resetSamples.Count;primary=@($script:resetSamples);weekly=@($script:resetSamplesB);durationMs=1200;curve=@(.20,.65,.30,1);classicHalo=$true;confetti=$true;nativeRenderer=$true;audioLoaded=$script:resetSounds.Ready;testAudioMuted=$true;realRedemptions=0} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $PreviewDirectory 'reset-animation-report.json') -Encoding UTF8
                Write-Output 'PASS: both real quota bars and percentages refill with monotonic intermediate frames and confirmed server values'
                Write-Output 'PASS: original halo and light points plus native confetti; bundled audio loads; overlay passes input and ends cleanly'
            }
            if($script:bankAvailable -ne 1 -or $ui.BarA.Value -ne 100 -or $ui.BarB.Value -ne 100){throw 'Successful reset did not apply refreshed server data'}
            Close-BankSheet
            $celebrationsBefore=$script:celebration.Plays
            foreach($outcome in 'nothingToReset','noCredit') {
                Update-BankSnapshot ([pscustomobject]@{availableCount=2;credits=$null})
                $fixture.Outcome=$outcome;Start-BankUse;Complete-BankUse;Complete-Refresh
                if($script:bankSuccess -or $script:bankAttemptKey){throw "Incorrect redemption state: $outcome"}
                if($script:bankAnimationActive -or $script:bankRestoreAwaiting -or $script:celebration.Plays -ne $celebrationsBefore){throw 'A rejected reset started a quota refill or celebration'}
            }
            $fixture.Outcome='alreadyRedeemed';$fixture.FailNext=$true
            Update-BankSnapshot ([pscustomobject]@{availableCount=2;credits=$null})
            Start-BankUse;$key=$script:bankAttemptKey;Complete-BankUse
            if(!$script:bankAttemptKey -or $script:bankSuccess){throw 'Ambiguous failure lost idempotency state'}
            Start-BankUse;Complete-BankUse;Complete-Refresh
            if($VerifyResetAnimation){Pump-VerifyUI 1800}
            if($fixture.Keys[$fixture.Keys.Count-1] -ne $key -or $script:bankAvailable -ne 1){throw 'Retry did not reuse the logical redemption key'}
            Close-BankSheet
            if($VerifyResetAnimation){
                $point=[Windows.Point]::new(140,90);$script:celebration.Play($point.X,$point.Y,$window.Resources['AccentBrush'].Color,$false);Pump-VerifyUI 160
                if($script:celebration.Confetti -or $script:celebration.Progress -ge 1){throw 'Disabling confetti disabled the original halo'}
                Save-Preview $window 'classic-without-confetti.png';$script:celebration.Stop()
                if($script:celebration.Progress -ne 1){throw 'Stopping celebration left a stuck overlay'}
                Write-Output 'PASS: confetti can be disabled independently while the original effect remains'
            }
            $celebrationsBefore=$script:celebration.Plays
            Update-Window ([pscustomobject]@{usedPercent=26;windowDurationMins=300}) 'A'
            Update-Window ([pscustomobject]@{usedPercent=62;windowDurationMins=10080}) 'B'
            Update-BankSnapshot ([pscustomobject]@{availableCount=2;credits=@()})
            $fixture.Outcome='reset';$fixture.FetchFailNext=$true
            Show-BankSheet;Start-BankUse;Complete-BankUse;Complete-Refresh
            if($script:bankAnimationActive -or $ui.BarA.Value -ne 74 -or $ui.BarB.Value -ne 38 -or $ui.Status.Text -ne '已重置 · 额度待同步' -or $script:celebration.Plays -ne $celebrationsBefore){throw 'Unconfirmed quota data was animated as full'}
            Write-Output 'PASS: confirmed redemption with failed quota read keeps prior values and clearly reports that quotas await sync'
            Close-BankSheet
            $fixture.Snapshot='{"rateLimits":{"primary":{"usedPercent":4.6,"windowDurationMins":300},"secondary":{"usedPercent":16.7,"windowDurationMins":10080}},"rateLimitResetCredits":{"availableCount":1,"credits":[]}}'
            Update-BankSnapshot ([pscustomobject]@{availableCount=2;credits=@()})
            Show-BankSheet;Start-BankUse;Complete-BankUse;Complete-Refresh
            if($VerifyResetAnimation){
                Pump-VerifyUI 400
                if($ui.ValueA.Text.Contains('.') -or $ui.ValueB.Text.Contains('.')){throw 'Animated percentages change their decimal width'}
                Pump-VerifyUI 1400
            }
            if([Math]::Abs($ui.BarA.Value-95.4) -gt .001 -or [Math]::Abs($ui.BarB.Value-83.3) -gt .001 -or $ui.ValueA.Text -ne '95.4%' -or $ui.ValueB.Text -ne '83.3%'){throw 'Partial quota values or decimal precision were not restored'}
            Write-Output 'PASS: partial server quotas remain partial, animated numbers stay stable, and final decimal precision is preserved'
            Close-BankSheet;Update-BankSnapshot ([pscustomobject]@{availableCount=0;credits=@()})
            if($ui.BankConfirm.IsEnabled){throw 'Empty bank balance permitted redemption'}
            Write-Output 'PASS: bank count/null/empty states, explicit confirmation, single submission, all four server outcomes, network failure and idempotent retry; fixture redemption only'
        } finally {$script:client=$realClient;$script:bankPending=$null;$script:pending=$null;Close-BankSheet;Update-BankSnapshot $null}
    }

    if ($VerifyDock) {
        $script:normalWidth=300; $script:normalHeight=208
        foreach ($side in 'left','right','top') {
            $script:dockSide = $side
            $script:dockCenterX = $window.Left + $window.Width / 2
            $script:dockCenterY = $window.Top + $window.Height / 2
            Show-Dock $true $true
            $expectedWidth=if ($side -eq 'top') { 196 } else { 80 }
            $expectedHeight=if ($side -eq 'top') { 64 } else { 144 }
            $visibleRect=Get-GlassRect
            if ([Math]::Abs($visibleRect.Width - $expectedWidth) -gt 1 -or $visibleRect.Height -ne $expectedHeight -or $ui.DockPeek.Visibility -ne 'Visible' -or $ui.FullContent.Visibility -ne 'Collapsed') { throw "Compact dock failed: $side" }
            $window.UpdateLayout(); $window.Dispatcher.Invoke([Action]{}, [Windows.Threading.DispatcherPriority]::Render)
            foreach ($peek in 'PeekA','PeekB','PeekLabelA','PeekLabelB') {
                $label = $ui[$peek]
                $bounds = $label.TransformToAncestor($ui.Outer).TransformBounds([Windows.Rect]::new(0,0,$label.ActualWidth,$label.ActualHeight))
                if ($bounds.Left -lt 0 -or $bounds.Right -gt ($ui.Outer.ActualWidth + 1) -or $bounds.Top -lt 0 -or $bounds.Bottom -gt ($ui.Outer.ActualHeight + 1)) { throw "Dock label clipped: $side $peek $bounds" }
            }
            Save-Preview $window "dock-$side.png"
            Show-Dock $false $true
            if ([Math]::Abs($window.Width - 300) -gt 1 -or [Math]::Abs($window.Height - 208) -gt 1 -or $ui.FullContent.Visibility -ne 'Visible') { throw "Dock expansion failed: $side" }
        }
        $script:dockSide = $null; $script:dockCollapsed = $false
        $window.MinWidth=260; $window.MinHeight=184
    }
    foreach ($palette in 'light','dark') {
      $script:theme = $palette; Set-Theme; Sync-ThemeMenu
      foreach ($size in @(@(300,208),@(260,184),@(500,208),@(501,208),@(560,290),@(300,459),@(300,460),@(300,800),@(980,550))) {
        $window.Width = $size[0]; $window.Height = $size[1]; $window.UpdateLayout()
        $window.Dispatcher.Invoke([Action]{}, [Windows.Threading.DispatcherPriority]::Render)
        foreach ($suffix in 'A','B') {
            $names = if ($ui["Detail$suffix"].IsVisible) { @("DetailLabel$suffix","Ring$suffix","DetailValue$suffix","DetailReset$suffix") } else { @("Label$suffix","Value$suffix","Bar$suffix") }
            foreach ($name in $names) {
                $element = $ui[$name]; $bounds = $element.TransformToAncestor($ui["Metric$suffix"]).TransformBounds([Windows.Rect]::new(0,0,$element.ActualWidth,$element.ActualHeight))
                if ($element.ActualHeight -lt 3 -or $bounds.Left -lt -1 -or $bounds.Top -lt -1 -or $bounds.Right -gt ($ui["Metric$suffix"].ActualWidth + 1) -or $bounds.Bottom -gt ($ui["Metric$suffix"].ActualHeight + 1)) { throw "Layout bounds: $name at $size" }
            }
        }
        Save-Preview $window "$palette-$($size[0])x$($size[1]).png"
      }
      $window.Width=300; $window.Height=208; $window.UpdateLayout()
      $menu.PlacementTarget = $ui.More; $menu.IsOpen = $true; $menu.UpdateLayout()
      $window.Dispatcher.Invoke([Action]{}, [Windows.Threading.DispatcherPriority]::Render)
      if (!$menu.Template -or $menu.ActualWidth -lt 200) { throw 'Menu template failed' }
      Save-Preview $menu "$palette-menu.png"
      $menu.IsOpen = $false
    }
    foreach ($sample in @(@(-10,100),@(0,100),@(100,0),@(120,0))) {
        Update-Window ([pscustomobject]@{usedPercent=$sample[0];windowDurationMins=300}) 'A'
        if ($ui.BarA.Value -ne $sample[1]) { throw 'Quota clamping failed' }
    }
    Update-Window $null 'A'
    if ($ui.BarA.Value -ne 0 -or $ui.ArcA.Visibility -ne 'Collapsed' -or $ui.ResetA.Text) { throw 'Missing quota state failed' }
    $window.Width=300; $window.Height=208; $window.Close()
    Write-Output 'PASS: 18 layout/theme combinations, visible content bounds, 2 menu snapshots, left/right/top docking, zero/full/missing quota states; fixture data unless -VerifyLive'
} else {
    # Restore the compact geometry before the first visible frame.
    $window.Opacity=0
    $window.Add_ContentRendered({
        if ($script:appReady) { return }
        $script:appReady=$true
        $area=Get-WorkArea
        $script:normalWidth=[Math]::Min($script:normalWidth,$area.Width-24)
        $script:normalHeight=[Math]::Min($script:normalHeight,$area.Height-24)
        Set-WindowRect @{ Width=$script:normalWidth; Height=$script:normalHeight
            Left=[Math]::Max($area.Left+12,[Math]::Min($window.Left,$area.Right-$script:normalWidth-12))
            Top=[Math]::Max($area.Top+12,[Math]::Min($window.Top,$area.Bottom-$script:normalHeight-12)) }
        $poll.Start(); $glassTimer.Start(); $refreshTimer.Start(); Start-Refresh
        if ($script:dockAllowed -and $settings.dockSide -in @('left','right','top')) {
            $script:dockSide = [string]$settings.dockSide
            $script:dockCenterX = [double]$settings.dockCenterX
            $script:dockCenterY = [double]$settings.dockCenterY
            Show-Dock $true $true
        }
        Update-GlassFrameNow
        $window.Opacity=1
        if ([Windows.SystemParameters]::ClientAreaAnimation) {
            $window.BeginAnimation([Windows.UIElement]::OpacityProperty,[Windows.Media.Animation.DoubleAnimation]::new(0.0,1.0,[Windows.Duration]::new([TimeSpan]::FromMilliseconds(160))))
        }
    })
    [void]$window.ShowDialog()
}
