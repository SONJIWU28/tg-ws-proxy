package com.tgwsproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.tgwsproxy.R
import com.tgwsproxy.proxy.ProxyServer
import com.tgwsproxy.proxy.TelegramDC
import com.tgwsproxy.ui.MainActivity
import java.util.concurrent.atomic.AtomicLong

/**
 * Foreground service hosting the MTProto proxy.
 *
 * Stability improvements vs previous version:
 *
 *   1) Health check uses ACTUAL byte movement (ProxyServer.stats.lastTrafficAt),
 *      not a periodic stats tick. A stalled transport is caught in 12s / 25s
 *      instead of 60+ s.
 *
 *   2) Exponential backoff on auto-restarts: 1s -> 2s -> 4s -> 8s -> 16s -> 30s cap.
 *      In a tunnel/elevator we stop burning battery on restart storms.
 *
 *   3) Network-availability gating. On onLost the server is paused, not restarted.
 *      When onAvailable fires, we force-restart with the failure counter reset.
 *
 *   4) When active connections == 0, traffic silence is NOT treated as a stall
 *      (idle Telegram = no packets = normal).
 */
class ProxyForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "tg_mtproxy_channel"
        private const val NOTIFICATION_ID = 1

        private const val AUTO_RESTART_BASE_MS = 1000L
        private const val AUTO_RESTART_MAX_MS = 30_000L
        private const val MANUAL_RESTART_DELAY_MS = 400L

        private const val HEALTH_CHECK_INTERVAL_MS = 5_000L

        private const val STALL_SOFT_MS = 12_000L
        private const val STALL_HARD_MS = 25_000L

        private const val MIN_RESTART_GAP_MS = 1_500L
        private const val SCREEN_OFF_PAUSE_DELAY_MS = 5 * 60 * 1000L
        private const val STOP_WAIT_BEFORE_RESTART_MS = 350L

        private const val BACKOFF_CAP_FAILURES = 5

        const val ACTION_START = "com.tgwsproxy.START"
        const val ACTION_STOP = "com.tgwsproxy.STOP"
        const val ACTION_RESTART = "com.tgwsproxy.RESTART"
        const val ACTION_STATUS_CHANGED = "com.tgwsproxy.STATUS_CHANGED"

        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_SECRET = "secret"
        const val EXTRA_DC_CONFIG = "dc_config"
        const val EXTRA_CFPROXY_ENABLED = "cfproxy_enabled"
        const val EXTRA_CFPROXY_DOMAIN = "cfproxy_domain"
        const val EXTRA_BATTERY_SAVER = "battery_saver"

        const val EXTRA_RUNNING = "running"
        const val EXTRA_STATE = "state"
        const val EXTRA_STATUS_TEXT = "status_text"
        const val EXTRA_RESTARTING = "restarting"
        const val EXTRA_USER_STOPPED = "user_stopped"
        const val EXTRA_LAST_ERROR = "last_error"

        const val STATE_STOPPED = "stopped"
        const val STATE_STARTING = "starting"
        const val STATE_RUNNING = "running"
        const val STATE_RESTARTING = "restarting"
        const val STATE_STOPPING = "stopping"
        const val STATE_PAUSED = "paused"
        const val STATE_ERROR = "error"

        var instance: ProxyForegroundService? = null
            private set

        fun startService(
            context: Context,
            host: String,
            port: Int,
            secret: String,
            dcConfig: String,
            cfproxyEnabled: Boolean,
            cfproxyDomain: String,
            batterySaver: Boolean
        ) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_HOST, host)
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_SECRET, secret)
                putExtra(EXTRA_DC_CONFIG, dcConfig)
                putExtra(EXTRA_CFPROXY_ENABLED, cfproxyEnabled)
                putExtra(EXTRA_CFPROXY_DOMAIN, cfproxyDomain)
                putExtra(EXTRA_BATTERY_SAVER, batterySaver)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun restartService(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java).apply {
                action = ACTION_RESTART
            }
            context.startService(intent)
        }
    }

    private var proxyServer: ProxyServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    private var batterySaver = false
    private var screenOff = false
    private var pausedForBattery = false
    private var pausedForNetwork = false
    private var userStopped = false
    private var restartScheduled = false
    private var restartInFlight = false

    private var currentState = STATE_STOPPED
    private var currentStatusText = "Остановлен"
    private var lastError = ""

    private val restartSeq = AtomicLong(0L)
    private var consecutiveFailures = 0
    private var lastStartAttemptAt = 0L
    private var lastRestartAt = 0L
    private var lastSuccessAt = 0L

    private var lastHost = "127.0.0.1"
    private var lastPort = 1443
    private var lastSecret = ""
    private var lastDcRaw = ""
    private var lastCfproxyEnabled = true
    private var lastCfproxyDomain = ""

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff = true
                    if (batterySaver) {
                        handler.postDelayed(pauseRunnable, SCREEN_OFF_PAUSE_DELAY_MS)
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOff = false
                    handler.removeCallbacks(pauseRunnable)
                    if (pausedForBattery) {
                        pausedForBattery = false
                        userStopped = false
                        setState(STATE_RESTARTING, "Возобновление прокси...", restarting = true)
                        scheduleRestart("screen_on_resume", MANUAL_RESTART_DELAY_MS, force = true)
                    }
                }
            }
        }
    }

    private val pauseRunnable = Runnable {
        if (screenOff && batterySaver && proxyServer?.isRunning() == true) {
            pausedForBattery = true
            restartScheduled = false
            restartInFlight = false
            setState(STATE_PAUSED, "Пауза (экран выключен)")
            stopProxyInternal(releaseLocks = true)
        }
    }

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            try {
                checkHealth()
            } finally {
                handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
            }
        }
    }

    private fun checkHealth() {
        if (userStopped || pausedForBattery || pausedForNetwork) return
        val server = proxyServer ?: return
        if (!server.isRunning()) return

        val stats = server.stats
        val active = stats.connectionsActive.get()
        if (active <= 0) return  // idle == normal

        val now = System.currentTimeMillis()
        val lastTraffic = stats.lastTrafficAt.get()
        val quietFor = now - lastTraffic

        when {
            quietFor >= STALL_HARD_MS -> {
                setState(STATE_RESTARTING,
                    "Трафик замер (${quietFor / 1000}s), полный перезапуск...",
                    restarting = true)
                scheduleRestart("health_hard_stall", 200L, force = true)
            }
            quietFor >= STALL_SOFT_MS -> {
                currentStatusText = "Мягкая очистка транспорта (${quietFor / 1000}s)"
                updateNotification(currentStatusText)
                broadcastStatus(running = true, restarting = true)
                runCatching { server.clearTransportState() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        registerNetworkCallback()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
        handler.post(healthCheckRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                performUserStop()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                if (lastSecret.isEmpty()) {
                    performUserStop()
                    return START_NOT_STICKY
                }
                userStopped = false
                pausedForBattery = false
                consecutiveFailures = 0
                handler.removeCallbacks(pauseRunnable)
                setState(STATE_RESTARTING, "Перезапуск...", restarting = true)
                scheduleRestart("manual_restart", MANUAL_RESTART_DELAY_MS, force = true)
                return START_STICKY
            }

            ACTION_START -> {
                lastHost = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty()
                    .ifEmpty { "127.0.0.1" }
                lastPort = intent.getIntExtra(EXTRA_PORT, 1443)
                lastSecret = intent.getStringExtra(EXTRA_SECRET)?.trim().orEmpty()
                lastDcRaw = intent.getStringExtra(EXTRA_DC_CONFIG)?.trim().orEmpty()
                lastCfproxyEnabled = intent.getBooleanExtra(EXTRA_CFPROXY_ENABLED, true)
                lastCfproxyDomain = intent.getStringExtra(EXTRA_CFPROXY_DOMAIN)?.trim().orEmpty()
                batterySaver = intent.getBooleanExtra(EXTRA_BATTERY_SAVER, false)

                userStopped = false
                pausedForBattery = false
                consecutiveFailures = 0
                handler.removeCallbacks(pauseRunnable)

                val modeLabel = if (lastCfproxyEnabled) "CF" else "Direct"
                startForegroundCompat(buildNotification("Запуск... [$modeLabel]"))
                startProxyInternal("user_start")
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        unregisterNetworkCallback()
        stopProxyInternal(releaseLocks = true)
        instance = null
        super.onDestroy()
    }

    fun isProxyRunning(): Boolean = proxyServer?.isRunning() == true

    private fun performUserStop() {
        userStopped = true
        pausedForBattery = false
        pausedForNetwork = false
        restartScheduled = false
        restartInFlight = false
        restartSeq.incrementAndGet()
        handler.removeCallbacks(pauseRunnable)
        setState(STATE_STOPPING, "Остановка...")
        stopProxyInternal(releaseLocks = true)
        setState(STATE_STOPPED, "Остановлен")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startProxyInternal(reason: String) {
        if (lastSecret.isEmpty()) return
        if (userStopped || pausedForBattery || pausedForNetwork) return

        restartScheduled = false
        restartInFlight = true
        lastStartAttemptAt = System.currentTimeMillis()
        acquireWakeLock()
        acquireWifiLock()

        val modeLabel = if (lastCfproxyEnabled) "CF" else "Direct"
        setState(STATE_STARTING, "Подключение... [$modeLabel]")

        stopProxyInternal(releaseLocks = false)

        val dcConfig = TelegramDC.parseDcConfig(lastDcRaw)
        val server = try {
            ProxyServer(
                host = lastHost,
                port = lastPort,
                secretHex = lastSecret,
                dcConfig = dcConfig,
                cfproxyEnabled = lastCfproxyEnabled,
                cfproxyUserDomain = lastCfproxyDomain
            )
        } catch (e: Exception) {
            lastError = e.message ?: "init error"
            restartInFlight = false
            consecutiveFailures++
            setState(STATE_ERROR, "Ошибка инициализации: $lastError")
            scheduleRestart("init_error", backoffDelay(), force = false)
            return
        }

        server.statusListener = object : ProxyServer.StatusListener {
            override fun onStarted(host: String, port: Int) {
                restartInFlight = false
                consecutiveFailures = 0
                lastSuccessAt = System.currentTimeMillis()
                val mode = if (lastCfproxyEnabled) "CF" else "Direct"
                setState(STATE_RUNNING, "Активен — $host:$port [$mode]")
            }

            override fun onStopped() {
                restartInFlight = false
                if (userStopped) {
                    setState(STATE_STOPPED, "Остановлен")
                    return
                }
                if (pausedForBattery) {
                    setState(STATE_PAUSED, "Пауза (экран выключен)")
                    return
                }
                if (pausedForNetwork) {
                    setState(STATE_PAUSED, "Пауза (нет сети)")
                    return
                }
                setState(STATE_RESTARTING, "Восстановление подключения...", restarting = true)
                scheduleRestart("server_stopped", backoffDelay(), force = false)
            }

            override fun onError(message: String) {
                lastError = message
                currentStatusText = "Ошибка: $message"
                updateNotification(currentStatusText)
                broadcastStatus(proxyServer?.isRunning() == true,
                    restarting = restartInFlight || restartScheduled)

                if (userStopped || pausedForBattery || pausedForNetwork) return

                when {
                    message.contains("bind failed", ignoreCase = true) ||
                        message.contains("EADDRINUSE", ignoreCase = true) -> {
                        setState(STATE_RESTARTING,
                            "Порт занят, мягкий перезапуск...",
                            restarting = true)
                        scheduleRestart("bind_failed", 1200L, force = true)
                    }

                    else -> {
                        runCatching { proxyServer?.clearTransportState() }
                        consecutiveFailures++
                        scheduleRestart("transport_error", backoffDelay(), force = false)
                    }
                }
            }

            override fun onStatsUpdate(
                active: Int, totalWs: Int, totalTcp: Int, totalCf: Int,
                up: Long, down: Long
            ) {
                val bat = if (batterySaver) " ⚡" else ""
                currentStatusText = "$active подкл | CF:$totalCf WS:$totalWs TCP:$totalTcp | " +
                    "↑${fmtB(up)} ↓${fmtB(down)}$bat"
                if (currentState == STATE_RUNNING) {
                    updateNotification(currentStatusText)
                    broadcastStatus(true, restarting = false)
                }
            }
        }
        proxyServer = server
        server.start()
    }

    private fun backoffDelay(): Long {
        val n = consecutiveFailures.coerceAtMost(BACKOFF_CAP_FAILURES)
        val exp = AUTO_RESTART_BASE_MS shl n
        return exp.coerceAtMost(AUTO_RESTART_MAX_MS)
    }

    private fun scheduleRestart(reason: String, delayMs: Long, force: Boolean) {
        if (userStopped || pausedForBattery || pausedForNetwork || lastSecret.isEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && restartScheduled) return
        if (!force && now - lastRestartAt < MIN_RESTART_GAP_MS) return

        restartScheduled = true
        val seq = restartSeq.incrementAndGet()
        val effectiveDelay = if (force) delayMs
            else maxOf(delayMs, MIN_RESTART_GAP_MS - (now - lastRestartAt))

        handler.postDelayed({
            if (seq != restartSeq.get()) return@postDelayed
            if (userStopped || pausedForBattery || pausedForNetwork || lastSecret.isEmpty()) {
                restartScheduled = false
                restartInFlight = false
                return@postDelayed
            }
            restartScheduled = false
            restartInFlight = true
            lastRestartAt = System.currentTimeMillis()
            setState(STATE_RESTARTING, "Переподключение...", restarting = true)
            stopProxyInternal(releaseLocks = false)
            handler.postDelayed({
                if (seq != restartSeq.get()) return@postDelayed
                startProxyInternal(reason)
            }, STOP_WAIT_BEFORE_RESTART_MS)
        }, effectiveDelay)
    }

    private fun stopProxyInternal(releaseLocks: Boolean) {
        runCatching { proxyServer?.stop() }
        proxyServer = null
        if (releaseLocks) {
            releaseWifiLock()
            releaseWakeLock()
        }
    }

    private fun setState(state: String, text: String, restarting: Boolean = false) {
        currentState = state
        currentStatusText = text
        updateNotification(text)
        broadcastStatus(
            proxyServer?.isRunning() == true ||
                state == STATE_RUNNING || state == STATE_STARTING || state == STATE_RESTARTING,
            restarting
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TG MTProto Proxy",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Локальный MTProto WebSocket proxy"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val stopIntent = Intent(this, ProxyForegroundService::class.java).apply { action = ACTION_STOP }
        val restartIntent = Intent(this, ProxyForegroundService::class.java).apply { action = ACTION_RESTART }

        val pOpen = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pStop = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pRestart = PendingIntent.getService(this, 2, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("TG MTProto Proxy")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pOpen)
            .addAction(R.drawable.ic_restart, "Перезапуск", pRestart)
            .addAction(R.drawable.ic_stop, "Стоп", pStop)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWsProxy:WL")
            .apply {
                setReferenceCounted(false)
                acquire(30 * 60 * 1000L)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TgWsProxy:WFL")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (pausedForNetwork) {
                    pausedForNetwork = false
                    if (!userStopped && !pausedForBattery && lastSecret.isNotEmpty()) {
                        consecutiveFailures = 0
                        runCatching { proxyServer?.clearTransportState() }
                        scheduleRestart("network_available", 250L, force = true)
                    }
                } else if (!userStopped && !pausedForBattery) {
                    runCatching { proxyServer?.clearTransportState() }
                    scheduleRestart("network_changed", 250L, force = false)
                }
            }

            override fun onLost(network: Network) {
                if (userStopped || pausedForBattery) return
                pausedForNetwork = true
                restartScheduled = false
                restartSeq.incrementAndGet()
                currentStatusText = "Нет сети — пауза"
                setState(STATE_PAUSED, currentStatusText)
                stopProxyInternal(releaseLocks = false)
            }

            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
                if (userStopped || pausedForBattery || pausedForNetwork) return
                runCatching { proxyServer?.clearTransportState() }
            }
        }
        connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        networkCallback = null
        connectivityManager = null
    }

    private fun broadcastStatus(running: Boolean, restarting: Boolean = false) {
        sendBroadcast(Intent(ACTION_STATUS_CHANGED).apply {
            putExtra(EXTRA_RUNNING, running)
            putExtra(EXTRA_STATE, currentState)
            putExtra(EXTRA_STATUS_TEXT, currentStatusText)
            putExtra(EXTRA_RESTARTING, restarting)
            putExtra(EXTRA_USER_STOPPED, userStopped)
            putExtra(EXTRA_LAST_ERROR, lastError)
            putExtra(EXTRA_HOST, lastHost)
            putExtra(EXTRA_PORT, lastPort)
            putExtra(EXTRA_CFPROXY_ENABLED, lastCfproxyEnabled)
            putExtra(EXTRA_CFPROXY_DOMAIN, lastCfproxyDomain)
        })
    }

    private fun fmtB(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1048576 -> "${b / 1024}K"
        b < 1073741824 -> String.format("%.1fM", b / 1048576.0)
        else -> String.format("%.1fG", b / 1073741824.0)
    }
}
