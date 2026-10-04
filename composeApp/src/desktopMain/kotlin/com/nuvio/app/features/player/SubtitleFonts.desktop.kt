package com.nuvio.app.features.player

import java.awt.GraphicsEnvironment

// Enumerating installed fonts via AWT can take a few hundred ms the first time, so cache it
// for the session. Prepend "" so the player default is always the first option.
//
// Deliberately not the app font's Skia-backed list (core/ui/AppFontFamilies.desktop.kt): these
// names go to mpv, which does its own matching and takes AWT's style variants ("Arial Black",
// "Calibri Light") as families in their own right. Narrowing this to Skia's typographic families
// would drop subtitle fonts that work today.
@Volatile
private var cachedSubtitleFontFamilies: List<String>? = null

private val systemSubtitleFontFamilies: List<String>
    get() = cachedSubtitleFontFamilies ?: loadSubtitleFontFamilies().also { cachedSubtitleFontFamilies = it }

/** Re-reads the family list, after a subtitle font import. */
internal fun refreshSubtitleFontFamilies() {
    cachedSubtitleFontFamilies = loadSubtitleFontFamilies()
}

private fun loadSubtitleFontFamilies(): List<String> {
    // Imported subtitle fonts are not installed system-wide; register them so they are listed.
    registerImportedSubtitleFonts()
    val families = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .availableFontFamilyNames
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedBy { it.lowercase() }
            .toList()
    }.getOrDefault(emptyList())
    return listOf("") + families
}

actual fun availableSubtitleFontFamilies(): List<String> = systemSubtitleFontFamilies

/** Warms the system-font cache off the UI thread so the first player open isn't janky. */
fun warmSubtitleFontCache() {
    Thread { systemSubtitleFontFamilies }
        .apply {
            name = "nuvio-subtitle-font-warmup"
            isDaemon = true
        }
        .start()
}
