package com.universalrouter.mode.domain

import android.Manifest
import android.content.Context
import android.os.Build
import com.universalrouter.mode.admin.DeviceOwnerManager
import com.universalrouter.mode.data.PreviousState
import com.universalrouter.mode.data.RouterPreferences
import com.universalrouter.mode.helpers.AppRestrictionHelper
import com.universalrouter.mode.helpers.BluetoothHelper
import com.universalrouter.mode.helpers.BrightnessHelper
import com.universalrouter.mode.helpers.DeviceProfile
import com.universalrouter.mode.helpers.DndHelper
import com.universalrouter.mode.helpers.PowerSaverHelper
import com.universalrouter.mode.helpers.SettingsTarget
import com.universalrouter.mode.helpers.TetheringHelper

/**
 * Orquesta el Modo Router Total. Cada pasada es idempotente: aplica todo lo
 * que la API oficial permite según el nivel de permisos y devuelve lo que
 * queda pendiente de un toque del usuario. La UI vuelve a llamar a la pasada
 * cada vez que el usuario regresa de una pantalla de Ajustes.
 */
class RouterModeController(
    private val context: Context,
    private val device: DeviceProfile,
    private val prefs: RouterPreferences,
) {
    val bluetooth = BluetoothHelper(context)
    val dnd = DndHelper(context)
    val brightness = BrightnessHelper(context)
    val power = PowerSaverHelper(context)
    val apps = AppRestrictionHelper(context)
    val owner = DeviceOwnerManager(context)

    // ------------------------------------------------------------------
    // ACTIVAR
    // ------------------------------------------------------------------

    fun activationPass(): PassResult {
        val pending = mutableListOf<PendingAction>()
        val applied = mutableListOf<String>()
        val isOwner = owner.isDeviceOwner
        val options = prefs.deviceOwnerOptions

        // Solo se guarda el estado una vez: si ya existe (modo activo o una
        // restauración a medias) se conserva el original.
        if (prefs.previousState == null) prefs.previousState = snapshot()
        prefs.routerActive = true

        // 1. Permiso runtime de Bluetooth (Android 12+): necesario para apagar
        //    (12) y para volver a encender al restaurar (12+).
        if (device.needsBluetoothConnectPermission && bluetooth.isAvailable && !bluetooth.hasConnectPermission()) {
            pending += PendingAction.RequestPermission(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // 2. No Molestar.
        if (dnd.isActive) {
            applied += "No Molestar ya activo"
        } else if (dnd.enable()) {
            applied += "No Molestar activado"
        } else {
            pending += PendingAction.OpenSettings(
                SettingsTarget.DND_ACCESS,
                "Permite el acceso a «No molestar» para Universal Router Mode y vuelve atrás",
            )
        }

        // 3. Brillo 10%.
        when {
            isOwner && owner.setBrightness(BrightnessHelper.TARGET) -> applied += "Brillo 10% (Device Owner)"
            brightness.setLow() -> applied += "Brillo 10%"
            else -> pending += PendingAction.OpenSettings(
                SettingsTarget.WRITE_SETTINGS,
                "Activa «Modificar ajustes del sistema» y vuelve atrás",
            )
        }

        // 4. Bluetooth.
        if (bluetooth.isEnabled) {
            if (isOwner && options.powerPolicies) {
                owner.setRestrictions(DeviceOwnerManager.POWER_RESTRICTIONS, true)
            }
            if (bluetooth.tryDisable()) {
                applied += "Bluetooth desactivado"
            } else if (!(isOwner && options.powerPolicies)) {
                pending += PendingAction.OpenSettings(
                    SettingsTarget.BLUETOOTH,
                    "Android ${device.release} no permite a las apps apagar Bluetooth: desactívalo con un toque",
                )
            }
        } else {
            if (isOwner && options.powerPolicies) {
                owner.setRestrictions(DeviceOwnerManager.POWER_RESTRICTIONS, true)
            }
            applied += "Bluetooth ya desactivado"
        }

        // 5. Aplicaciones seleccionadas / actividad en segundo plano.
        val selected = prefs.selectedPackages - apps.protectedPackages()
        if (selected.isNotEmpty()) {
            if (isOwner) {
                if (options.suspendApps) {
                    val toSuspend = selected - prefs.suspendedPackages
                    if (owner.setAppsSuspended(toSuspend, true) > 0) {
                        prefs.suspendedPackages = prefs.suspendedPackages + toSuspend
                    }
                }
                if (options.hideApps) {
                    val toHide = selected - prefs.hiddenPackages
                    owner.setAppsHidden(toHide, true)
                    prefs.hiddenPackages = prefs.hiddenPackages + toHide
                }
                applied += "Apps restringidas por política (${selected.size})"
            } else {
                val n = apps.killBackground(selected)
                applied += "Actividad en segundo plano reducida ($n apps)"
            }
        }

        // 6. Políticas Device Owner.
        if (isOwner) {
            owner.setRestrictions(DeviceOwnerManager.SETTINGS_RESTRICTIONS, options.blockSettings)
            owner.setRestrictions(DeviceOwnerManager.INSTALL_RESTRICTIONS, options.blockInstall)
            if (options.powerPolicies) {
                owner.setScreenOffTimeout(SCREEN_OFF_TIMEOUT_MS)
                owner.setStayOnWhilePluggedIn(0)
                applied += "Políticas de energía aplicadas"
            }
        }

        // 7. Ahorro de energía (sin API para activarlo: siempre requiere un toque).
        if (power.isPowerSaveMode) {
            applied += "Ahorro de energía activo"
        } else {
            pending += PendingAction.OpenSettings(
                SettingsTarget.BATTERY_SAVER,
                "Activa el «Ahorro de energía» con un toque y vuelve atrás",
            )
        }

        // 8. Compartir internet: USB si hay cable, si no WiFi. Se deja para el
        //    final para que sea la última pantalla que vea el usuario.
        val tether = TetheringHelper.status(context)
        if (tether.anyActive) {
            applied += if (tether.usbTetheringActive) "USB Tethering activo" else "Zona WiFi activa"
        } else {
            pending += tetheringAction(usb = tether.usbConnected)
        }

        // 9. Kiosco (solo Device Owner).
        val kiosk = isOwner && options.kiosk && owner.prepareKiosk()

        return PassResult(pending, applied, kiosk)
    }

    fun tetheringAction(usb: Boolean): PendingAction.OpenSettings =
        if (usb) {
            PendingAction.OpenSettings(
                SettingsTarget.USB_TETHERING,
                "Activa «${device.usbTetheringLabel}» con un toque",
            )
        } else {
            PendingAction.OpenSettings(
                SettingsTarget.WIFI_HOTSPOT,
                "Activa «${device.hotspotLabel}» con un toque " +
                    "(y desactiva su apagado automático si existe)",
            )
        }

    // ------------------------------------------------------------------
    // RESTAURAR
    // ------------------------------------------------------------------

    fun restorePass(): PassResult {
        val pending = mutableListOf<PendingAction>()
        val applied = mutableListOf<String>()
        val isOwner = owner.isDeviceOwner
        val previous = prefs.previousState

        // Device Owner: deshacer políticas y kiosco siempre, haya snapshot o no.
        if (isOwner) {
            releaseDeviceOwnerPolicies()
            applied += "Políticas Device Owner retiradas"
        }
        prefs.routerActive = false

        if (previous == null) return PassResult(pending, applied, kiosk = false)

        // Brillo previo.
        when {
            isOwner && owner.setBrightness(previous.brightness) &&
                owner.setBrightnessMode(previous.brightnessMode) -> applied += "Brillo restaurado"
            brightness.set(previous.brightness, previous.brightnessMode) -> applied += "Brillo restaurado"
            else -> pending += PendingAction.OpenSettings(
                SettingsTarget.WRITE_SETTINGS,
                "Activa «Modificar ajustes del sistema» para restaurar el brillo",
            )
        }

        // No Molestar previo.
        if (dnd.setFilter(previous.dndFilter)) {
            applied += "No Molestar restaurado"
        } else {
            pending += PendingAction.OpenSettings(
                SettingsTarget.DND_ACCESS,
                "Permite el acceso a «No molestar» para restaurarlo",
            )
        }

        // Energía (solo se cambió como Device Owner).
        if (isOwner) {
            owner.setScreenOffTimeout(previous.screenOffTimeout)
            owner.setStayOnWhilePluggedIn(previous.stayOnWhilePluggedIn)
        }

        // Bluetooth previo.
        if (previous.bluetoothEnabled && !bluetooth.isEnabled) {
            when {
                !bluetooth.hasConnectPermission() ->
                    pending += PendingAction.RequestPermission(Manifest.permission.BLUETOOTH_CONNECT)
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isOwner) && bluetooth.tryEnable() ->
                    applied += "Bluetooth reactivado"
                else -> pending += PendingAction.RequestBluetoothEnable
            }
        } else {
            applied += "Bluetooth en su estado previo"
        }

        return PassResult(pending, applied, kiosk = false)
    }

    /** Retira restricciones, kiosco y apps suspendidas/ocultas aplicadas como Device Owner. */
    fun releaseDeviceOwnerPolicies() {
        owner.setRestrictions(DeviceOwnerManager.SETTINGS_RESTRICTIONS, false)
        owner.setRestrictions(DeviceOwnerManager.INSTALL_RESTRICTIONS, false)
        owner.setRestrictions(DeviceOwnerManager.POWER_RESTRICTIONS, false)
        owner.releaseKiosk()
        releaseApps(prefs.suspendedPackages + prefs.hiddenPackages)
    }

    /**
     * Guarda la selección de apps. Si alguna deja de estar seleccionada y
     * estaba suspendida u oculta, se libera inmediatamente.
     */
    fun updateSelection(packages: Set<String>) {
        val removed = prefs.selectedPackages - packages
        prefs.selectedPackages = packages
        if (owner.isDeviceOwner) releaseApps(removed)
    }

    private fun releaseApps(packages: Set<String>) {
        val suspended = prefs.suspendedPackages intersect packages
        if (suspended.isNotEmpty()) {
            owner.setAppsSuspended(suspended, false)
            prefs.suspendedPackages = prefs.suspendedPackages - suspended
        }
        val hidden = prefs.hiddenPackages intersect packages
        if (hidden.isNotEmpty()) {
            owner.setAppsHidden(hidden, false)
            prefs.hiddenPackages = prefs.hiddenPackages - hidden
        }
    }

    /** Se llama cuando la restauración termina para olvidar el estado guardado. */
    fun finishRestore() {
        prefs.previousState = null
    }

    private fun snapshot() = PreviousState(
        brightness = brightness.current,
        brightnessMode = brightness.mode,
        bluetoothEnabled = bluetooth.isEnabled,
        dndFilter = dnd.currentFilter,
        screenOffTimeout = brightness.screenOffTimeout,
        stayOnWhilePluggedIn = owner.stayOnWhilePluggedIn(),
    )

    companion object {
        /** Tiempo de pantalla en Modo Router con Device Owner: 15 s. */
        const val SCREEN_OFF_TIMEOUT_MS = 15_000
    }
}
