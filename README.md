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

| Función | Con Shizuku (todo automático) | Modo estándar | Device Owner |
|---|---|---|---|
| Zona WiFi / USB Tethering | Se enciende sola (`TetheringManager`) | Abre la pantalla exacta de tethering (1 toque) | Igual que estándar |
| Bluetooth | Se apaga solo | Android 11-12: se apaga solo · 13+: abre Ajustes de Bluetooth | Se apaga y se bloquea (`DISALLOW_BLUETOOTH`) |
| No Molestar | Automático | Automático (tras conceder acceso una vez) | Igual |
| Brillo 10% | Automático | Automático (tras conceder «Modificar ajustes» una vez) | Automático sin permiso (`setSystemSetting`) |
| Ahorro de energía | Se activa solo | Abre la pantalla de ahorro (1 toque) | Igual + pantalla se apaga a los 15 s |
| Apps seleccionadas | Segundo plano restringido (como «Restringir» en Batería) | Cierra sus procesos en segundo plano | Suspendidas y/u ocultas |
| Kiosco, bloqueo de ajustes e instalación | — | — | Sí |

Con Shizuku activo, **RESTAURAR** también lo deshace todo solo: apaga el tethering que encendió la app, devuelve el ahorro de energía, Bluetooth, No Molestar y brillo a como estaban, y vuelve a permitir el segundo plano de las apps.

### Activar con un toque desde el PC o por ADB
El menú de **ShizukuAutoStart** (Windows) tiene «Activar Modo Router (USB)», «Activar Modo Router (Zona WiFi)», «Restaurar teléfono» y la opción «Activar Modo Router al conectar el teléfono». Por ADB:

```
adb shell am start -n com.universalrouter.mode/.ui.MainActivity -a com.universalrouter.mode.action.ACTIVATE [--es method usb|wifi]
adb shell am start -n com.universalrouter.mode/.ui.MainActivity -a com.universalrouter.mode.action.RESTORE
```

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

## Refresco de red 5G/4G (loop infinito, requiere Shizuku)

Toggle **Refresco automático**: cada 30 minutos (ajustable) el teléfono pasa a 4G durante 5 segundos (ajustable) y vuelve a 5G, en bucle infinito y en segundo plano, con la pantalla apagada. Sirve para mantener fresca la conexión que se comparte por hotspot.

- **Protección para jugar**: antes de cada cambio se mide el tráfico del teléfono (incluye el del hotspot). Si supera el umbral (15 KB/s por defecto), el cambio se pospone y se vuelve a comprobar cada minuto, así no corta una partida en curso. «Probar ahora» fuerza el cambio.
- Si la app se cierra a mitad de un ciclo, al volver restaura 5G automáticamente.
- Se muestra una notificación fija (obligatoria en Android para servicios en segundo plano) con botón **Detener**.

### Por qué necesita Shizuku
Cambiar el modo de red requiere el permiso `MODIFY_PHONE_STATE`, que Android solo concede a apps del sistema (ni siquiera a Device Owner). [Shizuku](https://shizuku.rikka.app/) es una app gratuita que permite a otras apps usar los permisos de ADB **sin root**. Desde el proceso de Shizuku la app llama a la API `TelephonyManager#setAllowedNetworkTypesForReason` (la misma que usa Ajustes › Modo de red). Si no está disponible, recurre por reflexión a `ITelephony`, desbloqueando las APIs ocultas que Android 14+ bloquea.

### Activar Shizuku (Samsung, sin PC)
1. Instala **Shizuku** desde Play Store.
2. Ajustes › Acerca del teléfono › Información de software › toca 7 veces «Número de compilación» (activa Opciones de desarrollador).
3. Conéctate a cualquier WiFi (requisito de Android para la depuración inalámbrica; puede ser otra red, solo para este paso).
4. Abre Shizuku › «Iniciar mediante depuración inalámbrica» › «Emparejamiento» y sigue los pasos (el código de emparejamiento se escribe en la notificación de Shizuku).
5. Pulsa **Iniciar** en Shizuku. Vuelve a Universal Router Mode › **Dar permiso a Shizuku** › Permitir.
6. Recomendado: **Quitar restricciones de batería** para que One UI no detenga el loop.
7. Si usas **Ahorro de energía máximo** de Samsung: ese modo pausa Shizuku y el loop deja de funcionar. Añade Shizuku y Universal Router Mode a las apps permitidas en Ajustes › Batería › Ahorro de energía › Ahorro de energía máximo (o usa el ahorro de energía normal).

Después de cada reinicio del teléfono hay que repetir el paso 5 (pulsar Iniciar en Shizuku), o usar ShizukuAutoStart (abajo) para que se haga solo al conectar el teléfono al PC.

### Arrancar Shizuku automáticamente desde el PC (Windows)
En el Release hay **ShizukuAutoStart.exe**, para no tener que pulsar Iniciar en Shizuku cada vez que se reinicia el teléfono:

1. Descárgalo y ejecútalo una vez. Si Windows SmartScreen avisa: «Más información» › «Ejecutar de todas formas» (no está firmado).
2. Se instala en `%LOCALAPPDATA%\ShizukuAutoStart`, arranca con Windows y queda como icono junto al reloj.
3. Activa la **Depuración USB** en el teléfono y conéctalo por cable. El programa:
   - descarga ADB (platform-tools oficial de Google) si el PC no lo tiene;
   - instala **Shizuku** y **Universal Router Mode** en el teléfono si faltan;
   - arranca Shizuku cada vez que conectas el teléfono (también después de reiniciarlo).
4. La primera vez, en Universal Router Mode pulsa **Dar permiso a Shizuku** › Permitir.

Desde el menú del icono: activar Modo Router (USB o zona WiFi), restaurar el teléfono, activar al conectar, estado, iniciar ahora, ayuda para conectar el teléfono, driver USB de Samsung, registro y desinstalar. Para compilarlo: `windows\build.bat` (usa el compilador de C# que ya trae Windows).

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
├── cycle/
│   ├── NetworkCycleService.kt       Loop 5G→4G→5G en primer plano
│   ├── CycleSettings.kt · NetworkCycleState.kt
├── shizuku/
│   ├── ShizukuManager.kt            Estado, permiso y conexión con Shizuku
│   └── NetworkShellService.kt       Corre con permisos de shell vía Shizuku
└── admin/
    ├── RouterDeviceAdminReceiver.kt DeviceAdminReceiver
    └── DeviceOwnerManager.kt        Políticas con DevicePolicyManager
```
