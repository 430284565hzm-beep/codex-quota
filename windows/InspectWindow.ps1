param([int]$TargetPid)
Add-Type @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class WindowInspector {
    public delegate bool EnumCallback(IntPtr hwnd, IntPtr state);
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumCallback callback, IntPtr state);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int max);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll", EntryPoint="GetWindowLongPtrW")] public static extern IntPtr GetWindowLongPtr(IntPtr hwnd, int index);
    [DllImport("user32.dll")] public static extern IntPtr GetWindow(IntPtr hwnd, uint command);
}
'@
$windows = [Collections.Generic.List[object]]::new()
[WindowInspector]::EnumWindows({
    param($hwnd,$state)
    [uint32]$processId=0
    [void][WindowInspector]::GetWindowThreadProcessId($hwnd,[ref]$processId)
    if ($processId -eq $TargetPid) {
        $title=[Text.StringBuilder]::new(256)
        [void][WindowInspector]::GetWindowText($hwnd,$title,256)
        $style=[WindowInspector]::GetWindowLongPtr($hwnd,-20).ToInt64()
        $windows.Add([pscustomobject]@{
            Handle=$hwnd.ToInt64(); Title=$title.ToString(); Visible=[WindowInspector]::IsWindowVisible($hwnd)
            TitleChars=(($title.ToString().ToCharArray() | ForEach-Object { '{0:X4}' -f [int]$_ }) -join ' ')
            ExactMatch=($title.ToString() -ceq 'Codex 余量 · WPF')
            ExtendedStyle=('0x{0:X8}' -f $style); ToolWindow=[bool]($style -band 0x80)
            AppWindow=[bool]($style -band 0x40000)
            Owner=[WindowInspector]::GetWindow($hwnd,4).ToInt64()
        })
    }
    return $true
},[IntPtr]::Zero) | Out-Null
$windows | Format-List
