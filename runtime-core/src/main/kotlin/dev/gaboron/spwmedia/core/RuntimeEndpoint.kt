package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.lang.management.ManagementFactory
import javax.management.*

/** JVM-local JMX transport. Only JDK String values cross the classloader boundary. */
class RuntimeEndpoint(private val engine: RuntimeEngine, private val identifyCaller: () -> String?) : DynamicMBean, AutoCloseable {
    private val server = ManagementFactory.getPlatformMBeanServer()
    private val name = ObjectName(Protocol.OBJECT_NAME)
    init { server.registerMBean(this, name) }
    override fun invoke(actionName: String, params: Array<out Any?>?, signature: Array<out String>?): Any {
        if (actionName != "exchange") throw ReflectionException(NoSuchMethodException(actionName))
        val input = params?.singleOrNull() as? String ?: throw IllegalArgumentException("Expected one JSON string")
        if (input.length > 1_048_576) return Protocol.json.encodeToString(Response(error = RuntimeError("REQUEST_TOO_LARGE", "Control messages are limited to 1 MiB")))
        val response = try {
            val owner = identifyCaller()
            if (owner == null) Response(error = RuntimeError("PERMISSION_DENIED", "Caller plugin could not be identified"))
            else engine.exchange(Protocol.json.decodeFromString<Request>(input), owner)
        }
        catch (_: Exception) { Response(error = RuntimeError("INVALID_ARGUMENT", "Invalid protocol request")) }
        return Protocol.json.encodeToString(response)
    }
    override fun getMBeanInfo() = MBeanInfo(javaClass.name, "SPW Media Runtime local protocol 1", emptyArray(), emptyArray(), arrayOf(MBeanOperationInfo("exchange", "JSON control request", arrayOf(MBeanParameterInfo("request", "java.lang.String", "Protocol request")), "java.lang.String", MBeanOperationInfo.ACTION)), emptyArray())
    override fun getAttribute(attribute: String?): Any = throw AttributeNotFoundException(attribute)
    override fun setAttribute(attribute: Attribute?) { throw AttributeNotFoundException(attribute?.name) }
    override fun getAttributes(attributes: Array<out String>?): AttributeList = AttributeList()
    override fun setAttributes(attributes: AttributeList?): AttributeList = AttributeList()
    override fun close() { if (server.isRegistered(name)) server.unregisterMBean(name); engine.close() }
}
