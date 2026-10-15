package com.universalrouter.mode.ui

import android.app.ActivityManager
import android.bluetooth.BluetoothAdapter
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.universalrouter.mode.R
import com.universalrouter.mode.admin.PermissionLevel
import com.universalrouter.mode.databinding.ActivityMainBinding
import com.universalrouter.mode.databinding.ItemStatusRowBinding
import com.universalrouter.mode.domain.PendingAction
import com.universalrouter.mode.helpers.OemSettingsNavigator
import com.universalrouter.mode.helpers.TetherMethod
import com.universalrouter.mode.shizuku.ShizukuManager
import com.universalrouter.mode.shizuku.ShizukuState
import rikka.shizuku.Shizuku
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val navigator by lazy { OemSettingsNavigator(viewModel.device) }

    /** Evita que los listeners de los switches reaccionen al actualizar la UI. */
    private var bindingOptions = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) toast(getString(R.string.permission_denied))
            // onResume continuará el flujo.
        }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> viewModel.requestRefresh() }

    private val bluetoothEnableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        setupCycleListeners()
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.events.collect(::handleEvent) }
                // Hotspot/USB no emiten broadcasts públicos: se sondea solo
                // mientras la app está visible (sin coste en segundo plano).
                launch {
                    while (true) {
                        delay(REFRESH_MS)
                        viewModel.requestRefresh()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (savedInstanceState == null) handleActionIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleActionIntent(intent)
    }

    /**
     * Acciones de un toque, por ejemplo desde ShizukuAutoStart en Windows:
     * adb shell am start -n com.universalrouter.mode/.ui.MainActivity -a com.universalrouter.mode.action.ACTIVATE [--es method usb|wifi]
     */
    private fun handleActionIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_ACTIVATE -> viewModel.onActivateClicked(
                when (intent.getStringExtra(EXTRA_METHOD)) {
                    "usb" -> TetherMethod.USB
                    "wifi" -> TetherMethod.WIFI
                    else -> null
                },
            )
            ACTION_RESTORE -> viewModel.onRestoreClicked()
            else -> return
        }
        // Que una rotación o volver a la app no repita la acción.
        intent.action = Intent.ACTION_MAIN
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResumed()
    }

    private fun setupListeners() = with(binding) {
        btnActivate.setOnClickListener { viewModel.onActivateClicked() }
        btnRestore.setOnClickListener { viewModel.onRestoreClicked() }
        btnUsbTethering.setOnClickListener { viewModel.onUsbTetheringClicked() }
        btnHotspot.setOnClickListener { viewModel.onHotspotClicked() }
        btnSelectApps.setOnClickListener { showAppPicker() }
        btnBackgroundManager.setOnClickListener { viewModel.onBackgroundManagerClicked() }
        btnRemoveOwner.setOnClickListener { confirmRemoveOwner() }

        listOf(swKiosk, swSuspend, swHide, swBlockSettings, swBlockInstall, swPower).forEach { sw ->
            sw.setOnCheckedChangeListener { _, _ ->
                if (bindingOptions) return@setOnCheckedChangeListener
                viewModel.setDeviceOwnerOptions(
                    viewModel.state.value.deviceOwnerOptions.copy(
                        kiosk = swKiosk.isChecked,
                        suspendApps = swSuspend.isChecked,
                        hideApps = swHide.isChecked,
                        blockSettings = swBlockSettings.isChecked,
                        blockInstall = swBlockInstall.isChecked,
                        powerPolicies = swPower.isChecked,
                    ),
                )
            }
        }
    }

    private fun setupCycleListeners() = with(binding) {
        swCycle.setOnCheckedChangeListener { _, checked ->
            if (bindingOptions) return@setOnCheckedChangeListener
            if (checked) requestNotificationPermission()
            if (!viewModel.setCycleEnabled(checked)) {
                bindingOptions = true
                swCycle.isChecked = false
                bindingOptions = false
                Snackbar.make(root, R.string.cycle_needs_shizuku, Snackbar.LENGTH_LONG).show()
            }
        }
        btnCycleNow.setOnClickListener {
            requestNotificationPermission()
            if (!viewModel.runCycleNow()) {
                Snackbar.make(root, R.string.cycle_needs_shizuku, Snackbar.LENGTH_LONG).show()
            }
        }
        btnShizuku.setOnClickListener { onShizukuButton() }
        btnBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        swProtect.setOnCheckedChangeListener { _, checked ->
            if (bindingOptions) return@setOnCheckedChangeListener
            viewModel.setCycleConfig(viewModel.state.value.cycleConfig.copy(protectGaming = checked))
        }
        sliderInterval.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.setCycleConfig(viewModel.state.value.cycleConfig.copy(intervalMinutes = value.toInt()))
        }
        sliderDuration.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.setCycleConfig(viewModel.state.value.cycleConfig.copy(durationSeconds = value.toInt()))
        }
        sliderThreshold.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.setCycleConfig(viewModel.state.value.cycleConfig.copy(busyThresholdKbps = value.toInt()))
        }
    }

    private fun onShizukuButton() {
        when (viewModel.state.value.shizukuState) {
            ShizukuState.NOT_INSTALLED -> {
                val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuManager.PACKAGE}"))
                try {
                    startActivity(market)
                } catch (e: Exception) {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")),
                    )
                }
            }
            ShizukuState.NOT_RUNNING ->
                packageManager.getLaunchIntentForPackage(ShizukuManager.PACKAGE)?.let { startActivity(it) }
            ShizukuState.NO_PERMISSION -> ShizukuManager.requestPermission()
            ShizukuState.READY -> Unit
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun renderCycle(state: RouterUiState) = with(binding) {
        cycleShizukuState.setText(
            when (state.shizukuState) {
                ShizukuState.NOT_INSTALLED -> R.string.shizuku_not_installed
                ShizukuState.NOT_RUNNING -> R.string.shizuku_not_running
                ShizukuState.NO_PERMISSION -> R.string.shizuku_no_permission
                ShizukuState.READY -> R.string.shizuku_ready
            },
        )
        btnShizuku.isVisible = state.shizukuState != ShizukuState.READY
        btnShizuku.setText(
            when (state.shizukuState) {
                ShizukuState.NOT_INSTALLED -> R.string.shizuku_install
                ShizukuState.NOT_RUNNING -> R.string.shizuku_open
                else -> R.string.shizuku_grant
            },
        )
        val c = state.cycleConfig
        val st = state.cycleStatus
        swCycle.isChecked = state.cycleEnabled
        swProtect.isChecked = c.protectGaming
        if (sliderInterval.value.toInt() != c.intervalMinutes) sliderInterval.value = c.intervalMinutes.toFloat()
        if (sliderDuration.value.toInt() != c.durationSeconds) sliderDuration.value = c.durationSeconds.toFloat()
        if (sliderThreshold.value.toInt() != c.busyThresholdKbps) sliderThreshold.value = c.busyThresholdKbps.toFloat()
        labelInterval.text = getString(R.string.cycle_interval, c.intervalMinutes)
        labelDuration.text = getString(R.string.cycle_duration, c.durationSeconds)
        labelThreshold.text = getString(R.string.cycle_threshold, c.busyThresholdKbps, st.trafficKbps)
        labelThreshold.isVisible = c.protectGaming
        sliderThreshold.isVisible = c.protectGaming
        cycleStatus.text = buildString {
            append(if (state.cycleEnabled) st.phase else "Detenido")
            if (st.lastResult.isNotEmpty()) append("\nÚltimo: ").append(st.lastResult)
            if (st.cyclesDone > 0) append("\nRefrescos en esta sesión: ").append(st.cyclesDone)
        }
        btnBattery.isVisible = !state.batteryUnrestricted
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    private fun render(state: RouterUiState) = with(binding) {
        progress.isVisible = state.busy
        btnActivate.isEnabled = !state.busy
        btnRestore.isEnabled = !state.busy

        val (title, subtitle, color) = when (state.indicator) {
            Indicator.ACTIVE -> Triple(R.string.indicator_active, R.string.indicator_active_sub, R.color.status_ok)
            Indicator.INCOMPLETE ->
                Triple(R.string.indicator_incomplete, R.string.indicator_incomplete_sub, R.color.status_error)
            Indicator.INACTIVE ->
                Triple(R.string.indicator_inactive, R.string.indicator_inactive_sub, R.color.status_idle_container)
        }
        indicatorTitle.setText(title)
        indicatorSubtitle.setText(subtitle)
        indicatorCard.setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, color))

        deviceSummary.text = state.deviceSummary

        usbState.text = getString(
            when {
                state.usbTetheringActive -> R.string.usb_tethering_active
                state.usbConnected -> R.string.usb_connected
                else -> R.string.usb_not_connected
            },
        )
        btnUsbTethering.isVisible = state.usbConnected && !state.usbTetheringActive
        hotspotTitle.text = state.hotspotTitle
        hotspotDetail.text = state.hotspotDetail

        renderRows(state.rows)

        selectedApps.text = getString(R.string.selected_apps, state.selectedAppsCount)

        permissionLevel.setText(
            when (state.permissionLevel) {
                PermissionLevel.DEVICE_OWNER -> R.string.level_owner
                PermissionLevel.DEVICE_ADMIN -> R.string.level_admin
                PermissionLevel.STANDARD -> R.string.level_standard
            },
        )
        deviceOwnerHint.isVisible = !state.isDeviceOwner
        deviceOwnerOptions.isVisible = state.isDeviceOwner
        bindingOptions = true
        renderCycle(state)
        state.deviceOwnerOptions.let {
            swKiosk.isChecked = it.kiosk
            swSuspend.isChecked = it.suspendApps
            swHide.isChecked = it.hideApps
            swBlockSettings.isChecked = it.blockSettings
            swBlockInstall.isChecked = it.blockInstall
            swPower.isChecked = it.powerPolicies
        }
        bindingOptions = false

        logTitle.isVisible = state.log.isNotEmpty()
        logText.isVisible = state.log.isNotEmpty()
        logText.text = state.log.joinToString("\n") { "✓ $it" }
    }

    private fun renderRows(rows: List<StatusRow>) {
        val container = binding.statusContainer
        // Reutiliza las vistas existentes para no re-inflar cada 2 segundos.
        while (container.childCount > rows.size) container.removeViewAt(container.childCount - 1)
        rows.forEachIndexed { index, row ->
            val rowBinding = container.getChildAt(index)?.let { ItemStatusRowBinding.bind(it) }
                ?: ItemStatusRowBinding.inflate(layoutInflater, container, true)
            rowBinding.stateIcon.text = when (row.state) {
                RowState.OK -> "✅"
                RowState.FAIL -> "❌"
                RowState.INFO -> "ℹ️"
            }
            rowBinding.title.text = "${row.emoji}  ${row.title}"
            rowBinding.detail.text = row.detail
            val clickable = row.fixTarget != null
            rowBinding.chevron.visibility = if (clickable) View.VISIBLE else View.INVISIBLE
            rowBinding.root.isClickable = clickable
            rowBinding.root.setOnClickListener(if (clickable) View.OnClickListener { viewModel.onRowClicked(row) } else null)
        }
    }

    // ------------------------------------------------------------------
    // Eventos
    // ------------------------------------------------------------------

    private fun handleEvent(event: UiEvent) {
        when (event) {
            is UiEvent.Execute -> execute(event.action)
            UiEvent.StartKiosk -> startKiosk()
            UiEvent.StopKiosk -> stopKiosk()
            is UiEvent.Message -> Snackbar.make(binding.root, event.text, Snackbar.LENGTH_LONG).show()
        }
    }

    private fun execute(action: PendingAction) {
        when (action) {
            is PendingAction.OpenSettings -> {
                // En kiosco no se pueden abrir otras apps: se sale temporalmente
                // y se vuelve a entrar al terminar el flujo.
                stopKiosk()
                if (navigator.launch(this, action.target)) {
                    // Los toasts de texto se muestran sobre Ajustes y guían el toque.
                    toast(action.guidance, long = true)
                }
            }
            is PendingAction.RequestPermission -> permissionLauncher.launch(action.permission)
            PendingAction.RequestBluetoothEnable ->
                try {
                    bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (e: SecurityException) {
                    toast(getString(R.string.permission_denied))
                }
        }
    }

    private fun startKiosk() {
        if (!isInLockTask()) {
            try {
                startLockTask()
            } catch (e: IllegalArgumentException) {
                // Sin permiso de lock task (no Device Owner): se ignora.
            }
        }
    }

    private fun stopKiosk() {
        if (isInLockTask()) {
            try {
                stopLockTask()
            } catch (e: Exception) {
                // Ignorado
            }
        }
    }

    private fun isInLockTask(): Boolean =
        requireNotNull(getSystemService(ActivityManager::class.java)).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    // ------------------------------------------------------------------
    // Diálogos
    // ------------------------------------------------------------------

    private fun showAppPicker() {
        lifecycleScope.launch {
            val (apps, selected) = viewModel.loadApps()
            if (apps.isEmpty()) {
                toast(getString(R.string.pick_apps_empty))
                return@launch
            }
            val suffix = getString(R.string.system_app_suffix)
            val labels = apps.map { if (it.isSystem) "${it.label} $suffix" else it.label }.toTypedArray()
            val checked = apps.map { it.packageName in selected }.toBooleanArray()
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.pick_apps_title)
                .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.ok) { _, _ ->
                    viewModel.saveSelection(
                        apps.filterIndexed { i, _ -> checked[i] }.map { it.packageName }.toSet(),
                    )
                }
                .show()
        }
    }

    private fun confirmRemoveOwner() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_owner_title)
            .setMessage(R.string.remove_owner_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ -> viewModel.removeDeviceOwner() }
            .show()
    }

    private fun toast(text: String, long: Boolean = false) {
        Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REFRESH_MS = 2_000L
        const val ACTION_ACTIVATE = "com.universalrouter.mode.action.ACTIVATE"
        const val ACTION_RESTORE = "com.universalrouter.mode.action.RESTORE"
        // Extra opcional de ACTIVATE: "usb" o "wifi" (por defecto USB si hay cable).
        const val EXTRA_METHOD = "method"
    }
}
