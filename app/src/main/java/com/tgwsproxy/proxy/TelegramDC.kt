package com.tgwsproxy.proxy

import java.net.InetAddress

object TelegramDC {
    val DC_DEFAULT_IPS: Map<Int, String> = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100"
    )

    fun wsDomainsFor(dc: Int, isMedia: Boolean): List<String> {
        return if (isMedia) {
            listOf("kws${dc}-1.web.telegram.org", "kws${dc}.web.telegram.org")
        } else {
            listOf("kws${dc}.web.telegram.org", "kws${dc}-1.web.telegram.org")
        }
    }

    fun fallbackIp(dc: Int): String? = DC_DEFAULT_IPS[dc]

    fun parseDcConfig(raw: String): Map<Int, String> {
        val result = linkedMapOf<Int, String>()
        for (entry in raw.split(';')) {
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(':', limit = 2)
            if (parts.size != 2) continue
            val dc = parts[0].trim().toIntOrNull() ?: continue
            val ip = parts[1].trim()
            if (isIpv4(ip)) {
                result[dc] = ip
            }
        }
        return if (result.isEmpty()) {
            linkedMapOf(2 to "149.154.167.220", 4 to "149.154.167.220")
        } else {
            result
        }
    }

    fun isIpv4(ip: String): Boolean {
        return runCatching {
            val bytes = InetAddress.getByName(ip).address
            bytes.size == 4
        }.getOrDefault(false)
    }
}
