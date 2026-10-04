package com.nuvio.app.features.autosync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_toast_failed
import nuvio.composeapp.generated.resources.autosync_toast_failed_no_reference
import nuvio.composeapp.generated.resources.autosync_toast_failed_unsupported
import nuvio.composeapp.generated.resources.autosync_toast_synced
import nuvio.composeapp.generated.resources.autosync_toast_synced_other
import nuvio.composeapp.generated.resources.autosync_toast_within_tolerance
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * AutoSync for the desktop mpv player; the counterpart of upstream's Android
 * AutoSyncPlayerCoordinator.
 *
 * The add-on subtitle is attached at once, as without AutoSync, so it shows while the analysis
 * runs. A confident result is applied by loading a copy of the subtitle with corrected timestamps
 * (see [AutoSyncSubtitleTimingRewriter]) in place of the original. That is a subtitle track swap
 * in mpv, not a reload of the video. Any other subtitle change cancels the run and bumps
 * [operationToken], so a late result can never overwrite a newer choice.
 *
 * All entry points are called on the UI thread, and the coroutines run there between suspensions.
 */
internal class DesktopAutoSyncCoordinator(
    /** The playing source and its request headers; null when nothing is loaded. */
    private val currentSource: () -> Pair<String, Map<String, String>>?,
    /** Loads a subtitle into mpv and selects it (sub-add ... select). */
    private val attachSubtitle: (String) -> Unit,
    /** Removes every app-added subtitle, then loads and selects [path]. */
    private val replaceSubtitles: (path: String) -> Unit,
    /** Sets the player's subtitle delay; a retimed file already carries the shift. */
    private val setPlayerSubtitleDelayMs: (Int) -> Unit,
    private val showMessage: (String) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var operationToken = 0L
    // The add-on subtitle URL the player currently shows (possibly as a retimed copy).
    private var activeSubtitleUrl: String? = null
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null

    fun setCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
    }

    fun setAppliedListener(listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?) {
        appliedListener = listener
    }

    fun cancel() {
        operationToken++
        job?.cancel()
        job = null
    }

    /** A built-in track was chosen or subtitles were turned off. */
    fun onSubtitleCleared() {
        cancel()
        activeSubtitleUrl = null
        AutoSyncSyncedSubtitle.clear()
    }

    /** A new source is loading; nothing from the previous one applies to it. */
    fun onSourceChanged() = onSubtitleCleared()

    fun dispose() {
        cancel()
        appliedListener = null
        scope.cancel()
    }

    fun attachWithoutAutoSync(url: String) {
        cancel()
        AutoSyncSyncedSubtitle.clear()
        activeSubtitleUrl = url
        attachSubtitle(url)
    }

    fun start(url: String, candidateScope: AutoSyncCandidateScope) {
        cancel()
        AutoSyncSyncedSubtitle.clear()
        val token = operationToken
        activeSubtitleUrl = url
        attachSubtitle(url)

        AutoSyncPreferencesRepository.ensureLoaded()
        val source = currentSource()
        val enabled = AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value
        if (decideAutoSyncStart(enabled) == AutoSyncStartAction.ATTACH_ORIGINAL || source == null) return
        val (sourceUrl, sourceHeaders) = source
        // Same scope as upstream: the Matroska index is read over HTTP range requests.
        if (!sourceUrl.startsWith("https://", true) && !sourceUrl.startsWith("http://", true)) return
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return

        EmbeddedSubtitleTimelineLoader.prefetch(scope, sourceUrl, sourceHeaders)
        job = scope.launch {
            try {
                var outcome: AutoSyncAnalysisOutcome? = null
                val resolved = AutomaticSubtitleSync.findTimelineRetime(
                    sourceKey = sourceUrl,
                    sourceHeaders = sourceHeaders,
                    selectedSubtitleUrl = url,
                    selectedSubtitleHeaders = emptyMap(),
                    preferredLanguage = candidates.firstOrNull { it.url == url }?.language
                        ?.takeIf { it.isNotBlank() },
                    alternativeSubtitles = candidateScope.alternativeCandidates(candidates),
                    alternativeSubtitlesProvider = if (candidateScope.usesAlternativeProvider) {
                        { candidates }
                    } else {
                        null
                    },
                    onAnalysisOutcome = { outcome = it },
                )
                currentCoroutineContext().ensureActive()
                if (token != operationToken) return@launch
                if (resolved == null) {
                    showStatus(token, failureMessage(outcome))
                    return@launch
                }

                val chosenUrl = resolved.subtitleUrl
                val tolerance = AutoSyncPreferencesRepository.syncToleranceMs.value
                if (tolerance > 0 && chosenUrl == url && resolved.timeline.maxAlignmentShiftMs() <= tolerance) {
                    AutoSyncSyncedSubtitle.mark(url)
                    showStatus(token, Res.string.autosync_toast_within_tolerance)
                    return@launch
                }

                val body = resolved.subtitleBody
                    ?: AutomaticSubtitleSync.downloadSubtitleBody(chosenUrl, resolved.subtitleHeaders)
                val format = AutoSyncSubtitleTimingRewriter.detectFormat(chosenUrl, body)
                if (format == null) {
                    showStatus(token, Res.string.autosync_toast_failed_unsupported)
                    return@launch
                }
                val path = withContext(Dispatchers.IO) {
                    val rewritten = AutoSyncSubtitleTimingRewriter.rewrite(body, format, resolved.timeline)
                        ?: return@withContext null
                    writeRetimedSubtitle(rewritten, format)
                }
                currentCoroutineContext().ensureActive()
                if (token != operationToken || activeSubtitleUrl != url) return@launch
                if (path == null) {
                    showStatus(token, Res.string.autosync_toast_failed)
                    return@launch
                }

                replaceSubtitles(path.toString())
                activeSubtitleUrl = chosenUrl
                setPlayerSubtitleDelayMs(0)
                appliedListener?.invoke(chosenUrl, 0)
                AutoSyncSyncedSubtitle.mark(chosenUrl)
                val shift = formatShift(typicalShiftMs(resolved.timeline))
                showStatus(
                    token,
                    if (chosenUrl == url) Res.string.autosync_toast_synced else Res.string.autosync_toast_synced_other,
                    shift,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // The original subtitle stays attached with its original timing.
                if (token == operationToken) showStatus(token, Res.string.autosync_toast_failed)
            }
        }
    }

    private fun failureMessage(outcome: AutoSyncAnalysisOutcome?): StringResource = when (outcome) {
        AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS,
        AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE,
        -> Res.string.autosync_toast_failed_no_reference
        else -> Res.string.autosync_toast_failed
    }

    private suspend fun showStatus(token: Long, resource: StringResource, vararg args: Any) {
        val text = getString(resource, *args)
        if (token == operationToken) showMessage(text)
    }

    private companion object {
        // One directory per app run; mpv reads a subtitle file fully when it is added, but the
        // file stays until exit so a re-selection of the same track keeps working.
        val retimedDirectory: Path by lazy {
            Files.createTempDirectory("nuvio-autosync-").also { it.toFile().deleteOnExit() }
        }

        fun writeRetimedSubtitle(text: String, format: AutoSyncSubtitleTimingRewriter.Format): Path {
            val file = Files.createTempFile(retimedDirectory, "retimed-", ".${format.extension}")
            file.toFile().deleteOnExit()
            Files.writeString(file, text)
            return file
        }

        /** The median shift across cues: the "how far off was it" number for the message. */
        fun typicalShiftMs(timeline: AutoSyncTimelineRetimeResult): Long {
            val shifts = timeline.cues.map { it.startTimeMs - it.originalStartTimeMs }.sorted()
            if (shifts.isEmpty()) return timeline.alignmentInterceptMs.roundToLong()
            return shifts[shifts.size / 2]
        }

        fun formatShift(shiftMs: Long): String {
            val sign = if (shiftMs < 0) "−" else "+"
            val tenths = (abs(shiftMs) + 50) / 100
            return "$sign${tenths / 10}.${tenths % 10} s"
        }
    }
}
