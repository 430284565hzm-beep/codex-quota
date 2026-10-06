using System;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Management.Automation;
using System.Management.Automation.Runspaces;
using System.Windows.Forms;

[assembly: AssemblyTitle("Codex 余量")]
[assembly: AssemblyDescription("便携式 Codex 余量小组件")]
[assembly: AssemblyVersion("1.4.0.0")]

internal static class PortableHost
{
    private const string WindowTitle = "Codex 余量 · WPF";
    private const string ExtractMutexName = @"Local\CodexUsagePortableExtract";
    private static readonly string[] Resources = { "CodexUsage.ps1", "UsageClient.cs", "Widget.xaml", "reset-click.wav", "reset-success.wav" };

    private delegate bool EnumWindowCallback(IntPtr handle, IntPtr state);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumWindows(EnumWindowCallback callback, IntPtr state);

    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetWindowText(IntPtr handle, StringBuilder title, int maxLength);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsWindowVisible(IntPtr handle);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool ShowWindow(IntPtr handle, int command);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(IntPtr handle);

    [STAThread]
    private static int Main()
    {
        try
        {
            IntPtr existing = FindWidgetWindow();
            if (existing != IntPtr.Zero)
            {
                ShowWindow(existing, 9);
                SetForegroundWindow(existing);
                return 0;
            }

            string directory = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "CodexUsageWidget");
            ExtractResources(directory);

            // The embedded scripts are executed by the Windows PowerShell engine in
            // this same STA GUI process. No console or second taskbar app is created.
            Environment.SetEnvironmentVariable("PSExecutionPolicyPreference", "Bypass",
                EnvironmentVariableTarget.Process);
            InitialSessionState state = InitialSessionState.CreateDefault();
            state.AuthorizationManager = new AuthorizationManager("Microsoft.PowerShell");
            using (Runspace runspace = RunspaceFactory.CreateRunspace(state))
            {
                runspace.ApartmentState = ApartmentState.STA;
                runspace.ThreadOptions = PSThreadOptions.UseCurrentThread;
                runspace.Open();
                using (PowerShell shell = PowerShell.Create())
                {
                    shell.Runspace = runspace;
                    string path = Path.Combine(directory, "CodexUsage.ps1");
                    shell.AddScript("& '" + path.Replace("'", "''") + "'");
                    shell.Invoke();
                    if (shell.HadErrors)
                    {
                        StringBuilder message = new StringBuilder();
                        foreach (ErrorRecord error in shell.Streams.Error)
                        {
                            if (message.Length > 0) message.AppendLine();
                            message.Append(error.ToString());
                        }
                        throw new InvalidOperationException(message.ToString());
                    }
                }
            }
            return 0;
        }
        catch (Exception error)
        {
            MessageBox.Show(error.Message, "Codex 余量启动失败", MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            return 1;
        }
    }

    private static void ExtractResources(string directory)
    {
        Directory.CreateDirectory(directory);
        using (Mutex mutex = new Mutex(false, ExtractMutexName))
        {
            bool acquired = false;
            try
            {
                try { acquired = mutex.WaitOne(TimeSpan.FromSeconds(20)); }
                catch (AbandonedMutexException) { acquired = true; }
                if (!acquired) throw new TimeoutException("等待小组件文件就绪超时。");

                foreach (string name in Resources)
                {
                    byte[] bytes;
                    using (Stream resource = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
                    {
                        if (resource == null) throw new FileNotFoundException("安装包缺少 " + name);
                        using (MemoryStream buffer = new MemoryStream())
                        {
                            resource.CopyTo(buffer);
                            bytes = buffer.ToArray();
                        }
                    }

                    string destination = Path.Combine(directory, name);
                    if (File.Exists(destination) && SameContents(destination, bytes)) continue;
                    string temporary = destination + "." + Guid.NewGuid().ToString("N") + ".tmp";
                    try
                    {
                        File.WriteAllBytes(temporary, bytes);
                        if (File.Exists(destination)) File.Replace(temporary, destination, null);
                        else File.Move(temporary, destination);
                    }
                    finally
                    {
                        if (File.Exists(temporary)) File.Delete(temporary);
                    }
                }
            }
            finally
            {
                if (acquired) mutex.ReleaseMutex();
            }
        }
    }

    private static IntPtr FindWidgetWindow()
    {
        IntPtr found = IntPtr.Zero;
        EnumWindows(delegate(IntPtr handle, IntPtr state)
        {
            if (!IsWindowVisible(handle)) return true;
            StringBuilder title = new StringBuilder(128);
            GetWindowText(handle, title, title.Capacity);
            if (!String.Equals(title.ToString(), WindowTitle, StringComparison.Ordinal)) return true;
            found = handle;
            return false;
        }, IntPtr.Zero);
        return found;
    }

    private static bool SameContents(string path, byte[] expected)
    {
        byte[] current = File.ReadAllBytes(path);
        if (current.Length != expected.Length) return false;
        for (int index = 0; index < current.Length; index++)
            if (current[index] != expected[index]) return false;
        return true;
    }
}
