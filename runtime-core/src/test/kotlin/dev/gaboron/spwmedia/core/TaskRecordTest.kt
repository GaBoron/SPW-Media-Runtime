package dev.gaboron.spwmedia.core

import kotlinx.serialization.json.JsonNull
import kotlin.test.*

class TaskRecordTest {
    @Test fun successCannotBeReplacedByLateFailure() {
        val task = TaskRecord("task", "connection", "plugin")
        assertTrue(task.running()); task.success(JsonNull)
        task.fail(IllegalArgumentException("late failure"))
        assertEquals("SUCCEEDED", task.snapshot.state)
        assertFalse(task.running())
        assertFalse(task.cancel())
    }
    @Test fun cancellationCleanupRunsAtMostOnce() {
        val task = TaskRecord("task", "connection", "plugin")
        var calls = 0
        task.onCancel { calls++ }
        assertTrue(task.cancel()); assertFalse(task.cancel())
        task.fail(IllegalStateException("late failure"))
        assertEquals(1, calls)
        assertEquals("CANCELLED", task.snapshot.state)
    }
}
