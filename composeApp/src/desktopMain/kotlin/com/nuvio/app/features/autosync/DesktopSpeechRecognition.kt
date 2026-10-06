package com.nuvio.app.features.autosync

import com.nuvio.app.features.player.audiosync.FfmpegTools
import com.nuvio.app.features.player.audiosync.asr.DesktopAsrModel
import kotlinx.coroutines.flow.StateFlow

/** Desktop's speech recognition: sherpa-onnx on the CPU, audio through the system ffmpeg. */
internal object DesktopSpeechRecognition : SpeechRecognitionPlatform {
    override val modelState: StateFlow<SpeechModelState> get() = DesktopAsrModel.state
    override val modelSizeMb: Int get() = DesktopAsrModel.DOWNLOAD_MB
    override val toolsAvailable: Boolean get() = FfmpegTools.available

    override fun downloadModel() {
        Thread({ DesktopAsrModel.download() }, "NuvioAsrModelDownload").apply { isDaemon = true }.start()
    }

    override fun deleteModel() = DesktopAsrModel.delete()
}
