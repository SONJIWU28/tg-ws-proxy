package com.tgwsproxy.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tgwsproxy.R
import com.tgwsproxy.databinding.ActivityMainBinding
import com.tgwsproxy.proxy.TelegramDC
import com.tgwsproxy.service.ProxyForegroundService
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var isProxyRunning = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateUi(intent?.getBooleanExtra("running", false) == true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("proxy_settings", MODE_PRIVATE)
        binding.inputDcIp.setText(prefs.getString("dc_ip", "4:149.154.167.220"))
        binding.inputHost.setText(prefs.getString("host", "127.0.0.1"))
        binding.inputPort.setText(prefs.getString("port", "1443"))
        binding.inputSecret.setText(prefs.getString("secret", generateSecret()))
        binding.switchCfproxy.isChecked = prefs.getBoolean("cfproxy_enabled", true)
        binding.inputCfproxyDomain.setText(prefs.getString("cfproxy_domain", ""))
        binding.switchBatterySaver.isChecked = prefs.getBoolean("battery_saver", false)

        requestNotificationPermission()
        requestBatteryOptimization()

        binding.btnGenerateSecret.setOnClickListener { binding.inputSecret.setText(generateSecret()) }
        binding.btnStartStop.setOnClickListener { if (isProxyRunning) stopProxy() else startProxy() }
        binding.btnConnectTelegram.setOnClickListener { openTelegramProxyLink() }

        updateUi(ProxyForegroundService.instance?.isProxyRunning() == true)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("com.tgwsproxy.STATUS_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else { registerReceiver(statusReceiver, filter) }
        updateUi(ProxyForegroundService.instance?.isProxyRunning() == true)
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(statusReceiver) }
    }

    private fun startProxy() {
        val host = binding.inputHost.text.toString().trim().ifEmpty { "127.0.0.1" }
        val port = binding.inputPort.text.toString().trim().toIntOrNull()
        val secret = binding.inputSecret.text.toString().trim().lowercase()
        val dcRaw = binding.inputDcIp.text.toString().trim()
        val cfproxyEnabled = binding.switchCfproxy.isChecked
        val cfproxyDomain = binding.inputCfproxyDomain.text.toString().trim()
        val batterySaver = binding.switchBatterySaver.isChecked

        if (!TelegramDC.isIpv4(host) && host != "0.0.0.0") { toast("Хост: IPv4 или 0.0.0.0"); return }
        if (port == null || port !in 1..65535) { toast("Порт: 1–65535"); return }
        if (!secret.matches(Regex("[0-9a-f]{32}"))) { toast("Secret: 32 hex"); return }
        val dcConfig = TelegramDC.parseDcConfig(dcRaw)
        if (dcConfig.isEmpty()) { toast("Неверный DC:IP"); return }

        getSharedPreferences("proxy_settings", MODE_PRIVATE).edit()
            .putString("dc_ip", dcRaw).putString("host", host)
            .putString("port", port.toString()).putString("secret", secret)
            .putBoolean("cfproxy_enabled", cfproxyEnabled)
            .putString("cfproxy_domain", cfproxyDomain)
            .putBoolean("battery_saver", batterySaver)
            .apply()

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
        updateUi(true)
    }

    private fun stopProxy() {
        startService(Intent(this, ProxyForegroundService::class.java).apply { action = ProxyForegroundService.ACTION_STOP })
        updateUi(false)
    }

    private fun openTelegramProxyLink() {
        val host = binding.inputHost.text.toString().trim().ifEmpty { "127.0.0.1" }
        val linkHost = if (host == "0.0.0.0") "127.0.0.1" else host
        val port = binding.inputPort.text.toString().trim().ifEmpty { "1443" }
        val secret = binding.inputSecret.text.toString().trim().lowercase()
        if (!secret.matches(Regex("[0-9a-f]{32}"))) { toast("Задай правильный secret"); return }
        val tgLink = "tg://proxy?server=$linkHost&port=$port&secret=dd$secret"
        val webLink = tgLink.replace("tg://proxy", "https://t.me/proxy")
        val opened = runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(tgLink))) }.isSuccess
            || runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(webLink))) }.isSuccess
        if (!opened) toast("Не удалось открыть Telegram")
    }

    private fun updateUi(running: Boolean) {
        isProxyRunning = running
        binding.btnStartStop.text = if (running) "ОСТАНОВИТЬ" else "ЗАПУСТИТЬ"
        binding.btnStartStop.setBackgroundColor(ContextCompat.getColor(this, if (running) R.color.button_stop else R.color.button_start))
        binding.statusText.text = if (running) "Статус: работает" else "Статус: остановлен"
        binding.statusText.setTextColor(ContextCompat.getColor(this, if (running) R.color.status_running else R.color.status_stopped))
        val e = !running
        binding.inputDcIp.isEnabled = e; binding.inputHost.isEnabled = e
        binding.inputPort.isEnabled = e; binding.inputSecret.isEnabled = e
        binding.switchCfproxy.isEnabled = e; binding.inputCfproxyDomain.isEnabled = e
        binding.switchBatterySaver.isEnabled = e; binding.btnGenerateSecret.isEnabled = e
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
    }

    private fun requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName))
                runCatching { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:$packageName") }) }
        }
    }

    private fun generateSecret(): String {
        val b = ByteArray(16); SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
