package dev.gaboron.spwmedia.sdk

import java.util.concurrent.*
import kotlin.test.*

class SdkLifecycleTest {
    @Test fun failedSessionReleaseCanBeRetriedAndCommandsAreRejected() = SdkFixture().use { fixture ->
        fixture.start()
        fixture.connect().use { client ->
            val session = fixture.session(client)
            fixture.failRelease.set(true)
            assertEquals("IO_FAILED", assertFailsWith<MediaRuntimeException> { session.close() }.detail.code)
            assertEquals("SESSION_CLOSED", assertFailsWith<MediaRuntimeException> { session.snapshot() }.detail.code)
            session.close(); session.close()
            assertEquals(1, fixture.releases.get())
        }
    }
    @Test fun concurrentCloseReleasesExactlyOnce() = SdkFixture().use { fixture ->
        fixture.start()
        fixture.connect().use { client ->
            val session = fixture.session(client)
            val pool = Executors.newFixedThreadPool(4)
            try { (1..12).map { pool.submit { session.close() } }.forEach { it.get(3, TimeUnit.SECONDS) } }
            finally { pool.shutdownNow() }
            assertEquals(1, fixture.releases.get())
        }
    }
    @Test fun clientCloseFailureIsRetryable() = SdkFixture().use { fixture ->
        fixture.start()
        val client = fixture.connect()
        fixture.session(client)
        fixture.failRelease.set(true)
        assertFailsWith<MediaRuntimeException> { client.close() }
        assertEquals("CLIENT_CLOSING", assertFailsWith<MediaRuntimeException> { client.capabilities() }.detail.code)
        client.close(); client.close()
        assertEquals(1, fixture.releases.get())
    }
    @Test fun runtimeStopAllowsLocalHandlesToFinishClosing() = SdkFixture().use { fixture ->
        fixture.start()
        val client = fixture.connect()
        val session = fixture.session(client)
        val lease = client.tools.acquire("ffmpeg")
        fixture.stop()
        session.close(); lease.close(); client.close()
        assertEquals(1, fixture.releases.get())
    }
    @Test fun cancellingFutureViewDoesNotCancelTaskOrPreventItsTerminalResult() = SdkFixture().use { fixture ->
        fixture.start()
        fixture.connect().use { client ->
            val task = client.invoke("test", "wait")
            assertTrue(task.result.toCompletableFuture().cancel(false))
            task.result.toCompletableFuture().get(3, TimeUnit.SECONDS)
            assertEquals("SUCCEEDED", task.snapshot().state)
        }
    }
    @Test fun unwrapAcceptsBothJavaWrappers() {
        val original = MediaRuntimeException(dev.gaboron.spwmedia.protocol.RuntimeError("EXPECTED", "expected"))
        assertSame(original, MediaRuntimeException.unwrap(ExecutionException(CompletionException(original))))
        assertNull(MediaRuntimeException.unwrap(IllegalArgumentException("unrelated")))
    }
}
