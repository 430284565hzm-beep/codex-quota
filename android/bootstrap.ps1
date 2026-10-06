$ErrorActionPreference = 'Stop'
$toolchainRoot = Join-Path $PSScriptRoot 'toolchain'
New-Item -ItemType Directory -Path $toolchainRoot -Force | Out-Null
$packages = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'toolchain-manifest.json') -Raw | ConvertFrom-Json
$destinations = @{'jdk17'='java';'platforms;android-35'='sdk-platform';'build-tools;35.0.0'='sdk-build';'platform-tools'='sdk-tools'}
foreach ($package in $packages) {
    $destination = Join-Path $toolchainRoot $destinations[$package.path]
    if (Test-Path -LiteralPath $destination) { continue }
    Write-Output ('Preparing '+$package.path)
    $zip = Join-Path $toolchainRoot (($package.path -replace ';','-')+'.zip')
    if (-not (Test-Path -LiteralPath $zip)) {
        & curl.exe -L --fail --silent --show-error --retry 2 --max-time 300 $package.url -o $zip
        if ($LASTEXITCODE -ne 0) { throw ('Download failed: '+$package.path) }
    }
    $algorithm = if ($package.checksum.Length -eq 64) {'SHA256'} else {'SHA1'}
    if ((Get-FileHash -LiteralPath $zip -Algorithm $algorithm).Hash.ToLowerInvariant() -ne $package.checksum) {throw ('Checksum mismatch: '+$package.path)}
    Expand-Archive -LiteralPath $zip -DestinationPath $destination
}
Write-Output 'Local toolchain ready. Run build.ps1 to produce the APK.'
