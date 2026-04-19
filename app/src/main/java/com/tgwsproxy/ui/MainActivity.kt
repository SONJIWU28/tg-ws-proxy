package com.tgwsproxy.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.tgwsproxy.proxy.TelegramDC
import com.tgwsproxy.service.ProxyForegroundService
import com.tgwsproxy.ui.compose.ProxyNativeScreen
import com.tgwsproxy.ui.model.ProxyScreenTab
import com.tgwsproxy.ui.model.ProxySettings
import com.tgwsproxy.ui.model.ProxyUiState
import java.security.SecureRandom

class MainActivity : ComponentActivity() {
    private var uiState by mutableStateOf(ProxyUiState())
    private val uiHandler = Handler(Looper.getMainLooper())
    private val connectingRunnable = Runnable {
        // По истечении таймера симуляции подключения — завершаем визуальный connecting
        if (uiState.connecting) {
            val actuallyRunning = ProxyForegroundService.instance?.isProxyRunning() == true
            syncRunningState(actuallyRunning)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val running = intent?.getBooleanExtra("running", false) == true
            syncRunningState(running)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        uiState = uiState.copy(settings = loadSettings())
        requestNotificationPermission()
        requestBatteryOptimization()
        syncRunningState(ProxyForegroundService.instance?.isProxyRunning() == true)
        setContent {
            ProxyNativeScreen(
                state = uiState,
                onTabChange = { tab -> uiState = uiState.copy(activeTab = tab) },
                onSettingsChange = { settings ->
                    uiState = uiState.copy(settings = settings)
                    saveSettings(settings)
                },
                onGenerateSecret = {
                    val secret = generateSecret()
                    val settings = uiState.settings.copy(secret = secret)
                    uiState = uiState.copy(settings = settings)
                    saveSettings(settings)
                },
                onStartStop = {
                    if (uiState.connecting) return@ProxyNativeScreen
                    if (uiState.running) stopProxy() else startProxy()
                },
                onRestart = { restartProxy() },
                onAddToTelegram = { openTelegramProxyLink() },
                onCopyProxy = { copyProxyLink() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.tgwsproxy.STATUS_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
        syncRunningState(ProxyForegroundService.instance?.isProxyRunning() == true)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(statusReceiver) }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(connectingRunnable)
        super.onDestroy()
    }

    private fun startProxy() {
        val settings = uiState.settings
        val host = settings.host.trim().ifEmpty { "127.0.0.1" }
        val port = settings.port.trim().toIntOrNull()
        val secret = settings.secret.trim().lowercase()
        val dcRaw = settings.dcIp.trim()
        val cfproxyEnabled = settings.cfProxyEnabled
        val cfproxyDomain = settings.cfProxyDomain.trim()
        val batterySaver = settings.batterySaver

        if (!TelegramDC.isIpv4(host) && host != "0.0.0.0") {
            toast("Хост: IPv4 или 0.0.0.0")
            return
        }
        if (port == null || port !in 1..65535) {
            toast("Порт: 1–65535")
            return
        }
        if (!secret.matches(Regex("[0-9a-f]{32}"))) {
            toast("Secret: 32 hex")
            return
        }
        if (TelegramDC.parseDcConfig(dcRaw).isEmpty()) {
            toast("Неверный DC:IP")
            return
        }

        saveSettings(uiState.settings.copy(host = host, port = port.toString(), secret = secret, dcIp = dcRaw, cfProxyDomain = cfproxyDomain))

        val intent = Intent(this, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_START
            putExtra(ProxyForegroundService.EXTRA_HOST, host)
            putExtra(ProxyForegroundService.EXTRA_PORT, port)
            putExtra(ProxyForegroundService.EXTRA_SECRET, secret)
            putExtra(ProxyForegroundService.EXTRA_DC_CONFIG, dcRaw)
            putExtra(ProxyForegroundService.EXTRA_CFPROXY_ENABLED, cfproxyEnabled)
            putExtra(ProxyForegroundService.EXTRA_CFPROXY_DOMAIN, cfproxyDomain)
            putExtra(ProxyForegroundService.EXTRA_BATTERY_SAVER, batterySaver)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        // Показываем анимацию подключения на 1.5 сек, затем синхронизируем с реальным состоянием сервиса
        uiHandler.removeCallbacks(connectingRunnable)
        uiState = uiState.copy(
            running = false,
            connecting = true,
            statusLabel = "Подключение…",
            statusDetail = "Устанавливаем соединение с Telegram DC"
        )
        uiHandler.postDelayed(connectingRunnable, 1500L)
    }

    private fun stopProxy() {
        startService(Intent(this, ProxyForegroundService::class.java).apply { action = ProxyForegroundService.ACTION_STOP })
        uiHandler.removeCallbacks(connectingRunnable)
        syncRunningState(false)
    }

    private fun restartProxy() {
        if (!uiState.running && !uiState.connecting) {
            toast("Прокси не запущен")
            return
        }
        startService(Intent(this, ProxyForegroundService::class.java).apply { action = ProxyForegroundService.ACTION_RESTART })
        uiHandler.removeCallbacks(connectingRunnable)
        uiState = uiState.copy(
            running = false,
            connecting = true,
            statusLabel = "Переподключение…",
            statusDetail = "Прокси пересобирает соединение"
        )
        uiHandler.postDelayed(connectingRunnable, 1500L)
    }

    private fun openTelegramProxyLink() {
        val host = uiState.settings.host.trim().ifEmpty { "127.0.0.1" }
        val linkHost = if (host == "0.0.0.0") "127.0.0.1" else host
        val port = uiState.settings.port.trim().ifEmpty { "1443" }
        val secret = uiState.settings.secret.trim().lowercase()
        if (!secret.matches(Regex("[0-9a-f]{32}"))) {
            toast("Задай правильный secret")
            return
        }
        val tgLink = "tg://proxy?server=$linkHost&port=$port&secret=dd$secret"
        val webLink = tgLink.replace("tg://proxy", "https://t.me/proxy")
        val opened = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tgLink))) }.isSuccess ||
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webLink))) }.isSuccess
        if (!opened) toast("Не удалось открыть Telegram")
    }

    private fun copyProxyLink() {
        val host = uiState.settings.host.trim().ifEmpty { "127.0.0.1" }
        val linkHost = if (host == "0.0.0.0") "127.0.0.1" else host
        val port = uiState.settings.port.trim().ifEmpty { "1443" }
        val secret = uiState.settings.secret.trim().lowercase()
        if (!secret.matches(Regex("[0-9a-f]{32}"))) {
            toast("Задай правильный secret")
            return
        }
        val link = "tg://proxy?server=$linkHost&port=$port&secret=dd$secret"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("tg-proxy", link))
        toast("Ссылка скопирована")
    }

    private fun syncRunningState(running: Boolean, labelOverride: String? = null, detailOverride: String? = null) {
        uiState = uiState.copy(
            running = running,
            connecting = false,
            statusLabel = labelOverride ?: if (running) "Подключено" else "Не подключено",
            statusDetail = detailOverride ?: if (running) "Локальный прокси активен и готов к добавлению в Telegram" else "Прокси остановлен. Нажми на центральную кнопку, чтобы быстро включить его"
        )
    }

    private fun loadSettings(): ProxySettings {
        val prefs = getSharedPreferences("proxy_settings", MODE_PRIVATE)
        return ProxySettings(
            dcIp = prefs.getString("dc_ip", "4:149.154.167.220") ?: "4:149.154.167.220",
            host = prefs.getString("host", "127.0.0.1") ?: "127.0.0.1",
            port = prefs.getString("port", "1443") ?: "1443",
            secret = prefs.getString("secret", generateSecret()) ?: generateSecret(),
            cfProxyEnabled = prefs.getBoolean("cfproxy_enabled", true),
            cfProxyDomain = prefs.getString("cfproxy_domain", "") ?: "",
            batterySaver = prefs.getBoolean("battery_saver", false)
        )
    }

    private fun saveSettings(settings: ProxySettings) {
        getSharedPreferences("proxy_settings", MODE_PRIVATE).edit()
            .putString("dc_ip", settings.dcIp)
            .putString("host", settings.host)
            .putString("port", settings.port)
            .putString("secret", settings.secret)
            .putBoolean("cfproxy_enabled", settings.cfProxyEnabled)
            .putString("cfproxy_domain", settings.cfProxyDomain)
            .putBoolean("battery_saver", settings.batterySaver)
            .apply()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }
            }
        }
    }

    private fun generateSecret(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}