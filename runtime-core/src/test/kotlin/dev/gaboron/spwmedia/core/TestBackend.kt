package dev.gaboron.spwmedia.core

import dev.gaboron.spwmedia.protocol.*
import kotlinx.serialization.json.*

/** Controllable child used by process and protocol tests; it never reads caller media. */
object TestBackend {
    @JvmStatic fun main(arguments: Array<String>) {
        if (arguments.firstOrNull() == "sleep") while (true) Thread.sleep(1000)
        if (arguments.firstOrNull() == "tree") {
            ProcessBuilder(testBackend("sleep")).start()
            while (true) Thread.sleep(1000)
        }
        if (arguments.firstOrNull() == "noisy") {
            System.err.print("x".repeat(3_000_000))
            System.err.println("\nAuthorization: Bearer sample-private-token")
            System.err.println("https://user:password@example.invalid/input?token=sample-private-token")
            System.err.println("Unknown encoder: example-codec")
            kotlin.system.exitProcess(9)
        }
        System.`in`.bufferedReader().useLines { lines -> lines.forEach { line ->
            val message = Protocol.json.parseToJsonElement(line).jsonObject
            when (message.text("method")) {
                "open" -> { println(obj("id" to message["id"], "result" to JsonNull)); System.out.flush() }
                "exit" -> kotlin.system.exitProcess(7)
                else -> {
                    println(obj("event" to "state", "values" to obj("state" to "WAITING"))); System.out.flush()
                    while (true) Thread.sleep(1000)
                }
            }
        } }
    }
}

internal fun testBackend(vararg arguments: String) = listOf(java.nio.file.Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString(), "-Dspw.test.classpath=${System.getProperty("spw.test.classpath")}", "-cp", System.getProperty("spw.test.classpath"), TestBackend::class.java.name) + arguments
