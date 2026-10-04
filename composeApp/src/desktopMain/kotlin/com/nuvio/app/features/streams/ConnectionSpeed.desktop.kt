package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import java.io.File

private val connectionStore by lazy { DesktopStorage.store("nuvio_connection_speed") }

internal actual object ConnectionSpeedStorage {
    // Throughput describes the machine's connection, not a profile, so it is not profile-scoped.
    private const val samplesKey = "throughput_samples_v2"

    actual fun load(): String? = connectionStore.getString(samplesKey)

    actual fun save(value: String) = connectionStore.putString(samplesKey, value)
}

internal actual object StreamConnectionFitPreferenceStorage {
    private const val preferConnectionFitKey = "prefer_connection_fit"

    actual fun load(): Boolean? = connectionStore.getBoolean(ProfileScopedKey.of(preferConnectionFitKey))

    actual fun save(enabled: Boolean) =
        connectionStore.putBoolean(ProfileScopedKey.of(preferConnectionFitKey), enabled)
}

internal actual fun currentNetworkKind(): NetworkKind? = DefaultRoute.current()?.kind

internal actual fun currentNetworkGeneration(): Int = DefaultRoute.current()?.hashCode() ?: 0

/**
 * The interface and gateway of the IPv4 default route, from /proc/net/route. Android tracks the
 * default network through ConnectivityManager; this is the Linux equivalent without a daemon
 * dependency. A different gateway or interface counts as a different network, so a sample that
 * spans a switch (another Wi-Fi, a tether) is dropped. Re-read at most once a second; on systems
 * without /proc/net/route the kind is [NetworkKind.OTHER] and the generation never changes.
 */
private object DefaultRoute {
    data class Route(val interfaceName: String, val gateway: String, val kind: NetworkKind)

    private const val CACHE_MS = 1_000L
    @Volatile private var cached: Route? = null
    @Volatile private var cachedAtMs = 0L

    fun current(): Route? {
        val now = System.currentTimeMillis()
        if (now - cachedAtMs < CACHE_MS) return cached
        val route = read()
        cached = route
        cachedAtMs = now
        return route
    }

    private fun read(): Route? {
        val table = File("/proc/net/route")
        if (!table.canRead()) return Route("", "", NetworkKind.OTHER)
        return runCatching {
            table.readLines()
                .drop(1)
                .map { it.trim().split(Regex("\\s+")) }
                // Destination 00000000 with mask 00000000 is the default route; lowest metric wins.
                .filter { it.size >= 8 && it[1] == "00000000" && it[7] == "00000000" }
                .minByOrNull { it[6].toIntOrNull() ?: Int.MAX_VALUE }
                ?.let { fields ->
                    val name = fields[0]
                    // Android files ethernet under WIFI ("unmetered home network"); same here.
                    val kind = when {
                        File("/sys/class/net/$name/wireless").exists() -> NetworkKind.WIFI
                        File("/sys/class/net/$name/device").exists() -> NetworkKind.WIFI
                        else -> NetworkKind.OTHER
                    }
                    Route(name, fields[2], kind)
                }
        }.getOrNull()
    }
}
