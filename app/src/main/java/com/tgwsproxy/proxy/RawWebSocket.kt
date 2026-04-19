package com.tgwsproxy.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class RawWebSocket private constructor(
    private val socket: Socket,
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream
) {
    @Volatile
    var isClosed = false
        private set

    companion object {
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val SOCKET_BUF_SIZE = 256 * 1024
        private const val STREAM_BUF_SIZE = 256 * 1024
        private const val MAX_FRAME_SIZE = 16 * 1024 * 1024
        private const val READ_TIMEOUT_MS = 180_000

        private val random = SecureRandom()
        private val threadLocalMaskKey = ThreadLocal.withInitial { ByteArray(4) }
        private val sslContext: SSLContext by lazy {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
            })
            SSLContext.getInstance("TLS").apply {
                init(null, trustAll, SecureRandom())
            }
        }

        fun connect(ip: String, domain: String, path: String = "/apiws", timeoutMs: Int = 10_000): RawWebSocket {
            val rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(ip, 443), timeoutMs)
            rawSocket.soTimeout = timeoutMs
            rawSocket.tcpNoDelay = true
            rawSocket.reuseAddress = true
            rawSocket.sendBufferSize = SOCKET_BUF_SIZE
            rawSocket.receiveBufferSize = SOCKET_BUF_SIZE
            rawSocket.keepAlive = true

            val sslSocket = sslContext.socketFactory.createSocket(rawSocket, domain, 443, true) as SSLSocket
            sslSocket.tcpNoDelay = true
            sslSocket.keepAlive = true
            sslSocket.useClientMode = true
            runCatching {
                val supported = sslSocket.supportedProtocols.toSet()
                if ("TLSv1.3" in supported) {
                    sslSocket.enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
                }
            }
            sslSocket.enableSessionCreation = true
            sslSocket.startHandshake()

            val input = BufferedInputStream(sslSocket.inputStream, STREAM_BUF_SIZE)
            val output = BufferedOutputStream(sslSocket.outputStream, STREAM_BUF_SIZE)

            val wsKeyBytes = ByteArray(16)
            random.nextBytes(wsKeyBytes)
            val wsKey = Base64.getEncoder().encodeToString(wsKeyBytes)
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $domain\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $wsKey\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Protocol: binary\r\n")
                append("Origin: https://web.telegram.org\r\n")
                append("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36\r\n")
                append("\r\n")
            }
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()

            val responseLine = readHttpLine(input)
            if (responseLine.isNullOrEmpty()) {
                sslSocket.close()
                throw WsHandshakeException(0, "empty response")
            }
            val parts = responseLine.split(" ", limit = 3)
            val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val headers = linkedMapOf<String, String>()
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }
            if (statusCode != 101) {
                sslSocket.close()
                throw WsHandshakeException(statusCode, responseLine, headers, headers["location"])
            }
            sslSocket.soTimeout = READ_TIMEOUT_MS
            return RawWebSocket(sslSocket, input, output)
        }

        private fun readHttpLine(input: InputStream): String? {
            val sb = StringBuilder(128)
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 8192) return sb.toString()
            }
        }
    }

    fun send(data: ByteArray) {
        if (isClosed) throw java.io.IOException("WebSocket closed")
        val frame = buildMaskedFrame(OP_BINARY, data)
        synchronized(output) {
            output.write(frame)
            output.flush()
        }
    }

    fun sendBatch(parts: List<ByteArray>) {
        if (isClosed) throw java.io.IOException("WebSocket closed")
        synchronized(output) {
            for (part in parts) {
                output.write(buildMaskedFrame(OP_BINARY, part))
            }
            output.flush()
        }
    }

    fun recv(): ByteArray? {
        while (!isClosed) {
            val (opcode, payload) = readFrame()
            when (opcode) {
                OP_CLOSE -> {
                    isClosed = true
                    runCatching {
                        synchronized(output) {
                            output.write(buildMaskedFrame(OP_CLOSE, payload.take(2).toByteArray()))
                            output.flush()
                        }
                    }
                    return null
                }
                OP_PING -> {
                    runCatching {
                        synchronized(output) {
                            output.write(buildMaskedFrame(OP_PONG, payload))
                            output.flush()
                        }
                    }
                }
                OP_PONG -> Unit
                OP_TEXT, OP_BINARY -> return payload
            }
        }
        return null
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        runCatching {
            synchronized(output) {
                output.write(buildMaskedFrame(OP_CLOSE, byteArrayOf()))
                output.flush()
            }
        }
        runCatching { socket.close() }
    }

    private fun buildMaskedFrame(opcode: Int, data: ByteArray): ByteArray {
        val length = data.size
        val maskKey = threadLocalMaskKey.get()
        random.nextBytes(maskKey)

        val headerSize = when {
            length < 126 -> 6
            length < 65536 -> 8
            else -> 14
        }
        val frame = ByteArray(headerSize + length)
        var pos = 0
        frame[pos++] = (0x80 or opcode).toByte()
        when {
            length < 126 -> frame[pos++] = (0x80 or length).toByte()
            length < 65536 -> {
                frame[pos++] = (0x80 or 126).toByte()
                frame[pos++] = (length ushr 8).toByte()
                frame[pos++] = length.toByte()
            }
            else -> {
                frame[pos++] = (0x80 or 127).toByte()
                val len = length.toLong()
                for (shift in 56 downTo 0 step 8) {
                    frame[pos++] = (len ushr shift).toByte()
                }
            }
        }
        System.arraycopy(maskKey, 0, frame, pos, 4)
        pos += 4
        for (i in data.indices) {
            frame[pos + i] = (data[i].toInt() xor maskKey[i and 3].toInt()).toByte()
        }
        return frame
    }

    private fun readFrame(): Pair<Int, ByteArray> {
        val header = readExactly(2)
        val opcode = header[0].toInt() and 0x0F
        val masked = (header[1].toInt() and 0x80) != 0
        var length = (header[1].toInt() and 0x7F).toLong()
        if (length == 126L) {
            val ext = readExactly(2)
            length = ((ext[0].toInt() and 0xFF).toLong() shl 8) or (ext[1].toInt() and 0xFF).toLong()
        } else if (length == 127L) {
            length = ByteBuffer.wrap(readExactly(8)).long
        }
        if (length > MAX_FRAME_SIZE) throw java.io.IOException("WebSocket frame too large: $length")
        val payload = if (masked) {
            val maskKey = readExactly(4)
            val data = readExactly(length.toInt())
            for (i in data.indices) {
                data[i] = (data[i].toInt() xor maskKey[i and 3].toInt()).toByte()
            }
            data
        } else {
            readExactly(length.toInt())
        }
        return opcode to payload
    }

    private fun readExactly(n: Int): ByteArray {
        val buf = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val read = input.read(buf, offset, n - offset)
            if (read == -1) throw java.io.EOFException("WebSocket connection closed")
            offset += read
        }
        return buf
    }
}

class WsHandshakeException(
    val statusCode: Int,
    val statusLine: String,
    val headers: Map<String, String> = emptyMap(),
    val location: String? = null
) : Exception("HTTP $statusCode: $statusLine") {
    val isRedirect: Boolean
        get() = statusCode in setOf(301, 302, 303, 307, 308)
}
