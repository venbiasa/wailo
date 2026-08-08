package com.venbiasa.wailo.desktop.adb

import java.io.File
import java.util.concurrent.TimeUnit

/** One entry of `adb devices -l`. */
internal data class AdbDevice(
    val serial: String,
    /** `device`, `unauthorized`, `offline`, `recovery`… — verbatim, so an unexpected one still reads. */
    val state: String,
    /** The `model:` property, which is what a human recognises. Empty for a device not yet authorised. */
    val model: String,
) {
    /** Whether it will answer commands at all. Everything else is a device that needs the user's help. */
    val ready: Boolean get() = state == STATE_READY

    val label: String get() = model.replace('_', ' ').ifEmpty { serial }

    internal companion object {
        const val STATE_READY = "device"
    }
}

internal class AdbException(message: String) : Exception(message)

private const val MODEL_PREFIX = "model:"
private const val HEADER = "List of devices attached"

/**
 * Reads `adb devices -l`: a header line, then one device per line.
 *
 * Split out from the process call so the shapes that actually break it can be pinned. Two of them do.
 * On a cold start adb prints `* daemon not running; starting now…` *before* its own header, so the
 * devices cannot be found by counting lines from the top — the header has to be located. And an
 * unauthorised device reports two fields where a ready one reports eight, so anything that assumes a
 * model column drops the very device the user needs told about.
 */
internal fun parseDevices(output: String): List<AdbDevice> {
    val lines = output.lines()
    val header = lines.indexOfFirst { it.trimStart().startsWith(HEADER) }
    return (if (header >= 0) lines.drop(header + 1) else lines).mapNotNull { line ->
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 2 || fields[0].isEmpty() || fields[0].startsWith("*")) return@mapNotNull null
        AdbDevice(
            serial = fields[0],
            state = fields[1],
            model = fields.drop(2).firstOrNull { it.startsWith(MODEL_PREFIX) }?.removePrefix(MODEL_PREFIX).orEmpty(),
        )
    }
}

/**
 * Runs the Android platform-tools `adb` binary.
 *
 * Installing a reverse is a single-shot command that happens when a device appears, so the CLI — the
 * interface Google keeps stable, and the one every Android developer already has — is the right way to
 * ask for it. Watching for those devices is not single-shot and is not done here; see [AdbTracker].
 *
 * [devices] survives as the fallback for when there is no adb server to track, and as what starts one.
 */
internal class AdbClient(private val executable: File? = findExecutable()) {

    val available: Boolean get() = executable != null

    /** Where `adb` was found, for the UI to name when someone asks why nothing is happening. */
    val path: String? get() = executable?.path

    fun devices(): List<AdbDevice> = parseDevices(run("devices", "-l"))

    /**
     * Routes `localhost:$devicePort` on the device to `$hostPort` on this machine.
     *
     * Idempotent: adb replaces an existing mapping for the same device port rather than erroring, so a
     * re-run after a reconnect or a port change needs no teardown first.
     */
    fun reverse(serial: String, devicePort: Int, hostPort: Int) {
        run("-s", serial, "reverse", "tcp:$devicePort", "tcp:$hostPort")
    }

    /** Best-effort: a device unplugged before its mapping was removed has nothing left to remove. */
    fun removeReverse(serial: String, devicePort: Int) {
        runCatching { run("-s", serial, "reverse", "--remove", "tcp:$devicePort") }
    }

    private fun run(vararg args: String): String {
        val binary = executable ?: throw AdbException("No adb on this machine.")
        val process = ProcessBuilder(listOf(binary.path) + args)
            .redirectErrorStream(true)
            .start()
        // Killed from a watchdog rather than by waiting with a timeout: the output has to be drained on
        // this thread (a full pipe buffer deadlocks a process that is never read), and that read is
        // itself the unbounded wait. Forcing the process down closes the pipe, which ends both at once.
        // `adb start-server` on a cold machine is the case this exists for.
        process.onExit()
            .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally { process.destroyForcibly(); null }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        if (exit != 0) {
            throw AdbException(output.trim().ifEmpty { "adb ${args.joinToString(" ")} failed ($exit)." })
        }
        return output
    }

    internal companion object {
        private const val TIMEOUT_SECONDS = 10L

        /**
         * Finds `adb` the way a desktop app has to.
         *
         * `PATH` alone is not enough: an app launched from Finder or a `.app` bundle inherits the
         * launchd environment, not the shell's, so the `platform-tools` entry a developer put in their
         * `.zshrc` is simply absent. The SDK's own well-known locations are checked as well, which is
         * where every Android Studio install puts it.
         */
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
