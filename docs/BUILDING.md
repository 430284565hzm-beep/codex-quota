# 构建

两个平台目前都使用 Windows PowerShell 构建脚本。建议在 Windows 10/11 使用 PowerShell 7；Windows EXE 在系统自带的 Windows PowerShell 5.1 中运行。

```powershell
git clone https://github.com/430284565hzm-beep/codex-quota.git
cd codex-quota
```

## Windows

需要系统的 .NET Framework 4.8、Windows PowerShell 与对应 C# 编译器。

```powershell
./windows/build.ps1
```

输出：`dist/CodexQuota-Windows.exe`。可以传 `-OutputPath` 指定位置。

宿主以 WinExe 编译，内嵌 WPF 代码、XAML 和两个 WAV。启动时解压至当前用户 `%LOCALAPPDATA%/CodexUsageWidget`，在同一个 GUI 进程运行 PowerShell。此位置、主题等配置不会放入 EXE。运行需当前用户已安装并登录 Codex。

`windows/CodexUsage.ps1` 必须保留 **UTF-8 BOM**，以保证 Windows PowerShell 5.1 正确读取中文。本仓库已包含 BOM。图标和 WAV 已包含在源码中，不需要生成工具即可构建。修改图标时可以安装 Pillow 并运行 `windows/make_icon.py`。

## Android

```powershell
./android/bootstrap.ps1
./android/build.ps1
```

输出：`dist/CodexQuota-Android.apk`。首个命令依据 `toolchain-manifest.json`，从 Google 和 Eclipse Adoptium 官方来源下载 JDK 17 与 Android SDK，并校验文件哈希。工具保存在 `android/toolchain`，不进入 Git。

应用使用 Java 与 Android 系统 API，无第三方运行库，不依赖 Gradle。构建使用 aapt2、javac、d8、zipalign 和 apksigner，minSdk 26、targetSdk 35。

首次签名会在 `android/.signing` 创建开发者自己的密钥；后续构建继续使用它。此目录已忽略。签名私钥和密码不要上传，也不要随源码分发。开发者自行生成的 APK 可以使用自己的账户登录，但签名不同，无法覆盖安装项目 Releases 的 APK。

```powershell
# 不签名的构建，便于接入自己的签名流程
./android/build.ps1 -Unsigned

# 模拟账户测试 APK；仅在专用测试设备上安装
./android/build.ps1 -Instrumentation
```

测试 APK 输出：`dist/CodexQuota-tests.apk`。它不属于正式发行。

## 原创音效

声音已随两个平台的源码资源提供。可选使用 Python 标准库重新生成：

```powershell
python ./android/generate-reset-sounds.py
```

生成器会同时写入 Android 的 `res/raw` 和 Windows 的 WAV 文件。不会下载第三方音频。
