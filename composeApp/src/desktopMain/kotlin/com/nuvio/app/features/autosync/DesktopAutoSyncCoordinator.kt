package com.nuvio.app.features.autosync

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.audiosync.FfmpegTools
import com.nuvio.app.features.player.audiosync.SubtitleSyncModel
import com.nuvio.app.features.player.audiosync.asr.DesktopAsrModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.autosync_toast_failed
import nuvio.composeapp.generated.resources.autosync_toast_failed_listening
import nuvio.composeapp.generated.resources.autosync_toast_listening
import nuvio.composeapp.generated.resources.autosync_toast_resynced
import nuvio.composeapp.generated.resources.autosync_toast_failed_no_reference
import nuvio.composeapp.generated.resources.autosync_toast_failed_unsupported
import nuvio.composeapp.generated.resources.autosync_toast_synced
import nuvio.composeapp.generated.resources.autosync_toast_synced_other
import nuvio.composeapp.generated.resources.autosync_run_comparing
import nuvio.composeapp.generated.resources.autosync_run_downloading_model
import nuvio.composeapp.generated.resources.autosync_run_embedded_needs_stream
import nuvio.composeapp.generated.resources.autosync_run_model_download_failed
import nuvio.composeapp.generated.resources.autosync_run_needs_addon_subtitle
import nuvio.composeapp.generated.resources.autosync_run_needs_ffmpeg
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
    /** Where playback is now; null when nothing is playing. */
    private val currentPositionMs: () -> Long?,
    /** Loads a subtitle into mpv and selects it (sub-add ... select). */
    private val attachSubtitle: (String) -> Unit,
    /** Removes every app-added subtitle, then loads and selects [path]. */
    private val replaceSubtitles: (path: String) -> Unit,
    /** Sets the player's subtitle delay; a retimed file already carries the shift. */
    private val setPlayerSubtitleDelayMs: (Int) -> Unit,
    private val showMessage: (String) -> Unit,
    /** The Auto Sync card's status line: what the last or current run says, and whether it runs. */
    private val showRunStatus: (text: String, running: Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val log = Logger.withTag("AutoSync")
    private var job: Job? = null
    private var operationToken = 0L
    // The add-on subtitle URL the player currently shows (possibly as a retimed copy).
    private var activeSubtitleUrl: String? = null
    private var candidates: List<AutoSyncSubtitleCandidate> = emptyList()
    // The card shows a run in progress (not a result) for the current job.
    private var runInProgress = false
    private var appliedListener: ((subtitleUrl: String, delayMs: Int) -> Unit)? = null

    fun setCandidates(value: List<AutoSyncSubtitleCandidate>) {
        candidates = value.distinctBy { it.url }
    }

    fun setAppliedListener(listener: ((subtitleUrl: String, delayMs: Int) -> Unit)?) {
        appliedListener = listener
    }

    fun cancel() {
        operationToken++
        // A listening run stays active while it follows the playhead; its result stays shown.
        if (job?.isActive == true && runInProgress) showRunStatus("", false)
        runInProgress = false
        job?.cancel()
        job = null
    }

    /** A built-in track was chosen or subtitles were turned off. */
    fun onSubtitleCleared() {
        cancel()
        showRunStatus("", false)
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
        showRunStatus("", false)
        AutoSyncSyncedSubtitle.clear()
        activeSubtitleUrl = url
        attachSubtitle(url)
    }

    fun start(url: String, candidateScope: AutoSyncCandidateScope) {
        cancel()
        showRunStatus("", false)
        AutoSyncSyncedSubtitle.clear()
        val token = operationToken
        activeSubtitleUrl = url
        attachSubtitle(url)

        AutoSyncPreferencesRepository.ensureLoaded()
        val source = currentSource()
        val enabled = AutoSyncPreferencesRepository.preferredSubtitleAutoSyncOnStart.value
        if (decideAutoSyncStart(enabled) == AutoSyncStartAction.ATTACH_ORIGINAL || source == null) {
            log.i { "skipped: ${if (!enabled) "AutoSync is off" else "no source loaded"}" }
            return
        }
        val (sourceUrl, sourceHeaders) = source
        // Same scope as upstream: the Matroska index is read over HTTP range requests.
        if (!sourceUrl.startsWith("https://", true) && !sourceUrl.startsWith("http://", true)) {
            log.i { "skipped: the video is not an http(s) stream (local files and torrents have no readable index)" }
            return
        }
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) {
            log.i { "skipped: the subtitle is not an http(s) URL" }
            return
        }
        log.i { "start: scope=$candidateScope candidates=${candidates.size} subtitle=${url.take(120)}" }

        EmbeddedSubtitleTimelineLoader.prefetch(scope, sourceUrl, sourceHeaders)
        job = launchRun(token) {
            compareWithEmbedded(token, url, sourceUrl, sourceHeaders, candidateScope, listenAsFallback = true)
        }
    }

    /**
     * The viewer asked for a sync of the add-on subtitle on screen, by [method], from the
     * subtitle panel. Unlike [start] this ignores the AutoSync on/off and speech settings: the
     * request is explicit. A speech run downloads the model first when it is missing. The run
     * always starts from the subtitle's original timing, so repeating it is safe.
     */
    fun runOnDemand(method: AutoSyncMethod) {
        val url = activeSubtitleUrl
        cancel()
        AutoSyncPreferencesRepository.ensureLoaded()
        val token = operationToken
        val source = currentSource()
        job = launchRun(token) {
            when {
                url == null -> showStatus(token, Res.string.autosync_run_needs_addon_subtitle)
                !url.isHttpUrl() -> showStatus(token, Res.string.autosync_run_needs_addon_subtitle)
                source == null -> showStatus(token, Res.string.autosync_toast_failed)
                method == AutoSyncMethod.EMBEDDED_SUBTITLES -> {
                    val (sourceUrl, sourceHeaders) = source
                    if (!sourceUrl.isHttpUrl()) {
                        showStatus(token, Res.string.autosync_run_embedded_needs_stream)
                        return@launchRun
                    }
                    log.i { "on demand: embedded subtitles, subtitle=${url.take(120)}" }
                    showProgress(token, Res.string.autosync_run_comparing)
                    EmbeddedSubtitleTimelineLoader.prefetch(scope, sourceUrl, sourceHeaders)
                    compareWithEmbedded(
                        token, url, sourceUrl, sourceHeaders, AutoSyncCandidateScope.SELECTED_ONLY,
                        listenAsFallback = false,
                    )
                }
                else -> {
                    log.i { "on demand: speech recognition, subtitle=${url.take(120)}" }
                    if (!prepareSpeechRecognition(token)) return@launchRun
                    listenToSync(token, url, source.first, source.second)
                }
            }
        }
    }

    /** Runs [block] as the current job; a failure leaves the subtitle as it was. */
    private fun launchRun(token: Long, block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            log.w(error) { "failed" }
            // The original subtitle stays attached with its original timing.
            if (token == operationToken) showStatus(token, Res.string.autosync_toast_failed)
        }
    }

    /** False (after reporting why) when ffmpeg is missing or the model can't be downloaded. */
    private suspend fun prepareSpeechRecognition(token: Long): Boolean {
        if (!FfmpegTools.available) {
            showStatus(token, Res.string.autosync_run_needs_ffmpeg)
            return false
        }
        if (DesktopAsrModel.isReady()) return true
        showProgress(token, Res.string.autosync_run_downloading_model, DesktopAsrModel.DOWNLOAD_MB)
        var ready = withContext(Dispatchers.IO) { DesktopAsrModel.download() }
        // False straight away when Settings is already downloading it; wait for that one.
        while (!ready && DesktopAsrModel.state.value.downloading) {
            delay(500)
            ready = DesktopAsrModel.isReady()
        }
        currentCoroutineContext().ensureActive()
        if (!ready) {
            log.w { "speech model download failed: ${DesktopAsrModel.state.value.error}" }
            showStatus(token, Res.string.autosync_run_model_download_failed)
        }
        return ready
    }

    /**
     * Compares the add-on subtitle [url] with the video's embedded subtitles and applies a
     * confident result. With [listenAsFallback], a missing reference hands over to speech
     * recognition when that is enabled in Settings.
     */
    private suspend fun compareWithEmbedded(
        token: Long,
        url: String,
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        candidateScope: AutoSyncCandidateScope,
        listenAsFallback: Boolean,
    ) {
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
        if (token != operationToken) return
        if (resolved == null) {
            log.i { "no result: outcome=${outcome ?: "analysis found no confident alignment"}" }
            if (
                listenAsFallback &&
                outcome != AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE &&
                AutoSyncPreferencesRepository.speechRecognition.value &&
                DesktopAsrSync.isUsable() &&
                listenToSync(token, url, sourceUrl, sourceHeaders)
            ) {
                return
            }
            showStatus(token, failureMessage(outcome))
            return
        }

        val chosenUrl = resolved.subtitleUrl
        val tolerance = AutoSyncPreferencesRepository.syncToleranceMs.value
        if (tolerance > 0 && chosenUrl == url && resolved.timeline.maxAlignmentShiftMs() <= tolerance) {
            log.i { "within tolerance (${resolved.timeline.maxAlignmentShiftMs()} ms <= $tolerance ms)" }
            AutoSyncSyncedSubtitle.mark(url)
            showStatus(token, Res.string.autosync_toast_within_tolerance)
            return
        }

        val body = resolved.subtitleBody
            ?: AutomaticSubtitleSync.downloadSubtitleBody(chosenUrl, resolved.subtitleHeaders)
        val format = AutoSyncSubtitleTimingRewriter.detectFormat(chosenUrl, body)
        if (format == null) {
            showStatus(token, Res.string.autosync_toast_failed_unsupported)
            return
        }
        val path = withContext(Dispatchers.IO) {
            val rewritten = AutoSyncSubtitleTimingRewriter.rewrite(body, format, resolved.timeline)
                ?: return@withContext null
            writeRetimedSubtitle(rewritten, format)
        }
        currentCoroutineContext().ensureActive()
        if (token != operationToken || activeSubtitleUrl != url) return
        if (path == null) {
            showStatus(token, Res.string.autosync_toast_failed)
            return
        }

        replaceSubtitles(path.toString())
        activeSubtitleUrl = chosenUrl
        setPlayerSubtitleDelayMs(0)
        appliedListener?.invoke(chosenUrl, 0)
        AutoSyncSyncedSubtitle.mark(chosenUrl)
        val shift = formatShift(typicalShiftMs(resolved.timeline))
        log.i { "applied: shift=$shift cues=${resolved.timeline.cues.size} switchedSubtitle=${chosenUrl != url}" }
        showStatus(
            token,
            if (chosenUrl == url) Res.string.autosync_toast_synced else Res.string.autosync_toast_synced_other,
            shift,
        )
    }

    /**
     * Speech recognition (see [DesktopAsrSync]): listen to the dialogue and pin the subtitle to it.
     * Runs as the fallback when no embedded subtitle could serve as a reference, or on demand.
     * The caller checks that ffmpeg and the model are in place. Always handles the outcome, by
     * applying a result or by reporting its own failure, and returns true.
     *
     * After the first result it keeps listening just ahead of the playhead for as long as this
     * subtitle stays on screen, and re-applies the timing whenever the subtitle drifts or jumps
     * somewhere else in the film. The run (and so [job]) lasts until playback or the choice changes.
     */
    private suspend fun listenToSync(
        token: Long,
        url: String,
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
    ): Boolean {
        showProgress(token, Res.string.autosync_toast_listening)
        val body = AutomaticSubtitleSync.downloadSubtitleBody(url, emptyMap())
        val format = AutoSyncSubtitleTimingRewriter.detectFormat(url, body)
        if (format == null) {
            showStatus(token, Res.string.autosync_toast_failed_unsupported)
            return true
        }
        val cues = AutoSyncSubtitleCueParser.parse(body, url)
        var applied: SubtitleSyncModel? = null
        val result = DesktopAsrSync.sync(
            sourceUrl = sourceUrl,
            sourceHeaders = sourceHeaders,
            targetUrl = url,
            targetBody = body,
            targetLanguage = candidates.firstOrNull { it.url == url }?.language?.takeIf { it.isNotBlank() },
            otherCandidates = candidates,
            playheadMs = {
                withContext(Dispatchers.Main) {
                    currentPositionMs().takeIf { token == operationToken && activeSubtitleUrl == url }
                }
            },
            onLock = { locked, update ->
                withContext(Dispatchers.Main) {
                    applyListened(token, url, body, format, cues, locked.model, previous = applied.takeIf { update })
                        .also { if (it) applied = locked.model }
                }
            },
        )
        currentCoroutineContext().ensureActive()
        if (token != operationToken || activeSubtitleUrl != url) return true
        if (result !is AsrSyncResult.Locked) {
            log.i { "listening found nothing: ${(result as AsrSyncResult.Failed).reason}" }
            showStatus(token, Res.string.autosync_toast_failed_listening)
        }
        return true
    }

    /**
     * Retimes the subtitle by [model] and loads it. [previous] is the mapping already applied when
     * this is a later change found while following the playhead; the first result reports in the
     * card, a later one only in the HUD pill. False when the run should stop listening.
     */
    private suspend fun applyListened(
        token: Long,
        url: String,
        body: String,
        format: AutoSyncSubtitleTimingRewriter.Format,
        cues: List<com.nuvio.app.features.player.SubtitleSyncCue>,
        model: SubtitleSyncModel,
        previous: SubtitleSyncModel?,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        if (token != operationToken || activeSubtitleUrl != url) return false
        val timeline = asrRetimeTimeline(cues, model)
        val tolerance = AutoSyncPreferencesRepository.syncToleranceMs.value
        if (previous == null && tolerance > 0 && timeline.maxAlignmentShiftMs() <= tolerance && model.segments.size == 1) {
            log.i { "listening: within tolerance; following the playhead" }
            AutoSyncSyncedSubtitle.mark(url)
            showStatus(token, Res.string.autosync_toast_within_tolerance)
            return true
        }
        val path = withContext(Dispatchers.IO) {
            AutoSyncSubtitleTimingRewriter.rewrite(body, format, timeline)?.let { writeRetimedSubtitle(it, format) }
        }
        currentCoroutineContext().ensureActive()
        if (token != operationToken || activeSubtitleUrl != url) return false
        if (path == null) {
            if (previous == null) showStatus(token, Res.string.autosync_toast_failed)
            return false
        }
        replaceSubtitles(path.toString())
        setPlayerSubtitleDelayMs(0)
        appliedListener?.invoke(url, 0)
        AutoSyncSyncedSubtitle.mark(url)
        if (previous == null) {
            log.i { "applied by listening: $model cues=${timeline.cues.size}" }
            showStatus(token, Res.string.autosync_toast_synced, formatShift(typicalShiftMs(timeline)))
        } else {
            // Where the new mapping first differs from the one on screen: what the viewer will notice.
            val changed = model.segments.firstOrNull { segment ->
                val before = previous.segmentAt(segment.fromMediaMs)
                before.scale != segment.scale || abs(before.shiftMs - segment.shiftMs) >= RESYNC_REPORT_MIN_MS
            } ?: model.segments.last()
            log.i { "re-applied by listening: $model (was $previous)" }
            showMessage(
                getString(
                    Res.string.autosync_toast_resynced,
                    formatPosition(changed.fromMediaMs),
                    formatShift(changed.shiftMs.roundToLong()),
                ),
            )
        }
        return true
    }

    private fun failureMessage(outcome: AutoSyncAnalysisOutcome?): StringResource = when (outcome) {
        AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS,
        AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE,
        -> Res.string.autosync_toast_failed_no_reference
        else -> Res.string.autosync_toast_failed
    }

    /** A final result: the HUD pill, and the Auto Sync card's status line. */
    private suspend fun showStatus(token: Long, resource: StringResource, vararg args: Any) {
        val text = getString(resource, *args)
        if (token == operationToken) {
            showMessage(text)
            runInProgress = false
            showRunStatus(text, false)
        }
    }

    /** A run in progress; the card keeps showing it until [showStatus] replaces it. */
    private suspend fun showProgress(token: Long, resource: StringResource, vararg args: Any) {
        val text = getString(resource, *args)
        if (token == operationToken) {
            showMessage(text)
            runInProgress = true
            showRunStatus(text, true)
        }
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

        /** A change smaller than this is fine-tuning, not what a viewer would call a re-sync. */
        const val RESYNC_REPORT_MIN_MS = 300.0

        /** Film time as m:ss or h:mm:ss. */
        fun formatPosition(positionMs: Long): String {
            val seconds = positionMs.coerceAtLeast(0L) / 1_000
            val h = seconds / 3_600
            val m = seconds / 60 % 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        fun formatShift(shiftMs: Long): String {
            val sign = if (shiftMs < 0) "−" else "+"
            val tenths = (abs(shiftMs) + 50) / 100
            return "$sign${tenths / 10}.${tenths % 10} s"
        }
    }
}

/** How the viewer asked to sync the subtitle from the subtitle panel. */
internal enum class AutoSyncMethod {
    /** Compare against the video's embedded subtitles (cue timing pattern and duration). */
    EMBEDDED_SUBTITLES,

    /** Hear the dialogue with the speech-recognition model and match it to the subtitle's words. */
    SPEECH,
}

private fun String.isHttpUrl(): Boolean = startsWith("https://", true) || startsWith("http://", true)
