package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.player.DesktopSeekBufferPreference
import com.nuvio.app.features.player.DesktopSeekBufferSize

/** Seek buffer size, from Nuvio Reshaped's "Seek buffer". */
@Composable
internal fun DesktopSeekBufferSettingsRow(isTablet: Boolean) {
    val size by remember {
        DesktopSeekBufferPreference.ensureLoaded()
        DesktopSeekBufferPreference.size
    }.collectAsStateWithLifecycle()
    SettingsChoiceRow(
        title = "Seek Buffer",
        description = size.description,
        options = DesktopSeekBufferSize.entries.map { SettingsChoiceOption(it, it.label) },
        selectedValue = size,
        isTablet = isTablet,
        onSelected = DesktopSeekBufferPreference::set,
    )
}
