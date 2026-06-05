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

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.tokhmi.xyz",
        "https://piped-api.garudalinux.org",
        "https://api.piped.projectsegfau.lt"
    )

    private val invidiousInstances = listOf(
        "https://yt.artemislena.eu",
        "https://invidious.tiekoetter.com",
        "https://inv.riverside.rocks",
        "https://invidious.slipfox.xyz"
    )

    suspend fun getAudioDownloadUrl(youtubeUrl: String): String = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(youtubeUrl)
            ?: throw Exception("URL inválida. Usa: youtube.com/watch?v=... o youtu.be/...")

        val errors = mutableListOf<String>()

        // 1. IOS client — returns direct audio URLs, maintained by yt-dlp
        try {
            return@withContext youtubePlayerApi(
                videoId,
                clientName = "IOS",
                clientVersion = "19.29.1",
                clientId = 5,
                userAgent = "com.google.ios.youtube/19.29.1 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15",
                apiKey = "AIzaSyB-63vPrdThhKuerbB2N_l7Kwwcxj6yUAc",
                extra = mapOf("deviceModel" to "iPhone16,2")
            )
        } catch (e: Exception) {
            errors += "ios: ${e.message}"
        }

        // 2. ANDROID_MUSIC client — alternative direct-URL client
        try {
            return@withContext youtubePlayerApi(
                videoId,
                clientName = "ANDROID_MUSIC",
                clientVersion = "6.42.52",
                clientId = 21,
                userAgent = "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 11) gzip",
                apiKey = null
            )
        } catch (e: Exception) {
            errors += "android_music: ${e.message}"
        }

        // 3. Piped instances
        for (instance in pipedInstances) {
            try {
                return@withContext fromPiped(instance, videoId)
            } catch (e: Exception) {
                errors += "${instance.removePrefix("https://pipedapi.").removePrefix("https://")}: ${e.message}"
            }
        }

        // 4. Invidious instances
        for (instance in invidiousInstances) {
            try {
                return@withContext fromInvidious(instance, videoId)
            } catch (e: Exception) {
                errors += "${instance.removePrefix("https://")}: ${e.message}"
            }
        }

        throw Exception(errors.joinToString("\n"))
    }

    private fun youtubePlayerApi(
        videoId: String,
        clientName: String,
        clientVersion: String,
        clientId: Int,
        userAgent: String,
        apiKey: String?,
        extra: Map<String, String> = emptyMap()
    ): String {
        val clientObj = JSONObject().apply {
            put("clientName", clientName)
            put("clientVersion", clientVersion)
            put("hl", "en")
            put("gl", "US")
            put("utcOffsetMinutes", 0)
            put("userAgent", userAgent)
            for ((k, v) in extra) put(k, v)
        }
        val body = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().put("client", clientObj))
        }.toString().toRequestBody("application/json".toMediaType())

        val url = buildString {
            append("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            if (apiKey != null) append("&key=$apiKey")
        }

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .header("X-YouTube-Client-Name", clientId.toString())
            .header("X-YouTube-Client-Version", clientVersion)
            .post(body)
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: throw Exception("sin respuesta"))

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status")
            if (status != "OK") {
                throw Exception(playability?.optString("reason") ?: "status=$status")
            }

            extractBestAudioUrl(json)
        }
    }

    private fun extractBestAudioUrl(json: JSONObject): String {
        val adaptiveFormats = json.optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats")
            ?: throw Exception("sin adaptiveFormats")

        var bestUrl = ""; var bestBitrate = -1

        // Prefer audio/mp4 (m4a/aac) with direct url
        for (i in 0 until adaptiveFormats.length()) {
            val f = adaptiveFormats.getJSONObject(i)
            val url = f.optString("url", "")
            if (url.isNotBlank() && "audio/mp4" in f.optString("mimeType", "")) {
                val br = f.optInt("bitrate", 0)
                if (br > bestBitrate) { bestBitrate = br; bestUrl = url }
            }
        }
        // Fallback: any audio with direct url
        if (bestUrl.isBlank()) {
            for (i in 0 until adaptiveFormats.length()) {
                val f = adaptiveFormats.getJSONObject(i)
                val url = f.optString("url", "")
                if (url.isNotBlank() && "audio" in f.optString("mimeType", "")) {
                    val br = f.optInt("bitrate", 0)
                    if (br > bestBitrate) { bestBitrate = br; bestUrl = url }
                }
            }
        }
        if (bestUrl.isBlank()) throw Exception("0 URLs directas (todos cifrados)")
        return bestUrl
    }

    private fun fromPiped(baseUrl: String, videoId: String): String {
        val request = Request.Builder()
            .url("$baseUrl/streams/$videoId")
            .header("User-Agent", "Mozilla/5.0 (Android 14)")
            .header("Accept", "application/json")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("sin respuesta")
            if (body.startsWith("<")) throw Exception("HTML en lugar de JSON")
            val json = JSONObject(body)
            val streams = json.optJSONArray("audioStreams")
                ?: throw Exception("sin audioStreams")
            if (streams.length() == 0) throw Exception("audioStreams vacío")

            var bestUrl = ""; var bestBitrate = -1
            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                val bitrate = s.optInt("bitrate", 0)
                val url = s.optString("url", "")
                if (url.isNotBlank() && bitrate > bestBitrate) { bestBitrate = bitrate; bestUrl = url }
            }
            if (bestUrl.isBlank()) throw Exception("sin URL en audioStreams")
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
            val body = response.body?.string() ?: throw Exception("sin respuesta")
            if (body.startsWith("<")) throw Exception("HTML en lugar de JSON")
            val json = JSONObject(body)
            val formats = json.optJSONArray("adaptiveFormats")
                ?: throw Exception("sin adaptiveFormats")

            var bestUrl = ""; var bestBitrate = -1
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                if ("audio/mp4" in f.optString("type", "")) {
                    val br = f.optInt("bitrate", 0)
                    if (br > bestBitrate) { bestBitrate = br; bestUrl = f.getString("url") }
                }
            }
            if (bestUrl.isBlank()) {
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    if ("audio" in f.optString("type", "")) {
                        val br = f.optInt("bitrate", 0)
                        if (br > bestBitrate) { bestBitrate = br; bestUrl = f.getString("url") }
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
