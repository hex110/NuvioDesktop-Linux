package com.nuvio.app.features.ratings

import co.touchlab.kermit.Logger
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local store for per-episode ratings, plus the queue of episodes still waiting
 * to be rated.
 *
 * Ratings are kept locally and are never dropped on the floor: an episode that
 * completes is queued, and the queue survives restarts, so dismissing the
 * player or finishing a season finale does not lose the prompt. State
 * transitions live as pure functions in `EpisodeRatingModels.kt`; this object
 * only adds locking and persistence.
 *
 * Distinct from `RatingPromptRepository`, which writes title-level ratings
 * straight to Trakt/Simkl and only fires on finales. This one is local-first
 * and works with nothing connected; [unsyncedRatings] is what a later push to a
 * provider would drain.
 */
object EpisodeRatingsRepository {

    /** Oldest prompts are dropped past this, so a long binge cannot pile up forever. */
    internal const val MaxPendingPrompts = 20

    val RatingRange: IntRange = 1..10

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }
    private val logger = Logger.withTag("EpisodeRatings")

    private val _uiState = MutableStateFlow(EpisodeRatingsUiState())
    val uiState: StateFlow<EpisodeRatingsUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var currentProfileId: Int = 1
    private var lastEnqueuedKey: String? = null

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk(ProfileRepository.activeProfileId)
        EpisodeRatingYamtrackSync.requestSync()
    }

    fun onProfileChanged(profileId: Int) {
        if (profileId == currentProfileId && hasLoaded) return
        lastEnqueuedKey = null
        loadFromDisk(profileId)
    }

    fun clearLocalState() {
        synchronized(lock) {
            hasLoaded = false
            currentProfileId = 1
            lastEnqueuedKey = null
            _uiState.value = EpisodeRatingsUiState()
        }
    }

    fun ratingFor(metaId: String, season: Int, episode: Int): EpisodeRating? =
        _uiState.value.ratings[ratingKey(metaId, season, episode)]

    /**
     * Queues a prompt for a freshly completed episode.
     *
     * Called from the playback-completion path rather than from the
     * next-episode card, so finales and manual episode switches are covered.
     */
    fun enqueuePrompt(
        metaId: String,
        metaType: String,
        showName: String,
        season: Int,
        episode: Int,
        completedAtEpochMs: Long,
    ) {
        ensureLoaded()
        val prompt = PendingRatingPrompt(
            metaId = metaId,
            metaType = metaType,
            showName = showName,
            season = season,
            episode = episode,
            completedAtEpochMs = completedAtEpochMs,
        )
        // The player flushes progress more than once as an episode ends, so the same
        // completion arrives repeatedly. Skipping a prompt only clears the queue, so
        // without this the episode the user just dismissed would be queued straight back.
        if (prompt.key == lastEnqueuedKey) return
        lastEnqueuedKey = prompt.key
        logger.i { "episode rating prompt queued: $showName ${episodeLabel(season, episode)}" }
        mutate { it.withPrompt(prompt, MaxPendingPrompts) }
    }

    /** Records [rating] for [prompt] and removes it from the queue. */
    fun rate(prompt: PendingRatingPrompt, rating: Int) {
        require(rating in RatingRange) { "rating must be in $RatingRange, was $rating" }
        ensureLoaded()
        mutate { it.withRating(prompt, rating, System.currentTimeMillis()) }
        EpisodeRatingYamtrackSync.requestSync()
    }

    /** Drops [prompt] without recording a rating. */
    fun skip(prompt: PendingRatingPrompt) {
        ensureLoaded()
        mutate { it.withoutPrompt(prompt) }
    }

    /** Ratings that [providerId] has not accepted yet. */
    fun unsyncedRatings(providerId: String): List<EpisodeRating> =
        _uiState.value.ratings.values.filterNot { providerId in it.syncedProviderIds }

    /** Marks [ratings] as accepted by [providerId] so a later pass skips them. */
    fun markSynced(providerId: String, ratings: Collection<EpisodeRating>) {
        if (ratings.isEmpty()) return
        mutate { it.withSyncedProvider(providerId, ratings) }
    }

    private inline fun mutate(transform: (EpisodeRatingsUiState) -> EpisodeRatingsUiState) {
        synchronized(lock) {
            val current = _uiState.value
            val updated = transform(current)
            if (updated == current) return
            _uiState.value = updated
            persist(updated)
        }
    }

    private fun loadFromDisk(profileId: Int) {
        synchronized(lock) {
            currentProfileId = profileId
            hasLoaded = true
            val raw = RatingsStorage.loadPayload(profileId)?.trim().orEmpty()
            if (raw.isEmpty()) {
                _uiState.value = EpisodeRatingsUiState()
                return
            }
            val payload = runCatching { json.decodeFromString<RatingsPayload>(raw) }
                .onFailure { logger.w(it) { "Discarding unreadable ratings payload" } }
                .getOrNull()
                ?: RatingsPayload()
            _uiState.value = EpisodeRatingsUiState(
                ratings = payload.ratings.associateBy { it.key },
                pending = payload.pending,
            )
        }
    }

    private fun persist(state: EpisodeRatingsUiState) {
        val payload = RatingsPayload(
            ratings = state.ratings.values.sortedBy { it.ratedAtEpochMs },
            pending = state.pending,
        )
        runCatching {
            RatingsStorage.savePayload(currentProfileId, json.encodeToString(payload))
        }.onFailure { logger.w(it) { "Failed to persist ratings" } }
    }
}
