param([string]$OutputPath)
$ErrorActionPreference = 'Stop'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (!(Test-Path $compiler)) { $compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework\v4.0.30319\csc.exe' }
if (!(Test-Path $compiler)) { throw '未找到 .NET Framework C# 编译器。' }
$automation = & (Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe') -NoProfile -Command '[psobject].Assembly.Location'
if (!(Test-Path $automation)) { throw '未找到 Windows PowerShell 引擎。' }
$out = if($OutputPath){$OutputPath}else{Join-Path $PSScriptRoot '..\dist\CodexQuota-Windows.exe'}
$out = [IO.Path]::GetFullPath($out)
[void](New-Item -ItemType Directory -Path (Split-Path $out) -Force)
& $compiler /nologo /target:winexe /platform:anycpu /optimize+ "/out:$out" "/reference:$automation" /reference:System.Windows.Forms.dll "/win32icon:$(Join-Path $PSScriptRoot 'CodexUsage.ico')" "/resource:$(Join-Path $PSScriptRoot 'CodexUsage.ps1'),CodexUsage.ps1" "/resource:$(Join-Path $PSScriptRoot 'UsageClient.cs'),UsageClient.cs" "/resource:$(Join-Path $PSScriptRoot 'Widget.xaml'),Widget.xaml" "/resource:$(Join-Path $PSScriptRoot 'reset-click.wav'),reset-click.wav" "/resource:$(Join-Path $PSScriptRoot 'reset-success.wav'),reset-success.wav" (Join-Path $PSScriptRoot 'PortableHost.cs')
if ($LASTEXITCODE -ne 0) { throw "编译失败：$LASTEXITCODE" }
Get-Item -LiteralPath $out | Select-Object FullName,Length,LastWriteTime
