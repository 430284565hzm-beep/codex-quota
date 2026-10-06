param([string]$OutputPath, [switch]$Unsigned, [switch]$Instrumentation)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$toolchainRoot = Join-Path $projectRoot 'toolchain'
$javaRoot = (Get-ChildItem -LiteralPath (Join-Path $toolchainRoot 'java') -Directory | Select-Object -First 1).FullName
$sdkToolsRoot = (Get-ChildItem -LiteralPath (Join-Path $toolchainRoot 'sdk-build') -Directory | Select-Object -First 1).FullName
$androidJar = Join-Path $toolchainRoot 'sdk-platform\android-35\android.jar'
$aapt = Join-Path $sdkToolsRoot 'aapt2.exe'
$buildRoot = Join-Path $projectRoot $(if ($Instrumentation) {'build-instrumentation'} else {'build'})
$classDirectory = Join-Path $buildRoot ('classes-'+[guid]::NewGuid().ToString('N'))
$sourceRoot = Join-Path $projectRoot 'app\src\main'
if ($Instrumentation) { $sourceRoot = Join-Path $projectRoot 'app\src\androidTest' }
if (-not $OutputPath) { $OutputPath = Join-Path $projectRoot $(if ($Instrumentation) {'..\dist\CodexQuota-tests.apk'} else {'..\dist\CodexQuota-Android.apk'}) }
[void](New-Item -ItemType Directory -Path (Split-Path ([IO.Path]::GetFullPath($OutputPath))) -Force)
$oldJavaRoot = $env:JAVA_HOME
$env:JAVA_HOME = $javaRoot
try {
    New-Item -ItemType Directory -Path $buildRoot,(Join-Path $buildRoot 'generated'),$classDirectory,(Join-Path $buildRoot 'dex') -Force | Out-Null
    $resources = Join-Path $buildRoot 'resources.zip'
    if (-not $Instrumentation) {
        & $aapt compile --dir (Join-Path $sourceRoot 'res') -o $resources
        if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed' }
    }
    $package = Join-Path $buildRoot 'resources.apk'
    $args = @('link','-I',$androidJar,'--manifest',(Join-Path $sourceRoot 'AndroidManifest.xml'),'--java',(Join-Path $buildRoot 'generated'),'--min-sdk-version','26','--target-sdk-version','35','-o',$package)
    if (-not $Instrumentation) {$args += $resources}
    & $aapt @args
    if ($LASTEXITCODE -ne 0) { throw 'Resource linking failed' }
    $sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'java') -Recurse -Filter '*.java') + @(Get-ChildItem -LiteralPath (Join-Path $buildRoot 'generated') -Recurse -Filter '*.java')
    $fileList = Join-Path $buildRoot 'sources.txt'
    [IO.File]::WriteAllLines($fileList,[string[]]@($sourceFiles | ForEach-Object { '"' + ($_.FullName -replace '\\','/') + '"' }),[Text.UTF8Encoding]::new($false))
    $bootClasspath = $androidJar + [IO.Path]::PathSeparator + (Join-Path $sdkToolsRoot 'core-lambda-stubs.jar')
    $javaArgs = @('-J-Duser.language=en','-J-Dfile.encoding=UTF-8','-encoding','UTF-8','-source','8','-target','8','-bootclasspath',$bootClasspath,'-d',$classDirectory)
    if ($Instrumentation) {$javaArgs += @('-classpath',(Join-Path $projectRoot 'build\classes.jar'))}
    $javaArgs += ('@'+$fileList)
    & (Join-Path $javaRoot 'bin\javac.exe') @javaArgs
    if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
    $classes = Join-Path $buildRoot 'classes.jar'
    & (Join-Path $javaRoot 'bin\jar.exe') cf $classes -C $classDirectory .
    & (Join-Path $sdkToolsRoot 'd8.bat') --release --min-api 26 --lib $androidJar --output (Join-Path $buildRoot 'dex') $classes
    if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed' }
    $unsignedApk = Join-Path $buildRoot 'unsigned.apk'
    Copy-Item -LiteralPath $package -Destination $unsignedApk -Force
    & (Join-Path $javaRoot 'bin\jar.exe') uf $unsignedApk -C (Join-Path $buildRoot 'dex') classes.dex
    if ($LASTEXITCODE -ne 0) { throw 'DEX packaging failed' }
    $aligned = Join-Path $buildRoot 'aligned.apk'
    & (Join-Path $sdkToolsRoot 'zipalign.exe') -f -p 4 $unsignedApk $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
    if ($Unsigned) { Copy-Item -LiteralPath $aligned -Destination $OutputPath -Force; return }
    $signingRoot = Join-Path $projectRoot '.signing'
    $keyStore = Join-Path $signingRoot 'release.jks'
    $passwordFile = Join-Path $signingRoot 'password.txt'
    if (-not (Test-Path -LiteralPath $keyStore)) {
        New-Item -ItemType Directory -Path $signingRoot -Force | Out-Null
        $bytes = New-Object byte[] 32
        [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
        $password = [Convert]::ToBase64String($bytes)
        [IO.File]::WriteAllText($passwordFile,$password)
        $owner = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        & icacls.exe $signingRoot /inheritance:r /grant:r ($owner+':(OI)(CI)F') | Out-Null
        & (Join-Path $javaRoot 'bin\keytool.exe') -genkeypair -keystore $keyStore -storepass:file $passwordFile -keypass:file $passwordFile -alias codex-glass -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=Codex Glass Personal Build, OU=Personal Tools' -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Signing key generation failed' }
    }
    & (Join-Path $sdkToolsRoot 'apksigner.bat') sign --ks $keyStore --ks-key-alias codex-glass --ks-pass ('file:'+$passwordFile) --v4-signing-enabled false --out $OutputPath $aligned
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
    & (Join-Path $sdkToolsRoot 'apksigner.bat') verify --verbose $OutputPath
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
    Get-Item -LiteralPath $OutputPath | Select-Object FullName,Length
} finally { $env:JAVA_HOME = $oldJavaRoot }
