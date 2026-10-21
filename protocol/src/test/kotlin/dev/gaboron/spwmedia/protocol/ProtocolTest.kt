package dev.gaboron.spwmedia.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.*

class ProtocolTest {
    @Test fun unknownFieldsRemainCompatible() {
        val error = Protocol.json.decodeFromString<RuntimeError>("""{"code":"FUTURE_ERROR","message":"failure","future":{"value":1}}""")
        assertEquals("FUTURE_ERROR", error.code)
        assertFalse(error.retryable)
        assertEquals(error, Protocol.json.decodeFromString<RuntimeError>(Protocol.json.encodeToString(error)))
    }

    @Test fun terminalSnapshotPreservesStructuredFailure() {
        val original = TaskSnapshot("operation", "TIMED_OUT", error = RuntimeError("TIMED_OUT", "deadline", "plugin", "operation", retryable = true))
        assertEquals(original, Protocol.json.decodeFromString<TaskSnapshot>(Protocol.json.encodeToString(original)))
        assertEquals(1, Protocol.VERSION)
    }
}
