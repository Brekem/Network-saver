package com.universalrouter.mode.cycle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.universalrouter.mode.R
import com.universalrouter.mode.shizuku.ShizukuManager
import com.universalrouter.mode.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date

/**
 * Servicio en primer plano con el loop infinito de refresco:
 * 5G durante [CycleConfig.intervalMinutes] → 4G durante [CycleConfig.durationSeconds] → 5G…
 *
 * Con la protección activada, antes de cada cambio mide el tráfico del
 * teléfono (incluye el del hotspot) y espera mientras se esté jugando.
 */
class NetworkCycleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private val runNow = Channel<Unit>(Channel.CONFLATED)
    private lateinit var settings: CycleSettings
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = CycleSettings(this)
        wakeLock = requireNotNull(getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UniversalRouter:NetworkCycle")
            .apply { setReferenceCounted(false) }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settings.enabled = false
            stopLoop()
            return START_NOT_STICKY
        }
        // intent == null: el sistema reinició el servicio tras cerrarlo.
        if (intent == null && !settings.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        settings.enabled = true
        goForeground("Iniciando…")
        if (loop?.isActive != true) loop = scope.launch { runLoop() }
        if (intent?.action == ACTION_RUN_NOW) runNow.trySend(Unit)
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        NetworkCycleState.update { it.copy(running = false, phase = "Detenido", nextCycleAtMillis = 0) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Loop
    // ------------------------------------------------------------------

    private suspend fun runLoop() {
        NetworkCycleState.update { it.copy(running = true) }
        restorePendingIfAny()
        while (scope.isActive) {
            val config = settings.config
            // Mantiene la CPU despierta (con el hotspot encendido apenas consume extra).
            wakeLock.acquire((config.intervalMinutes + 10) * 60_000L)

            val forced = waitUntilNextCycle(config)
            if (!forced && config.protectGaming) waitWhileBusy(config)

            val ok = runCycle(config)
            // Si falló (p. ej. Shizuku aún no listo tras reiniciar), reintenta en 1 minuto.
            if (!ok) waitFor(RETRY_MS)
        }
    }

    /** Espera el intervalo en 5G. Devuelve true si el usuario pidió "Probar ahora". */
    private suspend fun waitUntilNextCycle(config: CycleConfig): Boolean {
        val wakeAt = System.currentTimeMillis() + config.intervalMinutes * 60_000L
        val time = formatTime(wakeAt)
        NetworkCycleState.update { it.copy(phase = "En 5G · próximo cambio a las $time", nextCycleAtMillis = wakeAt) }
        notify("En 5G · próximo refresco a las $time")
        while (true) {
            val remaining = wakeAt - System.currentTimeMillis()
            if (remaining <= 0) return false
            val signal = withTimeoutOrNull(minOf(remaining, DISPLAY_REFRESH_MS)) { runNow.receive() }
            if (signal != null) return true
            val kbps = measureKbps(2_000)
            NetworkCycleState.update { it.copy(trafficKbps = kbps) }
        }
    }

    private suspend fun waitWhileBusy(config: CycleConfig) {
        while (true) {
            val kbps = measureKbps(MEASURE_MS)
            NetworkCycleState.update { it.copy(trafficKbps = kbps) }
            if (kbps < config.busyThresholdKbps) return
            val msg = "Jugando ($kbps KB/s): refresco en espera"
            NetworkCycleState.update { it.copy(phase = msg) }
            notify(msg)
            if (waitFor(BUSY_RECHECK_MS)) return // "Probar ahora" fuerza el cambio
        }
    }

    /** Espera [ms]; devuelve true si llega "Probar ahora" antes. */
    private suspend fun waitFor(ms: Long): Boolean =
        withTimeoutOrNull(ms) { runNow.receive() } != null

    private suspend fun runCycle(config: CycleConfig): Boolean {
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            return result("Error: no hay SIM de datos", ok = false)
        }
        return try {
            val shell = ShizukuManager.shell(this)
            val original = shell.getAllowedNetworkTypes(subId)
            if (original and NR == 0L) {
                return result("5G no está activado en Ajustes › Modo de red: no hay nada que refrescar", ok = true)
            }
            settings.pendingRestoreSubId = subId
            settings.pendingRestore = original
            NetworkCycleState.update { it.copy(phase = "En 4G (${config.durationSeconds} s)…") }
            notify("Refrescando: 4G durante ${config.durationSeconds} s")
            val start = SystemClock.elapsedRealtime()
            try {
                shell.setAllowedNetworkTypes(subId, original and NR.inv())
                delay(config.durationSeconds * 1_000L)
            } finally {
                withContext(NonCancellable) {
                    shell.setAllowedNetworkTypes(subId, original)
                    settings.pendingRestore = null
                }
            }
            val secs = (SystemClock.elapsedRealtime() - start) / 1000
            NetworkCycleState.update { it.copy(cyclesDone = it.cyclesDone + 1) }
            result("Refresco OK a las ${formatTime(System.currentTimeMillis())} ($secs s en 4G)", ok = true)
        } catch (e: Exception) {
            Log.w(TAG, "Ciclo fallido", e)
            result("Error: ${e.message ?: e.javaClass.simpleName}", ok = false)
        }
    }

    private suspend fun restorePendingIfAny() {
        val original = settings.pendingRestore ?: return
        try {
            ShizukuManager.shell(this).setAllowedNetworkTypes(settings.pendingRestoreSubId, original)
            settings.pendingRestore = null
            result("5G restaurado tras un cierre inesperado", ok = true)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo restaurar", e)
        }
    }

    private fun result(text: String, ok: Boolean): Boolean {
        NetworkCycleState.update {
            if (ok) it.copy(lastResult = text)
            else it.copy(lastResult = text, phase = "Reintentando en 1 min…")
        }
        if (!ok) notify(text)
        return ok
    }

    /** KB/s totales del teléfono (datos móviles + hotspot) durante [windowMs]. */
    private suspend fun measureKbps(windowMs: Long): Int {
        val before = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
        delay(windowMs)
        val after = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
        if (before < 0 || after < before) return 0
        return ((after - before) * 1000 / windowMs / 1024).toInt()
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
        scope.launch {
            // Garantiza que el teléfono no se quede en 4G.
            withContext(NonCancellable) { restorePendingIfAny() }
            withContext(Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ------------------------------------------------------------------
    // Notificación
    // ------------------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Refresco de red 5G/4G", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        requireNotNull(getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NetworkCycleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Refresco 5G/4G activo")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Detener", stop)
            .build()
    }

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notify(text: String) {
        runCatching {
            requireNotNull(getSystemService(NotificationManager::class.java))
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun formatTime(millis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

    companion object {
        private const val TAG = "NetworkCycle"
        private const val CHANNEL = "network_cycle"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ACTION_RUN_NOW = "run_now"
        private const val NR = TelephonyManager.NETWORK_TYPE_BITMASK_NR
        private const val MEASURE_MS = 10_000L
        private const val BUSY_RECHECK_MS = 60_000L
        private const val RETRY_MS = 60_000L
        private const val DISPLAY_REFRESH_MS = 30_000L

        fun start(context: Context) = send(context, ACTION_START)
        fun runNow(context: Context) = send(context, ACTION_RUN_NOW)
        fun stop(context: Context) {
            context.startService(Intent(context, NetworkCycleService::class.java).setAction(ACTION_STOP))
        }

        private fun send(context: Context, action: String) {
            ContextCompat.startForegroundService(
                context, Intent(context, NetworkCycleService::class.java).setAction(action),
            )
        }
    }
}
