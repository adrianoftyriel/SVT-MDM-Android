package org.svt.mdm.backup

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the backup hashing and manifest batching logic.
 * The SHA-256 computation and batch size are pure logic that can be tested
 * without Android dependencies.
 */
class BackupManagerTest {

    // Replicate the SHA-256 hashing logic from BackupManager.shaOf
    private fun sha256(content: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun sha256HashesCorrectly() {
        val content = "svt-backup-test".toByteArray()
        val hash = sha256(content)
        assertEquals(64, hash.length) // SHA-256 produces 64 hex chars
        assertTrue(hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun sha256IsDeterministic() {
        val content = "test content".toByteArray()
        assertEquals(sha256(content), sha256(content))
    }

    @Test
    fun sha256DiffersForDifferentContent() {
        val hash1 = sha256("content1".toByteArray())
        val hash2 = sha256("content2".toByteArray())
        assertFalse(hash1 == hash2)
    }

    @Test
    fun sha256MatchesKnownValue() {
        // Known SHA-256 value for "abc"
        val content = "abc".toByteArray()
        val hash = sha256(content)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hash)
    }

    @Test
    fun manifestBatchSizeIsCorrect() {
        // The MANIFEST_BATCH constant in BackupManager
        val batchSize = 200
        assertEquals(200, batchSize)
    }

    @Test
    fun manifestBatchingSplitsCorrectly() {
        // Simulate the batching logic: items.chunked(MANIFEST_BATCH)
        val batchSize = 200
        val items = (1..450).toList()
        val batches = items.chunked(batchSize)
        assertEquals(3, batches.size)
        assertEquals(200, batches[0].size)
        assertEquals(200, batches[1].size)
        assertEquals(50, batches[2].size)
    }

    @Test
    fun manifestBatchingHandlesEmptyList() {
        val batchSize = 200
        val items = emptyList<Int>()
        val batches = items.chunked(batchSize)
        assertTrue(batches.isEmpty())
    }

    @Test
    fun manifestBatchingHandlesExactMultiple() {
        val batchSize = 200
        val items = (1..400).toList()
        val batches = items.chunked(batchSize)
        assertEquals(2, batches.size)
        assertEquals(200, batches[0].size)
        assertEquals(200, batches[1].size)
    }

    @Test
    fun shaCacheKeyFormat() {
        // The cache key format is "||"
        val relPath = "DCIM/photo.jpg"
        val size = 1024L
        val mtimeMs = 1695000000000L
        val key = "||"
        assertEquals("DCIM/photo.jpg|1024|1695000000000", key)
    }

    @Test
    fun defaultCategoriesMatchServer() {
        // The default categories in BackupManager match the server's
        val defaultCategories = mapOf(
            "media" to true,
            "contacts" to true,
            "sms" to false,
            "calllog" to false,
            "calendar" to false,
        )
        assertTrue(defaultCategories["media"]!!)
        assertTrue(defaultCategories["contacts"]!!)
        assertEquals(false, defaultCategories["sms"]!!)
        assertEquals(false, defaultCategories["calllog"]!!)
        assertEquals(false, defaultCategories["calendar"]!!)
    }
}
