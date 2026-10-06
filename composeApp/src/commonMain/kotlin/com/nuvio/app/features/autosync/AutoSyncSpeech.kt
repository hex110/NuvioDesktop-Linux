package com.nuvio.app.features.autosync

import kotlinx.coroutines.flow.StateFlow

/** What Settings shows about the speech-recognition model. */
internal data class SpeechModelState(
    val downloaded: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val error: String? = null,
)

/**
 * Speech recognition for AutoSync (hearing the dialogue to pin an add-on subtitle to it). Only
 * platforms that can run it install one, so Settings shows the controls only where it works.
 */
internal interface SpeechRecognitionPlatform {
    val modelState: StateFlow<SpeechModelState>
    val modelSizeMb: Int

    /** False when something the recogniser needs (an ffmpeg install) is missing. */
    val toolsAvailable: Boolean

    fun downloadModel()
    fun deleteModel()
}

internal object AutoSyncSpeech {
    @kotlin.concurrent.Volatile
    var platform: SpeechRecognitionPlatform? = null
}
