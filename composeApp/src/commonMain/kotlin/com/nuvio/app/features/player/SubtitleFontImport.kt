package com.nuvio.app.features.player

/**
 * Subtitle fonts imported from a file, after Nuvio Reshaped's "Custom subtitle fonts". Desktop
 * already offers every installed system font; this adds a .ttf/.otf without installing it.
 */
internal expect object SubtitleFontImporter {
    /** Asks for a font file and imports it; returns its family name, or null if cancelled/invalid. */
    suspend fun importFromFile(): String?

    /** Families imported so far. */
    fun importedFamilies(): List<String>
}
