package com.universalrouter.mode.reverse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.universalrouter.mode.R
import com.universalrouter.mode.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Tether invertido por WiFi: red WiFi Direct propia + servidor SOCKS5.
 * El PC (con el programa de la carpeta pc/) envía todo su tráfico al proxy y
 * la app lo abre desde el teléfono por los datos móviles.
 */
class ReverseTetherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var server: Socks5Server? = null
    private lateinit var group: WifiDirectGroup
    private lateinit var cellular: CellularNetworkTracker
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        group = WifiDirectGroup(this)
        cellular = CellularNetworkTracker(requireNotNull(getSystemService(ConnectivityManager::class.java)))
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        goForeground("Iniciando red WiFi Direct…")
        if (job?.isActive != true) job = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseAll()
        scope.cancel()
        ReverseTetherState.update { ReverseTetherStatus(error = it.error) }
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private suspend fun run() {
        acquireLocks()
        cellular.start()
        val creds = credentials(this)
        while (scope.isActive) {
            try {
                ReverseTetherState.update { it.copy(running = true, phase = "Creando red WiFi Direct…", error = "") }
                val info = withContext(Dispatchers.Main) { group.create(creds.first, creds.second) }
                server?.stop()
                val socks = Socks5Server(info.ownerAddress, PORT, { cellular.upstream() })
                socks.start()
                server = socks
                val address = "${info.ownerAddress.hostAddress}:$PORT"
                ReverseTetherState.update {
                    it.copy(
                        running = true,
                        phase = "Activo",
                        networkName = info.networkName,
                        passphrase = info.passphrase,
                        proxyAddress = address,
                    )
                }
                notify("Red ${info.networkName} · proxy $address")
                monitor(socks)
            } catch (e: Exception) {
                if (!scope.isActive) break
                Log.w(TAG, "Fallo", e)
                ReverseTetherState.update {
                    it.copy(phase = "Reintentando en 10 s", error = e.message ?: e.javaClass.simpleName)
                }
                notify("Error: ${e.message} · reintentando")
                delay(RETRY_MS)
            }
        }
    }

    /** Estadísticas cada 2 s y comprobación de que la red WiFi Direct sigue viva. */
    private suspend fun monitor(socks: Socks5Server) {
        var lastUp = socks.counter.uploadBytes.get()
        var lastDown = socks.counter.downloadBytes.get()
        var ticks = 0
        while (scope.isActive) {
            delay(STATS_MS)
            val up = socks.counter.uploadBytes.get()
            val down = socks.counter.downloadBytes.get()
            val clients = if (ticks % 5 == 0) {
                if (!withContext(Dispatchers.Main) { group.isAlive() }) {
                    throw IllegalStateException("La red WiFi Direct se cerró")
                }
                withContext(Dispatchers.Main) { group.clientCount() }
            } else {
                ReverseTetherState.status.value.wifiClients
            }
            ReverseTetherState.update {
                it.copy(
                    wifiClients = clients,
                    tcpConnections = socks.counter.activeTcp.get(),
                    udpSessions = socks.counter.activeUdp.get(),
                    upKbps = ((up - lastUp) * 1000 / STATS_MS / 1024).toInt(),
                    downKbps = ((down - lastDown) * 1000 / STATS_MS / 1024).toInt(),
                    totalMb = (up + down) / (1024 * 1024),
                )
            }
            lastUp = up
            lastDown = down
            ticks++
        }
    }

    private fun acquireLocks() {
        val wifi = requireNotNull(applicationContext.getSystemService(WifiManager::class.java))
        // Modo de baja latencia: evita el ahorro de energía del WiFi mientras se juega.
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "UniversalRouter:Reverse")
            .apply { setReferenceCounted(false); acquire() }
        wakeLock = requireNotNull(getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UniversalRouter:Reverse")
            .apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }
    }

    private fun releaseAll() {
        server?.stop()
        server = null
        cellular.stop()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    private fun shutdown() {
        job?.cancel()
        scope.launch {
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { group.removeGroup() }
                releaseAll()
                ReverseTetherState.update { ReverseTetherStatus() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ------------------------------------------------------------------

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "Tether invertido", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        requireNotNull(getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(this, 10, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 11, Intent(this, ReverseTetherService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Tether invertido activo")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Detener", stop)
            .build()
    }

    private fun goForeground(text: String) {
        val n = build(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notify(text: String) {
        runCatching {
            requireNotNull(getSystemService(NotificationManager::class.java)).notify(NOTIFICATION_ID, build(text))
        }
    }

    companion object {
        private const val TAG = "ReverseTether"
        private const val CHANNEL = "reverse_tether"
        private const val NOTIFICATION_ID = 43
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        const val PORT = 1080
        const val NETWORK_NAME = "DIRECT-UR-RouterMode"
        private const val RETRY_MS = 10_000L
        private const val STATS_MS = 2_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ReverseTetherService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ReverseTetherService::class.java).setAction(ACTION_STOP))
        }

        /** Nombre y contraseña fijos (se generan una vez) para que el PC se reconecte solo. */
        fun credentials(context: Context): Pair<String, String> {
            val prefs = context.getSharedPreferences("reverse_tether", Context.MODE_PRIVATE)
            val existing = prefs.getString("passphrase", null)
            if (existing != null) return NETWORK_NAME to existing
            val chars = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            val pass = (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
            prefs.edit { putString("passphrase", pass) }
            return NETWORK_NAME to pass
        }
    }
}
