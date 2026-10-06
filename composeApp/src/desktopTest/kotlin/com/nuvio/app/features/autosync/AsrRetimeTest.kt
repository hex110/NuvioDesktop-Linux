package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.audiosync.SubtitleSyncModel
import com.nuvio.app.features.player.audiosync.SubtitleSyncSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AsrRetimeTest {
    private val srt = """
        1
        00:00:10,000 --> 00:00:12,000
        Hello there

        2
        00:01:40,500 --> 00:01:42,000
        General Kenobi
    """.trimIndent() + "\n"

    @Test
    fun appliesAConstantOffsetAndAScaleToEveryCue() {
        val cues = AutoSyncSubtitleCueParser.parse(srt, "x.srt")
        val timeline = asrRetimeTimeline(cues, SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, -4_000.0))))
        assertEquals(listOf(6_000L, 96_500L), timeline.cues.map { it.startTimeMs })

        val stretched = asrRetimeTimeline(cues, SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0427, -2_000.0))))
        assertEquals(10_000 * 1.0427 - 2_000, stretched.cues[0].startTimeMs.toDouble(), 1.0)
    }

    @Test
    fun rewritesTheSubtitleFileWithTheMapping() {
        val cues = AutoSyncSubtitleCueParser.parse(srt, "x.srt")
        val timeline = asrRetimeTimeline(cues, SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, -4_000.0))))
        val rewritten = assertNotNull(AutoSyncSubtitleTimingRewriter.rewrite(srt, AutoSyncSubtitleTimingRewriter.Format.Srt, timeline))
        assertTrue("00:00:06,000 --> 00:00:08,000" in rewritten, rewritten)
        assertTrue("00:01:36,500 --> 00:01:38,000" in rewritten, rewritten)
        assertTrue("Hello there" in rewritten && "General Kenobi" in rewritten)
    }

    @Test
    fun aSceneTheReleaseCutsMovesTheLaterCuesByAnotherOffset() {
        val cues = AutoSyncSubtitleCueParser.parse(srt, "x.srt")
        // After 60 s of media time the subtitle is 2 s further ahead.
        val model = SubtitleSyncModel(listOf(SubtitleSyncSegment(0L, 1.0, -4_000.0), SubtitleSyncSegment(60_000L, 1.0, -6_000.0)))
        val timeline = asrRetimeTimeline(cues, model)
        assertEquals(listOf(6_000L, 94_500L), timeline.cues.map { it.startTimeMs })
    }
}
