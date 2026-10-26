package com.nuvio.app.features.tracking

import com.nuvio.app.features.library.LibrarySourceMode

/**
 * Which provider a rating written from this app goes to.
 *
 * Ratings follow the selected *library* source rather than the scrobble fan-out: a rating is a
 * personal opinion recorded once, not an event every connected service should hear about. The
 * rating affordances stay hidden for providers without a writer instead of offering an action that
 * cannot be carried out.
 */
fun trackingRatingProviderFor(sourceMode: LibrarySourceMode): TrackingProviderId? =
    sourceMode.trackingProvider?.takeIf { provider ->
        provider == TrackingProviderId.SIMKL ||
            provider == TrackingProviderId.MDBLIST ||
            provider == TrackingProviderId.YAMTRACK
    }

/** The provider name shown in rating copy. Not localized — these are brand names. */
fun trackingRatingProviderDisplayName(providerId: TrackingProviderId?): String =
    when (providerId) {
        TrackingProviderId.SIMKL -> "SIMKL"
        TrackingProviderId.MDBLIST -> "MDBList"
        TrackingProviderId.YAMTRACK -> "Floppy (Yamtrack Fork)"
        else -> ""
    }

/**
 * The connected, rating-capable provider for the current library source, or null if there is none.
 *
 * ARI: when the library source has none (it is Nuvio's own, say), Floppy is used if it is connected
 * and enabled, so a rating still lands somewhere instead of the prompt silently never appearing.
 */
fun activeTrackingRatingProvider(): TrackingProviderId? {
    val providerId = trackingRatingProviderFor(LibrarySourceRepository.selectedSource())
        ?: TrackingProviderId.YAMTRACK
    if (TrackingProviderRegistry.ratingWriter(providerId) == null) return null
    if (!TrackingProviderRegistry.isAuthenticated(providerId)) return null
    return providerId
}
