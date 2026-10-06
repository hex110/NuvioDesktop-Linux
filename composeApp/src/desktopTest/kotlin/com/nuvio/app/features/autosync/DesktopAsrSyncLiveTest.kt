package com.nuvio.app.features.autosync

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * Manual end-to-end check of the speech-recognition path against a real file. Needs the speech
 * model downloaded, ffmpeg installed and two environment variables, so it does nothing in CI:
 *
 *   NUVIO_ASR_TEST_MEDIA=/path/to/video  NUVIO_ASR_TEST_SRT=/path/to/english.srt
 *   ./gradlew :composeApp:desktopTest --tests '*DesktopAsrSyncLiveTest*' -i
 */
class DesktopAsrSyncLiveTest {
    @Test
    fun printsTheMappingFoundForARealFile() {
        val media = System.getenv("NUVIO_ASR_TEST_MEDIA") ?: return
        val srt = File(System.getenv("NUVIO_ASR_TEST_SRT") ?: return)
        val started = System.nanoTime()
        val result = runBlocking {
            DesktopAsrSync.sync(
                sourceUrl = media,
                sourceHeaders = emptyMap(),
                targetUrl = srt.name,
                targetBody = srt.readText(),
                targetLanguage = "en",
                otherCandidates = emptyList(),
                // The first lock only: there is no playhead to follow.
                playheadMs = { null },
                onLock = { _, _ -> false },
            )
        }
        println("ASR-LIVE-RESULT after ${(System.nanoTime() - started) / 1_000_000} ms: $result")
    }
}
