package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleFontImporter
import kotlinx.coroutines.launch

/**
 * Import a .ttf/.otf for subtitles, from Nuvio Reshaped's "Custom subtitle fonts". The imported
 * font becomes the subtitle font; it stays selectable in the subtitle style panel afterwards.
 */
@Composable
internal fun SubtitleFontImportSettingsRow(isTablet: Boolean, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    var imported by remember { mutableStateOf(SubtitleFontImporter.importedFamilies()) }
    var status by remember { mutableStateOf<String?>(null) }
    SettingsNavigationRow(
        title = "Import Subtitle Font",
        description = status ?: if (imported.isEmpty()) {
            "Use a .ttf or .otf file for subtitles without installing it."
        } else {
            "Imported: ${imported.joinToString()}. Choose them under Subtitle Style."
        },
        enabled = enabled,
        isTablet = isTablet,
        onClick = {
            scope.launch {
                val family = runCatching { SubtitleFontImporter.importFromFile() }.getOrNull()
                if (family == null) {
                    status = null
                    return@launch
                }
                PlayerSettingsRepository.uiState.value.subtitleStyle.let { style ->
                    PlayerSettingsRepository.setSubtitleStyle(style.copy(fontFamily = family))
                }
                imported = SubtitleFontImporter.importedFamilies()
                status = "Imported $family and set it as the subtitle font."
            }
        },
    )
}
