package com.nuvio.app.features.ratings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.details.components.TrackingRatingDialog
import com.nuvio.app.features.tracking.RatingPromptRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_done
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
 * An episode is queued the moment it completes. While the player is still on that
 * very episode the prompt is held back; as soon as the player moves to another
 * episode, or closes, the prompt appears. [isSuppressed] additionally holds it
 * back while the provider-backed finale prompt is up, so a season finale does
 * not stack two dialogs. The queue is durable, so nothing is lost by waiting.
 *
 * Once shown, the dialog stays open after a score is picked, so the score can be
 * changed and the discussion link opened; the Done button closes it.
 */
@Composable
fun EpisodeRatingPromptHost(isPlayerOpen: Boolean, isSuppressed: Boolean) {
    LaunchedEffect(Unit) { EpisodeRatingsRepository.ensureLoaded() }

    val state by EpisodeRatingsRepository.uiState.collectAsStateWithLifecycle()
    val playingKey by ActiveEpisodePlayback.current.collectAsStateWithLifecycle()
    // Shares the single "ask me to rate" preference with the provider prompt. Turning that
    // off and still being asked after every episode would plainly be the wrong reading.
    val isEnabled by RatingPromptRepository.isEnabled.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    // The prompt on screen. Held here because rating removes it from the queue, and the
    // dialog must not jump to the next episode's prompt while the score is still editable.
    var shown by remember { mutableStateOf<PendingRatingPrompt?>(null) }

    val head = state.nextPrompt
    val stillWatchingHead = isPlayerOpen && head != null && head.key == playingKey
    LaunchedEffect(head, stillWatchingHead, isEnabled, isSuppressed) {
        if (shown == null && head != null && isEnabled && !isSuppressed && !stillWatchingHead) {
            shown = head
        }
    }

    val prompt = shown
    if (isSuppressed || prompt == null) return

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
        doneLabel = stringResource(Res.string.action_done),
        initialScore = EpisodeRatingsRepository.ratingFor(prompt.metaId, prompt.season, prompt.episode)?.rating,
        onRate = { score -> EpisodeRatingsRepository.rate(prompt, score) },
        onDismiss = {
            EpisodeRatingsRepository.skip(prompt)
            shown = null
        },
        onFindDiscussion = {
            runCatching {
                uriHandler.openUri(
                    redditSearchUrl(prompt.showName, prompt.season, prompt.episode),
                )
            }
        },
    )
}
