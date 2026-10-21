package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.*
import dev.gaboron.spwmedia.provider.*
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Plugin-neutral composition root; adding a provider does not add a media-specific core branch. */
class RuntimeEngine(
    private val providers: List<MediaProvider>, private val assets: Assets, private val storage: Path,
    private val workerCommands: Map<String, List<String>>, concurrency: Int = 4,
    private val workerCommand: (String) -> List<String>? = { workerCommands[it] }
) : AutoCloseable {
    private data class Owner(val plugin: String, @Volatile var closing: Boolean = false)
    private val clients = ConcurrentHashMap<String, Owner>()
    private val resources = ConcurrentHashMap<String, OwnedResource>()
    private val tasks = ConcurrentHashMap<String, TaskRecord>()
    private val fileLocks = Array(128) { ReentrantLock() }
    private val diagnostics = RuntimeDiagnostics()
    private val recycler: (Path) -> Boolean = { recycle(it, assets.locate("recycle") ?: System.getProperty("spw.media.recycle.helper")?.let(Path::of)) }
    private val buffers by lazy { BufferStorage(storage.resolve("buffers"), recycler) }
    @Volatile private var buffersUsed = false
    private val workers = ThreadPoolExecutor(concurrency, concurrency, 30, TimeUnit.SECONDS, ArrayBlockingQueue(128), { r -> Thread(r, "SPW Media task").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val timers = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "SPW Media deadline").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true; setKeepAliveTime(30, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    private val registry = providers.associateBy { it.id }
    @Volatile private var closed = false

    init { require(registry.size == providers.size); Files.createDirectories(storage); recoverBufferStorage(storage.resolve("buffers"), recycler) }

    /** Dispatch a protocol request. Calls only perform bookkeeping; media work is submitted to a bounded queue. */
    fun exchange(request: Request, authenticatedPluginId: String): Response = try {
        if (closed) throw ProviderFailure("CLIENT_CLOSED", "Runtime is stopped")
        if (request.protocol != Protocol.VERSION) throw ProviderFailure("PROTOCOL_INCOMPATIBLE", "Unsupported protocol version")
        Response(route(request, authenticatedPluginId))
    } catch (error: Throwable) {
        val detail = publicError(error, authenticatedPluginId)
        diagnostics.record(detail)
        Response(error = detail)
    }

    private fun route(request: Request, authenticatedPluginId: String): JsonElement {
        val args = request.payload
        if (request.operation == "connect") {
            val plugin = args.text("pluginId")
            require(plugin.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
            if (plugin != authenticatedPluginId) throw ProviderFailure("PERMISSION_DENIED", "Plugin identity does not match the caller classloader")
            if (clients.size >= 128) throw ProviderFailure("RESOURCE_LIMIT", "Too many clients")
            val token = UUID.randomUUID().toString()
            clients[token] = Owner(plugin)
            return obj("token" to token, "protocol" to Protocol.VERSION, "version" to Protocol.RUNTIME_VERSION)
        }
        val owner = clients[request.token] ?: throw ProviderFailure("CLIENT_CLOSED", "Unknown or closed connection")
        if (owner.plugin != authenticatedPluginId) throw ProviderFailure("PERMISSION_DENIED", "Connection belongs to another plugin")
        if (owner.closing && request.operation !in setOf("closeResource", "disconnect", "cancel", "diagnostics"))
            throw ProviderFailure("CLIENT_CLOSING", "Connection is closing")
        return when (request.operation) {
            "capabilities" -> Protocol.json.encodeToJsonElement(providers.map { it.availability(assets) })
            "diagnostics" -> Protocol.json.encodeToJsonElement(diagnostics.forPlugin(owner.plugin))
            "submit" -> submit(request, owner)
            "task" -> Protocol.json.encodeToJsonElement(ownedTask(request.token, args.text("id")).snapshot)
            "cancel" -> JsonPrimitive(ownedTask(request.token, args.text("id")).cancel())
            "sessionState" -> {
                val id = args.text("id"); val resource = ownedResource(request.token, id)
                val values = resource.session.state(); val error = resource.session.failure()?.copy(pluginId = owner.plugin, sessionId = id)
                error?.let(diagnostics::record)
                Protocol.json.encodeToJsonElement(SessionSnapshot(id, resource.capability, if (error != null) "FAILED" else values.text("state", "OPEN"), values, error))
            }
            "sessionBuffer" -> Protocol.json.encodeToJsonElement(ownedResource(request.token, args.text("id")).session.buffer() ?: throw ProviderFailure("BUFFER_UNAVAILABLE", "This session has no data channel"))
            "closeResource" -> { closeResource(request.token, args.text("id")); JsonNull }
            "acquireTool" -> {
                val name = args.text("name"); require(name in setOf("ffmpeg", "ffprobe"))
                val path = assets.find(name) ?: throw ProviderFailure("DEPENDENCY_MISSING", "Shared $name is unavailable")
                val external = (assets as? NativeAssets)?.externalTool()
                val id = registerOwned(request.token, "tools", object : ProviderSession {
                    override fun command(method: String, arguments: JsonObject) = JsonNull
                    override fun state() = obj("state" to "LEASED")
                    override fun close() { external?.close() }
                }, onDisconnect = {}) // Unknown caller-owned processes conservatively keep the cache marker.
                Protocol.json.encodeToJsonElement(ToolDescriptor(id, name, assets.version(name), assets.platform, path.toString()))
            }
            "disconnect" -> { disconnect(request.token); JsonNull }
            else -> throw ProviderFailure("OPERATION_UNSUPPORTED", "Unknown control operation")
        }
    }

    @Synchronized private fun submit(request: Request, owner: Owner): JsonElement {
        val provider = registry[request.capability] ?: throw ProviderFailure("CAPABILITY_UNSUPPORTED", "Unknown capability")
        val availability = provider.availability(assets)
        if (!availability.available) throw ProviderFailure(if (assets.platform == "unsupported" || availability.reason == "UNSUPPORTED_PLATFORM") "UNSUPPORTED_PLATFORM" else "DEPENDENCY_MISSING", availability.reason)
        val owned = tasks.values.filter { it.owner == request.token }
        if (owned.count { it.snapshot.state in setOf("QUEUED", "RUNNING") } >= 32) throw ProviderFailure("RESOURCE_LIMIT", "Too many active tasks")
        if (owned.size >= 256) owned.filter { it.snapshot.state !in setOf("QUEUED", "RUNNING") }.take(64).forEach { tasks.remove(it.id) }
        val timeout = request.payload.number("timeoutMillis", 120_000.0).toLong()
        require(timeout in 1..86_400_000)
        val record = TaskRecord(UUID.randomUUID().toString(), request.token, owner.plugin)
        tasks[record.id] = record
        if (!clients.containsKey(request.token)) { tasks.remove(record.id); throw ProviderFailure("CLIENT_CLOSED", "Connection closed") }
        try {
            record.future = workers.submit {
                if (!record.running()) return@submit
                try {
                    val arguments = request.payload["arguments"]!!.jsonObject
                    val operation = request.payload.text("method")
                    val invocation = Context(record)
                    val result = if (operation == "session") {
                        val resource = ownedResource(request.token, arguments.text("id"))
                        require(resource.capability == provider.id)
                        resource.session.command(arguments.text("method"), arguments["arguments"]!!.jsonObject)
                    } else provider.invoke(invocation, operation, arguments)
                    record.check(); record.success(result)
                } catch (e: Throwable) { record.fail(e); record.snapshot.error?.let(diagnostics::record) }
            }
            record.timeout = timers.schedule({ if (record.cancel(true)) record.snapshot.error?.let(diagnostics::record) }, timeout, TimeUnit.MILLISECONDS)
            if (record.snapshot.state !in setOf("QUEUED", "RUNNING")) record.timeout?.cancel(false)
        } catch (_: RejectedExecutionException) { tasks.remove(record.id); throw ProviderFailure("RESOURCE_LIMIT", "Runtime queue is full", true) }
        return JsonPrimitive(record.id)
    }

    private inner class Context(private val task: TaskRecord) : Invocation {
        override val owner get() = task.pluginId
        override val operationId get() = task.id
        override val assets get() = this@RuntimeEngine.assets
        override val storage: Path get() = this@RuntimeEngine.storage.resolve("plugins").resolve(owner).also { Files.createDirectories(it) }
        override fun checkCancelled() = task.check()
        override fun progress(value: Double) = task.progress(value)
        override fun onCancel(action: () -> Unit) = task.onCancel(action)
        override fun register(capability: String, session: ProviderSession): String {
            task.check()
            val id = registerOwned(task.owner, capability, session)
            task.onCancel { closeResource(task.owner, id) }
            if (clients[task.owner]?.closing != false) { closeResource(task.owner, id); throw CancellationException() }
            return id
        }
        override fun session(id: String) = ownedResource(task.owner, id).session
        override fun process(arguments: List<String>, progress: Boolean, durationSeconds: Double?) = ManagedProcess.run(arguments, task::check, task::onCancel, task::progress, if (progress) durationSeconds else null) { process -> (assets as? NativeAssets)?.track(process) }
        override fun worker(mode: String, arguments: JsonObject, lifetime: ResourceLifetime): ProviderSession {
            task.check()
            val command = workerCommand(mode) ?: throw ProviderFailure("DEPENDENCY_MISSING", "Requested backend is unavailable")
            var startup: AutoCloseable? = null
            val session = try { WorkerSession(command, arguments, mode) { process -> startup = task.onCancel { ManagedProcess.terminate(process) }; (assets as? NativeAssets)?.track(process); if (buffersUsed) buffers.track(process) } } finally { startup?.close() }
            if (lifetime == ResourceLifetime.INVOCATION) task.onCancel { session.close() }
            return session
        }
        override fun buffer(capacity: Int, format: String, width: Int, height: Int, lifetime: ResourceLifetime) = synchronized(this@RuntimeEngine) {
            task.check()
            buffersUsed = true
            MappedBuffer(buffers.directory, capacity, format, width, height, buffers::release).also { buffer -> if (lifetime == ResourceLifetime.INVOCATION) task.onCancel { buffer.close() } }
        }
        override fun <T> withFileLock(path: Path, block: () -> T): T {
            val absolute = path.toAbsolutePath().normalize()
            val canonical = if (Files.exists(absolute)) absolute.toRealPath() else absolute.parent.toRealPath().resolve(absolute.fileName)
            val key = canonical.toString().let { if (assets.platform.startsWith("windows")) it.lowercase() else it }
            return fileLocks[(key.hashCode() and Int.MAX_VALUE) % fileLocks.size].withLock { task.check(); block() }
        }
    }

    private fun registerOwned(owner: String, capability: String, session: ProviderSession, onDisconnect: () -> Unit = session::close): String = synchronized(resources) {
        if (clients[owner]?.closing != false) { session.close(); throw ProviderFailure("CLIENT_CLOSED", "Connection closed") }
        if (resources.values.count { it.owner == owner } >= 64) { session.close(); throw ProviderFailure("RESOURCE_LIMIT", "Too many resources") }
        UUID.randomUUID().toString().also { resources[it] = OwnedResource(owner, capability, session, onDisconnect) }
    }

    private fun ownedResource(owner: String, id: String): OwnedResource = resources[id]?.takeIf { it.owner == owner } ?: throw ProviderFailure("RESOURCE_NOT_FOUND", "Resource is absent or belongs to another connection")
    private fun ownedTask(owner: String, id: String): TaskRecord = tasks[id]?.takeIf { it.owner == owner } ?: throw ProviderFailure("TASK_NOT_FOUND", "Task is absent or belongs to another connection")
    private fun closeResource(owner: String, id: String) {
        val resource = resources[id] ?: return
        if (resource.owner != owner) throw ProviderFailure("RESOURCE_NOT_FOUND", "Resource belongs to another connection")
        resource.release(false)
        resources.remove(id, resource)
    }
    private fun disconnect(token: String) {
        clients[token]?.closing = true
        tasks.values.filter { it.owner == token }.forEach { it.cancel(); tasks.remove(it.id) }
        var failure: Throwable? = null
        resources.entries.filter { it.value.owner == token }.forEach { (id, resource) ->
            try { resource.release(true); resources.remove(id, resource) }
            catch (error: Throwable) { diagnostics.record(publicError(error, clients[token]?.plugin.orEmpty(), session = id)); if (failure == null) failure = error }
        }
        if (failure != null) throw ProviderFailure("RESOURCE_CLOSE_FAILED", "Resource cleanup failed; retry closing the connection", true)
        clients.remove(token)
    }
    @Synchronized override fun close() {
        if (closed && resources.isEmpty()) return
        closed = true
        clients.keys.toList().forEach { runCatching { disconnect(it) } }
        providers.forEach { runCatching { it.close() } }
        workers.shutdownNow(); timers.shutdownNow()
        if (buffersUsed) buffers.close()
        (assets as? AutoCloseable)?.close()
    }
}
