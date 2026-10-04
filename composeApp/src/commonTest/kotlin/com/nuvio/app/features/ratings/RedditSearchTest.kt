package com.nuvio.app.features.ratings

import kotlin.test.Test
import kotlin.test.assertEquals

class RedditSearchTest {

    @Test
    fun `builds the season by episode search terms`() {
        assertEquals("Ted Lasso 4x5 reddit", redditSearchTerms("Ted Lasso", 4, 5))
    }

    @Test
    fun `trims stray whitespace around the show name`() {
        assertEquals("Ted Lasso 4x5 reddit", redditSearchTerms("  Ted Lasso  ", 4, 5))
    }

    @Test
    fun `spaces become plus signs`() {
        assertEquals(
            "https://www.google.com/search?q=Ted+Lasso+4x5+reddit",
            redditSearchUrl("Ted Lasso", 4, 5),
        )
    }

    @Test
    fun `punctuation in titles is percent encoded`() {
        assertEquals("It%27s+Always+Sunny", encodeQueryComponent("It's Always Sunny"))
        assertEquals("Marvel%26DC", encodeQueryComponent("Marvel&DC"))
        assertEquals("S.W.A.T.", encodeQueryComponent("S.W.A.T."))
    }

    @Test
    fun `non ascii titles are encoded as utf-8`() {
        assertEquals("Pok%C3%A9mon", encodeQueryComponent("Pokémon"))
        assertEquals("%E9%AC%BC%E6%BB%85", encodeQueryComponent("鬼滅"))
    }

    @Test
    fun `finale and movie terms are worded for what was finished`() {
        assertEquals(
            "Ted Lasso season 4 finale reddit",
            seasonFinaleRedditSearchTerms("Ted Lasso", 4),
        )
        assertEquals("Ted Lasso series finale reddit", seriesFinaleRedditSearchTerms("Ted Lasso"))
        assertEquals("Ted Lasso finale reddit", finaleRedditSearchTerms("Ted Lasso"))
        assertEquals("Sinners reddit", movieRedditSearchTerms("Sinners"))
    }

    @Test
    fun `arbitrary terms are encoded into a search url`() {
        assertEquals(
            "https://www.google.com/search?q=Sinners+reddit",
            redditSearchUrlFor(movieRedditSearchTerms("Sinners")),
        )
    }
}
