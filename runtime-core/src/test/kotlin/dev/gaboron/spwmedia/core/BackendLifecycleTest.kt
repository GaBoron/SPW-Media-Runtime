package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.ProviderFailure
import java.io.StringReader
import java.util.concurrent.*
import kotlin.test.*

class BackendLifecycleTest {
    @Test fun unavailableWorkerReturnsOwnerScopedStartupDetail() {
        val failure = assertFailsWith<ProviderFailure> { WorkerSession(listOf("missing-spw-test-backend"), obj(), "playback") {} }
        assertEquals("BACKEND_START_FAILED", failure.code)
        assertEquals("playback", failure.backend?.backend)
        assertNull(failure.backend?.exitCode)
        val history = RuntimeDiagnostics()
        history.record(publicError(failure, "plugin"))
        assertNull(history.forPlugin("plugin").single().error.backend)
        assertTrue(history.forPlugin("other").isEmpty())
    }
    @Test fun cancellationTerminatesTheObservedProcessTree() {
        val task = TaskRecord("operation", "connection", "plugin")
        val started = CompletableFuture<Process>()
        val executor = Executors.newSingleThreadExecutor()
        var child: ProcessHandle? = null
        try {
            val result = executor.submit<Any> { ManagedProcess.run(testBackend("tree"), task::check, task::onCancel, {}, onSpawn = { started.complete(it) }) }
            val parent = started.get(5, TimeUnit.SECONDS)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (child == null && System.nanoTime() < deadline) { child = parent.descendants().use { it.findFirst().orElse(null) }; Thread.sleep(10) }
            assertNotNull(child)
            assertTrue(task.cancel())
            assertFailsWith<ExecutionException> { result.get(5, TimeUnit.SECONDS) }
            child!!.onExit().get(5, TimeUnit.SECONDS)
            assertFalse(parent.isAlive); assertFalse(child!!.isAlive)
        } finally { task.cancel(); child?.takeIf { it.isAlive }?.destroyForcibly(); executor.shutdownNow() }
    }
    @Test fun nonzeroExitPreservesRedactedBoundedDetails() {
        val task = TaskRecord("operation", "connection", "plugin")
        val failure = assertFailsWith<ProviderFailure> { ManagedProcess.run(testBackend("noisy"), task::check, task::onCancel, {}) }
        assertEquals("MEDIA_PROCESS_FAILED", failure.code)
        val detail = assertNotNull(failure.backend)
        assertEquals(9, detail.exitCode)
        assertTrue(detail.stderrTail.toByteArray().size <= 8192)
        assertContains(detail.stderrTail, "Unknown encoder")
        assertFalse("sample-private-token" in detail.stderrTail)
        assertFalse("password" in detail.stderrTail)
        val history = RuntimeDiagnostics()
        history.record(publicError(failure, "plugin", "operation"))
        assertNull(history.forPlugin("plugin").single().error.backend)
        assertTrue(history.forPlugin("other").isEmpty())
    }
    @Test fun outputAndControlLinesAreBoundedWithoutNewlines() {
        assertEquals(1024, BackendOutput(1024).capture(StringReader("x".repeat(500_000))).length)
        val unicode = BackendOutput(1024).capture(StringReader("中😀".repeat(10_000)))
        assertTrue(unicode.toByteArray().size <= 1024)
        assertFalse('\uFFFD' in unicode)
        assertFailsWith<IllegalArgumentException> { readControlLine(StringReader("x".repeat(1_048_577))) }
        val redacted = BackendRedaction.tail("echo abc-private-value", listOf("--token", "abc-private-value"))
        assertFalse("abc-private-value" in redacted)
        assertFalse("bearer-private-value" in BackendRedaction.tail("echo bearer-private-value", listOf("-headers", "Authorization: Bearer bearer-private-value")))
        assertFalse("short=xy" in BackendRedaction.tail("short=xy", listOf("--password", "xy")))
        assertEquals("[redacted]", BackendRedaction.tail("a", listOf("--token", "a", "--secret", "r")))
        assertFalse("cookie-private-value" in BackendRedaction.tail("echo cookie-private-value", listOf("{\"cookie\":\"sid=cookie-private-value\"}")))
        assertTrue(BackendRedaction.tail("中".repeat(10_000)).toByteArray().size <= 8192)
    }
    @Test fun closeCompletesPendingCommandsAndTerminatesWorker() {
        var process: Process? = null
        val worker = WorkerSession(testBackend(), obj(), "test") { process = it }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<Any> { worker.command("hang", obj()) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (worker.state().text("state") != "WAITING" && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("WAITING", worker.state().text("state"))
            worker.close(); worker.close()
            val failure = assertFailsWith<ExecutionException> { pending.get(3, TimeUnit.SECONDS) }
            assertEquals("SESSION_CLOSED", (failure.cause as ProviderFailure).code)
            assertFalse(process!!.isAlive)
        } finally { worker.close(); executor.shutdownNow() }
    }
    @Test fun unexpectedExitIsAStableSessionFailure() {
        WorkerSession(testBackend(), obj(), "test") {}.use { worker ->
            assertEquals("BACKEND_EXITED", assertFailsWith<ProviderFailure> { worker.command("exit", obj()) }.code)
            assertEquals("BACKEND_EXITED", worker.failure()?.code)
            assertEquals("FAILED", worker.state().text("state"))
        }
    }
}
