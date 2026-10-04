package com.nuvio.app.features.ratings

private const val GoogleSearchPrefix = "https://www.google.com/search?q="

private val UnreservedQueryChars: Set<Char> =
    (('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf('-', '_', '.', '~')).toSet()

/**
 * Percent-encodes [value] for use in a query string, mapping spaces to `+`.
 *
 * Kotlin common has no `URLEncoder`, and the search terms routinely contain
 * apostrophes, colons and accented characters from show titles.
 */
internal fun encodeQueryComponent(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val char = byte.toInt().toChar()
        when {
            char in UnreservedQueryChars -> append(char)
            char == ' ' -> append('+')
            else -> {
                val unsigned = byte.toInt() and 0xFF
                append('%')
                append(unsigned.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}

/** The search terms for a single episode, e.g. `Ted Lasso 4x5 reddit`. */
fun redditSearchTerms(showName: String, season: Int, episode: Int): String =
    "${showName.trim()} ${episodeLabel(season, episode)} reddit"

/** The search terms for a season's finale, e.g. `Ted Lasso season 4 finale reddit`. */
fun seasonFinaleRedditSearchTerms(showName: String, season: Int): String =
    "${showName.trim()} season $season finale reddit"

/** The search terms for a show's last ever episode, e.g. `Ted Lasso series finale reddit`. */
fun seriesFinaleRedditSearchTerms(showName: String): String =
    "${showName.trim()} series finale reddit"

/**
 * Fallback terms for a finale whose season number never resolved.
 *
 * Deliberately not the series-finale wording: a season finale with missing
 * metadata is not evidence the show has ended.
 */
fun finaleRedditSearchTerms(showName: String): String =
    "${showName.trim()} finale reddit"

/** The search terms for a film, e.g. `Sinners reddit`. */
fun movieRedditSearchTerms(title: String): String = "${title.trim()} reddit"

/**
 * A Google search for [terms].
 *
 * Reddit's own `.json` search endpoints reject unauthenticated requests, so
 * resolving the top thread directly would require registering an OAuth app.
 * Handing the user a search page keeps this dependency-free.
 */
fun redditSearchUrlFor(terms: String): String =
    GoogleSearchPrefix + encodeQueryComponent(terms)

/** A Google search for the episode's Reddit discussion. */
fun redditSearchUrl(showName: String, season: Int, episode: Int): String =
    redditSearchUrlFor(redditSearchTerms(showName, season, episode))
