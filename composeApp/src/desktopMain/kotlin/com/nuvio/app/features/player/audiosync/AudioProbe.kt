package com.nuvio.app.features.player.audiosync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit

/** What ffprobe says about a stream, as far as audio sync needs. */
internal data class AudioProbeResult(
    val durationMs: Long,
    /** Language tag of each audio track, in stream order (`0:a:N`), lower case; null when untagged. */
    val audioLanguages: List<String?>,
)

/** The ffmpeg tools are system binaries on Linux; AutoSync's speech recognition is off without them. */
internal object FfmpegTools {
    val available: Boolean by lazy {
        listOf("ffmpeg", "ffprobe").all { tool ->
            runCatching {
                ProcessBuilder(tool, "-version").redirectErrorStream(true).start().also {
                    it.inputStream.readAllBytes()
                    it.waitFor(5, TimeUnit.SECONDS)
                }.exitValue() == 0
            }.getOrDefault(false)
        }
    }

    /** `-headers` value for ffmpeg's http protocol. */
    fun headerArgs(headers: Map<String, String>): List<String> {
        val usable = headers.filterKeys { it.isNotBlank() }
        if (usable.isEmpty()) return emptyList()
        return listOf("-headers", usable.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
    }

    fun probe(url: String, headers: Map<String, String>): AudioProbeResult? = runCatching {
        val command = listOf("ffprobe", "-v", "error", "-of", "json", "-select_streams", "a",
            "-show_entries", "format=duration:stream_tags=language") + headerArgs(headers) + url
        val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val text = process.inputStream.readBytes().decodeToString()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        val root = Json.parseToJsonElement(text).jsonObject
        val seconds = root["format"]?.jsonObject?.get("duration")?.jsonPrimitive?.doubleOrNull ?: return@runCatching null
        val languages = root["streams"]?.jsonArray.orEmpty().map { stream ->
            ((stream as? JsonObject)?.get("tags") as? JsonObject)?.get("language")?.jsonPrimitive?.contentOrNull
                ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        }
        AudioProbeResult((seconds * 1_000).toLong(), languages)
    }.getOrNull()
}
