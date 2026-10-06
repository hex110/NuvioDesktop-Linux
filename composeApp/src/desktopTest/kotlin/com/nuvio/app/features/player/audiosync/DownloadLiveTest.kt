package com.nuvio.app.features.player.audiosync

import com.nuvio.app.features.player.audiosync.asr.DesktopAsrModel
import kotlin.test.Test

/** Manual: NUVIO_ASR_TEST_DOWNLOAD=1 downloads the real 74 MB model into the app data directory. */
class DownloadLiveTest {
    @Test
    fun downloadsTheModel() {
        if (System.getenv("NUVIO_ASR_TEST_DOWNLOAD") == null) return
        val ok = DesktopAsrModel.download()
        println("ASR-DOWNLOAD ok=$ok ready=${DesktopAsrModel.isReady()} state=${DesktopAsrModel.state.value}")
    }
}
