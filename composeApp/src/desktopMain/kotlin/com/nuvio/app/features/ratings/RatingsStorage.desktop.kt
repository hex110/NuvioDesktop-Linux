package com.nuvio.app.features.ratings

import com.nuvio.app.core.storage.DesktopStorage

actual object RatingsStorage {
    private val store = DesktopStorage.store("nuvio_ratings")

    actual fun loadPayload(profileId: Int): String? =
        store.getString("ratings_$profileId")

    actual fun savePayload(profileId: Int, payload: String) {
        store.putString("ratings_$profileId", payload)
    }
}
