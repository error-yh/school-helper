/* SPDX-License-Identifier: GPL-3.0-only
 * Cryptographic binary container using standard JCA PBKDF2WithHmacSHA256 and AES-256-GCM.
 */
package com.aggapp.app.migration

import com.lingion.sleepy.data.backup.TIMETABLE_BACKUP_FORMAT_VERSION
import com.lingion.sleepy.data.backup.TIMETABLE_BACKUP_MAX_CONTAINER_BYTES
import com.lingion.sleepy.data.backup.TIMETABLE_BACKUP_MAX_PLAINTEXT_BYTES
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object TimetableEncryption {

    private val MAGIC = "AGTBKP01".toByteArray(Charsets.US_ASCII) // 8 bytes
    private const val KDF_ALGO_PBKDF2_HMAC_SHA256: Byte = 1
    private const val PBKDF2_ITERATIONS = 600000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_LENGTH_BYTES = 16
    private const val NONCE_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        require(plaintext.size <= TIMETABLE_BACKUP_MAX_PLAINTEXT_BYTES) {
            "明文体积超出限制: ${plaintext.size} > $TIMETABLE_BACKUP_MAX_PLAINTEXT_BYTES"
        }
        require(passphrase.isNotEmpty()) { "密码不能为空" }

        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH_BYTES)
        random.nextBytes(salt)

        val nonce = ByteArray(NONCE_LENGTH_BYTES)
        random.nextBytes(nonce)

        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keySpec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val secretKeyBytes = keyFactory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(secretKeyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val headerBuffer = ByteBuffer.allocate(8 + 4 + 1 + 4 + 4).order(ByteOrder.BIG_ENDIAN)
        headerBuffer.put(MAGIC)
        headerBuffer.putInt(TIMETABLE_BACKUP_FORMAT_VERSION)
        headerBuffer.put(KDF_ALGO_PBKDF2_HMAC_SHA256)
        headerBuffer.putInt(PBKDF2_ITERATIONS)
        headerBuffer.putInt(plaintext.size + (GCM_TAG_LENGTH_BITS / 8))
        val aad = headerBuffer.array()

        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plaintext)

        val totalLength = aad.size + SALT_LENGTH_BYTES + NONCE_LENGTH_BYTES + ciphertext.size
        require(totalLength <= TIMETABLE_BACKUP_MAX_CONTAINER_BYTES) {
            "加密容器总体积超出限制: $totalLength > $TIMETABLE_BACKUP_MAX_CONTAINER_BYTES"
        }

        val container = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)
        container.put(aad)
        container.put(salt)
        container.put(nonce)
        container.put(ciphertext)

        return container.array()
    }

    fun decrypt(containerBytes: ByteArray, passphrase: CharArray): ByteArray {
        require(containerBytes.size <= TIMETABLE_BACKUP_MAX_CONTAINER_BYTES) {
            "容器体积超出上限: ${containerBytes.size} > $TIMETABLE_BACKUP_MAX_CONTAINER_BYTES"
        }
        val minHeaderSize = 8 + 4 + 1 + 4 + 4 + SALT_LENGTH_BYTES + NONCE_LENGTH_BYTES + (GCM_TAG_LENGTH_BITS / 8)
        require(containerBytes.size >= minHeaderSize) {
            "备份容器文件过小或已损坏"
        }

        val buffer = ByteBuffer.wrap(containerBytes).order(ByteOrder.BIG_ENDIAN)

        val magic = ByteArray(8)
        buffer.get(magic)
        require(magic.contentEquals(MAGIC)) { "非法的备份文件Magic头" }

        val formatVersion = buffer.getInt()
        require(formatVersion == TIMETABLE_BACKUP_FORMAT_VERSION) { "不支持的容器格式版本: $formatVersion" }

        val kdfAlgo = buffer.get()
        require(kdfAlgo == KDF_ALGO_PBKDF2_HMAC_SHA256) { "不支持的KDF算法: $kdfAlgo" }

        val iterations = buffer.getInt()
        require(iterations == PBKDF2_ITERATIONS) { "KDF迭代轮次不匹配: $iterations != $PBKDF2_ITERATIONS" }

        val ciphertextLen = buffer.getInt()
        val expectedRemaining = SALT_LENGTH_BYTES + NONCE_LENGTH_BYTES + ciphertextLen
        require(buffer.remaining() == expectedRemaining) {
            "容器声明的密文长度与实际文件大小不匹配"
        }

        // Reconstruct AAD
        val aadBuffer = ByteBuffer.allocate(8 + 4 + 1 + 4 + 4).order(ByteOrder.BIG_ENDIAN)
        aadBuffer.put(magic)
        aadBuffer.putInt(formatVersion)
        aadBuffer.put(kdfAlgo)
        aadBuffer.putInt(iterations)
        aadBuffer.putInt(ciphertextLen)
        val aad = aadBuffer.array()

        val salt = ByteArray(SALT_LENGTH_BYTES)
        buffer.get(salt)

        val nonce = ByteArray(NONCE_LENGTH_BYTES)
        buffer.get(nonce)

        val ciphertext = ByteArray(ciphertextLen)
        buffer.get(ciphertext)

        val keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keySpec = PBEKeySpec(passphrase, salt, iterations, KEY_LENGTH_BITS)
        val secretKeyBytes = keyFactory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(secretKeyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        cipher.updateAAD(aad)

        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            throw IllegalArgumentException("密码错误或备份文件已损坏/被篡改")
        }

        require(plaintext.size <= TIMETABLE_BACKUP_MAX_PLAINTEXT_BYTES) {
            "解密后的明文体积超出限制"
        }

        return plaintext
    }
}
