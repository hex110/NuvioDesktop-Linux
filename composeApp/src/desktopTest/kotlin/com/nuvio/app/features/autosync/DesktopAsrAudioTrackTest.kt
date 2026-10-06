package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopAsrAudioTrackTest {
    @Test
    fun picksTheEnglishTrackBehindADub() {
        assertEquals(1, DesktopAsrSync.chooseAudioTrack(listOf("ita", "eng")))
    }

    @Test
    fun prefersEnglishOverAnUntaggedFirstTrack() {
        assertEquals(2, DesktopAsrSync.chooseAudioTrack(listOf(null, "ita", "en")))
    }

    @Test
    fun fallsBackToAnUntaggedTrack() {
        assertEquals(1, DesktopAsrSync.chooseAudioTrack(listOf("ita", "und")))
        assertEquals(0, DesktopAsrSync.chooseAudioTrack(listOf(null)))
    }

    @Test
    fun refusesWhenEveryTrackIsAnotherLanguage() {
        assertNull(DesktopAsrSync.chooseAudioTrack(listOf("ita", "fre")))
        assertNull(DesktopAsrSync.chooseAudioTrack(emptyList()))
    }
}
