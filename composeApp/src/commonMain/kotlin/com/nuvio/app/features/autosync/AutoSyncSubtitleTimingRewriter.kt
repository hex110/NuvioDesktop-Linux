package com.nuvio.app.features.autosync

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Applies an AutoSync result to a subtitle file by rewriting only its timestamps.
 *
 * Android swaps retimed cues into ExoPlayer's sidecar renderer. mpv has no such hook, so desktop
 * writes a corrected copy of the subtitle and loads that instead. Text, tags, cue settings and ASS
 * styling are carried over byte for byte; only the start/end times change. Every timestamp is
 * mapped the way the Android sidecar maps cues: the retimed cue whose original boundary lies
 * within [BOUNDARY_TOLERANCE_MS] wins, otherwise the whole-film transform applies.
 */
internal object AutoSyncSubtitleTimingRewriter {
    private const val BOUNDARY_TOLERANCE_MS = 500L

    enum class Format(val extension: String) {
        Srt("srt"),
        WebVtt("vtt"),
        Ass("ass"),
    }

    private val srtTimingRegex = Regex(
        """^(\s*)((?:\d+:)?\d{1,2}:\d{2}[,.]\d{1,3})(\s*-->\s*)((?:\d+:)?\d{1,2}:\d{2}[,.]\d{1,3})(.*)$""",
    )
    private val clockRegex = Regex("""^(?:(\d+):)?(\d{1,2}):(\d{2})[,.](\d{1,3})$""")
    private val assClockRegex = Regex("""^(\d+):(\d{2}):(\d{2})[.,](\d{1,3})$""")

    /** Null for formats it cannot rewrite (TTML), which AutoSync then reports as unsupported. */
    fun detectFormat(sourceUrl: String?, body: String): Format? {
        val path = sourceUrl?.substringBefore('?')?.substringBefore('#')?.lowercase().orEmpty()
        val head = body.trimStart('﻿', ' ', '\n', '\r', '\t').take(4_096)
        val lowerHead = head.lowercase()
        return when {
            head.startsWith("WEBVTT") || path.endsWith(".vtt") || path.endsWith(".webvtt") -> Format.WebVtt
            "[script info]" in lowerHead || "[events]" in lowerHead ||
                path.endsWith(".ass") || path.endsWith(".ssa") -> Format.Ass
            lowerHead.startsWith("<?xml") || "<tt" in lowerHead || path.endsWith(".ttml") ||
                path.endsWith(".dfxp") -> null
            srtTimingRegex.containsMatchIn(body.take(16_384).replace("\r", "")
                .lineSequence().firstOrNull { "-->" in it }.orEmpty()) -> Format.Srt
            else -> null
        }
    }

    fun rewrite(body: String, format: Format, timeline: AutoSyncTimelineRetimeResult): String? {
        val mapper = TimeMapper(timeline)
        return when (format) {
            Format.Srt, Format.WebVtt -> rewriteCueFile(body, format, mapper)
            Format.Ass -> rewriteAss(body, mapper)
        }
    }

    private class Timing(
        val lineIndex: Int,
        val originalStartMs: Long,
        val originalEndMs: Long,
        var startMs: Long,
        var endMs: Long,
        val indent: String,
        val arrow: String,
        val tail: String,
    )

    private fun rewriteCueFile(body: String, format: Format, mapper: TimeMapper): String? {
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        val timings = ArrayList<Timing>()
        lines.forEachIndexed { index, line ->
            val match = srtTimingRegex.matchEntire(line) ?: return@forEachIndexed
            val start = parseClock(match.groupValues[2]) ?: return@forEachIndexed
            val end = parseClock(match.groupValues[4]) ?: return@forEachIndexed
            val mappedStart = mapper.map(start, isEnd = false)
            timings += Timing(
                lineIndex = index,
                originalStartMs = start,
                originalEndMs = end,
                startMs = mappedStart,
                endMs = mapper.map(end, isEnd = true).coerceAtLeast(mappedStart + 1L),
                indent = match.groupValues[1],
                arrow = match.groupValues[3],
                tail = match.groupValues[5],
            )
        }
        if (timings.isEmpty()) return null
        clampIntroducedOverlaps(timings)
        val separator = if (format == Format.Srt) ',' else '.'
        timings.forEach { timing ->
            lines[timing.lineIndex] = timing.indent + formatClock(timing.startMs, separator) +
                timing.arrow + formatClock(timing.endMs, separator) + timing.tail
        }
        return lines.joinToString("\n")
    }

    /** A cue that did not overlap its successor before retiming must not start to now. */
    private fun clampIntroducedOverlaps(timings: List<Timing>) {
        for (index in 0 until timings.lastIndex) {
            val current = timings[index]
            val next = timings[index + 1]
            if (current.originalEndMs > next.originalStartMs) continue
            if (current.endMs > next.startMs && next.startMs > current.startMs) {
                current.endMs = next.startMs
            }
        }
    }

    private fun rewriteAss(body: String, mapper: TimeMapper): String? {
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        var inEvents = false
        var startField = 1
        var endField = 2
        var fieldCount = 10
        var rewritten = 0
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inEvents = trimmed.equals("[events]", ignoreCase = true)
                return@forEachIndexed
            }
            if (!inEvents) return@forEachIndexed
            if (trimmed.startsWith("format:", ignoreCase = true)) {
                val fields = trimmed.substringAfter(':').split(',').map { it.trim().lowercase() }
                startField = fields.indexOf("start").takeIf { it >= 0 } ?: startField
                endField = fields.indexOf("end").takeIf { it >= 0 } ?: endField
                fieldCount = fields.size
                return@forEachIndexed
            }
            val colon = line.indexOf(':')
            if (colon < 0) return@forEachIndexed
            val kind = line.substring(0, colon).trim()
            if (!kind.equals("Dialogue", ignoreCase = true)) return@forEachIndexed
            // The last field (Text) may contain commas, so split only up to it.
            val fields = line.substring(colon + 1).split(',', limit = fieldCount).toMutableList()
            if (fields.size <= maxOf(startField, endField)) return@forEachIndexed
            val start = parseAssClock(fields[startField].trim()) ?: return@forEachIndexed
            val end = parseAssClock(fields[endField].trim()) ?: return@forEachIndexed
            val mappedStart = mapper.map(start, isEnd = false)
            val mappedEnd = mapper.map(end, isEnd = true).coerceAtLeast(mappedStart + 10L)
            fields[startField] = formatAssClock(mappedStart)
            fields[endField] = formatAssClock(mappedEnd)
            lines[index] = line.substring(0, colon + 1) + fields.joinToString(",")
            rewritten++
        }
        return if (rewritten == 0) null else lines.joinToString("\n")
    }

    private class TimeMapper(private val timeline: AutoSyncTimelineRetimeResult) {
        private val starts = timeline.cues.map { it.originalStartTimeMs to it.startTimeMs }.sortedBy { it.first }
        private val ends = timeline.cues.map { it.originalEndTimeMs to it.endTimeMs }.sortedBy { it.first }

        fun map(originalMs: Long, isEnd: Boolean): Long {
            val boundaries = if (isEnd) ends else starts
            if (boundaries.isNotEmpty()) {
                var low = 0
                var high = boundaries.size
                while (low < high) {
                    val mid = (low + high) ushr 1
                    if (boundaries[mid].first < originalMs) low = mid + 1 else high = mid
                }
                var best: Pair<Long, Long>? = null
                var bestError = Long.MAX_VALUE
                for (index in (low - 2).coerceAtLeast(0)..(low + 2).coerceAtMost(boundaries.lastIndex)) {
                    val error = abs(boundaries[index].first - originalMs)
                    if (error < bestError) {
                        best = boundaries[index]
                        bestError = error
                    }
                }
                if (best != null && bestError <= BOUNDARY_TOLERANCE_MS) {
                    return best.second.coerceAtLeast(0L)
                }
            }
            return (originalMs.toDouble() * timeline.alignmentScale + timeline.alignmentInterceptMs)
                .roundToLong()
                .coerceAtLeast(0L)
        }
    }

    private fun parseClock(value: String): Long? {
        val match = clockRegex.matchEntire(value.trim()) ?: return null
        val hours = match.groupValues[1].ifEmpty { "0" }.toLong()
        val minutes = match.groupValues[2].toLong()
        val seconds = match.groupValues[3].toLong()
        val fraction = match.groupValues[4].padEnd(3, '0').toLong()
        return ((hours * 60 + minutes) * 60 + seconds) * 1_000 + fraction
    }

    private fun parseAssClock(value: String): Long? {
        val match = assClockRegex.matchEntire(value) ?: return null
        val (hours, minutes, seconds) = match.destructured.let {
            Triple(it.component1().toLong(), it.component2().toLong(), it.component3().toLong())
        }
        val fraction = match.groupValues[4].padEnd(3, '0').toLong()
        return ((hours * 60 + minutes) * 60 + seconds) * 1_000 + fraction
    }

    private fun formatClock(ms: Long, separator: Char): String {
        val total = ms.coerceAtLeast(0L)
        val hours = total / 3_600_000
        val minutes = total / 60_000 % 60
        val seconds = total / 1_000 % 60
        val millis = total % 1_000
        return "${pad(hours, 2)}:${pad(minutes, 2)}:${pad(seconds, 2)}$separator${pad(millis, 3)}"
    }

    // ASS keeps centiseconds and a single-digit hour.
    private fun formatAssClock(ms: Long): String {
        val centis = (ms.coerceAtLeast(0L) + 5) / 10
        val hours = centis / 360_000
        val minutes = centis / 6_000 % 60
        val seconds = centis / 100 % 60
        return "$hours:${pad(minutes, 2)}:${pad(seconds, 2)}.${pad(centis % 100, 2)}"
    }

    private fun pad(value: Long, width: Int): String = value.toString().padStart(width, '0')
}
