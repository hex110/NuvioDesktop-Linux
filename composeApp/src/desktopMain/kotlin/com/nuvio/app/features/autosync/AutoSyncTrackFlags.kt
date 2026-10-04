package com.nuvio.app.features.autosync

/**
 * The Media3 track flags AutoSync's reference ranking reads. Upstream takes them from
 * `androidx.media3.common.C`; desktop has no Media3, so the same values live here and the
 * Matroska loader sets them from the track's own flags.
 */
internal object AutoSyncTrackFlags {
    const val SELECTION_FLAG_FORCED = 1 shl 1
    const val ROLE_FLAG_COMMENTARY = 1 shl 3
    const val ROLE_FLAG_SUBTITLE = 1 shl 7
    const val ROLE_FLAG_DESCRIBES_VIDEO = 1 shl 9
    const val ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND = 1 shl 10
    const val ROLE_FLAG_TRANSCRIBES_DIALOG = 1 shl 12
}
