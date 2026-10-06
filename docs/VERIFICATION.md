# 验证说明

首次开源发布对应 Android 1.3.0 / Windows 1.4.0。测试使用模拟账户，真实重置券消耗为 **0**；测试播放音效被关闭，WAV 解码与资源加载单独检查。

| 范围 | 已验证 |
| --- | --- |
| Android 完整检查 | 51 项：协议解析、未知值、身份校验、加密会话、兑换日志、重复点击、重试、组件布局、原生动效 |
| Android 原生桌面后台刷新 | 16 项：点击组件直接同步、不打开主界面、请求合并、失败保留缓存、定时任务和排期 |
| Android 最终 APK 界面复验 | 21 项：两个额度条、减速曲线、光环和彩纸、解码声音、状态收尾和输入透传 |
| Windows 布局 | 18 组尺寸与浅/深主题组合，左/右/上边缘收起，以及空值和百分比边界 |
| Windows 兑换与动画 | 原生连续填充、数字同步、非满额真实目标、最终小数精度、四种服务结果、重试与兑换后读取失败 |
| 发行 | APK v2/v3 签名、EXE 内嵌资源、所有安装文件副本哈希一致、源码排除凭据与私钥 |

Android 测试设备：专用 Android 15 AOSP 模拟器。三星 S22+ / One UI 的实机后台策略尚未自动验证，需要按使用说明设置电池权限。模拟器结果不代表所有桌面和系统版本的表现。

## Windows 协议测试

在专用测试目录执行，测试使用本机伪造 app-server，不需要登录账户：

```powershell
powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File ./windows/VerifyProtocol.ps1
```

WPF 布局和动效检查：

```powershell
powershell.exe -NoProfile -STA -ExecutionPolicy Bypass -File ./windows/CodexUsage.ps1 -VerifyResetAnimation -VerifyDock -PreviewDirectory ./dist/windows-verification
```

后一条命令依然需要安装 Codex 以定位可执行文件，但兑换由内置模拟客户端处理；不加 `-VerifyLive`。生成的预览和报告保存在本机 `dist`，不提交账户截图。

## Android 测试

构建并安装正式 APK 及测试 APK 到专用模拟器，不要在存有真实登录的手机上运行测试，因为测试会改变本机应用数据和体验模式。

```powershell
./android/build.ps1 -Instrumentation
$adb = './android/toolchain/sdk-tools/platform-tools/adb.exe'
& $adb -s emulator-5554 install -r ./dist/CodexQuota-Android.apk
& $adb -s emulator-5554 install -r ./dist/CodexQuota-tests.apk
& $adb -s emulator-5554 shell am instrument -w dev.codex.glass.test/dev.codex.glass.VerifyRunner
```

后台检查还需先在测试模拟器的系统桌面放置玻璃卡片：

```powershell
& $adb -s emulator-5554 shell am instrument -w -e backgroundOnly true dev.codex.glass.test/dev.codex.glass.VerifyRunner
```

判断结果时需检查输出的 `count`、`passed` 以及 `FAILURE`，不能只看 adb 的进程退出码。
