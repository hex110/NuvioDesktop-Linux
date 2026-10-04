package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Imported fonts are copied to $XDG_DATA_HOME/nuvio-htpc/subtitle-fonts. mpv gets that directory
 * as --sub-fonts-dir (libass loads every font in it), and each font is registered with AWT so it
 * appears in [availableSubtitleFontFamilies] next to the installed ones.
 */
internal actual object SubtitleFontImporter {
    private val extensions = setOf("ttf", "otf", "ttc")

    actual suspend fun importFromFile(): String? = withContext(Dispatchers.IO) {
        val chosen = chooseFontFile() ?: return@withContext null
        val directory = subtitleFontsDirectory() ?: return@withContext null
        // Validate before copying: a file AWT cannot read is not a font libass will use either.
        val family = runCatching { Font.createFont(Font.TRUETYPE_FONT, chosen).family }.getOrNull()
            ?: return@withContext null
        val target = File(directory, chosen.name)
        if (chosen.canonicalPath != target.canonicalPath) chosen.copyTo(target, overwrite = true)
        registerImportedSubtitleFonts()
        refreshSubtitleFontFamilies()
        family
    }

    actual fun importedFamilies(): List<String> =
        importedFontFiles().mapNotNull { file ->
            runCatching { Font.createFont(Font.TRUETYPE_FONT, file).family }.getOrNull()
        }.distinct().sorted()

    private fun chooseFontFile(): File? {
        val holder = arrayOfNulls<File>(1)
        val run = Runnable {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = false
                dialogTitle = "Import subtitle font"
                fileFilter = FileNameExtensionFilter("Fonts (*.ttf, *.otf, *.ttc)", *extensions.toTypedArray())
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) holder[0] = chooser.selectedFile
        }
        if (SwingUtilities.isEventDispatchThread()) run.run() else SwingUtilities.invokeAndWait(run)
        return holder[0]
    }

    internal fun importedFontFiles(): List<File> =
        subtitleFontsDirectory()
            ?.listFiles { file -> file.isFile && file.extension.lowercase() in extensions }
            ?.toList()
            .orEmpty()
}

/** Null when it cannot be created. Also the directory handed to mpv as --sub-fonts-dir. */
internal fun subtitleFontsDirectory(): File? {
    val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
        ?: File(System.getProperty("user.home") ?: return null, ".local/share")
    val directory = File(base, "nuvio-htpc/subtitle-fonts")
    return directory.takeIf { it.isDirectory || it.mkdirs() }
}

/** Makes imported fonts visible to AWT's family list; harmless to repeat. */
internal fun registerImportedSubtitleFonts() {
    val environment = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment() }.getOrNull() ?: return
    SubtitleFontImporter.importedFontFiles().forEach { file ->
        runCatching { environment.registerFont(Font.createFont(Font.TRUETYPE_FONT, file)) }
    }
}
