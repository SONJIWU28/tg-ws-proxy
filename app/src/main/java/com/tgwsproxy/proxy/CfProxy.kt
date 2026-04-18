package com.tgwsproxy.proxy

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Cloudflare Proxy fallback for Telegram MTProto WebSocket bridge.
 *
 * When direct WebSocket connections to Telegram DC IPs are blocked (e.g. by mobile operators),
 * this routes traffic through Cloudflare's network instead.
 *
 * How it works:
 *   1. A domain is registered on Cloudflare with DNS A records like:
 *      kws2.domain.co.uk → 149.154.167.51 (Telegram DC2)
 *      kws4.domain.co.uk → 149.154.167.91 (Telegram DC4)
 *   2. Cloudflare proxies WebSocket connections to these IPs
 *   3. The mobile operator only sees connections to Cloudflare IPs (unblockable)
 *   4. Telegram traffic flows: Client → LocalProxy → Cloudflare → Telegram DC
 *
 * Flowseal provides default public domains, but they can be overloaded.
 */
object CfProxy {

    // Flowseal's encoded default domains (ROT-N cipher + .co.uk suffix)
    private val ENCODED_DOMAINS = listOf(
        "virkgj.com",
        "vmmzovy.com",
        "mkuosckvso.com",
        "zaewayzmplad.com",
        "twdmbzcm.com"
    )

    // Decoded suffix: ".co.uk"
    private val DOMAIN_SUFFIX = charArrayOf('.', 'c', 'o', '.', 'u', 'k')
        .joinToString("")

    // URL to fetch updated domain list from GitHub
    private const val DOMAINS_URL =
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"

    /**
     * Decode Flowseal's obfuscated domain names.
     * They use a simple ROT-N cipher where N = number of alpha chars in the name part.
     */
    private fun decodeDomain(encoded: String): String {
        if (!encoded.endsWith(".com")) return encoded
        val name = encoded.dropLast(4) // remove ".com"
        val n = name.count { it.isLetter() }
        val decoded = name.map { c ->
            if (c.isLetter()) {
                val base = if (c.isLowerCase()) 'a' else 'A'
                val shifted = ((c - base - n) % 26 + 26) % 26
                (base + shifted)
            } else c
        }.joinToString("")
        return decoded + DOMAIN_SUFFIX
    }

    /** Default decoded domains from Flowseal */
    val DEFAULT_DOMAINS: List<String> = ENCODED_DOMAINS.map { decodeDomain(it) }

    /** Active domain pool (refreshed from GitHub periodically) */
    val domainPool = CopyOnWriteArrayList(DEFAULT_DOMAINS)

    /** Currently active domain (rotated on failure) */
    val activeDomain = AtomicReference(DEFAULT_DOMAINS.random())

    /**
     * Try connecting to Telegram DC via Cloudflare proxy.
     * Tries the active domain first, then others.
     *
     * @return connected RawWebSocket, or null if all domains failed
     */
    fun tryConnect(dc: Int, timeoutMs: Int = 10_000): RawWebSocket? {
        val active = activeDomain.get()
        val others = domainPool.filter { it != active }
        val candidates = listOf(active) + others

        for (baseDomain in candidates) {
            val domain = "kws${dc}.${baseDomain}"
            try {
                // For cfproxy, host=domain (Cloudflare resolves it)
                val ws = RawWebSocket.connect(
                    ip = domain,  // connect by domain name, not IP
                    domain = domain,
                    timeoutMs = timeoutMs
                )
                // Switch active domain if this one worked and wasn't active
                if (baseDomain != active) {
                    activeDomain.set(baseDomain)
                }
                return ws
            } catch (_: Exception) {
                // Try next domain
            }
        }
        return null
    }

    /**
     * Fetch updated domain list from Flowseal's GitHub repo.
     * Called periodically in background.
     */
    fun refreshDomains() {
        try {
            val randomSuffix = (1..7).map { ('a'..'z').random() }.joinToString("")
            val url = URL("$DOMAINS_URL?$randomSuffix")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "tg-ws-proxy-android")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().readText()
                val decoded = text.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { decodeDomain(it) }
                    .distinct()

                if (decoded.isNotEmpty()) {
                    domainPool.clear()
                    domainPool.addAll(decoded)
                    // Rotate active if current is no longer in pool
                    if (activeDomain.get() !in domainPool) {
                        activeDomain.set(domainPool.random())
                    }
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
            // Keep existing domains
        }
    }

    /**
     * Start periodic domain refresh (every hour).
     */
    fun startPeriodicRefresh() {
        Thread({
            // Initial refresh
            refreshDomains()
            while (true) {
                try {
                    Thread.sleep(3600_000) // 1 hour
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
}
