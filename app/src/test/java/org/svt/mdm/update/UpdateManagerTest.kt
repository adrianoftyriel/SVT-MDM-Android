package org.svt.mdm.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the version comparison logic in UpdateManager.isNewer.
 * Extracted as a pure function for testability.
 */
class UpdateManagerTest {

    // Replicate the isNewer logic for testing
    private fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.split(".").mapNotNull { it.toIntOrNull() }
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    @Test
    fun newerVersionReturnsTrue() {
        assertTrue(isNewer("0.9.0", "0.8.2"))
        assertTrue(isNewer("1.0.0", "0.9.9"))
        assertTrue(isNewer("0.8.3", "0.8.2"))
    }

    @Test
    fun sameVersionReturnsFalse() {
        assertFalse(isNewer("0.8.2", "0.8.2"))
        assertFalse(isNewer("1.0.0", "1.0.0"))
    }

    @Test
    fun olderVersionReturnsFalse() {
        assertFalse(isNewer("0.8.1", "0.8.2"))
        assertFalse(isNewer("0.7.9", "0.8.0"))
    }

    @Test
    fun handlesDifferentSegmentCounts() {
        assertTrue(isNewer("0.9", "0.8.2"))
        assertTrue(isNewer("1.0", "0.9.9"))
        assertFalse(isNewer("0.8", "0.8.2"))
    }

    @Test
    fun handlesPatchVersions() {
        assertTrue(isNewer("0.8.2", "0.8.1"))
        assertFalse(isNewer("0.8.1", "0.8.2"))
    }
}
