package com.nuvio.app.features.autosync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopAsrFollowTest {
    private val film = 2 * 60 * 60_000L

    @Test
    fun takesTheFirstGridPointAheadOfThePlayhead() {
        // 10 s minimum lead from 5:00 is 5:10; the 2-minute grid's next point is 6:00.
        assertEquals(360_000L, DesktopAsrSync.nextFollowSpot(300_000L, film, emptyList()))
    }

    @Test
    fun skipsWhatWasAlreadyHeard() {
        // From 5:50 the lead reaches 8:20, so both 6:00 and 8:00 are in range.
        val heard = listOf(360_000L until 390_000L)
        assertEquals(480_000L, DesktopAsrSync.nextFollowSpot(350_000L, film, heard))
    }

    @Test
    fun waitsWhenEverythingWithinTheLeadWasHeard() {
        val heard = listOf(360_000L until 390_000L, 480_000L until 510_000L)
        assertNull(DesktopAsrSync.nextFollowSpot(350_000L, film, heard))
    }

    @Test
    fun aSpotMostlyCoveredByAnInitialSpotCountsAsHeard() {
        // An initial 30 s spot at 5:50 covers 20 of the 30 s from 6:00.
        val heard = listOf(350_000L until 380_000L)
        assertEquals(480_000L, DesktopAsrSync.nextFollowSpot(350_000L, film, heard))
    }

    @Test
    fun stopsBeforeTheEndOfTheFilm() {
        assertNull(DesktopAsrSync.nextFollowSpot(film - 60_000L, film, emptyList()))
    }
}
