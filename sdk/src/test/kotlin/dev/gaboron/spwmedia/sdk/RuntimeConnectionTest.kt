package dev.gaboron.spwmedia.sdk

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function
import kotlin.test.*

class RuntimeConnectionTest {
    // Allow JMX/class loading on a cold CI worker; exhaustion has its own short deadline below.
    private val options = ConnectionOptions(5_000, 20, 30, 100)
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition()) { check(System.nanoTime() < deadline) { "Condition did not become true" }; Thread.sleep(5) }
    }
    @Test fun lateStartupConnectsAndCoalescesWaiters() = SdkFixture().use { fixture ->
        MediaRuntime.connectWhenAvailable("lifecycle", options).use { connection ->
            val first = connection.reconnect().toCompletableFuture()
            val second = connection.reconnect().toCompletableFuture()
            fixture.start()
            assertSame(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS))
            assertEquals(1, connection.snapshot.generation)
            assertEquals(ConnectionStatus.CONNECTED, connection.snapshot.status)
        }
    }
    @Test fun exhaustedRoundRequiresExplicitReconnect() = SdkFixture().use { fixture ->
        MediaRuntime.connectWhenAvailable("lifecycle", ConnectionOptions(80, 10, 30, 100)).use { connection ->
            waitUntil { connection.snapshot.status == ConnectionStatus.UNAVAILABLE }
            fixture.start(); Thread.sleep(100)
            assertNull(connection.current)
            connection.reconnect().toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertEquals(ConnectionStatus.CONNECTED, connection.snapshot.status)
        }
    }
    @Test fun restartCreatesNewGenerationAndCleansEachOldClientOnce() = SdkFixture().use { fixture ->
        fixture.start()
        val cleanups = AtomicInteger()
        val connection = MediaRuntime.connectWhenAvailable("lifecycle", options, Function { cleanups.incrementAndGet(); CompletableFuture.completedFuture(null) })
        waitUntil { connection.current != null }
        val old = connection.current!!
        fixture.session(old)
        fixture.stop(); fixture.start()
        waitUntil { connection.snapshot.generation == 2L }
        assertNotSame(old, connection.current)
        assertEquals(1, cleanups.get())
        connection.close(); connection.close()
        assertEquals(2, cleanups.get())
        assertEquals(ConnectionStatus.CLOSED, connection.snapshot.status)
    }
    @Test fun cleanupTimeoutPausesRecoveryUntilExplicitRetry() = SdkFixture().use { fixture ->
        fixture.start()
        val held = CompletableFuture<Void>()
        val connection = MediaRuntime.connectWhenAvailable("lifecycle", options, Function { held })
        waitUntil { connection.current != null }
        val failure = assertFailsWith<ExecutionException> { connection.reconnect().toCompletableFuture().get(3, TimeUnit.SECONDS) }
        val detail = MediaRuntimeException.unwrap(failure)!!.detail
        assertEquals("CLEANUP_FAILED", detail.code)
        assertTrue(detail.message.contains("timed out"))
        assertNull(connection.current)
        assertEquals(1, connection.snapshot.generation)
        held.complete(null)
        connection.reconnect().toCompletableFuture().get(3, TimeUnit.SECONDS)
        assertEquals(2, connection.snapshot.generation)
        connection.close()
    }
    @Test fun cleanupFailureReportsItsCauseAndAllowsExplicitRetry() = SdkFixture().use { fixture ->
        fixture.start()
        val fail = java.util.concurrent.atomic.AtomicBoolean(true)
        val failure = java.io.IOException("process shutdown failed")
        val connection = MediaRuntime.connectWhenAvailable("lifecycle", options, Function {
            if (fail.compareAndSet(true, false)) CompletableFuture.failedFuture(failure)
            else CompletableFuture.completedFuture(null)
        })
        waitUntil { connection.current != null }
        val thrown = assertFailsWith<ExecutionException> { connection.reconnect().toCompletableFuture().get(3, TimeUnit.SECONDS) }
        val error = MediaRuntimeException.unwrap(thrown)!!
        assertEquals("CLEANUP_FAILED", error.detail.code)
        assertTrue(error.detail.message.contains("IOException"))
        assertSame(failure, error.cause)
        assertEquals(ConnectionStatus.UNAVAILABLE, connection.snapshot.status)
        connection.reconnect().toCompletableFuture().get(3, TimeUnit.SECONDS)
        assertEquals(2, connection.snapshot.generation)
        connection.close()
    }
    @Test fun closePreventsLateCleanupFromPublishingAnotherGeneration() = SdkFixture().use { fixture ->
        fixture.start()
        val held = CompletableFuture<Void>()
        val connection = MediaRuntime.connectWhenAvailable("lifecycle", options, Function { held })
        waitUntil { connection.current != null }
        val reconnect = connection.reconnect().toCompletableFuture()
        assertFailsWith<MediaRuntimeException> { connection.close() }
        held.complete(null)
        Thread.sleep(50)
        assertTrue(reconnect.isCompletedExceptionally)
        assertEquals(ConnectionStatus.CLOSED, connection.snapshot.status)
        assertEquals(1, connection.snapshot.generation)
    }
}
