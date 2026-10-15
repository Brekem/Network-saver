// Shizuku AutoStart para Windows (Universal Router Mode)
//
// App de bandeja del sistema que se queda en segundo plano y, cada vez que se
// conecta el teléfono por USB (por ejemplo tras reiniciarlo), arranca Shizuku
// por ADB. Descarga ADB (platform-tools oficial de Google) si no está, e
// instala Shizuku y Universal Router Mode en el teléfono si faltan.
//
// Compilar (sin instalar nada, csc viene con Windows): windows\build.bat
// C# 5 (compilador de .NET Framework 4.x): sin interpolación ni operador ?.

using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Net;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

[assembly: System.Reflection.AssemblyTitle("Shizuku AutoStart")]
[assembly: System.Reflection.AssemblyProduct("Universal Router Mode")]
[assembly: System.Reflection.AssemblyVersion("1.3.0.0")]

static class Program
{
    public const string AppName = "ShizukuAutoStart";
    const string MutexName = "Local\\ShizukuAutoStart_Brekem";
    public const string ExitEventName = "Local\\ShizukuAutoStart_Brekem_Exit";

    public static string InstallDir
    {
        get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), AppName); }
    }

    public static string InstalledExe
    {
        get { return Path.Combine(InstallDir, AppName + ".exe"); }
    }

    [STAThread]
    static void Main(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);

        // Ejecutado desde Descargas u otra carpeta: se instala en
        // %LOCALAPPDATA%\ShizukuAutoStart y arranca la copia instalada.
        if (!SamePath(Application.ExecutablePath, InstalledExe))
        {
            Install();
            return;
        }

        bool created;
        using (var mutex = new Mutex(true, MutexName, out created))
        {
            if (!created)
            {
                MessageBox.Show("Shizuku AutoStart ya está en ejecución.\nBusca su icono junto al reloj de Windows.",
                    "Shizuku AutoStart", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            bool welcome = Array.IndexOf(args, "--installed") >= 0;
            Application.Run(new TrayApp(welcome));
        }
    }

    static bool SamePath(string a, string b)
    {
        return string.Equals(Path.GetFullPath(a).TrimEnd('\\'), Path.GetFullPath(b).TrimEnd('\\'),
            StringComparison.OrdinalIgnoreCase);
    }

    static void Install()
    {
        try
        {
            Directory.CreateDirectory(InstallDir);
            // Si hay una versión anterior en ejecución, se le pide que se cierre
            // para poder reemplazar el .exe.
            EventWaitHandle exitEvent;
            if (EventWaitHandle.TryOpenExisting(ExitEventName, out exitEvent))
            {
                exitEvent.Set();
                exitEvent.Dispose();
            }
            Exception last = null;
            for (int i = 0; i < 40; i++)
            {
                try
                {
                    File.Copy(Application.ExecutablePath, InstalledExe, true);
                    last = null;
                    break;
                }
                catch (IOException e)
                {
                    last = e;
                    Thread.Sleep(250);
                }
            }
            if (last != null) throw last;
            Settings.AutoStart = true;
            Process.Start(new ProcessStartInfo(InstalledExe, "--installed") { UseShellExecute = false });
        }
        catch (Exception e)
        {
            MessageBox.Show("No se pudo instalar Shizuku AutoStart:\n" + e.Message,
                "Shizuku AutoStart", MessageBoxButtons.OK, MessageBoxIcon.Error);
        }
    }
}

/// <summary>Preferencias en HKCU (sin permisos de administrador).</summary>
static class Settings
{
    const string Key = "Software\\" + Program.AppName;
    const string RunKey = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    public static bool AutoActivate
    {
        get { return GetInt("AutoActivate", 0) != 0; }
        set { SetInt("AutoActivate", value ? 1 : 0); }
    }

    public static bool InstallApps
    {
        get { return GetInt("InstallApps", 1) != 0; }
        set { SetInt("InstallApps", value ? 1 : 0); }
    }

    public static bool AutoStart
    {
        get
        {
            using (var k = Registry.CurrentUser.OpenSubKey(RunKey))
                return k != null && k.GetValue(Program.AppName) != null;
        }
        set
        {
            using (var k = Registry.CurrentUser.CreateSubKey(RunKey))
            {
                if (value) k.SetValue(Program.AppName, "\"" + Program.InstalledExe + "\"");
                else k.DeleteValue(Program.AppName, false);
            }
        }
    }

    static int GetInt(string name, int def)
    {
        using (var k = Registry.CurrentUser.OpenSubKey(Key))
        {
            object v = k == null ? null : k.GetValue(name);
            return v is int ? (int)v : def;
        }
    }

    static void SetInt(string name, int value)
    {
        using (var k = Registry.CurrentUser.CreateSubKey(Key))
            k.SetValue(name, value, RegistryValueKind.DWord);
    }

    public static void RemoveAll()
    {
        AutoStart = false;
        Registry.CurrentUser.DeleteSubKeyTree(Key, false);
    }
}

enum Health { Waiting, Working, Ok, Error }

class TrayApp : ApplicationContext
{
    const string ShizukuPkg = "moe.shizuku.privileged.api";
    const string RouterPkg = "com.universalrouter.mode";
    const string PlatformToolsUrl = "https://dl.google.com/android/repository/platform-tools-latest-windows.zip";
    const string ShizukuReleaseApi = "https://api.github.com/repos/RikkaApps/Shizuku/releases/latest";
    const string RouterApkUrl = "https://github.com/Brekem/Network-saver/releases/latest/download/UniversalRouterMode.apk";
    const string SamsungDriverUrl = "https://developer.samsung.com/android-usb-driver";
    const int PollMs = 4000;
    const int RetryMs = 30000;

    // Busca la librería de arranque de Shizuku en la APK instalada y la ejecuta
    // (es el método oficial "Iniciar mediante ADB" de Shizuku). Sin comillas
    // dobles: se pasa tal cual al shell del teléfono.
    const string StartShizukuCmd =
        "p=$(pm path " + ShizukuPkg + " | head -n1 | sed 's/^package://;s/base.apk$//'); " +
        "for a in arm64 arm x86_64 x86; do if [ -f ${p}lib/$a/libshizuku.so ]; then ${p}lib/$a/libshizuku.so; exit; fi; done; " +
        "echo LIB_NOT_FOUND";

    readonly NotifyIcon tray;
    readonly Control ui;
    readonly ToolStripMenuItem statusItem;
    readonly ToolStripMenuItem installAppsItem;
    readonly ToolStripMenuItem autoStartItem;
    readonly Dictionary<Health, Icon> icons = new Dictionary<Health, Icon>();
    readonly List<string> log = new List<string>();
    readonly AutoResetEvent wake = new AutoResetEvent(false);
    readonly EventWaitHandle exitEvent;

    // Estado del hilo de trabajo
    string adb;
    volatile bool running = true;
    volatile bool forceRetry;
    readonly Dictionary<string, DateTime> lastAttempt = new Dictionary<string, DateTime>();
    readonly HashSet<string> active = new HashSet<string>();
    // Teléfono con Shizuku activo, para los botones del menú (null = ninguno).
    volatile string readySerial;
    readonly HashSet<string> warned = new HashSet<string>();
    DateTime noDeviceSince = DateTime.Now;
    bool noDeviceHintShown;

    public TrayApp(bool welcome)
    {
        ui = new Control();
        ui.CreateControl();

        foreach (Health h in Enum.GetValues(typeof(Health))) icons[h] = MakeIcon(h);

        statusItem = new ToolStripMenuItem("Iniciando…") { Enabled = false };
        installAppsItem = new ToolStripMenuItem("Instalar Shizuku y Universal Router Mode si faltan")
        {
            CheckOnClick = true,
            Checked = Settings.InstallApps,
        };
        installAppsItem.CheckedChanged += delegate { Settings.InstallApps = installAppsItem.Checked; };
        autoStartItem = new ToolStripMenuItem("Arrancar con Windows") { CheckOnClick = true, Checked = Settings.AutoStart };
        autoStartItem.CheckedChanged += delegate { Settings.AutoStart = autoStartItem.Checked; };
        var autoActivateItem = new ToolStripMenuItem("Activar Modo Router al conectar el teléfono")
        {
            CheckOnClick = true,
            Checked = Settings.AutoActivate,
        };
        autoActivateItem.CheckedChanged += delegate { Settings.AutoActivate = autoActivateItem.Checked; };

        var menu = new ContextMenuStrip();
        menu.Items.Add(statusItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Activar Modo Router (USB)", null, delegate { MenuRouterAction("ACTIVATE", "usb", "Activar Modo Router"); });
        menu.Items.Add("Activar Modo Router (Zona WiFi)", null, delegate { MenuRouterAction("ACTIVATE", "wifi", "Activar Modo Router"); });
        menu.Items.Add("Restaurar teléfono", null, delegate { MenuRouterAction("RESTORE", null, "Restaurar el teléfono"); });
        menu.Items.Add(autoActivateItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Iniciar Shizuku ahora", null, delegate { forceRetry = true; wake.Set(); });
        menu.Items.Add(installAppsItem);
        menu.Items.Add(autoStartItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Cómo conectar el teléfono…", null, delegate { ShowHelp(); });
        menu.Items.Add("Driver USB de Samsung (si no detecta el teléfono)…", null, delegate { OpenUrl(SamsungDriverUrl); });
        menu.Items.Add("Ver registro…", null, delegate { ShowLog(); });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Desinstalar Shizuku AutoStart", null, delegate { Uninstall(); });
        menu.Items.Add("Salir", null, delegate { Quit(); });

        tray = new NotifyIcon
        {
            Icon = icons[Health.Waiting],
            Text = "Shizuku AutoStart",
            ContextMenuStrip = menu,
            Visible = true,
        };
        tray.MouseClick += delegate(object s, MouseEventArgs e)
        {
            if (e.Button == MouseButtons.Left) ShowLog();
        };

        // Una nueva versión instalándose pide a esta que se cierre.
        exitEvent = new EventWaitHandle(false, EventResetMode.AutoReset, Program.ExitEventName);
        var exitWatcher = new Thread(delegate()
        {
            exitEvent.WaitOne();
            if (running) ui.BeginInvoke(new Action(Quit));
        }) { IsBackground = true };
        exitWatcher.Start();

        if (welcome)
        {
            Balloon("Shizuku AutoStart instalado",
                "Se queda junto al reloj y arranca con Windows. Conecta el teléfono por USB con la depuración USB activada.",
                ToolTipIcon.Info);
        }

        var worker = new Thread(Work) { IsBackground = true, Name = "worker" };
        worker.Start();
    }

    // ------------------------------------------------------------------
    // Trabajo en segundo plano
    // ------------------------------------------------------------------

    void Work()
    {
        while (running)
        {
            try
            {
                if (adb == null && !EnsureAdb())
                {
                    wake.WaitOne(RetryMs);
                    continue;
                }
                Poll();
            }
            catch (Exception e)
            {
                Log("Error: " + e.Message);
                SetStatus(Health.Error, "Error: " + e.Message);
            }
            wake.WaitOne(PollMs);
        }
    }

    void Poll()
    {
        // Arranca el servidor ADB sin redirigir la salida (si se redirige, el
        // proceso servidor hereda las tuberías y las lecturas no terminan).
        RunPlain(adb, "start-server", 20000);

        var devices = new List<KeyValuePair<string, string>>();
        foreach (string raw in Adb("devices", 15000).Split('\n'))
        {
            string line = raw.Trim();
            if (line.Length == 0 || line.StartsWith("List of devices") || line.StartsWith("*")) continue;
            string[] parts = Regex.Split(line, "\\s+");
            if (parts.Length >= 2) devices.Add(new KeyValuePair<string, string>(parts[0], parts[1]));
        }

        if (devices.Count == 0)
        {
            active.Clear();
            readySerial = null;
            SetStatus(Health.Waiting, "Esperando el teléfono por USB…");
            if (!noDeviceHintShown && (DateTime.Now - noDeviceSince).TotalSeconds > 60)
            {
                noDeviceHintShown = true;
                Balloon("No se detecta ningún teléfono",
                    "Conecta el cable USB y activa la depuración USB. Menú › «Cómo conectar el teléfono».",
                    ToolTipIcon.Warning);
            }
            return;
        }
        noDeviceSince = DateTime.Now;
        noDeviceHintShown = true;

        bool force = forceRetry;
        forceRetry = false;
        foreach (var d in devices)
        {
            string serial = d.Key, state = d.Value;
            if (state == "unauthorized")
            {
                SetStatus(Health.Error, "Acepta la depuración USB en el teléfono");
                if (warned.Add(serial + ":unauth"))
                {
                    Balloon("Autoriza este PC en el teléfono",
                        "Desbloquea el teléfono y pulsa «Permitir» en el aviso de depuración USB (marca «Permitir siempre»).",
                        ToolTipIcon.Warning);
                }
            }
            else if (state == "device")
            {
                HandleDevice(serial, force);
            }
            else
            {
                SetStatus(Health.Waiting, "Teléfono en estado «" + state + "»…");
            }
        }
    }

    void HandleDevice(string serial, bool force)
    {
        if (Shell(serial, "getprop sys.boot_completed", 10000).Trim() != "1")
        {
            SetStatus(Health.Waiting, "El teléfono está arrancando…");
            return;
        }
        string model = Shell(serial, "getprop ro.product.model", 10000).Trim();
        if (model.Length == 0) model = serial;

        if (IsShizukuRunning(serial))
        {
            readySerial = serial;
            SetStatus(Health.Ok, "Shizuku activo · " + model);
            if (active.Add(serial))
            {
                Log("Shizuku activo en " + model);
                if (Settings.InstallApps) EnsureApp(serial, RouterPkg, "Universal Router Mode", RouterApkUrl);
                if (Settings.AutoActivate) RouterAction(serial, "ACTIVATE", null, "Activar Modo Router");
            }
            return;
        }
        active.Remove(serial);
        if (readySerial == serial) readySerial = null;

        DateTime last;
        if (!force && lastAttempt.TryGetValue(serial, out last) && (DateTime.Now - last).TotalMilliseconds < RetryMs)
            return;
        lastAttempt[serial] = DateTime.Now;

        if (!IsInstalled(serial, ShizukuPkg))
        {
            if (!Settings.InstallApps)
            {
                SetStatus(Health.Error, "Shizuku no está instalado en el teléfono");
                return;
            }
            if (!EnsureApp(serial, ShizukuPkg, "Shizuku", LatestShizukuApkUrl())) return;
        }
        if (Settings.InstallApps) EnsureApp(serial, RouterPkg, "Universal Router Mode", RouterApkUrl);

        SetStatus(Health.Working, "Iniciando Shizuku en " + model + "…");
        Log("Iniciando Shizuku en " + model);
        string output = Shell(serial, StartShizukuCmd, 60000);
        Log(output.Trim());
        Thread.Sleep(1500);

        if (IsShizukuRunning(serial))
        {
            active.Add(serial);
            SetStatus(Health.Ok, "Shizuku activo · " + model);
            readySerial = serial;
            Balloon("Shizuku iniciado", "Shizuku está activo en " + model + ". Menú › «Activar Modo Router» para aplicarlo todo.",
                ToolTipIcon.Info);
            if (Settings.AutoActivate)
            {
                // Margen para que la app reciba el binder de Shizuku.
                Thread.Sleep(3000);
                RouterAction(serial, "ACTIVATE", null, "Activar Modo Router");
            }
        }
        else
        {
            string why = output.Contains("LIB_NOT_FOUND")
                ? "no se encontró el iniciador de Shizuku (actualiza Shizuku)"
                : "revisa «Ver registro»";
            SetStatus(Health.Error, "No se pudo iniciar Shizuku");
            Balloon("No se pudo iniciar Shizuku", "Reintentando en 30 s: " + why + ".", ToolTipIcon.Error);
        }
    }

    /// <summary>Botón del menú: ejecuta la acción fuera del hilo de la interfaz.</summary>
    void MenuRouterAction(string action, string method, string label)
    {
        ThreadPool.QueueUserWorkItem(delegate
        {
            string serial = readySerial;
            if (serial == null)
            {
                Balloon("Teléfono no listo",
                    "Conecta el teléfono por USB y espera a que el icono se ponga verde (Shizuku activo).",
                    ToolTipIcon.Warning);
                return;
            }
            RouterAction(serial, action, method, label);
        });
    }

    /// <summary>
    /// Pide a Universal Router Mode que active o restaure el Modo Router. Con
    /// Shizuku activo la app aplica todos los pasos sola, sin pantallas de Ajustes.
    /// </summary>
    void RouterAction(string serial, string action, string method, string label)
    {
        if (!IsInstalled(serial, RouterPkg))
        {
            Balloon("Falta Universal Router Mode", "Activa «Instalar Shizuku y Universal Router Mode si faltan».",
                ToolTipIcon.Warning);
            return;
        }
        Shell(serial, "input keyevent KEYCODE_WAKEUP", 10000);
        string cmd = "am start -n " + RouterPkg + "/.ui.MainActivity -a " + RouterPkg + ".action." + action;
        if (method != null) cmd += " --es method " + method;
        string output = Shell(serial, cmd, 20000);
        Log(label + ": " + output.Trim());
        if (output.Contains("Error"))
        {
            Balloon("No se pudo " + label.ToLowerInvariant(), "Detalle en «Ver registro».", ToolTipIcon.Error);
        }
        else
        {
            Balloon(label, action == "RESTORE"
                ? "El teléfono vuelve a su estado anterior."
                : "El teléfono aplica todos los pasos automáticamente.", ToolTipIcon.Info);
        }
    }

    bool IsShizukuRunning(string serial)
    {
        return Regex.IsMatch(Shell(serial, "pidof shizuku_server", 10000), "\\d+");
    }

    bool IsInstalled(string serial, string pkg)
    {
        return Shell(serial, "pm path " + pkg, 15000).Contains("package:");
    }

    /// <summary>Instala la app si falta. Devuelve true si queda instalada.</summary>
    bool EnsureApp(string serial, string pkg, string label, string url)
    {
        if (IsInstalled(serial, pkg)) return true;
        if (url == null)
        {
            SetStatus(Health.Error, "No se pudo obtener la descarga de " + label);
            return false;
        }
        try
        {
            SetStatus(Health.Working, "Descargando " + label + "…");
            Log("Descargando " + label + ": " + url);
            string apk = Path.Combine(Program.InstallDir, pkg + ".apk");
            Download(url, apk);

            SetStatus(Health.Working, "Instalando " + label + " en el teléfono…");
            Log("Instalando " + label);
            string output = Adb("-s " + serial + " install -r \"" + apk + "\"", 180000);
            Log(output.Trim());
            TryDelete(apk);

            if (IsInstalled(serial, pkg))
            {
                Balloon(label + " instalado", label + " se instaló en el teléfono.", ToolTipIcon.Info);
                return true;
            }
            SetStatus(Health.Error, "No se pudo instalar " + label);
            Balloon("No se pudo instalar " + label,
                "Si el teléfono muestra un aviso, acéptalo. Detalle en «Ver registro».", ToolTipIcon.Error);
        }
        catch (Exception e)
        {
            Log("Error instalando " + label + ": " + e.Message);
            SetStatus(Health.Error, "Error instalando " + label);
        }
        return false;
    }

    string LatestShizukuApkUrl()
    {
        try
        {
            string json = DownloadString(ShizukuReleaseApi);
            Match m = Regex.Match(json, "\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"");
            return m.Success ? m.Groups[1].Value : null;
        }
        catch (Exception e)
        {
            Log("No se pudo consultar la última versión de Shizuku: " + e.Message);
            return null;
        }
    }

    // ------------------------------------------------------------------
    // ADB
    // ------------------------------------------------------------------

    bool EnsureAdb()
    {
        adb = FindAdb();
        if (adb != null)
        {
            Log("ADB: " + adb);
            return true;
        }
        try
        {
            SetStatus(Health.Working, "Descargando ADB (Google platform-tools)…");
            Log("ADB no encontrado; descargando " + PlatformToolsUrl);
            Directory.CreateDirectory(Program.InstallDir);
            string zip = Path.Combine(Program.InstallDir, "platform-tools.zip");
            string dir = Path.Combine(Program.InstallDir, "platform-tools");
            Download(PlatformToolsUrl, zip);
            if (Directory.Exists(dir)) Directory.Delete(dir, true);
            ZipFile.ExtractToDirectory(zip, Program.InstallDir);
            TryDelete(zip);
            adb = FindAdb();
            if (adb == null) throw new IOException("adb.exe no está en el paquete descargado");
            Log("ADB instalado en " + adb);
            Balloon("ADB instalado", "Ya puedes conectar el teléfono por USB.", ToolTipIcon.Info);
            return true;
        }
        catch (Exception e)
        {
            Log("Error descargando ADB: " + e.Message);
            SetStatus(Health.Error, "No se pudo descargar ADB (¿sin internet?)");
            return false;
        }
    }

    static string FindAdb()
    {
        var candidates = new List<string>();
        candidates.Add(Path.Combine(Program.InstallDir, "platform-tools\\adb.exe"));
        string local = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        string home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        candidates.Add(Path.Combine(local, "Android\\Sdk\\platform-tools\\adb.exe"));
        string sdk = Environment.GetEnvironmentVariable("ANDROID_HOME") ?? Environment.GetEnvironmentVariable("ANDROID_SDK_ROOT");
        if (!string.IsNullOrEmpty(sdk)) candidates.Add(Path.Combine(sdk, "platform-tools\\adb.exe"));
        string path = Environment.GetEnvironmentVariable("PATH") ?? "";
        foreach (string p in path.Split(';'))
        {
            if (p.Trim().Length > 0) candidates.Add(Path.Combine(p.Trim().Trim('"'), "adb.exe"));
        }
        string winget = Path.Combine(local, "Microsoft\\WinGet\\Packages");
        try
        {
            if (Directory.Exists(winget))
                foreach (string d in Directory.GetDirectories(winget, "Google.PlatformTools*"))
                    candidates.Add(Path.Combine(d, "platform-tools\\adb.exe"));
        }
        catch (Exception) { }
        candidates.Add(Path.Combine(home, ".android-build\\sdk\\platform-tools\\adb.exe"));

        foreach (string c in candidates)
        {
            try { if (File.Exists(c)) return c; }
            catch (Exception) { }
        }
        return null;
    }

    string Adb(string args, int timeoutMs)
    {
        return Run(adb, args, timeoutMs);
    }

    string Shell(string serial, string cmd, int timeoutMs)
    {
        return Run(adb, "-s " + serial + " shell \"" + cmd + "\"", timeoutMs);
    }

    static string Run(string exe, string args, int timeoutMs)
    {
        var psi = new ProcessStartInfo(exe, args)
        {
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        using (var p = Process.Start(psi))
        {
            Task<string> stdout = p.StandardOutput.ReadToEndAsync();
            Task<string> stderr = p.StandardError.ReadToEndAsync();
            if (!p.WaitForExit(timeoutMs))
            {
                try { p.Kill(); }
                catch (Exception) { }
                return "TIMEOUT";
            }
            var sb = new StringBuilder();
            if (stdout.Wait(2000)) sb.Append(stdout.Result);
            if (stderr.Wait(2000)) sb.Append(stderr.Result);
            return sb.ToString();
        }
    }

    static void RunPlain(string exe, string args, int timeoutMs)
    {
        var psi = new ProcessStartInfo(exe, args) { UseShellExecute = false, CreateNoWindow = true };
        using (var p = Process.Start(psi))
        {
            if (!p.WaitForExit(timeoutMs))
            {
                try { p.Kill(); }
                catch (Exception) { }
            }
        }
    }

    // ------------------------------------------------------------------
    // Descargas
    // ------------------------------------------------------------------

    static WebClient NewClient()
    {
        ServicePointManager.SecurityProtocol |= (SecurityProtocolType)3072; // TLS 1.2
        var wc = new WebClient();
        wc.Headers[HttpRequestHeader.UserAgent] = "ShizukuAutoStart/1.3";
        return wc;
    }

    static void Download(string url, string file)
    {
        string tmp = file + ".part";
        using (var wc = NewClient()) wc.DownloadFile(url, tmp);
        TryDelete(file);
        File.Move(tmp, file);
    }

    static string DownloadString(string url)
    {
        using (var wc = NewClient())
        {
            wc.Encoding = Encoding.UTF8;
            return wc.DownloadString(url);
        }
    }

    static void TryDelete(string file)
    {
        try { if (File.Exists(file)) File.Delete(file); }
        catch (Exception) { }
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    void OnUi(Action a)
    {
        if (!running) return;
        try
        {
            if (ui.InvokeRequired) ui.BeginInvoke(a);
            else a();
        }
        catch (Exception) { }
    }

    string lastStatus;

    void SetStatus(Health h, string text)
    {
        if (text == lastStatus) return;
        lastStatus = text;
        Log(text);
        OnUi(delegate
        {
            statusItem.Text = text;
            tray.Icon = icons[h];
            string tip = "Shizuku AutoStart\n" + text;
            tray.Text = tip.Length > 63 ? tip.Substring(0, 62) + "…" : tip;
        });
    }

    void Balloon(string title, string text, ToolTipIcon icon)
    {
        OnUi(delegate { tray.ShowBalloonTip(8000, title, text, icon); });
    }

    void Log(string line)
    {
        if (string.IsNullOrEmpty(line)) return;
        lock (log)
        {
            log.Add(DateTime.Now.ToString("HH:mm:ss") + "  " + line);
            if (log.Count > 400) log.RemoveRange(0, log.Count - 400);
        }
    }

    void ShowLog()
    {
        string text;
        lock (log) text = string.Join(Environment.NewLine, log.ToArray());
        var form = new Form
        {
            Text = "Shizuku AutoStart · registro",
            Width = 760,
            Height = 480,
            StartPosition = FormStartPosition.CenterScreen,
            Icon = icons[Health.Ok],
        };
        var box = new TextBox
        {
            Multiline = true,
            ReadOnly = true,
            ScrollBars = ScrollBars.Both,
            WordWrap = false,
            Dock = DockStyle.Fill,
            Font = new Font("Consolas", 9f),
            Text = text,
        };
        form.Controls.Add(box);
        form.Shown += delegate
        {
            box.SelectionStart = box.TextLength;
            box.ScrollToCaret();
        };
        form.Show();
    }

    static void ShowHelp()
    {
        MessageBox.Show(
            "Para que Shizuku arranque solo al conectar el teléfono:\n\n" +
            "1. En el teléfono: Ajustes › Acerca del teléfono › Información de software › toca 7 veces " +
            "«Número de compilación» (activa las Opciones de desarrollador).\n\n" +
            "2. Ajustes › Opciones de desarrollador › activa «Depuración USB».\n\n" +
            "3. Conecta el teléfono al PC con un cable USB de datos.\n\n" +
            "4. En el teléfono aparecerá «¿Permitir depuración USB?»: marca «Permitir siempre desde este equipo» " +
            "y pulsa Permitir.\n\n" +
            "5. Listo: este programa instala lo que falte y arranca Shizuku. La primera vez, en Universal Router Mode " +
            "pulsa «Dar permiso a Shizuku» › Permitir.\n\n" +
            "6. Desde el menú de este icono, «Activar Modo Router» aplica todo en el teléfono (Bluetooth, ahorro de " +
            "energía, No Molestar, brillo, apps y zona WiFi o USB) sin tocar nada. «Restaurar teléfono» lo deshace.\n\n" +
            "Si el teléfono no aparece, instala el driver USB de Samsung (opción del menú) y prueba otro cable o puerto.\n\n" +
            "Si usas «Ahorro de energía máximo» de Samsung, añade Shizuku a las apps permitidas: ese modo pausa Shizuku.",
            "Cómo conectar el teléfono", MessageBoxButtons.OK, MessageBoxIcon.Information);
    }

    static void OpenUrl(string url)
    {
        try { Process.Start(new ProcessStartInfo(url) { UseShellExecute = true }); }
        catch (Exception) { }
    }

    void Uninstall()
    {
        var r = MessageBox.Show(
            "¿Desinstalar Shizuku AutoStart de este PC?\n\nSe quitará del inicio de Windows y se borrará su carpeta " +
            "(incluido el ADB descargado). No toca nada del teléfono.",
            "Desinstalar", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
        if (r != DialogResult.Yes) return;
        try { Settings.RemoveAll(); }
        catch (Exception) { }
        try
        {
            if (adb != null && adb.StartsWith(Program.InstallDir, StringComparison.OrdinalIgnoreCase))
                RunPlain(adb, "kill-server", 10000);
        }
        catch (Exception) { }
        // Borra la carpeta cuando este proceso ya haya terminado.
        Process.Start(new ProcessStartInfo("cmd.exe",
            "/c timeout /t 3 /nobreak >nul & rmdir /s /q \"" + Program.InstallDir + "\"")
        {
            UseShellExecute = false,
            CreateNoWindow = true,
        });
        Quit();
    }

    void Quit()
    {
        if (!running) return;
        running = false;
        wake.Set();
        tray.Visible = false;
        tray.Dispose();
        ExitThread();
    }

    static Icon MakeIcon(Health h)
    {
        Color c;
        switch (h)
        {
            case Health.Ok: c = Color.FromArgb(46, 160, 67); break;
            case Health.Working: c = Color.FromArgb(9, 105, 218); break;
            case Health.Error: c = Color.FromArgb(207, 34, 46); break;
            default: c = Color.FromArgb(110, 118, 129); break;
        }
        using (var bmp = new Bitmap(32, 32))
        {
            using (var g = Graphics.FromImage(bmp))
            using (var brush = new SolidBrush(c))
            using (var font = new Font("Segoe UI", 17f, FontStyle.Bold, GraphicsUnit.Pixel))
            {
                g.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
                g.TextRenderingHint = System.Drawing.Text.TextRenderingHint.AntiAliasGridFit;
                g.FillEllipse(brush, 1, 1, 30, 30);
                var fmt = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
                g.DrawString("S", font, Brushes.White, new RectangleF(0, 1, 32, 32), fmt);
            }
            return Icon.FromHandle(bmp.GetHicon());
        }
    }
}
