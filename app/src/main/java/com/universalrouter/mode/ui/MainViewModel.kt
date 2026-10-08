package com.universalrouter.mode.ui

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.universalrouter.mode.admin.PermissionLevel
import com.universalrouter.mode.data.DeviceOwnerOptions
import com.universalrouter.mode.data.RouterPreferences
import com.universalrouter.mode.domain.PassResult
import com.universalrouter.mode.domain.PendingAction
import com.universalrouter.mode.domain.RouterModeController
import com.universalrouter.mode.helpers.DeviceDetector
import com.universalrouter.mode.helpers.InstalledApp
import com.universalrouter.mode.helpers.SettingsTarget
import com.universalrouter.mode.helpers.TetheringHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Eventos de una sola vez que la Activity debe ejecutar. */
sealed interface UiEvent {
    data class Execute(val action: PendingAction) : UiEvent
    data object StartKiosk : UiEvent
    data object StopKiosk : UiEvent
    data class Message(val text: String) : UiEvent
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val device = DeviceDetector.detect()
    private val prefs = RouterPreferences(app)
    private val controller = RouterModeController(app, device, prefs)

    private val _state = MutableStateFlow(RouterUiState())
    val state: StateFlow<RouterUiState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private enum class Phase { IDLE, ACTIVATING, RESTORING }

    @Volatile
    private var phase = Phase.IDLE
    /** Acciones ya abiertas en esta sesión: cada pantalla se abre automáticamente una sola vez. */
    private val attempted = mutableSetOf<String>()
    private val passMutex = Mutex()

    init {
        requestRefresh()
    }

    // ------------------------------------------------------------------
    // Acciones de la UI
    // ------------------------------------------------------------------

    fun onActivateClicked() {
        phase = Phase.ACTIVATING
        attempted.clear()
        runPass()
    }

    fun onRestoreClicked() {
        phase = Phase.RESTORING
        attempted.clear()
        _events.trySend(UiEvent.StopKiosk)
        runPass()
    }

    /** Llamado en onResume: continúa el flujo tras volver de una pantalla de Ajustes. */
    fun onResumed() {
        if (phase != Phase.IDLE) {
            runPass()
            return
        }
        requestRefresh()
        // Dispositivo dedicado: al volver a la app (o tras reiniciar, como
        // launcher) se vuelve a fijar el kiosco si el Modo Router sigue activo.
        val owner = controller.owner
        if (prefs.routerActive && prefs.deviceOwnerOptions.kiosk && owner.isDeviceOwner && owner.isKioskPermitted()) {
            _events.trySend(UiEvent.StartKiosk)
        }
    }

    /** Refresco del panel fuera del hilo principal. */
    fun requestRefresh() {
        viewModelScope.launch(Dispatchers.IO) { refresh() }
    }

    fun onRowClicked(row: StatusRow) {
        val target = row.fixTarget ?: return
        val action = when (target) {
            SettingsTarget.WIFI_HOTSPOT -> controller.tetheringAction(usb = false)
            SettingsTarget.USB_TETHERING -> controller.tetheringAction(usb = true)
            else -> PendingAction.OpenSettings(target, row.detail)
        }
        _events.trySend(UiEvent.Execute(action))
    }

    fun onUsbTetheringClicked() {
        _events.trySend(UiEvent.Execute(controller.tetheringAction(usb = true)))
    }

    fun onHotspotClicked() {
        _events.trySend(UiEvent.Execute(controller.tetheringAction(usb = false)))
    }

    fun onBackgroundManagerClicked() {
        _events.trySend(
            UiEvent.Execute(
                PendingAction.OpenSettings(
                    SettingsTarget.BACKGROUND_MANAGER,
                    "Gestión de segundo plano de ${device.romLabel}",
                ),
            ),
        )
    }

    suspend fun loadApps(): Pair<List<InstalledApp>, Set<String>> = withContext(Dispatchers.IO) {
        val selected = prefs.selectedPackages
        controller.apps.launchableApps(alsoInclude = selected) to selected
    }

    fun saveSelection(packages: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            controller.updateSelection(packages)
            refresh()
            _events.send(
                UiEvent.Message(
                    if (prefs.routerActive) "Selección guardada. Pulsa ACTIVAR para aplicarla."
                    else "Selección guardada (${packages.size} apps).",
                ),
            )
        }
    }

    fun setDeviceOwnerOptions(options: DeviceOwnerOptions) {
        prefs.deviceOwnerOptions = options
        _state.update { it.copy(deviceOwnerOptions = options) }
    }

    fun removeDeviceOwner() {
        viewModelScope.launch(Dispatchers.IO) {
            _events.send(UiEvent.StopKiosk)
            controller.releaseDeviceOwnerPolicies()
            val ok = controller.owner.clearDeviceOwner()
            refresh()
            _events.send(
                UiEvent.Message(
                    if (ok) "Device Owner retirado. La app funciona en modo estándar."
                    else "No se pudo retirar Device Owner.",
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Flujo activar / restaurar
    // ------------------------------------------------------------------

    private fun runPass() {
        viewModelScope.launch(Dispatchers.IO) {
            passMutex.withLock {
                val current = phase
                if (current == Phase.IDLE) return@withLock
                _state.update { it.copy(busy = true) }
                val result = if (current == Phase.ACTIVATING) {
                    controller.activationPass()
                } else {
                    controller.restorePass()
                }
                handleResult(current, result)
                refresh(result.applied)
            }
        }
    }

    private suspend fun handleResult(current: Phase, result: PassResult) {
        val next = result.pending.firstOrNull { it.id !in attempted }
        if (next != null) {
            attempted += next.id
            _events.send(UiEvent.Execute(next))
            return
        }
        // No quedan pantallas por abrir: fin del flujo.
        phase = Phase.IDLE
        val skipped = result.pending.size
        if (current == Phase.ACTIVATING) {
            if (result.kiosk) _events.send(UiEvent.StartKiosk)
            _events.send(
                UiEvent.Message(
                    if (skipped == 0) "Modo Router aplicado"
                    else "Modo Router aplicado con $skipped paso(s) pendiente(s): toca las filas en rojo",
                ),
            )
        } else {
            if (skipped == 0) controller.finishRestore()
            _events.send(
                UiEvent.Message(
                    if (skipped == 0) "Estado previo restaurado"
                    else "Restauración incompleta: $skipped paso(s) pendiente(s). Pulsa RESTAURAR de nuevo.",
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Estado
    // ------------------------------------------------------------------

    fun refresh(newLog: List<String>? = null) {
        val app = getApplication<Application>()
        val tether = TetheringHelper.status(app)
        val bt = controller.bluetooth
        val btOff = !bt.isEnabled
        val powerSave = controller.power.isPowerSaveMode
        val dnd = controller.dnd
        val brightness = controller.brightness
        val level = controller.owner.level
        val active = prefs.routerActive
        val selected = prefs.selectedPackages

        val brightnessOk = brightness.isLow
        val tetherOk = tether.anyActive
        val allOk = tetherOk && btOff && powerSave && dnd.isActive && brightnessOk

        val hotspotDetail = tether.wifiHotspot?.let { "Activa · interfaz ${it.name} · IP ${it.ipv4}" }
            ?: "Inactiva — toca para abrir «${device.hotspotLabel}»"

        val rows = buildList {
            add(
                StatusRow(
                    "📶", "WiFi Hotspot",
                    if (tether.wifiHotspotActive) RowState.OK else if (tether.usbTetheringActive) RowState.INFO else RowState.FAIL,
                    hotspotDetail, SettingsTarget.WIFI_HOTSPOT,
                ),
            )
            add(
                StatusRow(
                    "🔌", "USB Tethering",
                    when {
                        tether.usbTetheringActive -> RowState.OK
                        tether.usbConnected -> RowState.FAIL
                        else -> RowState.INFO
                    },
                    tether.usbTethering?.let { "Activo · interfaz ${it.name} · IP ${it.ipv4}" }
                        ?: if (tether.usbConnected) "Inactivo — toca para activar «${device.usbTetheringLabel}»"
                        else "Sin cable USB de datos",
                    SettingsTarget.USB_TETHERING,
                ),
            )
            add(
                StatusRow(
                    "🔗", "USB conectado",
                    if (tether.usbConnected) RowState.OK else RowState.INFO,
                    if (tether.usbConnected) "USB conectado" else "No",
                ),
            )
            add(
                StatusRow(
                    "🅱️", "Bluetooth",
                    if (btOff) RowState.OK else RowState.FAIL,
                    when {
                        !bt.isAvailable -> "No disponible en este dispositivo"
                        btOff -> "Desactivado"
                        device.canToggleBluetoothDirectly || level == PermissionLevel.DEVICE_OWNER ->
                            "Activado — se apagará al activar"
                        else -> "Activado — Android ${device.release} requiere apagarlo en Ajustes"
                    },
                    SettingsTarget.BLUETOOTH,
                ),
            )
            add(
                StatusRow(
                    "🔋", "Ahorro de energía",
                    if (powerSave) RowState.OK else RowState.FAIL,
                    if (powerSave) "Activo" else "Inactivo — toca para activarlo",
                    SettingsTarget.BATTERY_SAVER,
                ),
            )
            add(
                StatusRow(
                    "🌙", "No Molestar",
                    if (dnd.isActive) RowState.OK else RowState.FAIL,
                    when {
                        dnd.isActive -> "Activo"
                        !dnd.hasAccess -> "Sin acceso — toca para conceder permiso"
                        else -> "Inactivo"
                    },
                    if (dnd.hasAccess) null else SettingsTarget.DND_ACCESS,
                ),
            )
            add(
                StatusRow(
                    "🔆", "Brillo",
                    if (brightnessOk) RowState.OK else RowState.FAIL,
                    buildString {
                        append("${brightness.percent}%")
                        if (brightness.mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) append(" (automático)")
                        if (!brightness.canWrite && level != PermissionLevel.DEVICE_OWNER) {
                            append(" — toca para permitir modificar ajustes")
                        }
                    },
                    if (brightness.canWrite) null else SettingsTarget.WRITE_SETTINGS,
                ),
            )
            add(
                StatusRow(
                    "🧊", "Apps restringidas",
                    RowState.INFO,
                    when {
                        selected.isEmpty() -> "Ninguna seleccionada"
                        level == PermissionLevel.DEVICE_OWNER ->
                            "${selected.size} seleccionadas · ${prefs.suspendedPackages.size} suspendidas · " +
                                "${prefs.hiddenPackages.size} ocultas"
                        else -> "${selected.size} seleccionadas · segundo plano reducido al activar"
                    },
                ),
            )
            add(
                StatusRow(
                    "✨", "Optimización completada",
                    if (active && allOk) RowState.OK else if (active) RowState.FAIL else RowState.INFO,
                    when {
                        !active -> "Modo Router desactivado"
                        allOk -> "Todo listo"
                        else -> "Faltan pasos: toca las filas en rojo"
                    },
                ),
            )
        }

        val deviceName = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)
        _state.update {
            it.copy(
                deviceSummary = device.summary,
                permissionLevel = level,
                indicator = when {
                    active && allOk -> Indicator.ACTIVE
                    active -> Indicator.INCOMPLETE
                    else -> Indicator.INACTIVE
                },
                routerActive = active,
                usbConnected = tether.usbConnected,
                hotspotTitle = if (tether.wifiHotspotActive) "Zona WiFi activa" else "Zona WiFi inactiva",
                hotspotDetail = buildString {
                    append(hotspotDetail)
                    // Android no expone el SSID a apps normales; la mayoría de
                    // fabricantes usan el nombre del dispositivo por defecto.
                    if (!deviceName.isNullOrBlank()) {
                        append("\nNombre de red por defecto habitual: «$deviceName» (se cambia en Ajustes)")
                    }
                },
                usbTetheringActive = tether.usbTetheringActive,
                rows = rows,
                selectedAppsCount = selected.size,
                deviceOwnerOptions = prefs.deviceOwnerOptions,
                busy = phase != Phase.IDLE,
                log = newLog ?: it.log,
            )
        }
    }
}
