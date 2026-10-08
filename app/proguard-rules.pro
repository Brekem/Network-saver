# El receptor de administración se referencia desde el Manifest y desde
# "adb shell dpm set-device-owner", por lo que su nombre debe mantenerse.
-keep class com.universalrouter.mode.admin.RouterDeviceAdminReceiver { *; }
