package com.nuvio.app.features.tracking

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.components.TrackingRatingDialog
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.ratings.finaleRedditSearchTerms
import com.nuvio.app.features.ratings.movieRedditSearchTerms
import com.nuvio.app.features.ratings.redditSearchUrlFor
import com.nuvio.app.features.ratings.seasonFinaleRedditSearchTerms
import com.nuvio.app.features.ratings.seriesFinaleRedditSearchTerms
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.details_rating_failed
import nuvio.composeapp.generated.resources.details_rating_kicker
import nuvio.composeapp.generated.resources.details_rating_saved
import nuvio.composeapp.generated.resources.details_rating_title
import nuvio.composeapp.generated.resources.rating_prompt_movie_body
import nuvio.composeapp.generated.resources.rating_prompt_not_now
import nuvio.composeapp.generated.resources.rating_prompt_season_finale_body
import nuvio.composeapp.generated.resources.rating_prompt_series_finale_body
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The rating provider this prompt would write to, or null if there is not one.
 *
 * Resolved at display time rather than when the prompt was queued: the user may have
 * disconnected the service or switched Library source in between.
 */
@Composable
private fun resolveRatingPromptProvider(): TrackingProviderId? {
    val connectedProviderIds by TrackingProviderRegistry.connectedProviderIds
        .collectAsStateWithLifecycle()
    val librarySource by remember {
        LibrarySourceRepository.ensureLoaded()
        LibrarySourceRepository.uiState
    }.collectAsStateWithLifecycle()
    return trackingRatingProviderFor(librarySource)?.takeIf { it in connectedProviderIds }
}

/**
 * ARI: whether [RatingPromptHost] is actually putting a dialog on screen right now.
 *
 * Shares [resolveRatingPromptProvider] with the host so the two cannot drift. Exists so the
 * local per-episode prompt can queue behind this one instead of stacking a second dialog on
 * top of it after a season finale. Deliberately not just `pendingRequest != null`: a request
 * queued while a provider was connected stays pending after it is disconnected, and that must
 * not suppress the episode prompt forever.
 */
@Composable
internal fun isProviderRatingPromptVisible(isSuppressed: Boolean): Boolean {
    val request by RatingPromptRepository.pendingRequest.collectAsStateWithLifecycle()
    val providerId = resolveRatingPromptProvider()
    if (isSuppressed || request == null || providerId == null) return false
    return TrackingProviderRegistry.ratingWriter(providerId) != null
}

/** ARI: the Reddit thread to offer for a finished title, worded to match what was finished. */
private fun redditSearchUrlForRequest(request: RatingPromptRequest): String {
    val terms = when (request.reason) {
        RatingPromptReason.MOVIE -> movieRedditSearchTerms(request.title)
        RatingPromptReason.SERIES_FINALE -> seriesFinaleRedditSearchTerms(request.title)
        RatingPromptReason.SEASON_FINALE -> request.seasonNumber
            ?.let { seasonFinaleRedditSearchTerms(request.title, it) }
            ?: finaleRedditSearchTerms(request.title)
    }
    return redditSearchUrlFor(terms)
}

/**
 * Shows the queued "rate what you just finished" prompt.
 *
 * Hosted above the navigation graph rather than inside a screen, because the prompt outlives the
 * player it was queued from: the user finishes something, leaves the player, and gets asked
 * wherever they land. [isSuppressed] lets the caller hold it back while playback is still on
 * screen.
 */
@Composable
fun RatingPromptHost(isSuppressed: Boolean) {
    val request by RatingPromptRepository.pendingRequest.collectAsStateWithLifecycle()
    val resolvedProviderId = resolveRatingPromptProvider()
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var isPending by remember(request) { mutableStateOf(false) }
    var errorMessage by remember(request) { mutableStateOf<String?>(null) }

    val pending = request ?: return
    if (isSuppressed) return

    val providerId = resolvedProviderId ?: return
    val writer = TrackingProviderRegistry.ratingWriter(providerId) ?: return
    val providerName = trackingRatingProviderDisplayName(providerId)

    val body = stringResource(
        when (pending.reason) {
            RatingPromptReason.MOVIE -> Res.string.rating_prompt_movie_body
            RatingPromptReason.SEASON_FINALE -> Res.string.rating_prompt_season_finale_body
            RatingPromptReason.SERIES_FINALE -> Res.string.rating_prompt_series_finale_body
        },
        providerName,
    )

    TrackingRatingDialog(
        visible = true,
        kicker = stringResource(Res.string.details_rating_kicker),
        headline = stringResource(Res.string.details_rating_title, pending.title),
        body = body,
        isPending = isPending,
        errorMessage = errorMessage,
        dismissLabel = stringResource(Res.string.rating_prompt_not_now),
        onFindDiscussion = {
            runCatching { uriHandler.openUri(redditSearchUrlForRequest(pending)) }
        },
        onRate = { score ->
            scope.launch {
                isPending = true
                errorMessage = null
                runCatching {
                    writer.setRating(
                        profileId = ProfileRepository.activeProfileId,
                        media = pending.media,
                        rating = score,
                    )
                }.onSuccess { result ->
                    if (result.isComplete) {
                        RatingPromptRepository.dismiss()
                        NuvioToastController.show(
                            getString(Res.string.details_rating_saved, providerName),
                        )
                    } else {
                        // The provider accepted the request but could not address this title, so
                        // nothing was recorded. Saying "saved" here would be a lie.
                        errorMessage = getString(Res.string.details_rating_failed)
                    }
                }.onFailure { error ->
                    errorMessage = error.message ?: getString(Res.string.details_rating_failed)
                }
                isPending = false
            }
        },
        onDismiss = {
            if (!isPending) RatingPromptRepository.dismiss()
        },
    )
}
