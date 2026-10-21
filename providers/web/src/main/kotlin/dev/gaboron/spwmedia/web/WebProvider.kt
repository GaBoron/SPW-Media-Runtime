package dev.gaboron.spwmedia.web

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** All browser instances for one plugin/profile use the same persistent data directory. */
class WebProvider : MediaProvider {
    override val id = "web"
    private var checkedAt = 0L
    private var installed = false
    @Synchronized override fun availability(assets: Assets): Capability {
        val supported = assets.platform == "windows-x64"
        val helper = assets.locate("platform")
        val binariesReady = helper != null && assets.locate("webview2") != null
        if (supported && binariesReady && (checkedAt == 0L || System.nanoTime() - checkedAt > TimeUnit.SECONDS.toNanos(20))) {
            installed = runCatching {
                val process = ProcessBuilder(helper!!.toString(), "web-status").start()
                try { process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0 } finally { if (process.isAlive) process.destroyForcibly() }
            }.getOrDefault(false)
            checkedAt = System.nanoTime()
        }
        return Capability(id, available = supported && binariesReady && installed, reason = if (!supported) "UNSUPPORTED_PLATFORM" else if (!installed || !binariesReady) "WebView2 Evergreen Runtime or loader is unavailable" else "", features = listOf("navigation", "scripts", "origin-messages", "profiles", "cookies", "clear-data"))
    }
    override fun invoke(context: Invocation, operation: String, arguments: JsonObject): JsonElement {
        if (operation != "open") throw ProviderFailure("OPERATION_UNSUPPORTED", "Unknown web operation")
        val options = Protocol.json.decodeFromJsonElement<WebOptions>(arguments)
        require(options.profile.matches(Regex("[A-Za-z0-9._-]{1,64}")) && options.profile !in setOf(".", ".."))
        require(options.width in 1..16384 && options.height in 1..16384)
        val profile = context.storage.resolve("profiles").resolve(options.profile)
        Files.createDirectories(profile)
        val session = context.worker("web", JsonObject(arguments + mapOf("profilePath" to JsonPrimitive(profile.toString()))))
        return obj("id" to context.register(id, session))
    }
}
