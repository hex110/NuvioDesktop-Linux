package com.nuvio.app.features.ratings

import kotlinx.serialization.Serializable

/**
 * A rating the user gave to a single episode.
 *
 * The local store is the source of truth. [syncedProviderIds] records which
 * tracking providers have already accepted this rating so a later sync pass can
 * push only what is outstanding. Simkl cannot store episode ratings at all, so
 * it will never appear here for episode rows.
 */
@Serializable
data class EpisodeRating(
    val metaId: String,
    val metaType: String,
    val showName: String,
    val season: Int,
    val episode: Int,
    val rating: Int,
    val ratedAtEpochMs: Long,
    val syncedProviderIds: Set<String> = emptySet(),
)

/**
 * An episode that finished playing and has not been rated or skipped yet.
 *
 * Prompts are queued rather than shown inline so that finishing an episode with
 * no next episode (a finale), or switching episodes manually before the
 * next-episode card appears, still results in a prompt.
 */
@Serializable
data class PendingRatingPrompt(
    val metaId: String,
    val metaType: String,
    val showName: String,
    val season: Int,
    val episode: Int,
    val completedAtEpochMs: Long,
)

@Serializable
internal data class RatingsPayload(
    val ratings: List<EpisodeRating> = emptyList(),
    val pending: List<PendingRatingPrompt> = emptyList(),
)

data class EpisodeRatingsUiState(
    val ratings: Map<String, EpisodeRating> = emptyMap(),
    val pending: List<PendingRatingPrompt> = emptyList(),
) {
    val nextPrompt: PendingRatingPrompt? get() = pending.firstOrNull()
}

internal fun ratingKey(metaId: String, season: Int, episode: Int): String =
    "$metaId:$season:$episode"

internal val EpisodeRating.key: String get() = ratingKey(metaId, season, episode)

internal val PendingRatingPrompt.key: String get() = ratingKey(metaId, season, episode)

/** Human label for an episode, e.g. `4x5`. */
fun episodeLabel(season: Int, episode: Int): String = "${season}x$episode"

/**
 * Queues [prompt] unless the episode is already rated or already waiting.
 *
 * Oldest prompts are dropped past [maxPending] so a long binge cannot grow the
 * queue without bound.
 */
internal fun EpisodeRatingsUiState.withPrompt(
    prompt: PendingRatingPrompt,
    maxPending: Int,
): EpisodeRatingsUiState {
    if (ratings.containsKey(prompt.key)) return this
    if (pending.any { it.key == prompt.key }) return this
    return copy(pending = (pending + prompt).takeLast(maxPending))
}

/** Records [rating] for [prompt] and drops it from the queue. */
internal fun EpisodeRatingsUiState.withRating(
    prompt: PendingRatingPrompt,
    rating: Int,
    ratedAtEpochMs: Long,
): EpisodeRatingsUiState {
    val entry = EpisodeRating(
        metaId = prompt.metaId,
        metaType = prompt.metaType,
        showName = prompt.showName,
        season = prompt.season,
        episode = prompt.episode,
        rating = rating,
        ratedAtEpochMs = ratedAtEpochMs,
        // A changed score is new information for every provider, so nothing counts as synced.
        syncedProviderIds = ratings[prompt.key]?.takeIf { it.rating == rating }?.syncedProviderIds.orEmpty(),
    )
    return copy(
        ratings = ratings + (entry.key to entry),
        pending = pending.filterNot { it.key == prompt.key },
    )
}

/** Drops [prompt] from the queue without recording a rating. */
internal fun EpisodeRatingsUiState.withoutPrompt(
    prompt: PendingRatingPrompt,
): EpisodeRatingsUiState = copy(pending = pending.filterNot { it.key == prompt.key })

/** Marks [synced] as accepted by [providerId]. Unknown rows are ignored. */
internal fun EpisodeRatingsUiState.withSyncedProvider(
    providerId: String,
    synced: Collection<EpisodeRating>,
): EpisodeRatingsUiState {
    if (synced.isEmpty()) return this
    val updated = ratings.toMutableMap()
    for (rating in synced) {
        val existing = updated[rating.key] ?: continue
        updated[rating.key] =
            existing.copy(syncedProviderIds = existing.syncedProviderIds + providerId)
    }
    return copy(ratings = updated)
}
