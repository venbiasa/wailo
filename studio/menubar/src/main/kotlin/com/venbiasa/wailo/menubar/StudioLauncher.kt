package com.venbiasa.wailo.menubar

import java.io.File
import java.nio.file.Path

/**
 * Starts Studio for a "Show Studio" with none attached. This agent inherits the classpath of whichever
 * frontend spawned it, so Studio is reachable exactly when Studio (or something packaged with it) started
 * the daemon — the case that matters is an agent outliving a Studio that was killed. Started by an MCP
 * session, the class is simply absent and the row stays disabled rather than failing on click.
 */
internal object StudioLauncher {
    fun canLaunch(): Boolean = runCatching {
        Class.forName(MAIN_CLASS, false, StudioLauncher::class.java.classLoader)
        true
    }.getOrDefault(false)

    fun launch() {
        runCatching {
            val java = Path.of(
                System.getProperty("java.home"),
                "bin",
                if (isWindows) "java.exe" else "java",
            )
            ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"), MAIN_CLASS)
                .redirectInput(File(if (isWindows) "NUL" else "/dev/null"))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }
    }

    private val isWindows get() = System.getProperty("os.name").startsWith("Windows", true)

    private const val MAIN_CLASS = "com.venbiasa.wailo.desktop.MainKt"
}
