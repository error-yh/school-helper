/* SPDX-License-Identifier: GPL-3.0-only
 * Tests verifying standard JCA PBKDF2 600,000 rounds and AES-GCM container authentication.
 */
package com.aggapp.app.migration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimetableEncryptionTest {

    @Test
    fun testProductionEncryptionRoundTrip_success() {
        val plaintext = "{\"testKey\":\"测试中文内容数据1234567890\",\"timestamp\":1730700000}".toByteArray(Charsets.UTF_8)
        val password = "StrongPassword@2026".toCharArray()

        val encrypted = TimetableEncryption.encrypt(plaintext, password)
        assertNotNull(encrypted)
        assertTrue(encrypted.size > plaintext.size)

        val decrypted = TimetableEncryption.decrypt(encrypted, password)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun testWrongPassword_rejected() {
        val plaintext = "Hello Sensitive Timetable Data".toByteArray(Charsets.UTF_8)
        val password = "CorrectPassword123".toCharArray()
        val wrongPassword = "WrongPassword456".toCharArray()

        val encrypted = TimetableEncryption.encrypt(plaintext, password)

        try {
            TimetableEncryption.decrypt(encrypted, wrongPassword)
            fail("Expected rejection on wrong password")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("密码错误或备份文件已损坏") == true)
        }
    }

    @Test
    fun testTamperedCiphertext_rejected() {
        val plaintext = "Secret Data".toByteArray(Charsets.UTF_8)
        val password = "Pass123".toCharArray()

        val encrypted = TimetableEncryption.encrypt(plaintext, password)
        // Flip one byte in the ciphertext payload
        encrypted[encrypted.size - 5] = (encrypted[encrypted.size - 5].toInt() xor 0xFF).toByte()

        try {
            TimetableEncryption.decrypt(encrypted, password)
            fail("Expected rejection on tampered ciphertext")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("密码错误或备份文件已损坏") == true)
        }
    }

    @Test
    fun testTamperedAadHeader_rejected() {
        val plaintext = "Secret Data".toByteArray(Charsets.UTF_8)
        val password = "Pass123".toCharArray()

        val encrypted = TimetableEncryption.encrypt(plaintext, password)
        // Tamper formatVersion in AAD header (byte offset 11)
        encrypted[11] = (encrypted[11] + 1).toByte()

        try {
            TimetableEncryption.decrypt(encrypted, password)
            fail("Expected rejection on tampered AAD header")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun testTruncatedContainer_rejected() {
        val plaintext = "Secret Data".toByteArray(Charsets.UTF_8)
        val password = "Pass123".toCharArray()

        val encrypted = TimetableEncryption.encrypt(plaintext, password)
        val truncated = encrypted.copyOf(30) // less than minimum container size

        try {
            TimetableEncryption.decrypt(truncated, password)
            fail("Expected rejection on truncated container")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("过小或已损坏") == true)
        }
    }
}
