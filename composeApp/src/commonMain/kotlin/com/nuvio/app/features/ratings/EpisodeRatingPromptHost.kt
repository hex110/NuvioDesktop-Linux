package com.nuvio.app.features.ratings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.details.components.TrackingRatingDialog
import com.nuvio.app.features.tracking.RatingPromptRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.details_rating_kicker
import nuvio.composeapp.generated.resources.details_rating_title
import nuvio.composeapp.generated.resources.rating_prompt_episode_body
import nuvio.composeapp.generated.resources.rating_prompt_not_now
import org.jetbrains.compose.resources.stringResource

/**
 * Shows the rating prompt for whichever episode is at the head of the queue.
 *
 * Hosted above the navigation graph rather than inside the player so it still
 * appears after a finale, or when the player was dismissed before the
 * next-episode card had a chance to show.
 *
 * Episodes are queued the moment playback completes, which during a binge is
 * mid auto-play into the next episode, so [isSuppressed] holds the dialog back
 * while the player is on screen — and while the provider-backed finale prompt
 * is up, so a season finale does not stack two dialogs. The queue is durable,
 * so nothing is lost by waiting.
 *
 * Rendered through the same [TrackingRatingDialog] as the provider prompt: one
 * rating dialog, one set of localized strings, one score rail.
 */
@Composable
fun EpisodeRatingPromptHost(isSuppressed: Boolean) {
    LaunchedEffect(Unit) { EpisodeRatingsRepository.ensureLoaded() }

    val state by EpisodeRatingsRepository.uiState.collectAsStateWithLifecycle()
    // Shares the single "ask me to rate" preference with the provider prompt. Turning that
    // off and still being asked after every episode would plainly be the wrong reading.
    val isEnabled by RatingPromptRepository.isEnabled.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    val prompt = state.nextPrompt
    if (isSuppressed || !isEnabled || prompt == null) return

    TrackingRatingDialog(
        visible = true,
        kicker = stringResource(Res.string.details_rating_kicker),
        headline = stringResource(
            Res.string.details_rating_title,
            "${prompt.showName} ${episodeLabel(prompt.season, prompt.episode)}",
        ),
        body = stringResource(Res.string.rating_prompt_episode_body, prompt.season, prompt.episode),
        isPending = false,
        errorMessage = null,
        dismissLabel = stringResource(Res.string.rating_prompt_not_now),
        onRate = { score -> EpisodeRatingsRepository.rate(prompt, score) },
        onDismiss = { EpisodeRatingsRepository.skip(prompt) },
        onFindDiscussion = {
            runCatching {
                uriHandler.openUri(
                    redditSearchUrl(prompt.showName, prompt.season, prompt.episode),
                )
            }
        },
    )
}
