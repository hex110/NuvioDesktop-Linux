package com.nuvio.app.features.player

import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.isInternetPlaybackSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Seek buffer, after Nuvio Reshaped's: mpv reads this far ahead of playback, so a forward jump
 * inside it is instant. Reshaped keeps it in a temporary file; mpv's --cache-on-disk cannot do
 * that boundedly (its cache file is append-only and --demuxer-max-bytes only caps the in-memory
 * index, so a full viewing leaves a file as large as everything downloaded), hence RAM here,
 * where --demuxer-max-bytes is a hard cap. Off keeps mpv's default (150 MiB).
 */
enum class DesktopSeekBufferSize(val label: String, val mebibytes: Long) {
    Off("Off", 0),
    Size256("256 MB", 256),
    Size512("512 MB", 512),
    Size1024("1 GB", 1024),
    ;

    val description: String
        get() = if (this == Off) {
            "Reads ahead as far as mpv does by default (150 MB)."
        } else {
            "Reads up to $label ahead of playback, in memory, so skipping forward inside it is " +
                "instant. Internet streams only; not used with the Metered preset."
        }
}

internal object DesktopSeekBufferPreference {
    private val _size = MutableStateFlow(DesktopSeekBufferSize.Off)
    val size: StateFlow<DesktopSeekBufferSize> = _size.asStateFlow()
    private var loadedProfileId: Int? = null

    fun current(): DesktopSeekBufferSize {
        ensureLoaded()
        return _size.value
    }

    fun ensureLoaded() {
        val profileId = ProfileRepository.activeProfileId
        if (loadedProfileId == profileId) return
        _size.value = DesktopSeekBufferStorage.load()
            ?.let { stored -> DesktopSeekBufferSize.entries.firstOrNull { it.name == stored } }
            ?: DesktopSeekBufferSize.Off
        loadedProfileId = profileId
    }

    fun set(size: DesktopSeekBufferSize) {
        ensureLoaded()
        if (_size.value == size) return
        _size.value = size
        DesktopSeekBufferStorage.save(size.name)
    }
}

internal expect object DesktopSeekBufferStorage {
    fun load(): String?
    fun save(value: String)
}

/**
 * Pre-init mpv options for the seek buffer, or nothing when it does not apply: off, the Metered
 * preset (whose point is to not download ahead), local or LAN sources, or a machine whose RAM
 * ([totalMemoryBytes], null when unknown) is under 4 GiB plus the buffer.
 */
internal fun desktopSeekBufferMpvOptions(
    size: DesktopSeekBufferSize,
    preset: DesktopBufferPreset,
    sourceUrl: String,
    totalMemoryBytes: Long?,
): List<String> {
    if (size == DesktopSeekBufferSize.Off || preset == DesktopBufferPreset.Metered) return emptyList()
    if (!sourceUrl.isInternetPlaybackSource()) return emptyList()
    val bytes = size.mebibytes * 1024 * 1024
    if (totalMemoryBytes != null && totalMemoryBytes < 4L * 1024 * 1024 * 1024 + bytes) return emptyList()
    return listOf(
        "cache=yes",
        "demuxer-max-bytes=${size.mebibytes}MiB",
    )
}
