package com.nuvio.app.features.player.desktop

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keeps the screen awake while a film is playing, on Linux.
 *
 * NUVIO-LINUX: ported from upstream NuvioMedia/NuvioDesktop, which the fork
 * dropped along with the rest of its Linux support. It matters here more than
 * most places: Nuvio plays through libmpv with no VO window of its own and
 * publishes no MPRIS, so nothing in the session knows a film is running and the
 * compositor blanks the screen mid-film.
 *
 * Both mechanisms are held together rather than one falling back to the other.
 * Upstream's note: on KDE Plasma a successful D-Bus Inhibit alone does not stop
 * the screen lock, only systemd-inhibit does; other desktops lean on the D-Bus
 * call instead, so run both.
 */
internal object DesktopScreenAwake {
    private var cookie: Long? = null
    private var inhibitProcess: Process? = null
    private var executor: ExecutorService? = null

    private fun executor(): ExecutorService =
        executor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "nuvio-linux-screensaver-inhibit").apply { isDaemon = true }
        }.also { executor = it }

    /** Subprocess calls below block, so they are routed off the Compose thread. */
    fun setEnabled(enabled: Boolean) {
        if (DesktopHostOs.current != DesktopHostOs.LINUX) return
        executor().execute {
            if (enabled) {
                if (cookie == null) startDbusInhibit()
                if (inhibitProcess?.isAlive != true) startSystemdInhibit()
            } else {
                stopDbusInhibit()
                stopSystemdInhibit()
            }
        }
    }

    // The same freedesktop ScreenSaver call mpv, VLC and browsers make.
    private fun startDbusInhibit() {
        cookie = runCatching {
            val (exitCode, output) = runDbusSend(
                "--print-reply=literal",
                "--dest=org.freedesktop.ScreenSaver",
                "/org/freedesktop/ScreenSaver",
                "org.freedesktop.ScreenSaver.Inhibit",
                "string:Nuvio",
                "string:Media playback",
            )
            // --print-reply=literal still prefixes the value with its D-Bus type
            // name ("uint32 19346"), so take the last token rather than parsing
            // the whole reply.
            output.trim().substringAfterLast(' ')
                .takeIf { exitCode == 0 && it.isNotEmpty() }
                ?.toLongOrNull()
        }.getOrNull()
    }

    private fun stopDbusInhibit() {
        val current = cookie ?: return
        runCatching {
            runDbusSend(
                "--dest=org.freedesktop.ScreenSaver",
                "/org/freedesktop/ScreenSaver",
                "org.freedesktop.ScreenSaver.UnInhibit",
                "uint32:$current",
            )
        }
        cookie = null
    }

    private fun startSystemdInhibit() {
        inhibitProcess = runCatching {
            ProcessBuilder(
                "systemd-inhibit",
                "--what=idle:sleep",
                "--who=Nuvio",
                "--why=Media playback",
                "--mode=block",
                "sleep",
                "infinity",
            ).start()
        }.getOrNull()
    }

    private fun stopSystemdInhibit() {
        inhibitProcess?.takeIf(Process::isAlive)?.destroy()
        inhibitProcess = null
    }

    // If dbus-daemon never replies, readText() blocks forever before a waitFor()
    // timeout would get a chance to fire, so the watchdog thread is the thing
    // that actually kills it.
    private fun runDbusSend(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder("dbus-send", "--session", "--type=method_call", *args)
            .redirectErrorStream(true)
            .start()
        Thread {
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        }.apply {
            isDaemon = true
            start()
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }
}
