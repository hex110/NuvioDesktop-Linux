package com.nuvio.app.features.ratings

import co.touchlab.kermit.Logger
import com.nuvio.app.features.yamtrack.YamtrackScrobbleRepository
import com.nuvio.app.features.yamtrack.YamtrackScrobbleRepository.RatingPushResult
import com.nuvio.app.features.yamtrack.YamtrackSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Pushes locally stored episode ratings to Floppy/Yamtrack.
 *
 * Runs after each rating and once at startup, so a rating given while the instance was
 * unreachable goes out later. Ratings the instance cannot resolve are marked synced anyway:
 * retrying a title TMDB does not know would only repeat the same 404 on every launch.
 */
internal object EpisodeRatingYamtrackSync {
    private const val PROVIDER_ID = "yamtrack"

    private val log = Logger.withTag("EpisodeRatingSync")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    fun requestSync() {
        scope.launch {
            mutex.withLock {
                if (!YamtrackSettingsRepository.snapshot().isActive) return@withLock
                for (rating in EpisodeRatingsRepository.unsyncedRatings(PROVIDER_ID)) {
                    val result = YamtrackScrobbleRepository.rateEpisode(
                        metaType = rating.metaType,
                        metaId = rating.metaId,
                        showName = rating.showName,
                        season = rating.season,
                        episode = rating.episode,
                        rating = rating.rating,
                    )
                    when (result) {
                        RatingPushResult.Accepted, RatingPushResult.Unresolvable ->
                            EpisodeRatingsRepository.markSynced(PROVIDER_ID, listOf(rating))
                        // The instance is down or refusing us; the rest would fail the same way.
                        RatingPushResult.Rejected, RatingPushResult.Unreachable -> {
                            log.w { "Stopped pushing episode ratings: $result" }
                            return@withLock
                        }
                    }
                }
            }
        }
    }
}
