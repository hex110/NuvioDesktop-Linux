package com.nuvio.app.features.ratings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpisodeRatingsTest {

    @Test
    fun `queues one prompt per episode`() {
        val state = EpisodeRatingsUiState()
            .withPrompt(prompt(season = 4, episode = 5), maxPending = 20)
            .withPrompt(prompt(season = 4, episode = 5), maxPending = 20)

        assertEquals(1, state.pending.size)
        assertEquals(5, state.nextPrompt?.episode)
    }

    @Test
    fun `does not re-queue an episode that is already rated`() {
        val target = prompt(season = 4, episode = 5)
        val rated = EpisodeRatingsUiState()
            .withPrompt(target, maxPending = 20)
            .withRating(target, rating = 8, ratedAtEpochMs = 1_000L)

        assertEquals(emptyList(), rated.pending)
        assertEquals(rated, rated.withPrompt(target, maxPending = 20))
    }

    @Test
    fun `rating an episode records it and advances the queue`() {
        val first = prompt(season = 4, episode = 5)
        val second = prompt(season = 4, episode = 6)
        val state = EpisodeRatingsUiState()
            .withPrompt(first, maxPending = 20)
            .withPrompt(second, maxPending = 20)
            .withRating(first, rating = 9, ratedAtEpochMs = 1_700L)

        val stored = state.ratings.getValue(ratingKey("tt10986410", 4, 5))
        assertEquals(9, stored.rating)
        assertEquals(1_700L, stored.ratedAtEpochMs)
        assertEquals("Ted Lasso", stored.showName)
        assertEquals(6, state.nextPrompt?.episode)
    }

    @Test
    fun `skipping drops the prompt without recording a rating`() {
        val target = prompt(season = 4, episode = 5)
        val state = EpisodeRatingsUiState()
            .withPrompt(target, maxPending = 20)
            .withoutPrompt(target)

        assertTrue(state.pending.isEmpty())
        assertNull(state.nextPrompt)
        assertTrue(state.ratings.isEmpty())
    }

    @Test
    fun `queue drops the oldest prompts past the cap`() {
        val state = (1..5).fold(EpisodeRatingsUiState()) { acc, episode ->
            acc.withPrompt(prompt(season = 1, episode = episode), maxPending = 3)
        }

        assertEquals(listOf(3, 4, 5), state.pending.map { it.episode })
    }

    @Test
    fun `re-rating keeps the providers that already accepted the row`() {
        val target = prompt(season = 4, episode = 5)
        val state = EpisodeRatingsUiState()
            .withPrompt(target, maxPending = 20)
            .withRating(target, rating = 7, ratedAtEpochMs = 1L)

        val synced = state.withSyncedProvider("trakt", state.ratings.values)
        val reRated = synced.withRating(target, rating = 10, ratedAtEpochMs = 2L)
        val stored = reRated.ratings.getValue(ratingKey("tt10986410", 4, 5))

        assertEquals(10, stored.rating)
        assertEquals(setOf("trakt"), stored.syncedProviderIds)
    }

    @Test
    fun `unknown rows are ignored when marking synced`() {
        val state = EpisodeRatingsUiState()
            .withSyncedProvider("trakt", listOf(rating(season = 9, episode = 9)))

        assertTrue(state.ratings.isEmpty())
    }

    @Test
    fun `episode label reads as season by episode`() {
        assertEquals("4x5", episodeLabel(4, 5))
        assertEquals("10x12", episodeLabel(10, 12))
    }

    private fun prompt(season: Int, episode: Int) = PendingRatingPrompt(
        metaId = "tt10986410",
        metaType = "series",
        showName = "Ted Lasso",
        season = season,
        episode = episode,
        completedAtEpochMs = 1_788_804_014_605L,
    )

    private fun rating(season: Int, episode: Int) = EpisodeRating(
        metaId = "tt10986410",
        metaType = "series",
        showName = "Ted Lasso",
        season = season,
        episode = episode,
        rating = 5,
        ratedAtEpochMs = 1L,
    )
}
