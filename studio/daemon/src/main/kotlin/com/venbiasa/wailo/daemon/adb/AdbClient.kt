package com.venbiasa.wailo.daemon.adb

import java.io.File
import java.util.concurrent.TimeUnit

internal data class AdbDevice(
    val serial: String,
    val state: String,
    val model: String,
) {
    val ready: Boolean get() = state == STATE_READY
    val label: String get() = model.replace('_', ' ').ifEmpty { serial }

    companion object {
        const val STATE_READY = "device"
    }
}

internal class AdbException(message: String) : Exception(message)

private const val MODEL_PREFIX = "model:"
private const val HEADER = "List of devices attached"

internal fun parseDevices(output: String): List<AdbDevice> {
    val lines = output.lines()
    val header = lines.indexOfFirst { it.trimStart().startsWith(HEADER) }
    return (if (header >= 0) lines.drop(header + 1) else lines).mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 2 || fields[0].isEmpty() || fields[0].startsWith("*")) return@mapNotNull null
        AdbDevice(
            serial = fields[0],
            state = fields[1],
            model = fields.drop(2)
                .firstOrNull { it.startsWith(MODEL_PREFIX) }
                ?.removePrefix(MODEL_PREFIX)
                .orEmpty(),
        )
    }
}

internal class AdbClient(private val executable: File? = findExecutable()) {
    val available: Boolean get() = executable != null
    val path: String? get() = executable?.path

    fun devices(): List<AdbDevice> = parseDevices(run("devices", "-l"))

    fun reverse(serial: String, devicePort: Int, hostPort: Int) {
        run("-s", serial, "reverse", "tcp:$devicePort", "tcp:$hostPort")
    }

    fun removeReverse(serial: String, devicePort: Int) {
        runCatching { run("-s", serial, "reverse", "--remove", "tcp:$devicePort") }
    }

    private fun run(vararg args: String): String {
        val binary = executable ?: throw AdbException("No adb on this machine.")
        val process = ProcessBuilder(listOf(binary.path) + args)
            .redirectErrorStream(true)
            .start()
        process.onExit()
            .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally {
                process.destroyForcibly()
                null
            }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0) {
            throw AdbException(output.trim().ifEmpty { "adb ${args.joinToString(" ")} failed ($exit)." })
        }
        return output
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10L

        fun findExecutable(): File? {
            val name = if (System.getProperty("os.name").startsWith("Windows", true)) "adb.exe" else "adb"
            val home = System.getProperty("user.home").orEmpty()
            val roots = listOfNotNull(
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
                "$home/Library/Android/sdk",
                "$home/Android/Sdk",
                "$home/AppData/Local/Android/Sdk",
            ).map { File(it, "platform-tools/$name") }
            val onPath = System.getenv("PATH").orEmpty()
                .split(File.pathSeparatorChar)
                .filter(String::isNotEmpty)
                .map { File(it, name) }
            return (roots + onPath).firstOrNull { it.isFile && it.canExecute() }
        }
    }
}
