package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import com.nuvio.app.features.autosync.AutoSyncPlayerController
import com.nuvio.app.features.autosync.AutoSyncPreferencesRepository
import com.nuvio.app.features.autosync.AutoSyncSubtitleCandidate

/*
 * AutoSync hooks for the player runtime, adapted from upstream NuvioMobile PR #2143
 * (PlayerRuntimeAutoSync.kt + PlayerAutoSyncAdapter.kt) to this fork's subtitle selection.
 * Controllers that do not implement AutoSyncPlayerController get plain setSubtitleUri.
 */

private fun List<AddonSubtitle>.toAutoSyncCandidates(): List<AutoSyncSubtitleCandidate> =
    map { subtitle ->
        AutoSyncSubtitleCandidate(
            url = subtitle.url,
            language = subtitle.language,
            name = subtitle.display,
        )
    }

@Composable
internal fun PlayerScreenRuntime.BindAutoSyncRuntimeEffects() {
    val activeController = playerController
    DisposableEffect(activeController) {
        (activeController as? AutoSyncPlayerController)?.setAutoSyncAppliedListener { subtitleUrl, delayMs ->
            onAutoSyncApplied(subtitleUrl, delayMs)
        }
        onDispose { (activeController as? AutoSyncPlayerController)?.setAutoSyncAppliedListener(null) }
    }
    LaunchedEffect(activeController, addonSubtitles) {
        (activeController as? AutoSyncPlayerController)
            ?.setAutoSyncSubtitleCandidates(addonSubtitles.toAutoSyncCandidates())
    }
}

/**
 * AutoSync replaced the subtitle's timing (and, on a startup search, possibly the subtitle). The
 * correction lives in the loaded file, so the manual delay goes back to zero. A swapped-in
 * subtitle is not persisted as the viewer's preference: they did not pick it.
 */
private fun PlayerScreenRuntime.onAutoSyncApplied(subtitleUrl: String, delayMs: Int) {
    val applied = addonSubtitles.firstOrNull { it.url == subtitleUrl }
    selectedAddonSubtitleId = applied?.id?.ifBlank { applied.url } ?: subtitleUrl
    selectedSubtitleIndex = -1
    useCustomSubtitles = true
    val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    subtitleDelayMs = clamped
    PlayerTrackPreferenceStorage.saveSubtitleDelayMs(playbackSession.videoId, clamped)
}

/**
 * An add-on subtitle chosen automatically (startup preference or a restored choice). AutoSync may
 * swap in a better-matching subtitle of the same language, once per playback.
 */
internal fun PlayerScreenRuntime.attachAutomaticAddonSubtitle(url: String) {
    val controller = playerController ?: return
    if (
        controller is AutoSyncPlayerController &&
        AutoSyncPreferencesRepository.claimStartupRun(hashCode(), activePlaybackIdentity)
    ) {
        controller.setSubtitleUriWithAutoSync(url)
    } else {
        controller.setSubtitleUri(url)
    }
}

/** The viewer picked this add-on subtitle: AutoSync checks its timing but never replaces it. */
internal fun PlayerScreenRuntime.attachChosenAddonSubtitle(url: String) {
    val controller = playerController ?: return
    if (controller is AutoSyncPlayerController) {
        controller.setSubtitleUriWithSelectedAutoSync(url)
    } else {
        controller.setSubtitleUri(url)
    }
}

/** A manual delay change wins over a pending AutoSync result. */
internal fun PlayerScreenRuntime.cancelAutoSyncForManualDelay() {
    (playerController as? AutoSyncPlayerController)?.cancelForManualSubtitleDelay()
}
