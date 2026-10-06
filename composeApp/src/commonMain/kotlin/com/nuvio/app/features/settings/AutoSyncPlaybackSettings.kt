package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.autosync.AutoSyncSpeech
import com.nuvio.app.features.player.SubtitleLanguageOption
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_mode_aggressive
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_mode_aggressive_description
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_mode_passive
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_mode_passive_description
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_speech
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_speech_description
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_speech_downloading
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_speech_error
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_speech_no_tools
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_speech_remove
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_speech_remove_description
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_tolerance
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_tolerance_description
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_tolerance_off
import nuvio.composeapp.generated.resources.settings_playback_auto_sync_tolerance_value
import nuvio.composeapp.generated.resources.settings_playback_subtitle_auto_sync
import nuvio.composeapp.generated.resources.settings_playback_subtitle_auto_sync_description
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AutoSyncPlaybackSettingsRows(
    isTablet: Boolean,
    enabled: Boolean,
    preferredSubtitleLanguage: String,
) {
    val preferredSubtitleAutoSyncOnStart by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart
    }.collectAsStateWithLifecycle()
    val aggressiveMode by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.aggressiveMode
    }.collectAsStateWithLifecycle()
    val syncToleranceMs by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.syncToleranceMs
    }.collectAsStateWithLifecycle()

    val preferredLanguageAvailable =
        preferredSubtitleLanguage.isNotBlank() &&
            preferredSubtitleLanguage != SubtitleLanguageOption.NONE &&
            preferredSubtitleLanguage != SubtitleLanguageOption.FORCED

    SettingsSwitchRow(
        title = stringResource(Res.string.settings_playback_subtitle_auto_sync),
        description = stringResource(
            Res.string.settings_playback_subtitle_auto_sync_description,
        ),
        checked = preferredSubtitleAutoSyncOnStart,
        enabled = enabled && preferredLanguageAvailable,
        isTablet = isTablet,
        onCheckedChange = AutoSyncPreferencesRepository::setPreferredSubtitleAutoSyncOnStart,
    )
    if (!preferredSubtitleAutoSyncOnStart) return
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(
            if (aggressiveMode) {
                Res.string.settings_playback_auto_sync_mode_aggressive
            } else {
                Res.string.settings_playback_auto_sync_mode_passive
            },
        ),
        description = stringResource(
            if (aggressiveMode) {
                Res.string.settings_playback_auto_sync_mode_aggressive_description
            } else {
                Res.string.settings_playback_auto_sync_mode_passive_description
            },
        ),
        checked = aggressiveMode,
        enabled = enabled,
        isTablet = isTablet,
        onCheckedChange = AutoSyncPreferencesRepository::setAggressiveMode,
    )
    SettingsGroupDivider(isTablet = isTablet)
    SettingsNavigationRow(
        title = stringResource(
            Res.string.settings_playback_auto_sync_tolerance,
            if (syncToleranceMs > 0) {
                stringResource(Res.string.settings_playback_auto_sync_tolerance_value, syncToleranceMs)
            } else {
                stringResource(Res.string.settings_playback_auto_sync_tolerance_off)
            },
        ),
        description = stringResource(
            Res.string.settings_playback_auto_sync_tolerance_description,
        ),
        enabled = enabled,
        isTablet = isTablet,
        onClick = {
            val options = AutoSyncPreferencesRepository.syncToleranceOptionsMs
            val next = options[(options.indexOf(syncToleranceMs) + 1) % options.size]
            AutoSyncPreferencesRepository.setSyncToleranceMs(next)
        },
    )
    AutoSyncSpeechRows(isTablet = isTablet, enabled = enabled)
}

@Composable
private fun AutoSyncSpeechRows(isTablet: Boolean, enabled: Boolean) {
    val speech = AutoSyncSpeech.platform ?: return
    val speechRecognition by remember {
        AutoSyncPreferencesRepository.ensureLoaded()
        AutoSyncPreferencesRepository.speechRecognition
    }.collectAsStateWithLifecycle()
    val model by speech.modelState.collectAsStateWithLifecycle()

    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = stringResource(Res.string.settings_playback_auto_sync_speech),
        description = when {
            !speech.toolsAvailable -> stringResource(Res.string.settings_playback_auto_sync_speech_no_tools)
            model.downloading -> stringResource(
                Res.string.settings_playback_auto_sync_speech_downloading,
                (model.progress * 100).toInt(),
            )
            model.error != null && speechRecognition -> stringResource(
                Res.string.settings_playback_auto_sync_speech_error,
                model.error.orEmpty(),
            )
            else -> stringResource(Res.string.settings_playback_auto_sync_speech_description, speech.modelSizeMb)
        },
        checked = speechRecognition,
        enabled = enabled && speech.toolsAvailable,
        isTablet = isTablet,
        onCheckedChange = { on ->
            AutoSyncPreferencesRepository.setSpeechRecognition(on)
            if (on && !model.downloaded && !model.downloading) speech.downloadModel()
        },
    )
    if (model.downloaded) {
        SettingsGroupDivider(isTablet = isTablet)
        SettingsNavigationRow(
            title = stringResource(Res.string.settings_playback_auto_sync_speech_remove, speech.modelSizeMb),
            description = stringResource(Res.string.settings_playback_auto_sync_speech_remove_description),
            enabled = enabled,
            isTablet = isTablet,
            onClick = {
                AutoSyncPreferencesRepository.setSpeechRecognition(false)
                speech.deleteModel()
            },
        )
    }
}
