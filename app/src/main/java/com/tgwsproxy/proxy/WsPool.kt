package com.tgwsproxy.proxy

import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WsPool(
    private val poolSize: Int = 4,
    private val maxAgeMs: Long = 120_000L
) {
    data class PoolKey(val dc: Int, val isMedia: Boolean)
    private data class Entry(val ws: RawWebSocket, val createdAt: Long)

    private val idle = ConcurrentHashMap<PoolKey, ConcurrentLinkedDeque<Entry>>()
    private val refilling = ConcurrentHashMap.newKeySet<PoolKey>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "ws-pool").apply { isDaemon = true }
    }

    @Volatile
    private var isShutdown = false

    fun get(dc: Int, isMedia: Boolean, targetIp: String, domains: List<String>): RawWebSocket? {
        if (isShutdown) return null
        val key = PoolKey(dc, isMedia)
        val bucket = idle.getOrPut(key) { ConcurrentLinkedDeque() }
        val now = System.currentTimeMillis()
        while (true) {
            val entry = bucket.pollFirst() ?: break
            val expired = now - entry.createdAt > maxAgeMs
            if (expired || entry.ws.isClosed) {
                closeQuietly(entry.ws)
                continue
            }
            scheduleRefill(key, targetIp, domains)
            return entry.ws
        }
        scheduleRefill(key, targetIp, domains)
        return null
    }

    fun warmup(dcConfig: Map<Int, String>) {
        if (isShutdown) return
        for ((dc, targetIp) in dcConfig) {
            scheduleRefill(PoolKey(dc, false), targetIp, TelegramDC.wsDomainsFor(dc, false))
            scheduleRefill(PoolKey(dc, true), targetIp, TelegramDC.wsDomainsFor(dc, true))
        }
    }

    fun clearIdle() {
        for ((_, bucket) in idle) {
            while (true) {
                val entry = bucket.pollFirst() ?: break
                closeQuietly(entry.ws)
            }
        }
        idle.clear()
        refilling.clear()
    }

    fun shutdown() {
        isShutdown = true
        clearIdle()
        executor.shutdownNow()
    }

    private fun scheduleRefill(key: PoolKey, targetIp: String, domains: List<String>) {
        if (isShutdown) return
        if (!refilling.add(key)) return
        executor.execute {
            try {
                val bucket = idle.getOrPut(key) { ConcurrentLinkedDeque() }
                val needed = poolSize - bucket.size
                if (needed <= 0) return@execute
                val futures = (0 until needed).map {
                    executor.submit(Callable { connectOne(targetIp, domains) })
                }
                for (future in futures) {
                    val ws = runCatching { future.get(12, TimeUnit.SECONDS) }.getOrNull()
                    if (ws != null && !isShutdown) {
                        bucket.addLast(Entry(ws, System.currentTimeMillis()))
                    }
                }
            } finally {
                refilling.remove(key)
            }
        }
    }

    private fun connectOne(targetIp: String, domains: List<String>): RawWebSocket? {
        for (domain in domains) {
            try {
                return RawWebSocket.connect(targetIp, domain, timeoutMs = 8_000)
            } catch (e: WsHandshakeException) {
                if (e.isRedirect) continue
                return null
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun closeQuietly(ws: RawWebSocket) {
        runCatching { ws.close() }
    }
}
