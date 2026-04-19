package com.tgwsproxy.service

import android.content.Context

object ProxyServiceController {
    fun start(
        context: Context,
        host: String,
        port: Int,
        secret: String,
        dcConfig: String,
        cfproxyEnabled: Boolean,
        cfproxyDomain: String,
        batterySaver: Boolean
    ) {
        ProxyForegroundService.startService(
            context = context,
            host = host,
            port = port,
            secret = secret,
            dcConfig = dcConfig,
            cfproxyEnabled = cfproxyEnabled,
            cfproxyDomain = cfproxyDomain,
            batterySaver = batterySaver
        )
    }

    fun stop(context: Context) {
        ProxyForegroundService.stopService(context)
    }

    fun restart(context: Context) {
        ProxyForegroundService.restartService(context)
    }
}
