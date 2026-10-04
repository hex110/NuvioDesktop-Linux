package com.nuvio.app.features.player

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object DesktopSeekBufferStorage {
    private const val sizeKey = "desktop_seek_buffer_size"
    private val store by lazy { DesktopStorage.store("nuvio_seek_buffer_settings") }

    actual fun load(): String? = store.getString(ProfileScopedKey.of(sizeKey))

    actual fun save(value: String) = store.putString(ProfileScopedKey.of(sizeKey), value)
}

/** Physical RAM, or null when the JVM cannot tell. */
internal fun desktopTotalMemoryBytes(): Long? = runCatching {
    (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
        ?.totalMemorySize
        ?.takeIf { it > 0 }
}.getOrNull()
