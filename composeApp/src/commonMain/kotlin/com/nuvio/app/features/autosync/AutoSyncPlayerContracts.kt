package com.nuvio.app.features.autosync

internal data class AutoSyncSubtitleCandidate(
    val url: String,
    val language: String,
    val name: String? = null,
)

/**
 * Optional AutoSync capability layered beside PlayerEngineController. Android implements it over
 * ExoPlayer's sidecar renderer upstream; here the desktop mpv controller does.
 */
internal interface AutoSyncPlayerController {
    fun cancelForManualSubtitleDelay()
    fun setAutoSyncSubtitleCandidates(candidates: List<AutoSyncSubtitleCandidate>)
    fun setSubtitleUriWithAutoSync(url: String)
    fun setSubtitleUriWithSelectedAutoSync(url: String)
    fun setAutoSyncAppliedListener(
        listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?,
    )
}
