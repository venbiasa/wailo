package com.venbiasa.wailo.daemon

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal fun runMachineProcess(
    command: List<String>,
    timeoutSeconds: Long,
): String? = runCatching {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = BoundedOutput(MAX_MACHINE_OUTPUT_BYTES)
    val readFailure = AtomicReference<Throwable?>()
    val reader = Thread.ofVirtual().name("wailo-machine-output").start {
        runCatching { process.inputStream.use { it.copyTo(output) } }
            .exceptionOrNull()
            ?.let(readFailure::set)
    }

    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        runCatching {
            process.descendants().forEach { child ->
                runCatching { child.destroyForcibly() }
            }
        }
        process.destroyForcibly()
        process.waitFor(PROCESS_STOP_SECONDS, TimeUnit.SECONDS)
        runCatching { process.inputStream.close() }
        reader.interrupt()
        reader.join(TimeUnit.SECONDS.toMillis(PROCESS_STOP_SECONDS))
        return@runCatching null
    }
    reader.join(TimeUnit.SECONDS.toMillis(PROCESS_STOP_SECONDS))
    if (reader.isAlive) {
        runCatching { process.inputStream.close() }
        reader.interrupt()
        return@runCatching null
    }
    if (readFailure.get() != null || process.exitValue() != 0) null else output.text()
}.getOrNull()

private class BoundedOutput(private val limit: Int) : OutputStream() {
    private val bytes = ByteArrayOutputStream(limit)
    private var size = 0L

    override fun write(value: Int) {
        if (size < limit) bytes.write(value)
        size++
    }

    override fun write(source: ByteArray, offset: Int, length: Int) {
        val kept = minOf(length.toLong(), (limit - size).coerceAtLeast(0)).toInt()
        if (kept > 0) bytes.write(source, offset, kept)
        size += length
    }

    fun text(): String = bytes.toString(Charsets.UTF_8)
}

private const val MAX_MACHINE_OUTPUT_BYTES = 256 * 1024
private const val PROCESS_STOP_SECONDS = 2L
