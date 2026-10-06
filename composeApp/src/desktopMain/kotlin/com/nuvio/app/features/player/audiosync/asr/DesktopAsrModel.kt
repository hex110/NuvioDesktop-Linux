package com.nuvio.app.features.player.audiosync.asr

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.autosync.SpeechModelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The English recognition model (about 74 MB), kept in the app data directory and downloaded once
 * from Settings (or when AutoSync first needs it and the user has allowed it). The files are the
 * sherpa-onnx GigaSpeech int8 Zipformer; sizes are checked so a truncated download is never used.
 */
internal object DesktopAsrModel {
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.int8.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    const val DOWNLOAD_MB = 74

    private const val VERSION = "gigaspeech-2023-12-12"
    private const val UPSTREAM = "https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-gigaspeech-2023-12-12/resolve/main/"
    private const val MIRROR = "https://github.com/DavidVamaiotu/NuvioMobile-AutoSync/releases/download/asr-models/"

    private class ModelFile(val local: String, val upstream: String, val mirror: String, val size: Long)

    private val FILES = listOf(
        ModelFile(ENCODER, "encoder-epoch-30-avg-1.int8.onnx", "gigaspeech-encoder.int8.onnx", 72_850_738L),
        ModelFile(DECODER, "decoder-epoch-30-avg-1.int8.onnx", "gigaspeech-decoder.int8.onnx", 540_688L),
        ModelFile(JOINER, "joiner-epoch-30-avg-1.int8.onnx", "gigaspeech-joiner.int8.onnx", 259_417L),
        ModelFile(TOKENS, "tokens.txt", "gigaspeech-tokens.txt", 5_020L),
    )
    private val TOTAL_BYTES = FILES.sumOf { it.size }

    private val log = Logger.withTag("AutoSyncAsr")
    private val downloading = AtomicBoolean(false)
    private val _state = MutableStateFlow(SpeechModelState(downloaded = isReady()))
    val state: StateFlow<SpeechModelState> = _state.asStateFlow()

    val directory: File
        get() = DesktopStorage.rootDir.resolve("audiosync").resolve("asr").resolve(VERSION).toFile()

    fun isReady(): Boolean = FILES.all { File(directory, it.local).length() == it.size }

    fun delete() {
        if (downloading.get()) return
        directory.deleteRecursively()
        _state.value = SpeechModelState(downloaded = false)
    }

    /** Downloads missing files. Blocking; returns true when the model is ready afterwards. */
    fun download(): Boolean {
        if (isReady()) {
            _state.value = SpeechModelState(downloaded = true)
            return true
        }
        if (!downloading.compareAndSet(false, true)) return false
        var error: String? = null
        try {
            publish(progress = 0f)
            val dir = directory.apply { mkdirs() }
            var doneBytes = 0L
            for (file in FILES) {
                val target = File(dir, file.local)
                if (target.length() != file.size) {
                    val onBytes: (Long) -> Unit = { publish(progress = (doneBytes + it).toFloat() / TOTAL_BYTES) }
                    val failure = fetch(UPSTREAM + file.upstream, target, file.size, onBytes)
                        ?.let { first -> fetch(MIRROR + file.mirror, target, file.size, onBytes)?.let { "$first; $it" } }
                    if (failure != null) {
                        error = failure
                        return false
                    }
                }
                doneBytes += file.size
            }
            return isReady().also { if (!it) error = "downloaded files are incomplete" }
        } finally {
            downloading.set(false)
            _state.value = SpeechModelState(downloaded = isReady(), error = error)
        }
    }

    private fun publish(progress: Float) {
        _state.value = SpeechModelState(downloaded = false, downloading = true, progress = progress.coerceIn(0f, 1f))
    }

    /** Returns null on success, otherwise a short reason. */
    private fun fetch(url: String, target: File, expectedSize: Long, onBytes: (Long) -> Unit): String? {
        val partial = File(target.path + ".part")
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode != 200) {
                val code = connection.responseCode
                connection.disconnect()
                log.w { "model download HTTP $code for $url" }
                return "HTTP $code"
            }
            var written = 0L
            var lastReport = 0L
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReport >= 512 * 1024) {
                            lastReport = written
                            onBytes(written)
                        }
                    }
                }
            }
            connection.disconnect()
            if (partial.length() != expectedSize) {
                val actual = partial.length()
                partial.delete()
                return "size $actual != $expectedSize"
            }
            if (!partial.renameTo(target)) return "could not save file"
            null
        } catch (failure: Exception) {
            log.w(failure) { "model download failed for $url" }
            partial.delete()
            failure.message ?: failure.javaClass.simpleName
        }
    }
}
