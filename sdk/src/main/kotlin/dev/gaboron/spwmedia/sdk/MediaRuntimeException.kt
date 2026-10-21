package dev.gaboron.spwmedia.sdk

import dev.gaboron.spwmedia.protocol.RuntimeError
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException

/** Structured runtime failure. [detail] is suitable for display; backend details belong only to the caller. */
class MediaRuntimeException(val detail: RuntimeError) : RuntimeException("${detail.code}: ${detail.message}") {
    companion object {
        /** Unwrap standard Java completion wrappers; returns null for unrelated exceptions. Since 0.2.0. */
        @JvmStatic fun unwrap(error: Throwable?): MediaRuntimeException? {
            var current = error
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            while (current is CompletionException || current is ExecutionException) {
                if (!seen.add(current) || current.cause == null) break
                current = current.cause
            }
            return current as? MediaRuntimeException
        }
    }
}
