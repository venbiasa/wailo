package com.venbiasa.wailo.mcp

import com.venbiasa.wailo.daemon.DaemonClient
import com.venbiasa.wailo.engine.WailoEngine
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

internal data class McpConfig(
    val port: Int? = null,
    val maxRetained: Int? = null,
)

fun main(args: Array<String>) {
    if (args.any { it == "--help" || it == "-h" }) {
        System.err.println(USAGE)
        return
    }
    val config = try {
        parseMcpArgs(args)
    } catch (failure: IllegalArgumentException) {
        System.err.println("wailo-mcp: ${failure.message}")
        System.err.println(USAGE)
        exitProcess(2)
    }

    val daemon = try {
        runBlocking {
            DaemonClient.connect(
                initialCapturePort = config.port,
                initialMaxRetained = config.maxRetained,
                holdPresence = true,
            ).also { client ->
                config.port?.let {
                    check(client.rebind(it)) { "could not bind shared capture daemon to port $it" }
                }
                config.maxRetained?.let { client.setMaxRetained(it) }
            }
        }
    } catch (failure: Exception) {
        System.err.println("wailo-mcp: ${failure.message ?: "could not connect to Wailo daemon"}")
        exitProcess(1)
    }

    val clientClosed = CountDownLatch(1)
    val input = CloseAwareInputStream(System.`in`) { clientClosed.countDown() }
    val server = try {
        WailoMcpServer.create(daemon, input, System.out)
    } catch (failure: Exception) {
        daemon.close()
        System.err.println("wailo-mcp: ${failure.message ?: "could not start MCP server"}")
        exitProcess(1)
    }
    val shutdownHook = Thread(
        {
            runCatching { server.closeGracefully() }
            daemon.close()
        },
        "wailo-mcp-shutdown",
    )
    Runtime.getRuntime().addShutdownHook(shutdownHook)

    try {
        clientClosed.await()
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    } finally {
        runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        runCatching { server.closeGracefully() }
        daemon.close()
    }
}

internal fun parseMcpArgs(args: Array<String>): McpConfig {
    var port: Int? = null
    var maxRetained: Int? = null
    var index = 0
    while (index < args.size) {
        val option = args[index]
        val value = args.getOrNull(index + 1) ?: throw IllegalArgumentException("$option requires a value")
        when (option) {
            "--port" -> port = value.toIntOrNull()
                ?.takeIf { it in 1..65535 }
                ?: throw IllegalArgumentException("--port must be between 1 and 65535")
            "--max-retained" -> maxRetained = value.toIntOrNull()
                ?.takeIf { it in WailoEngine.RETAINED_RANGE }
                ?: throw IllegalArgumentException(
                    "--max-retained must be between ${WailoEngine.RETAINED_RANGE.first} and " +
                        WailoEngine.RETAINED_RANGE.last,
                )
            else -> throw IllegalArgumentException("unknown option: $option")
        }
        index += 2
    }
    return McpConfig(port, maxRetained)
}

private class CloseAwareInputStream(
    delegate: InputStream,
    private val onClosed: () -> Unit,
) : FilterInputStream(delegate) {
    private val notified = AtomicBoolean()

    override fun read(): Int = super.read().also(::notifyOnEof)

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also(::notifyOnEof)

    override fun close() {
        try {
            super.close()
        } finally {
            notifyClosed()
        }
    }

    private fun notifyOnEof(result: Int) {
        if (result == -1) notifyClosed()
    }

    private fun notifyClosed() {
        if (notified.compareAndSet(false, true)) onClosed()
    }
}

private const val USAGE =
    "Usage: wailo-mcp [--port 8899] [--max-retained 10000]\n" +
        "Options update the persistent shared daemon; omit them to keep its current settings."
