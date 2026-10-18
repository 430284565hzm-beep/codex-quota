param([string]$OutputDirectory)
$ErrorActionPreference='Stop'
if($PSVersionTable.PSVersion.Major -gt 5){throw 'Use Windows PowerShell 5.1 with -STA'}
if(!$OutputDirectory){$OutputDirectory=Join-Path $PSScriptRoot '..\dist\material-verification'}
[void](New-Item -ItemType Directory -Path $OutputDirectory -Force)
$sourceText=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'CodexUsage.ps1'))
$initialization=$sourceText.Substring(0,$sourceText.IndexOf('if ($Verify -or $Preview) {'))
$initialization=$initialization.Substring($initialization.IndexOf("`n")+1).Replace('$PSScriptRoot',("'"+$PSScriptRoot.Replace("'","''")+"'"))
$Verify=$true;$VerifyLive=$false;$VerifyDock=$false;$VerifyMotion=$false;$VerifyBank=$false;$VerifyResetAnimation=$false;$Preview=$false
Invoke-Expression $initialization
Add-Type @'
using System;using System.Runtime.InteropServices;
public static class MaterialReference {
 [DllImport("user32.dll")]public static extern bool GetWindowDisplayAffinity(IntPtr hwnd,out uint affinity);
 [DllImport("user32.dll")]public static extern uint GetGuiResources(IntPtr process,uint flags);
 public static byte[] Blur(byte[] input,int width,int height,int radius){
  byte[] pass=new byte[input.Length],output=new byte[input.Length];int count=radius*2+1;
  for(int y=0;y<height;y++){int row=y*width*4;for(int c=0;c<3;c++){int sum=0;for(int i=-radius;i<=radius;i++)sum+=input[row+Math.Max(0,Math.Min(width-1,i))*4+c];for(int x=0;x<width;x++){pass[row+x*4+c]=(byte)(sum/count);int remove=Math.Max(0,x-radius),add=Math.Min(width-1,x+radius+1);sum+=input[row+add*4+c]-input[row+remove*4+c];}}for(int x=0;x<width;x++)pass[row+x*4+3]=255;}
  for(int x=0;x<width;x++){for(int c=0;c<3;c++){int sum=0;for(int i=-radius;i<=radius;i++)sum+=pass[(Math.Max(0,Math.Min(height-1,i))*width+x)*4+c];for(int y=0;y<height;y++){output[(y*width+x)*4+c]=(byte)(sum/count);int remove=Math.Max(0,y-radius),add=Math.Min(height-1,y+radius+1);sum+=pass[(add*width+x)*4+c]-pass[(remove*width+x)*4+c];}}for(int y=0;y<height;y++)output[(y*width+x)*4+3]=255;}return output;
 }
 public static bool Equal(byte[] a,byte[] b){if(a.Length!=b.Length)return false;for(int i=0;i<a.Length;i++)if(a[i]!=b[i])return false;return true;}
}
'@
function Pump([int]$milliseconds){
    $frame=[Windows.Threading.DispatcherFrame]::new();$timer=[Windows.Threading.DispatcherTimer]::new();$timer.Interval=[TimeSpan]::FromMilliseconds($milliseconds)
    $timer.Add_Tick({$frame.Continue=$false});$timer.Start();try{[Windows.Threading.Dispatcher]::PushFrame($frame)}finally{$timer.Stop()}
}
function Save-MaterialPreview([string]$name){
    $image=[Windows.Media.Imaging.RenderTargetBitmap]::new([int]$window.ActualWidth,[int]$window.ActualHeight,96,96,[Windows.Media.PixelFormats]::Pbgra32);$image.Render($window)
    $encoder=[Windows.Media.Imaging.PngBitmapEncoder]::new();$encoder.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($image))
    $stream=[IO.File]::Create((Join-Path $OutputDirectory $name));try{$encoder.Save($stream)}finally{$stream.Dispose()}
}
$random=[Random]::new(4001);$blurMethod=[GlassCapture].GetMethod('Blur',[Reflection.BindingFlags]'NonPublic,Static');$blurCases=0
foreach($size in @(@(1,1),@(2,7),@(13,9),@(32,23))){foreach($radius in 1,5,12){
    $bytes=New-Object byte[] ($size[0]*$size[1]*4);$random.NextBytes($bytes)
    $expected=[MaterialReference]::Blur($bytes,$size[0],$size[1],$radius)
    $actual=$blurMethod.Invoke($null,[object[]]@($bytes.PSObject.BaseObject,[int]$size[0],[int]$size[1],[int]$radius))
    if(![MaterialReference]::Equal($expected,$actual)){throw 'The optimized material blur changed a pixel'};$blurCases++
}}
$back=[Windows.Window]::new();$back.Title='Codex glass dynamic-background verification';$back.WindowStyle='None';$back.ResizeMode='NoResize';$back.ShowInTaskbar=$false;$back.Topmost=$true
$back.Left=60;$back.Top=50;$back.Width=850;$back.Height=640
$background=[Windows.Media.SolidColorBrush]::new([Windows.Media.Colors]::Coral);$back.Background=$background
$window.Title='Codex glass verification - fixture quotas';$window.Topmost=$true;$window.Left=160;$window.Top=140;$window.Width=300;$window.Height=208
Update-Window ([pscustomobject]@{usedPercent=28.6;windowDurationMins=300}) 'A';Update-Window ([pscustomobject]@{usedPercent=62;windowDurationMins=10080}) 'B';$ui.Plan.Text='DEMO';$ui.Status.Text='动态背景验证 · 示例数据'
try{
    $back.Show();$window.Show();$Verify=$false;$script:appReady=$true;Update-GlassFrame;Pump 1100
    if(!$script:glassRenderer.Frame -or !$backdropBrush.ImageSource){throw "Material renderer did not initialize: $($script:glassRenderer.LastError)"}
    $bitmap=$backdropBrush.ImageSource
    $colorAnimation=[Windows.Media.Animation.ColorAnimation]::new([Windows.Media.Colors]::Coral,[Windows.Media.Colors]::CornflowerBlue,[Windows.Duration]::new([TimeSpan]::FromMilliseconds(700)))
    $colorAnimation.AutoReverse=$true;$colorAnimation.RepeatBehavior=[Windows.Media.Animation.RepeatBehavior]::Forever
    $background.BeginAnimation([Windows.Media.SolidColorBrush]::ColorProperty,$colorAnimation);Pump 300
    $cpuStart=[Diagnostics.Process]::GetCurrentProcess().TotalProcessorTime;$watch=[Diagnostics.Stopwatch]::StartNew();$startFrames=$script:glassRenderer.PresentedFrames
    $script:glassRenderer.StartMeasurement();Pump 5000;$intervals=$script:glassRenderer.StopMeasurement();$watch.Stop()
    $fps=($script:glassRenderer.PresentedFrames-$startFrames)*1000/$watch.Elapsed.TotalMilliseconds
    $sorted=@($intervals|Sort-Object);$p95=$sorted[[Math]::Min($sorted.Count-1,[int][Math]::Floor($sorted.Count*.95))]
    $cpuMs=([Diagnostics.Process]::GetCurrentProcess().TotalProcessorTime-$cpuStart).TotalMilliseconds
    $liveTilePixels=$script:glassRenderer.LastTilePixels
    if($fps -lt 25 -or $p95 -gt 85){throw "Dynamic background still stutters: $([Math]::Round($fps,1)) FPS, p95 $([Math]::Round($p95,1)) ms"}
    if(![object]::ReferenceEquals($bitmap,$backdropBrush.ImageSource)){throw 'The renderer allocated a new WPF bitmap for a live tile'}
    if($script:glassRenderer.LastTilePixels -ge $bitmap.PixelWidth*$bitmap.PixelHeight/2){throw 'Live capture did not stay local to the widget'}
    Save-MaterialPreview 'dynamic-material.png'
    $script:isDragging=$true;Sync-CapturePause;$before=$backdropBrush.Viewport;$moves=@()
    for($i=0;$i -lt 80;$i++){$move=[Diagnostics.Stopwatch]::StartNew();$window.Left+=1;$move.Stop();$moves+=$move.Elapsed.TotalMilliseconds}
    $moveSorted=@($moves|Sort-Object);$moveP95=$moveSorted[76]
    if($moveP95 -gt 8 -or $before.X -eq $backdropBrush.Viewport.X){throw 'Native movement no longer updates the cached texture synchronously'}
    $stoppedAt=$script:glassRenderer.PresentedFrames;Pump 180
    if($script:glassRenderer.PresentedFrames -ne $stoppedAt){throw 'A stale frame was painted during native movement'}
    $script:isDragging=$false;Sync-CapturePause;Pump 350
    if($script:glassRenderer.PresentedFrames -le $stoppedAt){throw 'The material did not resume after native movement'}
    $window.WindowState='Minimized';$pausedAt=$script:glassRenderer.CaptureAttempts;Pump 220
    if($script:glassRenderer.CaptureAttempts -gt $pausedAt+1){throw 'Minimized widget continued capturing the screen'}
    $window.WindowState='Normal';Pump 350
    $background.BeginAnimation([Windows.Media.SolidColorBrush]::ColorProperty,$null)
    $drawing=[Windows.Media.DrawingGroup]::new()
    foreach($stripe in 0..3){$geometry=[Windows.Media.RectangleGeometry]::new([Windows.Rect]::new($stripe*20,0,20,80));$color=if($stripe%2){[Windows.Media.Brushes]::Coral}else{[Windows.Media.Brushes]::CornflowerBlue};$drawing.Children.Add([Windows.Media.GeometryDrawing]::new($color,$null,$geometry))}
    $pattern=[Windows.Media.DrawingBrush]::new($drawing);$pattern.Viewport=[Windows.Rect]::new(0,0,80,80);$pattern.ViewportUnits='Absolute';$pattern.TileMode='Tile';$back.Background=$pattern;Pump 500
    $steadyAt=$script:glassRenderer.PresentedFrames;Pump 350
    if($script:glassRenderer.PresentedFrames-$steadyAt -gt 1){throw 'An unchanged background kept uploading textures'}
    $script:glassRenderer.SetPaused($true);[GlassScreenCapture]::SetPaused($false)
    $screen=$script:glassRenderer.Frame
    $reference=[GlassScreenCapture]::CaptureAsync($script:glassHandle,$screen.Left,$screen.Top,$screen.NativeWidth,$screen.NativeHeight).GetAwaiter().GetResult()
    if(!$reference){throw 'No reference material frame'}
    $bounds=[WidgetWindow]::Bounds($script:glassHandle);$sx=$screen.Width/$screen.NativeWidth;$sy=$screen.Height/$screen.NativeHeight
    $x=[int][Math]::Ceiling(($bounds[0]-$screen.Left)*$sx)+8;$y=[int][Math]::Ceiling(($bounds[1]-$screen.Top)*$sy)+8
    $crop=[Windows.Int32Rect]::new($x,$y,40,30);$actual=New-Object byte[] (40*30*4);$bitmap.CopyPixels($crop,$actual,160,0)
    $expected=New-Object byte[] $actual.Length
    for($row=0;$row -lt 30;$row++){[Buffer]::BlockCopy($reference.Pixels,(($y+$row)*$screen.Width+$x)*4,$expected,$row*160,160)}
    if(![MaterialReference]::Equal($expected,$actual)){throw 'Local material pixels differ from the original whole-screen blur'}
    $script:glassRenderer.SetPaused($false);Pump 180
    Save-MaterialPreview 'still-material.png'
    $script:glassRenderer.Dispose();$script:glassRenderer=$null
    [uint32]$affinity=99
    if(![MaterialReference]::GetWindowDisplayAffinity($script:glassHandle,[ref]$affinity) -or $affinity -ne 0){throw 'Capture exclusion was not restored after shutdown'}
    $report=[ordered]@{blurCases=$blurCases;blurPixelsExactlyPreserved=$true;dynamicFps=[Math]::Round($fps,2);p95PresentationMs=[Math]::Round($p95,2);cpuMsInFiveSeconds=[Math]::Round($cpuMs,1);liveTilePixels=$liveTilePixels;canvasPixels=$bitmap.PixelWidth*$bitmap.PixelHeight;dragP95Ms=[Math]::Round($moveP95,3);oneReusableBitmap=$true;noUploadsWhenUnchanged=$true;minimizedCapturePaused=$true;captureExclusionRestored=$true;realRedemptions=0}
    $report|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $OutputDirectory 'material-report.json') -Encoding UTF8
    $report|ConvertTo-Json -Compress
}finally{
    $Verify=$true;$window.Close();$back.Close();[GlassScreenCapture]::SetPaused($false)
}
