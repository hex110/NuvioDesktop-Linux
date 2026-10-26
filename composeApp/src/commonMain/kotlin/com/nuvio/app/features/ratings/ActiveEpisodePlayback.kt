package com.nuvio.app.features.ratings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The episode the player is on right now, as a rating key, or null outside the player.
 *
 * Lets the episode rating prompt appear as soon as playback moves on to a different episode,
 * instead of waiting for the player to be closed.
 */
object ActiveEpisodePlayback {
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()

    fun enter(metaId: String, season: Int, episode: Int) {
        _current.value = ratingKey(metaId, season, episode)
    }

    fun leave(metaId: String, season: Int, episode: Int) {
        _current.compareAndSet(ratingKey(metaId, season, episode), null)
    }
}
