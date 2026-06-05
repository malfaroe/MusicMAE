package com.mae.musicmae

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object YouTubeExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Invidious instances as last-resort fallback
    private val invidiousInstances = listOf(
        "https://invidious.io.lol",
        "https://invidious.fdn.fr",
        "https://invidious.lunar.icu",
        "https://vid.puffyan.us",
        "https://invidious.perennialte.ch"
    )

    suspend fun getAudioDownloadUrl(youtubeUrl: String): String = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(youtubeUrl)
            ?: throw Exception("URL inválida. Usa: youtube.com/watch?v=... o youtu.be/...")

        val errors = mutableListOf<String>()

        // Primary: YouTube internal API (ANDROID_TESTSUITE client — no cipher, no 3rd party)
        try {
            return@withContext fromYouTubeInternal(videoId)
        } catch (e: Exception) {
            errors += "youtube-direct: ${e.message}"
        }

        // Fallback: Invidious public instances
        for (instance in invidiousInstances) {
            try {
                return@withContext fromInvidious(instance, videoId)
            } catch (e: Exception) {
                errors += "${instance.removePrefix("https://")}: ${e.message}"
            }
        }

        throw Exception(errors.joinToString("\n"))
    }

    // Uses YouTube's internal player API with ANDROID_TESTSUITE client.
    // This client returns direct (non-ciphered) stream URLs without any JS execution.
    private fun fromYouTubeInternal(videoId: String): String {
        val body = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID_TESTSUITE")
                    put("clientVersion", "1.9")
                    put("androidSdkVersion", 30)
                    put("hl", "en")
                    put("gl", "US")
                    put("utcOffsetMinutes", 0)
                })
            })
        }

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", "com.google.android.youtube/1.9 (Linux; U; Android 10) gzip")
            .header("X-YouTube-Client-Name", "30")
            .header("X-YouTube-Client-Version", "1.9")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: throw Exception("sin respuesta"))

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status")
            if (status != "OK") {
                throw Exception(playability?.optString("reason") ?: "status=$status")
            }

            val adaptiveFormats = json.optJSONObject("streamingData")
                ?.optJSONArray("adaptiveFormats")
                ?: throw Exception("sin adaptiveFormats")

            var bestUrl = ""
            var bestBitrate = -1

            // Prefer audio/mp4 (m4a/aac) with a direct url field
            for (i in 0 until adaptiveFormats.length()) {
                val f = adaptiveFormats.getJSONObject(i)
                val url = f.optString("url", "")
                if (url.isNotBlank() && "audio/mp4" in f.optString("mimeType", "")) {
                    val bitrate = f.optInt("bitrate", 0)
                    if (bitrate > bestBitrate) { bestBitrate = bitrate; bestUrl = url }
                }
            }
            // Fallback: any audio format with a direct url
            if (bestUrl.isBlank()) {
                for (i in 0 until adaptiveFormats.length()) {
                    val f = adaptiveFormats.getJSONObject(i)
                    val url = f.optString("url", "")
                    if (url.isNotBlank() && "audio" in f.optString("mimeType", "")) {
                        val bitrate = f.optInt("bitrate", 0)
                        if (bitrate > bestBitrate) { bestBitrate = bitrate; bestUrl = url }
                    }
                }
            }
            if (bestUrl.isBlank()) throw Exception("0 URLs directas (todos los formatos están cifrados)")
            bestUrl
        }
    }

    private fun fromInvidious(baseUrl: String, videoId: String): String {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/videos/$videoId?fields=adaptiveFormats")
            .header("User-Agent", "Mozilla/5.0 (Android 14)")
            .header("Accept", "application/json")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: throw Exception("sin respuesta"))
            val formats = json.optJSONArray("adaptiveFormats")
                ?: throw Exception("campo adaptiveFormats ausente")

            var bestUrl = ""
            var bestBitrate = -1

            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                if ("audio/mp4" in f.optString("type", "")) {
                    val bitrate = f.optInt("bitrate", 0)
                    if (bitrate > bestBitrate) { bestBitrate = bitrate; bestUrl = f.getString("url") }
                }
            }
            if (bestUrl.isBlank()) {
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    if ("audio" in f.optString("type", "")) {
                        val bitrate = f.optInt("bitrate", 0)
                        if (bitrate > bestBitrate) { bestBitrate = bitrate; bestUrl = f.getString("url") }
                    }
                }
            }
            if (bestUrl.isBlank()) throw Exception("0 formatos de audio")
            bestUrl
        }
    }

    fun extractVideoId(url: String): String? =
        Regex("(?:v=|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
}
