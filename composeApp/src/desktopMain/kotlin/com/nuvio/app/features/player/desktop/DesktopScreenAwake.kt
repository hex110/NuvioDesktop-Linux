package com.nuvio.app.features.player.desktop

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
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
 *
 * **The ScreenSaver inhibit is held on our own D-Bus connection, and has to be.**
 * An inhibit lives exactly as long as the connection that took it. The obvious
 * implementation -- shelling out to `dbus-send` -- opens a connection, gets a
 * cookie and exits, so the shell drops the inhibitor in the same millisecond it
 * granted it, logging "peer :1.x died, removing inhibitor". That is silent: the
 * call succeeds, a valid cookie comes back, and the screen still blanks. It cost
 * a bug report on Hyprland/DankMaterialShell, where systemd-inhibit is not
 * consulted either (that shell's idle timer honours the freedesktop ScreenSaver
 * service only, so the logind inhibitor below is invisible to it and the D-Bus
 * half was the only half that could have worked). [LinuxSessionBus] therefore
 * keeps the connection open for as long as the inhibit is meant to last;
 * `dbus-send` remains only as a fallback for setups it cannot connect to, where
 * it is still better than nothing on desktops that do honour systemd-inhibit.
 */
internal object DesktopScreenAwake {
    private const val SCREENSAVER_SERVICE = "org.freedesktop.ScreenSaver"
    private const val SCREENSAVER_PATH = "/org/freedesktop/ScreenSaver"
    private const val REASSERT_SECONDS = 30L

    private var cookie: Long? = null
    private var inhibitProcess: Process? = null
    private var executor: ScheduledExecutorService? = null
    private var reassertTask: ScheduledFuture<*>? = null

    /** Non-null only while the inhibit is held on our own connection. */
    private var bus: LinuxSessionBus? = null
    private var busUnavailable = false

    /** Who owned the ScreenSaver name when we inhibited; see [reassertInhibit]. */
    private var inhibitOwner: String? = null

    private fun executor(): ScheduledExecutorService =
        executor ?: Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "nuvio-linux-screensaver-inhibit").apply { isDaemon = true }
        }.also { executor = it }

    /**
     * Socket and subprocess work below blocks, so it is routed off the Compose
     * thread. Everything touching the state above runs on that one executor
     * thread; only the scheduling handle is touched from here.
     */
    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (DesktopHostOs.current != DesktopHostOs.LINUX) return
        val executor = executor()
        reassertTask?.cancel(false)
        reassertTask = null
        executor.execute {
            if (enabled) {
                startDbusInhibit()
                if (inhibitProcess?.isAlive != true) startSystemdInhibit()
            } else {
                stopDbusInhibit()
                stopSystemdInhibit()
            }
        }
        if (enabled) {
            reassertTask = executor.scheduleWithFixedDelay(
                { runCatching { reassertInhibit() } },
                REASSERT_SECONDS,
                REASSERT_SECONDS,
                TimeUnit.SECONDS,
            )
        }
    }

    /**
     * A shell restarting mid-film takes every inhibitor it was holding with it,
     * without our connection noticing: the cookie is simply no longer honoured
     * by whatever replaced it. Comparing the current owner of the ScreenSaver
     * name against the owner we inhibited tells us that happened. This also
     * retries an acquire that failed because nothing owned the name yet.
     */
    private fun reassertInhibit() {
        if (inhibitProcess?.isAlive != true) startSystemdInhibit()
        if (cookie == null) {
            startDbusInhibit()
            return
        }
        val bus = bus ?: return // the dbus-send fallback has nothing to re-check
        val owner = runCatching { nameOwner(bus) }.getOrNull()
        if (owner != null && owner == inhibitOwner) return
        cookie = null
        inhibitOwner = null
        startDbusInhibit()
    }

    private fun nameOwner(bus: LinuxSessionBus): String = bus.call(
        LinuxSessionBus.DBUS_SERVICE,
        LinuxSessionBus.DBUS_PATH,
        LinuxSessionBus.DBUS_SERVICE,
        "GetNameOwner",
        SCREENSAVER_SERVICE,
    ).string()

    // The same freedesktop ScreenSaver call mpv, VLC and browsers make.
    private fun startDbusInhibit() {
        if (cookie != null) return
        sessionBus()?.let { bus ->
            val acquired = runCatching {
                val granted = bus.call(
                    SCREENSAVER_SERVICE,
                    SCREENSAVER_PATH,
                    SCREENSAVER_SERVICE,
                    "Inhibit",
                    "Nuvio",
                    "Media playback",
                ).uint32()
                inhibitOwner = runCatching { nameOwner(bus) }.getOrNull()
                granted.toLong() and 0xFFFFFFFFL
            }
            if (acquired.isSuccess) {
                cookie = acquired.getOrNull()
                return
            }
            // The connection is no longer trustworthy; drop it and fall back so
            // that a shell which is merely absent does not disable the feature.
            closeBus()
        }
        cookie = legacyInhibit()
    }

    private fun stopDbusInhibit() {
        val current = cookie ?: return
        cookie = null
        inhibitOwner = null
        bus?.let { bus ->
            val released = runCatching {
                bus.call(
                    SCREENSAVER_SERVICE,
                    SCREENSAVER_PATH,
                    SCREENSAVER_SERVICE,
                    "UnInhibit",
                    current.toInt(),
                )
            }
            // Closing the connection would release it anyway, but keeping it
            // open costs nothing and saves a reconnect on the next resume.
            if (released.isFailure) closeBus()
            return
        }
        runCatching {
            runDbusSend(
                "--dest=$SCREENSAVER_SERVICE",
                SCREENSAVER_PATH,
                "$SCREENSAVER_SERVICE.UnInhibit",
                "uint32:$current",
            )
        }
    }

    private fun sessionBus(): LinuxSessionBus? {
        if (bus != null || busUnavailable) return bus
        // Also catches the linkage error on a pre-16 runtime, where java.nio has
        // no AF_UNIX support and the fallback is all we have.
        bus = runCatching { LinuxSessionBus.connect() }.getOrNull()
        busUnavailable = bus == null
        return bus
    }

    private fun closeBus() {
        bus?.close()
        bus = null
        busUnavailable = true
        inhibitOwner = null
    }

    /**
     * Fallback only. The cookie this returns is very likely already dead -- see
     * the class comment -- but the call is harmless and some session managers do
     * keep it.
     */
    private fun legacyInhibit(): Long? = runCatching {
        val (exitCode, output) = runDbusSend(
            "--print-reply=literal",
            "--dest=$SCREENSAVER_SERVICE",
            SCREENSAVER_PATH,
            "$SCREENSAVER_SERVICE.Inhibit",
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
