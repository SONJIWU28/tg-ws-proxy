package com.tgwsproxy.ui.model

data class ProxySettings(
    val dcIp: String = "4:149.154.167.220",
    val host: String = "127.0.0.1",
    val port: String = "1443",
    val secret: String = "",
    val cfProxyEnabled: Boolean = true,
    val cfProxyDomain: String = "",
    val batterySaver: Boolean = false
)

enum class ProxyScreenTab { Main, Settings }

data class ProxyUiState(
    val running: Boolean = false,
    val connecting: Boolean = false,
    val activeTab: ProxyScreenTab = ProxyScreenTab.Main,
    val settings: ProxySettings = ProxySettings(),
    val statusLabel: String = "Не подключено",
    val statusDetail: String = "Прокси остановлен",
    val lastToastMessage: String? = null
)