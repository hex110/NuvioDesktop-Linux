package com.nuvio.app.features.ratings

expect object RatingsStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
}
