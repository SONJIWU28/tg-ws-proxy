package com.tgwsproxy.proxy

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cloudflare-proxied fallback transport.
 *
 * - Decodes encoded default domain list (Caesar-by-letter-count shift + .co.uk suffix)
 * - Periodically refreshes list from GitHub
 * - Uses [Balancer] for per-DC domain memory
 * - Tracks per-domain fail cooldown
 */
object CfProxy {
    private val ENCODED_DEFAULTS = listOf(
        "virkgj.com",
        "vmmzovy.com",
        "mkuosckvso.com",
        "zaewayzmplad.com",
        "twdmbzcm.com"
    )

    private val DOMAIN_SUFFIX = charArrayOf('.', 'c', 'o', '.', 'u', 'k').joinToString("")
    private const val DOMAINS_URL =
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
    private const val DOMAIN_FAIL_COOLDOWN_MS = 45_000L
    private const val REFRESH_INTERVAL_MS = 3_600_000L

    private val refreshStarted = AtomicBoolean(false)
    private val failUntil = ConcurrentHashMap<String, Long>()

    private fun decodeDomain(encoded: String): String {
        if (!encoded.endsWith(".com")) return encoded
        val name = encoded.dropLast(4)
        val n = name.count { it.isLetter() }
        val decoded = name.map { c ->
            if (c.isLetter()) {
                val base = if (c.isLowerCase()) 'a' else 'A'
                val shifted = ((c - base - n) % 26 + 26) % 26
                base + shifted
            } else c
        }.joinToString("")
        return decoded + DOMAIN_SUFFIX
    }

    val DEFAULT_DOMAINS: List<String> = ENCODED_DEFAULTS.map { decodeDomain(it) }

    init {
        Balancer.updateDomainsList(DEFAULT_DOMAINS)
    }

    fun useUserDomain(domain: String) {
        Balancer.updateDomainsList(listOf(domain))
    }

    fun tryConnect(dc: Int, timeoutMs: Int = 10_000): RawWebSocket? {
        if (!Balancer.hasDomains()) return null
        val now = System.currentTimeMillis()
        val candidates = Balancer.getDomainsForDc(dc)
            .filter { (failUntil[it] ?: 0L) <= now }
        val pool = candidates.ifEmpty {
            Balancer.getDomainsForDc(dc).take(1)
        }

        for (baseDomain in pool) {
            val domain = "kws${dc}.${baseDomain}"
            try {
                val ws = RawWebSocket.connect(
                    ip = domain,
                    domain = domain,
                    timeoutMs = timeoutMs
                )
                failUntil.remove(baseDomain)
                Balancer.updateDomainForDc(dc, baseDomain)
                return ws
            } catch (_: Exception) {
                failUntil[baseDomain] = now + DOMAIN_FAIL_COOLDOWN_MS
            }
        }
        return null
    }

    fun refreshDomains() {
        try {
            val randomSuffix = (1..7).map { ('a'..'z').random() }.joinToString("")
            val url = URL("$DOMAINS_URL?$randomSuffix")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "tg-ws-proxy-android")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.instanceFollowRedirects = true

            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().readText()
                val decoded = text.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { decodeDomain(it) }
                    .distinct()
                if (decoded.isNotEmpty()) {
                    Balancer.updateDomainsList(decoded)
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
    }

    fun startPeriodicRefresh() {
        if (!refreshStarted.compareAndSet(false, true)) return
        Thread({
            refreshDomains()
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(REFRESH_INTERVAL_MS)
                    refreshDomains()
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "cfproxy-domain-refresh").apply {
            isDaemon = true
            start()
        }
    }

    fun clearFailures() {
        failUntil.clear()
    }
}
