package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoSyncSubtitleTimingRewriterTest {
    private fun timeline(
        cues: List<AutoSyncRetimedCue>,
        scale: Double = 1.0,
        interceptMs: Double = 0.0,
    ) = AutoSyncTimelineRetimeResult(
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
        alignmentScale = scale,
        alignmentInterceptMs = interceptMs,
    )

    private fun shifted(startMs: Long, endMs: Long, byMs: Long) =
        AutoSyncRetimedCue(startMs, endMs, startMs + byMs, endMs + byMs)

    @Test
    fun srtKeepsTextAndTagsAndShiftsTimes() {
        val srt = "1\r\n00:00:01,000 --> 00:00:02,500\r\n<i>Hello,</i> there\r\n\r\n" +
            "2\r\n00:00:03,000 --> 00:00:04,000\r\nSecond line\r\n"
        val result = AutoSyncSubtitleTimingRewriter.rewrite(
            srt,
            AutoSyncSubtitleTimingRewriter.Format.Srt,
            timeline(listOf(shifted(1_000, 2_500, 1_250), shifted(3_000, 4_000, 1_250)), interceptMs = 1_250.0),
        )
        assertEquals(
            "1\n00:00:02,250 --> 00:00:03,750\n<i>Hello,</i> there\n\n" +
                "2\n00:00:04,250 --> 00:00:05,250\nSecond line\n",
            result,
        )
    }

    @Test
    fun unmatchedTimesFallBackToTheWholeFilmTransform() {
        val srt = "1\n00:01:00,000 --> 00:01:02,000\nOnly cue\n"
        val result = AutoSyncSubtitleTimingRewriter.rewrite(
            srt,
            AutoSyncSubtitleTimingRewriter.Format.Srt,
            timeline(emptyList(), scale = 1.001, interceptMs = -500.0),
        )
        assertEquals("1\n00:00:59,560 --> 00:01:01,562\nOnly cue\n", result)
    }

    @Test
    fun vttKeepsHourlessClocksReadableAndCueSettings() {
        val vtt = "WEBVTT\n\n00:05.000 --> 00:06.000 align:start position:10%\nHi\n"
        val result = AutoSyncSubtitleTimingRewriter.rewrite(
            vtt,
            AutoSyncSubtitleTimingRewriter.Format.WebVtt,
            timeline(listOf(shifted(5_000, 6_000, -2_000))),
        )
        assertEquals("WEBVTT\n\n00:00:03.000 --> 00:00:04.000 align:start position:10%\nHi\n", result)
    }

    @Test
    fun retimingDoesNotIntroduceOverlaps() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nA\n\n2\n00:00:02,000 --> 00:00:03,000\nB\n"
        val result = AutoSyncSubtitleTimingRewriter.rewrite(
            srt,
            AutoSyncSubtitleTimingRewriter.Format.Srt,
            timeline(
                listOf(
                    AutoSyncRetimedCue(1_000, 2_000, 1_000, 2_400),
                    AutoSyncRetimedCue(2_000, 3_000, 2_200, 3_000),
                ),
            ),
        )
        assertEquals("1\n00:00:01,000 --> 00:00:02,200\nA\n\n2\n00:00:02,200 --> 00:00:03,000\nB\n", result)
    }

    @Test
    fun assRewritesOnlyDialogueTimesAndKeepsCommasInText() {
        val ass = """
            [Script Info]
            Title: x

            [V4+ Styles]
            Format: Name, Fontname
            Style: Default,Arial

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:10.00,0:00:12.50,Default,,0,0,0,,{\i1}Well, hello{\i0}
            Comment: 0,0:00:20.00,0:00:21.00,Default,,0,0,0,,note
        """.trimIndent()
        val result = AutoSyncSubtitleTimingRewriter.rewrite(
            ass,
            AutoSyncSubtitleTimingRewriter.Format.Ass,
            timeline(listOf(shifted(10_000, 12_500, 1_000)), interceptMs = 1_000.0),
        )!!
        val lines = result.lines()
        assertEquals("Dialogue: 0,0:00:11.00,0:00:13.50,Default,,0,0,0,,{\\i1}Well, hello{\\i0}", lines.single { it.startsWith("Dialogue:") })
        assertEquals("Comment: 0,0:00:20.00,0:00:21.00,Default,,0,0,0,,note", lines.single { it.startsWith("Comment:") })
    }

    @Test
    fun detectsFormats() {
        val f = AutoSyncSubtitleTimingRewriter
        val formats = AutoSyncSubtitleTimingRewriter.Format.entries.associateBy { it.name }
        assertEquals(formats.getValue("WebVtt"), f.detectFormat("https://x/a.vtt?x=1", "WEBVTT\n"))
        assertEquals(formats.getValue("Ass"), f.detectFormat("https://x/sub", "[Script Info]\n"))
        assertEquals(formats.getValue("Srt"), f.detectFormat("https://x/sub", "1\n00:00:01,000 --> 00:00:02,000\nA\n"))
        assertNull(f.detectFormat("https://x/sub.ttml", "<?xml version=\"1.0\"?><tt/>"))
    }
}
