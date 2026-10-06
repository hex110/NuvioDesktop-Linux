package com.nuvio.app.features.player.audiosync

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Reads short stretches of one of a stream's audio tracks with an ffmpeg subprocess, decoded to
 * 16 kHz mono float PCM, and feeds them to a [SpeechAnalyzer] at their true media time. This is
 * the desktop counterpart of Reshaped's ExoPlayer-extractor spot sampler: ffmpeg seeks (HTTP
 * range requests for remote files) and decodes any codec the player can play, over its own
 * connection, without touching mpv's playback. Blocking; run it on a background thread.
 */
internal class FfmpegSpotSampler(
    private val url: String,
    private val headers: Map<String, String>,
    /** Which audio track to decode, counted among audio tracks only (ffmpeg's `0:a:N`). */
    private val audioTrack: Int = 0,
) {
    /** Reads [spotMs] of audio starting at [startMs] into [analyzer]; false when it failed or was cancelled. */
    fun readSpot(startMs: Long, spotMs: Long, analyzer: SpeechAnalyzer, isCancelled: () -> Boolean): Boolean {
        val command = buildList {
            // Sampling must never compete with playback for CPU.
            if (NICE_AVAILABLE) addAll(listOf("nice", "-n", "10"))
            addAll(listOf("ffmpeg", "-nostdin", "-v", "error"))
            addAll(FfmpegTools.headerArgs(headers))
            addAll(listOf("-ss", "%.3f".format(startMs / 1_000.0), "-i", url, "-t", "%.3f".format(spotMs / 1_000.0)))
            addAll(listOf("-map", "0:a:$audioTrack", "-vn", "-sn", "-dn", "-ac", "1", "-ar", "16000", "-f", "f32le", "pipe:1"))
        }
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val block = ByteArray(BLOCK_SAMPLES * 4)
            val samples = FloatArray(BLOCK_SAMPLES)
            var fed = 0L
            val input = process.inputStream
            while (!isCancelled()) {
                val read = input.readNBytes(block, 0, block.size)
                if (read <= 0) break
                val count = read / 4
                ByteBuffer.wrap(block, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(samples, 0, count)
                analyzer.accept(samples, count, SAMPLE_RATE, startMs * 1_000L + fed * 1_000_000L / SAMPLE_RATE)
                fed += count
                if (read < block.size) break
            }
            return !isCancelled() && fed > 0
        } finally {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val BLOCK_SAMPLES = 16_000
        val NICE_AVAILABLE: Boolean by lazy {
            runCatching { ProcessBuilder("nice", "true").start().waitFor() == 0 }.getOrDefault(false)
        }
    }
}
