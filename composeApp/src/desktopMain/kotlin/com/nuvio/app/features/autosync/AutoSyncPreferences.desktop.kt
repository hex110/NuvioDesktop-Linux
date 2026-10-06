package com.nuvio.app.features.autosync

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

private const val preferredSubtitleAutoSyncOnStartKey = "preferred_subtitle_auto_sync_on_start"
private const val aggressiveModeKey = "auto_sync_aggressive_mode"
private const val syncToleranceMsKey = "auto_sync_tolerance_ms"
private const val speechRecognitionKey = "auto_sync_speech_recognition"

// Same keys and profile scoping as upstream's Android SharedPreferences file.
internal actual fun installPlatformAutoSyncPersistence() {
    val store = DesktopStorage.store("nuvio_autosync_settings")
    AutoSyncPreferencesRepository.installPersistence(
        load = { store.getBoolean(ProfileScopedKey.of(preferredSubtitleAutoSyncOnStartKey)) },
        save = { store.putBoolean(ProfileScopedKey.of(preferredSubtitleAutoSyncOnStartKey), it) },
        loadAggressiveMode = { store.getBoolean(ProfileScopedKey.of(aggressiveModeKey)) },
        saveAggressiveMode = { store.putBoolean(ProfileScopedKey.of(aggressiveModeKey), it) },
        loadSyncToleranceMs = { store.getInt(ProfileScopedKey.of(syncToleranceMsKey)) },
        saveSyncToleranceMs = { store.putInt(ProfileScopedKey.of(syncToleranceMsKey), it) },
        loadSpeechRecognition = { store.getBoolean(ProfileScopedKey.of(speechRecognitionKey)) },
        saveSpeechRecognition = { store.putBoolean(ProfileScopedKey.of(speechRecognitionKey), it) },
    )
    AutoSyncSpeech.platform = DesktopSpeechRecognition
}
