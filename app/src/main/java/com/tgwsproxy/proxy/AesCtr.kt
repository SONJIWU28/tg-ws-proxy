package com.tgwsproxy.proxy

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesCtr(key: ByteArray, iv: ByteArray) {
    private val cipher: Cipher

    init {
        require(key.size == 32)
        require(iv.size == 16)
        cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    fun process(data: ByteArray): ByteArray {
        return cipher.update(data) ?: ByteArray(0)
    }

    fun skip(n: Int) {
        if (n <= 0) return
        process(ByteArray(n))
    }
}

object CryptoUtils {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) {
            digest.update(part)
        }
        return digest.digest()
    }
}
