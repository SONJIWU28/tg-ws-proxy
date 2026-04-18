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

class ProxyForegroundService : Service() {
    companion object {
        private const val CHANNEL_ID = "tg_mtproxy_channel"
        private const val NOTIFICATION_ID = 1
        private const val AUTO_RESTART_DELAY_MS = 3000L
        private const val SCREEN_OFF_PAUSE_DELAY_MS = 5 * 60 * 1000L // 5 min after screen off
        const val ACTION_START = "com.tgwsproxy.START"
        const val ACTION_STOP = "com.tgwsproxy.STOP"
        const val ACTION_RESTART = "com.tgwsproxy.RESTART"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_SECRET = "secret"
        const val EXTRA_DC_CONFIG = "dc_config"
        const val EXTRA_CFPROXY_ENABLED = "cfproxy_enabled"
        const val EXTRA_CFPROXY_DOMAIN = "cfproxy_domain"
        const val EXTRA_BATTERY_SAVER = "battery_saver"

        var instance: ProxyForegroundService? = null
            private set
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

    // Saved params for restart
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
                        updateNotification("Возобновление прокси...")
                        restartProxyInternal()
                    }
                }
            }
        }
    }

    private val pauseRunnable = Runnable {
        if (screenOff && batterySaver && proxyServer?.isRunning() == true) {
            pausedForBattery = true
            proxyServer?.stop()
            releaseWakeLock()
            releaseWifiLock()
            updateNotification("Пауза (экран выключен)")
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                handler.removeCallbacks(pauseRunnable)
                stopProxy()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                stopProxy()
                updateNotification("Перезапуск...")
                handler.postDelayed({ restartProxyInternal() }, 1000)
                return START_STICKY
            }
            ACTION_START -> {
                lastHost = intent.getStringExtra(EXTRA_HOST)?.trim().orEmpty().ifEmpty { "127.0.0.1" }
                lastPort = intent.getIntExtra(EXTRA_PORT, 1443)
                lastSecret = intent.getStringExtra(EXTRA_SECRET)?.trim().orEmpty()
                lastDcRaw = intent.getStringExtra(EXTRA_DC_CONFIG)?.trim().orEmpty()
                lastCfproxyEnabled = intent.getBooleanExtra(EXTRA_CFPROXY_ENABLED, true)
                lastCfproxyDomain = intent.getStringExtra(EXTRA_CFPROXY_DOMAIN)?.trim().orEmpty()
                batterySaver = intent.getBooleanExtra(EXTRA_BATTERY_SAVER, false)

                val modeLabel = if (lastCfproxyEnabled) "CF" else "Direct"
                startForegroundCompat(buildNotification("Запуск... [$modeLabel]"))
                acquireWakeLock()
                acquireWifiLock()
                startProxy(lastHost, lastPort, lastSecret, TelegramDC.parseDcConfig(lastDcRaw), lastCfproxyEnabled, lastCfproxyDomain)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(pauseRunnable)
        runCatching { unregisterReceiver(screenReceiver) }
        unregisterNetworkCallback()
        stopProxy()
        releaseWifiLock()
        releaseWakeLock()
        instance = null
        super.onDestroy()
    }

    fun isProxyRunning(): Boolean = proxyServer?.isRunning() == true

    private fun restartProxyInternal() {
        if (lastSecret.isEmpty()) return
        acquireWakeLock()
        acquireWifiLock()
        startProxy(lastHost, lastPort, lastSecret, TelegramDC.parseDcConfig(lastDcRaw), lastCfproxyEnabled, lastCfproxyDomain)
    }

    private fun startProxy(
        host: String, port: Int, secret: String, dcConfig: Map<Int, String>,
        cfproxyEnabled: Boolean, cfproxyDomain: String
    ) {
        stopProxy()
        proxyServer = ProxyServer(
            host = host, port = port, secretHex = secret, dcConfig = dcConfig,
            cfproxyEnabled = cfproxyEnabled, cfproxyUserDomain = cfproxyDomain
        ).apply {
            statusListener = object : ProxyServer.StatusListener {
                override fun onStarted(host: String, port: Int) {
                    val mode = if (cfproxyEnabled) "CF" else "Direct"
                    updateNotification("Активен — $host:$port [$mode]")
                    broadcastStatus(true)
                }
                override fun onStopped() {
                    broadcastStatus(false)
                    // Auto-restart if not paused for battery and not user-stopped
                    if (instance != null && lastSecret.isNotEmpty() && !pausedForBattery) {
                        handler.postDelayed({
                            if (instance != null && proxyServer?.isRunning() != true && !pausedForBattery) {
                                updateNotification("Авто-перезапуск...")
                                restartProxyInternal()
                            }
                        }, AUTO_RESTART_DELAY_MS)
                    }
                }
                override fun onError(message: String) { updateNotification("Ошибка: $message") }
                override fun onStatsUpdate(active: Int, totalWs: Int, totalTcp: Int, totalCf: Int, up: Long, down: Long) {
                    val bat = if (batterySaver) " ⚡" else ""
                    updateNotification("$active подкл | CF:$totalCf WS:$totalWs TCP:$totalTcp | ↑${fmtB(up)} ↓${fmtB(down)}$bat")
                }
            }
            start()
        }
    }

    fun stopProxy() {
        proxyServer?.stop()
        proxyServer = null
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "TG MTProto Proxy", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Локальный MTProto WebSocket proxy"; setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val stopIntent = Intent(this, ProxyForegroundService::class.java).apply { action = ACTION_STOP }
        val restartIntent = Intent(this, ProxyForegroundService::class.java).apply { action = ACTION_RESTART }
        val pOpen = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pStop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pRestart = PendingIntent.getService(this, 2, restartIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("TG MTProto Proxy")
            .setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pOpen)
            .addAction(R.drawable.ic_restart, "Перезапуск", pRestart)
            .addAction(R.drawable.ic_stop, "Стоп", pStop)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TgWsProxy:WL")
            .apply { setReferenceCounted(false); acquire(30 * 60 * 1000L) } // 30 min max, auto-release
    }

    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TgWsProxy:WFL")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWifiLock() { wifiLock?.let { if (it.isHeld) it.release() }; wifiLock = null }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { proxyServer?.clearTransportState() }
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) { proxyServer?.clearTransportState() }
        }
        connectivityManager?.registerDefaultNetworkCallback(networkCallback!!)
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        runCatching { connectivityManager?.unregisterNetworkCallback(cb) }
        networkCallback = null; connectivityManager = null
    }

    private fun broadcastStatus(running: Boolean) {
        sendBroadcast(Intent("com.tgwsproxy.STATUS_CHANGED").apply { putExtra("running", running) })
    }

    private fun fmtB(b: Long): String = when {
        b < 1024 -> "${b}B"; b < 1048576 -> "${b / 1024}K"
        b < 1073741824 -> String.format("%.1fM", b / 1048576.0)
        else -> String.format("%.1fG", b / 1073741824.0)
    }
}
