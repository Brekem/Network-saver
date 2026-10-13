# El receptor de administración se referencia desde el Manifest y desde
# "adb shell dpm set-device-owner", por lo que su nombre debe mantenerse.
-keep class com.universalrouter.mode.admin.RouterDeviceAdminReceiver { *; }

# Servicio que Shizuku arranca por nombre en un proceso con permisos de shell.
-keep class com.universalrouter.mode.shizuku.NetworkShellService { *; }
-keep class com.universalrouter.mode.INetworkShell { *; }
-keep class com.universalrouter.mode.INetworkShell$* { *; }
