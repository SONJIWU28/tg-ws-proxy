package com.tgwsproxy.proxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Per-DC Cloudflare-proxied domain balancer.
 *
 * Port of Flowseal/tg-ws-proxy/proxy/balancer.py:
 *   - Each DC remembers its own currently-working CF domain
 *   - getDomainsForDc(dc) yields: [remembered_for_this_dc] then shuffled others
 *   - updateDomainForDc(dc, domain) only updates if different
 *
 * The previous implementation used a single global `activeDomain` which caused
 * DC4 to keep trying a domain that only worked for DC2, thrashing connections
 * on poor mobile networks.
 */
object Balancer {
    private val domains = CopyOnWriteArrayList<String>()
    private val dcToDomain = ConcurrentHashMap<Int, String>()
    private val allDcs = listOf(1, 2, 3, 4, 5, 203)

    @Volatile
    private var domainsHash = 0

    fun updateDomainsList(newDomains: List<String>) {
        val deduped = newDomains.distinct()
        val newHash = deduped.toSet().hashCode()
        if (newHash == domainsHash && domains.size == deduped.size) return

        domainsHash = newHash
        domains.clear()
        domains.addAll(deduped)

        if (deduped.isNotEmpty()) {
            val shuffled = deduped.shuffled()
            for ((i, dc) in allDcs.withIndex()) {
                dcToDomain[dc] = shuffled[i % shuffled.size]
            }
        } else {
            dcToDomain.clear()
        }
    }

    fun updateDomainForDc(dcId: Int, domain: String): Boolean {
        val prev = dcToDomain[dcId]
        if (prev == domain) return false
        dcToDomain[dcId] = domain
        return true
    }

    fun getDomainsForDc(dcId: Int): List<String> {
        val current = dcToDomain[dcId]
        val all = domains.toList()
        if (all.isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        if (current != null && current in all) result.add(current)
        val others = all.filter { it != current }.shuffled()
        result.addAll(others)
        return result
    }

    fun hasDomains(): Boolean = domains.isNotEmpty()

    fun snapshot(): List<String> = domains.toList()
}
