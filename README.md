# Universal Router Mode

Aplicación Android (Kotlin · MVVM · Material Design 3 · ViewBinding · Android 11+) que convierte cualquier teléfono en un dispositivo dedicado a compartir internet con el menor consumo posible, **sin root y solo con APIs públicas**.

## Compilar

1. Abre la carpeta en Android Studio (Ladybug o superior) y deja que sincronice Gradle.
2. APK debug: `./gradlew assembleDebug`
3. APK release: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
   - Sin `keystore.properties` el release se firma con la clave debug (instalable para pruebas).
   - Para publicar, crea `keystore.properties` en la raíz:
     ```properties
     storeFile=mi-clave.jks
     storePassword=...
     keyAlias=...
     keyPassword=...
     ```

`minSdk 30` (Android 11) · `targetSdk 35` (Android 15) · AGP 8.7.3 · Kotlin 2.0.21 · Gradle 8.11.1.

## Qué hace al pulsar ACTIVAR

La activación es una secuencia idempotente. Aplica todo lo que Android permite automáticamente y, cuando el sistema bloquea una acción, abre **la pantalla exacta de Ajustes** (según fabricante) con un aviso de qué tocar. Al volver a la app continúa con el siguiente paso.

| Función | Modo estándar | Device Owner |
|---|---|---|
| Zona WiFi / USB Tethering | Abre la pantalla exacta de tethering (1 toque) | Igual |
| Bluetooth | Android 11-12: se apaga solo · 13+: abre Ajustes de Bluetooth | Se apaga y se bloquea (`DISALLOW_BLUETOOTH`) |
| No Molestar | Automático (tras conceder acceso una vez) | Igual |
| Brillo 10% | Automático (tras conceder «Modificar ajustes» una vez) | Automático sin permiso (`setSystemSetting`) |
| Ahorro de energía | Abre la pantalla de ahorro (1 toque) | Igual + pantalla se apaga a los 15 s |
| Apps seleccionadas | Cierra sus procesos en segundo plano | Suspendidas y/u ocultas |
| Kiosco, bloqueo de ajustes e instalación | — | Sí |

### Detección del método de conexión

- **Cable USB conectado a un ordenador** (`BatteryManager.BATTERY_PLUGGED_USB`): muestra «USB conectado» y el botón **Activar USB Tethering**, que abre la pantalla de tethering y guía al usuario.
- **Sin cable**: usa la Zona WiFi y muestra su estado (interfaz e IP).

### Detección del fabricante

`Build.MANUFACTURER`, `Build.BRAND`, `Build.MODEL` y `Build.VERSION.SDK_INT` determinan la familia (Samsung One UI, Xiaomi/Redmi/Poco HyperOS-MIUI, Motorola My UX, Pixel, OnePlus OxygenOS, Oppo ColorOS, Vivo, Realme UI), los textos de ayuda y la lista ordenada de pantallas a probar (`OemSettingsNavigator`). Siempre se termina en una acción pública genérica de `android.provider.Settings`, así que nunca falla la navegación.

## Limitaciones de Android (por qué algunos pasos requieren un toque)

Son restricciones del sistema, no de la app; ninguna app sin root ni firma de sistema puede saltárselas:

- **Encender la Zona WiFi o el USB tethering**: `TetheringManager.startTethering` es `@SystemApi`. Ni siquiera Device Owner puede hacerlo. La app abre directamente la pantalla del interruptor.
- **Leer el nombre (SSID) de la Zona WiFi**: `getSoftApConfiguration` es `@SystemApi`. La app muestra la interfaz/IP activa y el nombre del dispositivo, que es el SSID por defecto en la mayoría de modelos.
- **Estado del tethering**: no hay API pública; se detecta por las interfaces de red activas (`ap0`, `swlan0`, `wlan1`, `rndis0`, `ncm0`…).
- **Ahorro de energía**: no existe API pública para activarlo.
- **Bluetooth en Android 13+**: `BluetoothAdapter.disable()` no funciona para apps normales.

## Modo Device Owner (opcional)

La app detecta automáticamente su nivel de privilegios (estándar, administrador o Device Owner) y adapta las funciones.

Configuración (teléfono restablecido de fábrica, **sin cuentas** añadidas):

```bash
adb install app-release.apk
adb shell dpm set-device-owner com.universalrouter.mode/.admin.RouterDeviceAdminReceiver
```

Con Device Owner, al activar se puede (configurable con interruptores en la app):

- **Modo kiosco**: `setLockTaskPackages` + `startLockTask`, y la app se fija como launcher (`addPersistentPreferredActivity`), de modo que el teléfono arranca siempre en Universal Router Mode.
- **Ocultar** (`setApplicationHidden`) y **suspender** (`setPackagesSuspended`) las apps seleccionadas.
- **Bloquear configuraciones no autorizadas** (Bluetooth, fecha, ubicación, cuentas, usuarios, credenciales, modo seguro).
- **Restringir la instalación de apps** (`DISALLOW_INSTALL_APPS`, orígenes desconocidos).
- **Políticas de energía**: brillo 10%, apagado de pantalla a 15 s, sin «pantalla siempre encendida al cargar», Bluetooth bloqueado.

**RESTAURAR** retira todas las políticas, sale del kiosco y devuelve brillo, Bluetooth y No Molestar a su estado previo. El botón **Quitar Device Owner** devuelve la app a modo estándar.

> Nota: con «Restringir instalación» activo, tampoco se pueden instalar actualizaciones por `adb`. Pulsa RESTAURAR antes de actualizar.

## Estructura

```
app/src/main/java/com/universalrouter/mode/
├── UniversalRouterApp.kt            Material You (Android 12+)
├── ui/
│   ├── MainActivity.kt              Vista (ViewBinding), ejecuta eventos
│   ├── MainViewModel.kt             Estado (StateFlow) y flujo activar/restaurar
│   └── RouterUiState.kt             Modelo del panel de estado
├── domain/
│   ├── RouterModeController.kt      Orquesta el Modo Router Total
│   └── PendingAction.kt             Acciones que requieren al usuario
├── data/RouterPreferences.kt        Estado previo y opciones
├── helpers/
│   ├── DeviceDetector.kt            Fabricante, ROM y capacidades
│   ├── OemSettingsNavigator.kt      Pantallas de Ajustes por fabricante
│   ├── TetheringHelper.kt           USB conectado, Zona WiFi, USB tethering
│   ├── BluetoothHelper.kt · DndHelper.kt · BrightnessHelper.kt
│   ├── PowerSaverHelper.kt · AppRestrictionHelper.kt
└── admin/
    ├── RouterDeviceAdminReceiver.kt DeviceAdminReceiver
    └── DeviceOwnerManager.kt        Políticas con DevicePolicyManager
```
