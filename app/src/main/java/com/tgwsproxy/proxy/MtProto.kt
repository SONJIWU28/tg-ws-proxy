package com.tgwsproxy.proxy

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.math.abs

object MtProto {
    const val HANDSHAKE_LEN = 64
    private const val SKIP_LEN = 8
    private const val PREKEY_LEN = 32
    private const val KEY_LEN = 32
    private const val IV_LEN = 16
    private const val PROTO_TAG_POS = 56
    private const val DC_IDX_POS = 60

    const val PROTO_ABRIDGED = 0xEFEFEFEF.toInt()
    const val PROTO_INTERMEDIATE = 0xEEEEEEEE.toInt()
    const val PROTO_PADDED_INTERMEDIATE = 0xDDDDDDDD.toInt()

    private val RESERVED_FIRST_BYTES = setOf(0xEF)
    private val RESERVED_STARTS = setOf(
        byteArrayOf(0x48, 0x45, 0x41, 0x44),
        byteArrayOf(0x50, 0x4F, 0x53, 0x54),
        byteArrayOf(0x47, 0x45, 0x54, 0x20),
        byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte()),
        byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte()),
        byteArrayOf(0x16, 0x03, 0x01, 0x02)
    )
    private val RESERVED_CONTINUE = byteArrayOf(0, 0, 0, 0)
    private val zero64 = ByteArray(HANDSHAKE_LEN)
    private val random = SecureRandom()

    data class ClientHandshakeInfo(
        val dcId: Int,
        val isMedia: Boolean,
        val protoTagBytes: ByteArray,
        val protoTagInt: Int,
        val clientDecPrekeyIv: ByteArray
    )

    data class CipherPair(
        val decryptor: AesCtr,
        val encryptor: AesCtr
    )

    fun tryClientHandshake(handshake: ByteArray, secret: ByteArray): ClientHandshakeInfo? {
        if (handshake.size < HANDSHAKE_LEN) return null
        return try {
            val clientDecPrekeyIv = handshake.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
            val clientDecPrekey = clientDecPrekeyIv.copyOfRange(0, PREKEY_LEN)
            val clientDecIv = clientDecPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)
            val clientDecKey = CryptoUtils.sha256(clientDecPrekey, secret)
            val decryptor = AesCtr(clientDecKey, clientDecIv)
            val decrypted = decryptor.process(handshake)
            val protoTagBytes = decrypted.copyOfRange(PROTO_TAG_POS, PROTO_TAG_POS + 4)
            val protoTagInt = ByteBuffer.wrap(protoTagBytes).order(ByteOrder.LITTLE_ENDIAN).int
            if (protoTagInt != PROTO_ABRIDGED && protoTagInt != PROTO_INTERMEDIATE && protoTagInt != PROTO_PADDED_INTERMEDIATE) {
                return null
            }
            val dcIdx = ByteBuffer.wrap(decrypted, DC_IDX_POS, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            val dcId = abs(dcIdx)
            if (dcId !in 1..1000) return null
            ClientHandshakeInfo(
                dcId = dcId,
                isMedia = dcIdx < 0,
                protoTagBytes = protoTagBytes,
                protoTagInt = protoTagInt,
                clientDecPrekeyIv = clientDecPrekeyIv
            )
        } catch (_: Exception) {
            null
        }
    }

    fun createClientCiphers(clientDecPrekeyIv: ByteArray, secret: ByteArray): CipherPair {
        val clientDecPrekey = clientDecPrekeyIv.copyOfRange(0, PREKEY_LEN)
        val clientDecIv = clientDecPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)
        val clientDecKey = CryptoUtils.sha256(clientDecPrekey, secret)

        val clientEncPrekeyIv = clientDecPrekeyIv.reversedArray()
        val clientEncKey = CryptoUtils.sha256(clientEncPrekeyIv.copyOfRange(0, PREKEY_LEN), secret)
        val clientEncIv = clientEncPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)

        val decryptor = AesCtr(clientDecKey, clientDecIv)
        val encryptor = AesCtr(clientEncKey, clientEncIv)
        decryptor.skip(HANDSHAKE_LEN)
        return CipherPair(decryptor = decryptor, encryptor = encryptor)
    }

    fun createRelayCiphers(relayInit: ByteArray): CipherPair {
        val relayEncKey = relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN)
        val relayEncIv = relayInit.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val relayDecPrekeyIv = relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN).reversedArray()
        val relayDecKey = relayDecPrekeyIv.copyOfRange(0, KEY_LEN)
        val relayDecIv = relayDecPrekeyIv.copyOfRange(KEY_LEN, KEY_LEN + IV_LEN)

        val decryptor = AesCtr(relayDecKey, relayDecIv)
        val encryptor = AesCtr(relayEncKey, relayEncIv)
        encryptor.skip(HANDSHAKE_LEN)
        return CipherPair(decryptor = decryptor, encryptor = encryptor)
    }

    fun generateRelayInit(protoTagBytes: ByteArray, dcIdx: Int): ByteArray {
        while (true) {
            val rnd = ByteArray(HANDSHAKE_LEN)
            random.nextBytes(rnd)
            if ((rnd[0].toInt() and 0xFF) in RESERVED_FIRST_BYTES) continue
            if (RESERVED_STARTS.any { rnd.copyOfRange(0, 4).contentEquals(it) }) continue
            if (rnd.copyOfRange(4, 8).contentEquals(RESERVED_CONTINUE)) continue

            val relayCipher = AesCtr(
                rnd.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN),
                rnd.copyOfRange(SKIP_LEN + PREKEY_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
            )
            val keystream = relayCipher.process(zero64)
            val dcBytes = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(dcIdx.toShort()).array()
            val tailPlain = ByteArray(8)
            System.arraycopy(protoTagBytes, 0, tailPlain, 0, 4)
            System.arraycopy(dcBytes, 0, tailPlain, 4, 2)
            val randomTail = ByteArray(2)
            random.nextBytes(randomTail)
            System.arraycopy(randomTail, 0, tailPlain, 6, 2)

            val result = rnd.copyOf()
            for (i in 0 until 8) {
                result[PROTO_TAG_POS + i] = (tailPlain[i].toInt() xor keystream[PROTO_TAG_POS + i].toInt()).toByte()
            }
            return result
        }
    }

    fun isHttpTransport(data: ByteArray): Boolean {
        if (data.size < 4) return false
        val start = String(data, 0, minOf(8, data.size), Charsets.US_ASCII)
        return start.startsWith("POST ") ||
            start.startsWith("GET ") ||
            start.startsWith("HEAD ") ||
            start.startsWith("OPTIONS ")
    }
}

class MsgSplitter(relayInit: ByteArray, private val protoInt: Int) {
    private val decryptor = AesCtr(relayInit.copyOfRange(8, 40), relayInit.copyOfRange(40, 56))
    private var cipherBuf = ByteArray(0)
    private var plainBuf = ByteArray(0)
    private var disabled = false

    init {
        decryptor.skip(MtProto.HANDSHAKE_LEN)
    }

    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipherBuf += chunk
        plainBuf += decryptor.process(chunk)

        val parts = mutableListOf<ByteArray>()
        while (cipherBuf.isNotEmpty()) {
            val packetLen = nextPacketLen() ?: break
            if (packetLen <= 0) {
                parts += cipherBuf
                cipherBuf = ByteArray(0)
                plainBuf = ByteArray(0)
                disabled = true
                break
            }
            parts += cipherBuf.copyOfRange(0, packetLen)
            cipherBuf = cipherBuf.copyOfRange(packetLen, cipherBuf.size)
            plainBuf = plainBuf.copyOfRange(packetLen, plainBuf.size)
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipherBuf.isEmpty()) return emptyList()
        val tail = cipherBuf
        cipherBuf = ByteArray(0)
        plainBuf = ByteArray(0)
        return listOf(tail)
    }

    private fun nextPacketLen(): Int? {
        if (plainBuf.isEmpty()) return null
        return when (protoInt) {
            MtProto.PROTO_ABRIDGED -> nextAbridgedLen()
            MtProto.PROTO_INTERMEDIATE, MtProto.PROTO_PADDED_INTERMEDIATE -> nextIntermediateLen()
            else -> 0
        }
    }

    private fun nextAbridgedLen(): Int? {
        if (plainBuf.isEmpty()) return null
        val first = plainBuf[0].toInt() and 0xFF
        val headerLen: Int
        val payloadLen: Int
        if (first == 0x7F || first == 0xFF) {
            if (plainBuf.size < 4) return null
            payloadLen = ((plainBuf[1].toInt() and 0xFF) or
                ((plainBuf[2].toInt() and 0xFF) shl 8) or
                ((plainBuf[3].toInt() and 0xFF) shl 16)) * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }
        if (payloadLen <= 0) return 0
        val packetLen = headerLen + payloadLen
        if (plainBuf.size < packetLen) return null
        return packetLen
    }

    private fun nextIntermediateLen(): Int? {
        if (plainBuf.size < 4) return null
        val payloadLen = ByteBuffer.wrap(plainBuf, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int and 0x7FFFFFFF
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        if (plainBuf.size < packetLen) return null
        return packetLen
    }
}
