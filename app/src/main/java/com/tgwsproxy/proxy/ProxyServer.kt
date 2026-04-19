package com.tgwsproxy.proxy

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * MTProto WebSocket proxy server.
 *
 * API identical to the previous version (rawSecret removed — uses secretHex as before).
 *
 * Stability improvements over the prior port:
 *   1. stats.lastTrafficAt updated on EVERY byte in EITHER direction.
 *      Foreground service uses this for true stall detection instead of a 30s timer tick.
 *   2. MsgSplitter is now applied in TCP fallback too (parity with Python do_fallback).
 *   3. CfProxy integrated with per-DC Balancer (each DC keeps its own working CF edge).
 *   4. clearTransportState also clears CF per-domain failure cooldowns.
 */
class ProxyServer(
    private val host: String = "127.0.0.1",
    private val port: Int = 1443,
    secretHex: String,
    private val dcConfig: Map<Int, String> = linkedMapOf(2 to "149.154.167.220", 4 to "149.154.167.220"),
    private val cfproxyEnabled: Boolean = true,
    private val cfproxyUserDomain: String = ""
) {
    data class ProxyStats(
        val connectionsTotal: AtomicInteger = AtomicInteger(0),
        val connectionsActive: AtomicInteger = AtomicInteger(0),
        val connectionsWs: AtomicInteger = AtomicInteger(0),
        val connectionsTcpFallback: AtomicInteger = AtomicInteger(0),
        val connectionsCfProxy: AtomicInteger = AtomicInteger(0),
        val connectionsBad: AtomicInteger = AtomicInteger(0),
        val wsErrors: AtomicInteger = AtomicInteger(0),
        val bytesUp: AtomicLong = AtomicLong(0),
        val bytesDown: AtomicLong = AtomicLong(0),
        val poolHits: AtomicInteger = AtomicInteger(0),
        val poolMisses: AtomicInteger = AtomicInteger(0),
        // Timestamp (ms) of the most recent byte in either direction on ANY connection.
        // Used by the Foreground Service for true health checking.
        val lastTrafficAt: AtomicLong = AtomicLong(System.currentTimeMillis())
    )

    interface StatusListener {
        fun onStarted(host: String, port: Int)
        fun onStopped()
        fun onError(message: String)
        fun onStatsUpdate(active: Int, totalWs: Int, totalTcp: Int, totalCf: Int, up: Long, down: Long)
    }

    companion object {
        private const val READ_BUF_SIZE = 32 * 1024
        private const val SOCKET_BUF_SIZE = 128 * 1024
        private const val DRAIN_THRESHOLD = 128 * 1024
        private const val WS_FAIL_COOLDOWN_MS = 30_000L
        private const val WS_FAIL_TIMEOUT_MS = 2_000
        private const val WS_NORMAL_TIMEOUT_MS = 10_000
        private const val STATS_INTERVAL_MS = 30_000L
        private const val HANDSHAKE_READ_TIMEOUT_MS = 10_000
        private const val SESSION_SO_TIMEOUT_MS = 120_000
    }

    private val secretBytes = secretHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val running = AtomicBoolean(false)
    private val wsPool = WsPool(poolSize = 2)
    private val executor = ThreadPoolExecutor(
        2, 64, 30L, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, "proxy-worker").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy()
    )
    private val wsBlacklist = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Int, Boolean>>()
    private val dcFailUntil = java.util.concurrent.ConcurrentHashMap<Pair<Int, Boolean>, Long>()
    private var serverSocket: ServerSocket? = null

    val stats = ProxyStats()
    var statusListener: StatusListener? = null

    fun generateProxyLink(): String {
        val linkHost = if (host == "0.0.0.0") "127.0.0.1" else host
        val sHex = secretBytes.joinToString("") { "%02x".format(it) }
        return "tg://proxy?server=$linkHost&port=$port&secret=dd$sHex"
    }

    fun start() {
        if (running.getAndSet(true)) return
        if (cfproxyEnabled) {
            if (cfproxyUserDomain.isNotBlank()) {
                CfProxy.useUserDomain(cfproxyUserDomain)
            } else {
                CfProxy.startPeriodicRefresh()
            }
        }
        Thread({ runServer() }, "proxy-server").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        wsPool.shutdown()
        executor.shutdownNow()
        statusListener?.onStopped()
    }

    fun isRunning(): Boolean = running.get()

    fun clearTransportState() {
        wsBlacklist.clear()
        dcFailUntil.clear()
        wsPool.clearIdle()
        CfProxy.clearFailures()
    }

    private fun runServer() {
        try {
            val socket = ServerSocket()
            serverSocket = socket
            socket.reuseAddress = true
            socket.receiveBufferSize = SOCKET_BUF_SIZE
            socket.bind(InetSocketAddress(host, port))
            statusListener?.onStarted(host, port)
            wsPool.warmup(dcConfig)

            executor.execute {
                while (running.get()) {
                    runCatching { Thread.sleep(STATS_INTERVAL_MS) }
                    if (!running.get()) break
                    statusListener?.onStatsUpdate(
                        stats.connectionsActive.get(),
                        stats.connectionsWs.get(),
                        stats.connectionsTcpFallback.get(),
                        stats.connectionsCfProxy.get(),
                        stats.bytesUp.get(), stats.bytesDown.get()
                    )
                }
            }

            while (running.get()) {
                try {
                    val client = socket.accept()
                    configureSocket(client)
                    executor.execute { handleClient(client) }
                } catch (_: SocketException) {
                    if (!running.get()) break
                }
            }
        } catch (e: Exception) {
            if (running.get()) statusListener?.onError(e.message ?: "Server error")
        } finally {
            running.set(false)
            runCatching { serverSocket?.close() }
        }
    }

    private fun configureSocket(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.sendBufferSize = SOCKET_BUF_SIZE
        socket.receiveBufferSize = SOCKET_BUF_SIZE
    }

    private fun handleClient(clientSocket: Socket) {
        stats.connectionsTotal.incrementAndGet()
        stats.connectionsActive.incrementAndGet()
        try {
            clientSocket.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            val handshake = readExact(input, MtProto.HANDSHAKE_LEN)
                ?: run { stats.connectionsBad.incrementAndGet(); return }
            if (MtProto.isHttpTransport(handshake)) {
                stats.connectionsBad.incrementAndGet(); return
            }
            val clientInfo = MtProto.tryClientHandshake(handshake, secretBytes)
            if (clientInfo == null) {
                stats.connectionsBad.incrementAndGet()
                drainQuietly(input)
                return
            }

            clientSocket.soTimeout = SESSION_SO_TIMEOUT_MS
            routeClient(clientSocket, input, output, clientInfo)
        } catch (_: EOFException) {
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        } catch (e: Exception) {
            statusListener?.onError(e.message ?: "Client error")
        } finally {
            stats.connectionsActive.decrementAndGet()
            runCatching { clientSocket.close() }
        }
    }

    private fun routeClient(
        clientSocket: Socket, input: InputStream, output: OutputStream,
        clientInfo: MtProto.ClientHandshakeInfo
    ) {
        val dc = clientInfo.dcId
        val isMedia = clientInfo.isMedia
        val dcKey = dc to isMedia
        val relayInit = MtProto.generateRelayInit(clientInfo.protoTagBytes, if (isMedia) -dc else dc)
        val clientCiphers = MtProto.createClientCiphers(clientInfo.clientDecPrekeyIv, secretBytes)
        val relayCiphers = MtProto.createRelayCiphers(relayInit)
        val configuredTarget = dcConfig[dc]

        // 1. Try direct WS first if DC is configured
        if (configuredTarget != null && dcKey !in wsBlacklist) {
            val ws = tryDirectWs(dc, isMedia, configuredTarget, dcKey)
            if (ws != null) {
                stats.connectionsWs.incrementAndGet()
                ws.send(relayInit)
                val splitter = MsgSplitter(relayInit, clientInfo.protoTagInt)
                bridgeWs(clientSocket, input, output, ws, clientCiphers, relayCiphers, splitter)
                return
            }
        }

        // 2. CF proxy
        if (cfproxyEnabled) {
            val cfWs = tryCfProxy(dc)
            if (cfWs != null) {
                stats.connectionsCfProxy.incrementAndGet()
                cfWs.send(relayInit)
                val splitter = MsgSplitter(relayInit, clientInfo.protoTagInt)
                bridgeWs(clientSocket, input, output, cfWs, clientCiphers, relayCiphers, splitter)
                return
            }
        }

        // 3. TCP fallback
        val fallbackIp = TelegramDC.fallbackIp(dc) ?: configuredTarget
        if (fallbackIp != null) {
            val splitter = MsgSplitter(relayInit, clientInfo.protoTagInt)
            tcpFallback(clientSocket, input, output, relayInit, fallbackIp, 443,
                clientCiphers, relayCiphers, splitter)
        }
    }

    private fun tryCfProxy(dc: Int): RawWebSocket? =
        try { CfProxy.tryConnect(dc, timeoutMs = WS_NORMAL_TIMEOUT_MS) } catch (_: Exception) { null }

    private fun tryDirectWs(dc: Int, isMedia: Boolean, targetIp: String, dcKey: Pair<Int, Boolean>): RawWebSocket? {
        val now = System.currentTimeMillis()
        val failUntil = dcFailUntil[dcKey] ?: 0L
        val wsTimeout = if (now < failUntil) WS_FAIL_TIMEOUT_MS else WS_NORMAL_TIMEOUT_MS
        val domains = TelegramDC.wsDomainsFor(dc, isMedia)
        var ws: RawWebSocket? = wsPool.get(dc, isMedia, targetIp, domains)
        if (ws != null) { stats.poolHits.incrementAndGet(); return ws }
        stats.poolMisses.incrementAndGet()

        var redirectOnly = false
        var allRedirects = true
        for (domain in domains) {
            try {
                ws = RawWebSocket.connect(targetIp, domain, timeoutMs = wsTimeout)
                allRedirects = false
                break
            } catch (e: WsHandshakeException) {
                stats.wsErrors.incrementAndGet()
                if (e.isRedirect) { redirectOnly = true; continue }
                allRedirects = false
            } catch (_: Exception) {
                stats.wsErrors.incrementAndGet()
                allRedirects = false
            }
        }
        if (ws == null) {
            if (redirectOnly && allRedirects) wsBlacklist.add(dcKey)
            dcFailUntil[dcKey] = System.currentTimeMillis() + WS_FAIL_COOLDOWN_MS
        }
        return ws
    }

    private fun bridgeWs(
        clientSocket: Socket, clientInput: InputStream, clientOutput: OutputStream,
        ws: RawWebSocket, clientCiphers: MtProto.CipherPair,
        relayCiphers: MtProto.CipherPair, splitter: MsgSplitter?
    ) {
        val done = AtomicBoolean(false)
        val tcpToWs = executor.submit {
            val readBuf = ByteArray(READ_BUF_SIZE)
            try {
                while (!done.get()) {
                    val n = clientInput.read(readBuf)
                    if (n <= 0) break
                    stats.bytesUp.addAndGet(n.toLong())
                    stats.lastTrafficAt.set(System.currentTimeMillis())
                    val cipherChunk = if (n == readBuf.size) readBuf.clone() else readBuf.copyOf(n)
                    val plain = clientCiphers.decryptor.process(cipherChunk)
                    val relayChunk = relayCiphers.encryptor.process(plain)
                    if (splitter != null) {
                        val parts = splitter.split(relayChunk)
                        if (parts.isNotEmpty()) {
                            if (parts.size == 1) ws.send(parts[0]) else ws.sendBatch(parts)
                        }
                    } else {
                        ws.send(relayChunk)
                    }
                }
                if (splitter != null) {
                    val tail = splitter.flush()
                    if (tail.isNotEmpty()) {
                        if (tail.size == 1) ws.send(tail[0]) else ws.sendBatch(tail)
                    }
                }
            } catch (_: Exception) {
            } finally {
                done.set(true); runCatching { ws.close() }
            }
        }
        val wsToTcp = executor.submit {
            var pending = 0
            try {
                while (!done.get()) {
                    val data = ws.recv() ?: break
                    stats.bytesDown.addAndGet(data.size.toLong())
                    stats.lastTrafficAt.set(System.currentTimeMillis())
                    val plain = relayCiphers.decryptor.process(data)
                    val clientChunk = clientCiphers.encryptor.process(plain)
                    synchronized(clientOutput) {
                        clientOutput.write(clientChunk)
                        pending += clientChunk.size
                        if (pending >= DRAIN_THRESHOLD || clientChunk.size < 4096) {
                            clientOutput.flush(); pending = 0
                        }
                    }
                }
                if (pending > 0) synchronized(clientOutput) { clientOutput.flush() }
            } catch (_: Exception) {
            } finally {
                done.set(true); runCatching { clientSocket.close() }
            }
        }
        runCatching { tcpToWs.get() }
        done.set(true)
        runCatching { ws.close() }
        runCatching { wsToTcp.get(3, TimeUnit.SECONDS) }
    }

    private fun tcpFallback(
        clientSocket: Socket, clientInput: InputStream, clientOutput: OutputStream,
        relayInit: ByteArray, remoteIp: String, remotePort: Int,
        clientCiphers: MtProto.CipherPair, relayCiphers: MtProto.CipherPair,
        splitter: MsgSplitter?
    ) {
        val remoteSocket = Socket()
        try {
            remoteSocket.connect(InetSocketAddress(remoteIp, remotePort), 10_000)
            configureSocket(remoteSocket)
            remoteSocket.soTimeout = SESSION_SO_TIMEOUT_MS
            val remoteOut = remoteSocket.getOutputStream()
            val remoteIn = remoteSocket.getInputStream()
            synchronized(remoteOut) {
                remoteOut.write(relayInit); remoteOut.flush()
            }
            stats.connectionsTcpFallback.incrementAndGet()

            val done = AtomicBoolean(false)
            val c2r = executor.submit {
                val readBuf = ByteArray(READ_BUF_SIZE)
                try {
                    while (!done.get()) {
                        val n = clientInput.read(readBuf)
                        if (n <= 0) break
                        stats.bytesUp.addAndGet(n.toLong())
                        stats.lastTrafficAt.set(System.currentTimeMillis())
                        val chunk = if (n == readBuf.size) readBuf.clone() else readBuf.copyOf(n)
                        val plain = clientCiphers.decryptor.process(chunk)
                        val relayChunk = relayCiphers.encryptor.process(plain)
                        if (splitter != null) {
                            val parts = splitter.split(relayChunk)
                            if (parts.isNotEmpty()) {
                                synchronized(remoteOut) {
                                    for (p in parts) remoteOut.write(p)
                                    remoteOut.flush()
                                }
                            }
                        } else {
                            synchronized(remoteOut) {
                                remoteOut.write(relayChunk); remoteOut.flush()
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    done.set(true); runCatching { remoteSocket.close() }
                }
            }
            val r2c = executor.submit {
                val readBuf = ByteArray(READ_BUF_SIZE)
                var pending = 0
                try {
                    while (!done.get()) {
                        val n = remoteIn.read(readBuf)
                        if (n <= 0) break
                        stats.bytesDown.addAndGet(n.toLong())
                        stats.lastTrafficAt.set(System.currentTimeMillis())
                        val chunk = if (n == readBuf.size) readBuf.clone() else readBuf.copyOf(n)
                        val plain = relayCiphers.decryptor.process(chunk)
                        val out = clientCiphers.encryptor.process(plain)
                        synchronized(clientOutput) {
                            clientOutput.write(out)
                            pending += out.size
                            if (pending >= DRAIN_THRESHOLD || out.size < 4096) {
                                clientOutput.flush(); pending = 0
                            }
                        }
                    }
                    if (pending > 0) synchronized(clientOutput) { clientOutput.flush() }
                } catch (_: Exception) {
                } finally {
                    done.set(true); runCatching { clientSocket.close() }
                }
            }
            runCatching { c2r.get() }
            done.set(true)
            runCatching { remoteSocket.close() }
            runCatching { r2c.get(3, TimeUnit.SECONDS) }
        } catch (_: Exception) {
            runCatching { remoteSocket.close() }
        }
    }

    private fun readExact(input: InputStream, n: Int): ByteArray? {
        val out = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val r = try { input.read(out, offset, n - offset) } catch (_: IOException) { return null }
            if (r == -1) return null
            offset += r
        }
        return out
    }

    private fun drainQuietly(input: InputStream) {
        val buf = ByteArray(4096)
        runCatching { while (input.read(buf) != -1) {} }
    }
}
