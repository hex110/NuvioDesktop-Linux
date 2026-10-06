package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.audiosync.SubtitleSyncModel
import kotlin.math.roundToLong

/**
 * Turns a speech-recognition mapping (subtitle time -> media time, possibly in several pieces)
 * into the timeline [AutoSyncSubtitleTimingRewriter] applies, so a result found by listening is
 * written into the subtitle file exactly like one found by comparing subtitles.
 */
internal fun asrRetimeTimeline(
    originalCues: List<com.nuvio.app.features.player.SubtitleSyncCue>,
    model: SubtitleSyncModel,
): AutoSyncTimelineRetimeResult {
    val fallback = model.segments.first()
    fun map(ms: Long): Long =
        (model.mediaTimeUs(ms * 1_000L)?.let { it / 1_000.0 } ?: (ms * fallback.scale + fallback.shiftMs))
            .roundToLong().coerceAtLeast(0L)

    val cues = originalCues.sortedBy { it.startTimeMs }.map { cue ->
        AutoSyncRetimedCue(
            originalStartTimeMs = cue.startTimeMs,
            originalEndTimeMs = cue.endTimeMs,
            startTimeMs = map(cue.startTimeMs),
            endTimeMs = map(cue.endTimeMs),
        )
    }
    return AutoSyncTimelineRetimeResult(
        cues = cues,
        groups = emptyList(),
        targetCoverage = 1.0,
        referenceCoverage = 1.0,
        skippedTargetCues = 0,
        skippedReferenceCues = 0,
        longestTargetSkipRun = 0,
        averageGroupCost = 0.0,
        oneToOneGroups = 0,
        oneToTwoGroups = 0,
        twoToOneGroups = 0,
        oneToThreeGroups = 0,
        threeToOneGroups = 0,
        twoToTwoGroups = 0,
        confident = true,
        alignmentSource = "speech-recognition",
        alignmentScale = fallback.scale,
        alignmentInterceptMs = fallback.shiftMs,
    )
}
