package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.DiagnosticEvent
import dev.gaboron.spwmedia.protocol.RuntimeError

/** Bounded in-memory failure history; callers can only query their authenticated plugin identity. */
internal class RuntimeDiagnostics {
    private val events = ArrayDeque<DiagnosticEvent>()
    @Synchronized fun record(error: RuntimeError) {
        val safe = error.copy(message = "Operation failed (${error.code})", backend = null)
        if (events.lastOrNull()?.error == safe) return
        if (events.size == 256) events.removeFirst()
        events.addLast(DiagnosticEvent(System.currentTimeMillis(), safe))
    }
    @Synchronized fun forPlugin(plugin: String): List<DiagnosticEvent> = events.filter { it.error.pluginId == plugin }
}
