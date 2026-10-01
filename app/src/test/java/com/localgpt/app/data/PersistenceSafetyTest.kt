package com.localgpt.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM unit tests for the data/persistence hardening (B2, B5, B6, B28, P6).
 *
 * All targets are pure-JVM internals of ChatRepository / SettingsRepository —
 * no Android Context involved, so they run under plain JUnit 4.
 */
class PersistenceSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── B28: conversation-id validation ───────────────────────────────

    @Test
    fun `valid ids are accepted`() {
        assertTrue(isValidConversationId("abc123"))
        assertTrue(isValidConversationId("550e8400-e29b-41d4-a716-446655440000"))
        assertTrue(isValidConversationId("chat-1-new"))
        assertTrue(isValidConversationId("A"))
    }

    @Test
    fun `path traversal and suffix smuggling are rejected`() {
        assertFalse(isValidConversationId(""))
        assertFalse(isValidConversationId("../secret"))
        assertFalse(isValidConversationId("a/b"))
        assertFalse(isValidConversationId("..\\windows"))
        assertFalse(isValidConversationId("conv.json"))
        assertFalse(isValidConversationId("conv.txt"))
        assertFalse(isValidConversationId("a b"))
        assertFalse(isValidConversationId("café"))
        assertFalse(isValidConversationId(".hidden"))
    }

    // ── B2: unique tmp files per write ──────────────────────────────────

    @Test
    fun `each write gets a distinct tmp file name`() {
        val target = File(tmp.root, "conv.json")
        val names = (1..50).map { uniqueTmpFileFor(target).name }.toSet()
        assertEquals(50, names.size)
        names.forEach { assertTrue(it.endsWith(".tmp")) }
    }

    @Test
    fun `writeAtomically round-trips and leaves no tmp files`() {
        val target = File(tmp.root, "conv.json")
        val content = "{\"id\":\"x\"}".repeat(1000)
        assertTrue(writeAtomically(target, content))
        assertEquals(content, target.readText())
        // Second write overwrites; no *.tmp must remain either way.
        assertTrue(writeAtomically(target, "v2"))
        assertEquals("v2", target.readText())
        val leftovers = tmp.root.listFiles { f -> f.name.endsWith(".tmp") }
        assertTrue("tmp leftovers: ${leftovers?.map { it.name }}", leftovers.isNullOrEmpty())
    }

    // ── B2/B6: striped write locks ──────────────────────────────────────

    @Test
    fun `same id yields same lock, different ids yield different locks`() {
        val locks = StripedWriteLocks()
        assertSame(locks.forId("a"), locks.forId("a"))
        assertNotSame(locks.forId("a"), locks.forId("b"))
        assertNotEquals(locks.forId("a"), locks.forId("b"))
    }

    // ── B5: migration runs until it reports success ─────────────────────

    @Test
    fun `migration returning false is retried until true`() =
        runBlocking {
            val tracker = SettingsRepository.MigrationTracker()
            var runs = 0
            tracker.runIfNeeded { runs++; false }
            tracker.runIfNeeded { runs++; false }
            tracker.runIfNeeded { runs++; true }
            tracker.runIfNeeded { runs++; true }
            assertEquals("false must not mark the migration complete", 3, runs)
            assertTrue(tracker.completed)
        }

    @Test
    fun `successful migration runs exactly once`() =
        runBlocking {
            val tracker = SettingsRepository.MigrationTracker()
            var runs = 0
            repeat(3) { tracker.runIfNeeded { runs++; true } }
            assertEquals(1, runs)
            assertTrue(tracker.completed)
        }

    @Test
    fun `throwing migration is retried, not swallowed as done`() =
        runBlocking {
            val tracker = SettingsRepository.MigrationTracker()
            var runs = 0
            tracker.runIfNeeded {
                runs++
                throw RuntimeException("keystore broken")
            }
            assertFalse(tracker.completed)
            tracker.runIfNeeded { runs++; true }
            assertEquals(2, runs)
            assertTrue(tracker.completed)
        }

    @Test
    fun `concurrent migration attempts execute the block only once on success`() =
        runBlocking {
            val tracker = SettingsRepository.MigrationTracker()
            var runs = 0
            (1..10).map { async { tracker.runIfNeeded { runs++; kotlinx.coroutines.delay(5); true } } }.awaitAll()
            assertEquals(1, runs)
        }
}
