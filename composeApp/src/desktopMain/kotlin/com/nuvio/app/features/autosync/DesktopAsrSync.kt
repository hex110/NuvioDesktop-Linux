package com.nuvio.app.features.autosync

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.SubtitleSdhFilter
import com.nuvio.app.features.player.audiosync.DialogueSpotPlanner
import com.nuvio.app.features.player.audiosync.FfmpegSpotSampler
import com.nuvio.app.features.player.audiosync.FfmpegTools
import com.nuvio.app.features.player.audiosync.SileroVad
import com.nuvio.app.features.player.audiosync.SileroVadWeights
import com.nuvio.app.features.player.audiosync.SpeechAnalyzer
import com.nuvio.app.features.player.audiosync.SpeechTimeline
import com.nuvio.app.features.player.audiosync.SubtitleSpeechTrack
import com.nuvio.app.features.player.audiosync.SubtitleSyncModel
import com.nuvio.app.features.player.audiosync.asr.AsrLock
import com.nuvio.app.features.player.audiosync.asr.AsrSyncEngine
import com.nuvio.app.features.player.audiosync.asr.DesktopAsrModel
import com.nuvio.app.features.player.audiosync.asr.ReferenceSubtitle
import com.nuvio.app.features.player.audiosync.asr.SharedRecognizer
import com.nuvio.app.features.player.audiosync.asr.SpeechSegmenter
import com.nuvio.app.features.player.audiosync.asr.SubtitleBridge
import com.nuvio.app.features.player.audiosync.asr.WordAnchorMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference

internal sealed interface AsrSyncResult {
    /** The target subtitle maps onto the media timeline by [model]. */
    data class Locked(val model: SubtitleSyncModel, val referenceKey: String) : AsrSyncResult

    /** Nothing confident was found; [reason] is for the log. */
    data class Failed(val reason: String) : AsrSyncResult
}

/**
 * AutoSync's speech-recognition path for desktop (Reshaped's "layer 2"): hears English dialogue
 * at a few spots across the film and pins the subtitle to those words. It is used when the
 * embedded-subtitle comparison has no reference, which is the case for most releases.
 *
 * Audio comes from ffmpeg subprocesses (see [FfmpegSpotSampler]); speech is cut out with Silero
 * VAD, recognised by sherpa-onnx, and matched by [AsrSyncEngine] against an English subtitle: the
 * target itself, or another English one bridged to it by timing pattern.
 */
internal object DesktopAsrSync {
    private val log = Logger.withTag("AutoSyncAsr")

    private const val SPOT_COUNT = 4
    private const val SPOT_MS = 30_000L
    private const val SPOT_WORKERS = 2
    private const val RECOGNIZER_THREADS = 2
    private const val MIN_TARGET_CUES = 20
    private const val MAX_REFERENCES = 4
    private const val OVERALL_TIMEOUT_MS = 5 * 60_000L
    private const val POLL_MS = 200L
    private const val MIN_FILM_MS = 10 * 60_000L

    /** While following the playhead: this much audio heard per [FOLLOW_EVERY_MS] of the film. */
    private const val FOLLOW_SPOT_MS = 30_000L
    private const val FOLLOW_EVERY_MS = 120_000L

    /**
     * How far ahead of the playhead a followed spot may start. Its end must stay within the
     * engine's post-lock window (speech up to 3 minutes ahead), or the engine skips its speech.
     */
    private const val FOLLOW_LEAD_MS = 150_000L

    /** Speech closer than this to the playhead would be recognised too late to matter. */
    private const val FOLLOW_MIN_LEAD_MS = 10_000L
    private const val FOLLOW_POLL_MS = 5_000L

    private val englishStopWords = setOf("the", "and", "you", "to", "is", "it", "that", "of", "what", "this", "don't", "we", "your", "are", "have")

    /** Language tags that say nothing about the language spoken. */
    private val unknownLanguages = setOf("und", "mul", "zxx", "mis", "unknown")

    @Volatile
    private var cachedWeights: SileroVadWeights? = null

    private fun weights(): SileroVadWeights = cachedWeights ?: synchronized(this) {
        cachedWeights ?: checkNotNull(DesktopAsrSync::class.java.getResourceAsStream("/audiosync/silero_vad_v5_16k.bin")) {
            "Silero VAD weights are missing from the app resources"
        }.use(SileroVadWeights::read).also { cachedWeights = it }
    }

    /** True when everything speech recognition needs on this machine is in place. */
    fun isUsable(): Boolean = FfmpegTools.available && DesktopAsrModel.isReady()

    fun dialogueOf(body: String, url: String?): List<Triple<Long, Long, String>> =
        AutoSyncSubtitleCueParser.parse(body, url).mapNotNull { cue ->
            SubtitleSdhFilter.filter(cue.text)?.let { Triple(cue.startTimeMs, cue.endTimeMs, it) }
        }

    /**
     * Which audio track to listen to: the first English one, else the first that says nothing
     * about its language (it may well be English), else none. Not necessarily the track the viewer
     * hears: dubs of one release share the video's timeline, so a sync found on the English track
     * holds for all of them. Indexes count audio tracks only, as ffmpeg's `0:a:N` does.
     */
    fun chooseAudioTrack(languages: List<String?>): Int? {
        languages.indexOfFirst { isEnglish(it) }.takeIf { it >= 0 }?.let { return it }
        return languages.indexOfFirst { it == null || it in unknownLanguages }.takeIf { it >= 0 }
    }

    fun isEnglish(language: String?): Boolean {
        val value = language?.trim()?.lowercase() ?: return false
        return value == "en" || value == "eng" || value.startsWith("en-") || value.startsWith("en_") ||
            value.contains("english")
    }

    private fun looksEnglish(dialogue: List<Triple<Long, Long, String>>): Boolean {
        val words = dialogue.asSequence().flatMap { WordAnchorMatcher.tokenize(it.third) }.take(2_000).toList()
        if (words.size < 50) return false
        return words.count { it in englishStopWords } >= words.size * 0.15
    }

    /**
     * Listens at a few spots across the film and, once the subtitle is pinned, hands the mapping
     * to [onLock]. While that returns true, listening goes on just ahead of the playhead (see
     * [follow]): the subtitle can drift or jump again later in the film, and each change of the
     * mapping is handed to [onLock] again, with `update` set. Returns when [onLock] declines,
     * playback stops ([playheadMs] null) or the coroutine is cancelled: a failure if nothing was
     * ever pinned, else the last mapping.
     *
     * @param targetBody the add-on subtitle to sync.
     * @param targetLanguage its language tag, if known.
     * @param otherCandidates other subtitles for the title; English ones serve as references when
     *   the target is not English.
     */
    suspend fun sync(
        sourceUrl: String,
        sourceHeaders: Map<String, String>,
        targetUrl: String,
        targetBody: String,
        targetLanguage: String?,
        otherCandidates: List<AutoSyncSubtitleCandidate>,
        playheadMs: suspend () -> Long?,
        onLock: suspend (result: AsrSyncResult.Locked, update: Boolean) -> Boolean,
    ): AsrSyncResult = withContext(Dispatchers.IO) {
        if (!FfmpegTools.available) return@withContext AsrSyncResult.Failed("ffmpeg/ffprobe are not installed")
        if (!DesktopAsrModel.isReady()) return@withContext AsrSyncResult.Failed("the speech model is not downloaded")

        val dialogue = dialogueOf(targetBody, targetUrl)
        val track = SubtitleSpeechTrack.fromCues(dialogue)
        if (track.size < MIN_TARGET_CUES) return@withContext AsrSyncResult.Failed("the subtitle has too few dialogue lines (${track.size})")

        val probe = FfmpegTools.probe(sourceUrl, sourceHeaders)
            ?: return@withContext AsrSyncResult.Failed("ffprobe could not read the stream")
        val audioTrack = chooseAudioTrack(probe.audioLanguages)
            ?: return@withContext AsrSyncResult.Failed(
                if (probe.audioLanguages.isEmpty()) {
                    "the stream has no audio track"
                } else {
                    "no English audio track (${probe.audioLanguages.joinToString()}); the speech model understands English only"
                },
            )
        log.i { "listening to audio track $audioTrack of ${probe.audioLanguages}" }
        if (probe.durationMs < MIN_FILM_MS) return@withContext AsrSyncResult.Failed("the video is too short to sample")

        val references = buildReferences(dialogue, track, targetUrl, targetLanguage, otherCandidates)
        if (references.isEmpty()) return@withContext AsrSyncResult.Failed("no English subtitle to match the speech against")

        val lock = AtomicReference<AsrLock?>(null)
        val timeline = SpeechTimeline()
        val engine = AsrSyncEngine(
            timeline = timeline,
            onLock = { lock.set(it) },
            log = { log.d { it } },
            workerSetup = {},
        )
        val recognizer = SharedRecognizer.acquire(DesktopAsrModel.directory, RECOGNIZER_THREADS)
        try {
            engine.setRecognizer(recognizer)
            engine.startSession(track, references)

            val tracks = listOf(track) + references.filter { it.key != targetUrl }.map { it.speechTrack }
            val spots = DialogueSpotPlanner.plan(tracks, probe.durationMs, SPOT_COUNT, SPOT_MS)
            if (spots.isEmpty()) return@withContext AsrSyncResult.Failed("no dialogue spots to sample")
            log.i { "sampling audio at ${spots.map { it / 1_000 }}s, references=${references.map { it.key.take(60) }}" }

            val vadWeights = weights()
            val sampler = FfmpegSpotSampler(sourceUrl, sourceHeaders, audioTrack)

            /** Reads [lengthMs] of audio from [start] into the engine; [spread] as in [AsrSyncEngine.offerSegment]. */
            fun readSpot(start: Long, lengthMs: Long, spread: Boolean, cancelled: () -> Boolean) {
                // One analyzer per spot: each reads a different place, so audio must not mix.
                val analyzer = SpeechAnalyzer(SileroVad(vadWeights), timeline)
                val segmenter = SpeechSegmenter { startFrame, samples ->
                    engine.offerSegment(startFrame, samples, spread = spread)
                }
                analyzer.chunkListener = segmenter
                val ok = runCatching { sampler.readSpot(start, lengthMs, analyzer, cancelled) }
                    .onFailure { log.w(it) { "spot at ${start / 1_000}s failed" } }
                    .getOrDefault(false)
                segmenter.flush()
                if (!ok) log.i { "spot at ${start / 1_000}s gave no audio" }
            }

            val deadline = System.nanoTime() + OVERALL_TIMEOUT_MS * 1_000_000L
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val cancelled = { stop.get() || System.nanoTime() > deadline }
            val permits = Semaphore(SPOT_WORKERS)
            try {
              coroutineScope {
                spots.map { start ->
                    async(Dispatchers.IO) {
                        permits.acquire()
                        try {
                            readSpot(start, SPOT_MS, spread = true, cancelled)
                        } finally {
                            permits.release()
                        }
                    }
                }.awaitAll()
              }
            } finally {
                // Also on cancellation: running ffmpeg readers notice the flag and stop.
                stop.set(true)
            }

            while (currentCoroutineContext().isActive && !engine.isIdle() && System.nanoTime() < deadline) delay(POLL_MS)
            val found = lock.get()
            log.i {
                "done: heard ${engine.heardWordCount} words, " +
                    "lock=${found?.let { SubtitleSyncModel(it.segments) }} final=${found?.final}"
            }
            val first = when {
                found == null -> return@withContext AsrSyncResult.Failed("heard ${engine.heardWordCount} words, none matched the subtitle confidently")
                !found.final -> return@withContext AsrSyncResult.Failed("only a provisional match (frame rate unconfirmed)")
                else -> found
            }
            if (!onLock(first.toResult(), false)) return@withContext first.toResult()
            follow(
                engine = engine,
                lock = lock,
                applied = first,
                alreadySampled = spots.map { it until it + SPOT_MS },
                durationMs = probe.durationMs,
                playheadMs = playheadMs,
                readSpot = { start, cancelled -> readSpot(start, FOLLOW_SPOT_MS, spread = false, cancelled) },
                onLock = onLock,
            )
        } finally {
            engine.release()
            SharedRecognizer.release()
        }
    }

    /**
     * After the first lock: keeps hearing [FOLLOW_SPOT_MS] of audio every [FOLLOW_EVERY_MS] of the
     * film, up to [FOLLOW_LEAD_MS] ahead of the playhead, so a later scene the subtitle was not
     * made for shows up as a new piece of the mapping before it is on screen. The engine re-fits
     * the whole film with every word heard and reports only real changes (see
     * [AsrSyncEngine]); each is handed to [onLock]. Costs about a quarter of the stream's bitrate
     * on top of playback, and nothing while paused once the spots ahead are heard.
     */
    private suspend fun follow(
        engine: AsrSyncEngine,
        lock: AtomicReference<AsrLock?>,
        applied: AsrLock,
        alreadySampled: List<LongRange>,
        durationMs: Long,
        playheadMs: suspend () -> Long?,
        readSpot: (start: Long, cancelled: () -> Boolean) -> Unit,
        onLock: suspend (AsrSyncResult.Locked, Boolean) -> Boolean,
    ): AsrSyncResult.Locked {
        val job = currentCoroutineContext()[kotlinx.coroutines.Job]
        val cancelled = { job?.isActive == false }
        val sampled = alreadySampled.toMutableList()
        var current = applied
        log.i { "following the playhead: ${FOLLOW_SPOT_MS / 1_000}s of audio every ${FOLLOW_EVERY_MS / 1_000}s of the film" }
        while (currentCoroutineContext().isActive) {
            val playhead = playheadMs() ?: break
            // Speech the engine may recognise once locked is judged against the playhead.
            engine.onPlayhead(playhead)
            val latest = lock.get()
            if (latest != null && latest !== current && latest.final) {
                current = latest
                log.i {
                    "follow: mapping changed at playhead ${playhead / 1_000}s, heard ${engine.heardWordCount} words, " +
                        "lock=${SubtitleSyncModel(latest.segments)}"
                }
                if (!onLock(latest.toResult(), true)) break
            }
            val next = nextFollowSpot(playhead, durationMs, sampled)
            if (next == null) {
                delay(FOLLOW_POLL_MS)
                continue
            }
            sampled += next until next + FOLLOW_SPOT_MS
            log.d { "follow: sampling ${next / 1_000}s (playhead ${playhead / 1_000}s)" }
            readSpot(next, cancelled)
        }
        return current.toResult()
    }

    /**
     * The next stretch to hear while following: the earliest point of the [FOLLOW_EVERY_MS] grid
     * between [FOLLOW_MIN_LEAD_MS] and [FOLLOW_LEAD_MS] ahead of [playheadMs] whose stretch was not
     * mostly heard yet ([sampled]); null when there is none. A fixed grid means a seek back over
     * heard ground reads nothing again.
     */
    internal fun nextFollowSpot(playheadMs: Long, durationMs: Long, sampled: List<LongRange>): Long? {
        val from = playheadMs + FOLLOW_MIN_LEAD_MS
        val until = minOf(playheadMs + FOLLOW_LEAD_MS, durationMs - FOLLOW_SPOT_MS)
        var spot = (from + FOLLOW_EVERY_MS - 1) / FOLLOW_EVERY_MS * FOLLOW_EVERY_MS
        while (spot <= until) {
            val range = spot until spot + FOLLOW_SPOT_MS
            val heard = sampled.sumOf { (minOf(it.last, range.last) - maxOf(it.first, range.first) + 1).coerceAtLeast(0L) }
            if (heard * 2 < FOLLOW_SPOT_MS) return spot
            spot += FOLLOW_EVERY_MS
        }
        return null
    }

    private fun AsrLock.toResult() = AsrSyncResult.Locked(SubtitleSyncModel(segments), referenceKey)

    private suspend fun buildReferences(
        dialogue: List<Triple<Long, Long, String>>,
        track: SubtitleSpeechTrack,
        targetUrl: String,
        targetLanguage: String?,
        otherCandidates: List<AutoSyncSubtitleCandidate>,
    ): List<ReferenceSubtitle> {
        if (isEnglish(targetLanguage) || looksEnglish(dialogue)) {
            return listOf(ReferenceSubtitle(targetUrl, dialogue, null))
        }
        val references = ArrayList<ReferenceSubtitle>()
        for (candidate in otherCandidates.filter { isEnglish(it.language) && it.url != targetUrl }.take(MAX_REFERENCES)) {
            val body = runCatching { AutomaticSubtitleSync.downloadSubtitleBody(candidate.url, emptyMap()) }.getOrNull() ?: continue
            val referenceDialogue = dialogueOf(body, candidate.url)
            val referenceTrack = SubtitleSpeechTrack.fromCues(referenceDialogue)
            if (referenceTrack.size < MIN_TARGET_CUES) continue
            val bridge = SubtitleBridge.align(track, referenceTrack)
            log.i { "reference ${candidate.url.take(80)}: bridge=$bridge" }
            if (bridge != null) references += ReferenceSubtitle(candidate.url, referenceDialogue, bridge)
        }
        return references
    }
}
