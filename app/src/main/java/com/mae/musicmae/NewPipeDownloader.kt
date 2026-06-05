package com.mae.musicmae

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object YouTubeExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://piped-api.garudalinux.org",
        "https://api.piped.projectsegfau.lt"
    )

    suspend fun getAudioStreamUrl(youtubeUrl: String): String = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(youtubeUrl)
            ?: throw Exception("URL inválida. Usa: youtube.com/watch?v=... o youtu.be/...")

        var lastError: Exception = Exception("No se pudo contactar el servidor")
        for (instance in pipedInstances) {
            try {
                return@withContext fetchFromInstance(instance, videoId)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError
    }

    private fun fetchFromInstance(baseUrl: String, videoId: String): String {
        val request = Request.Builder()
            .url("$baseUrl/streams/$videoId")
            .header("User-Agent", "MusicMAE/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} desde $baseUrl")
            val json = JSONObject(response.body!!.string())
            val streams = json.getJSONArray("audioStreams")

            var bestUrl = ""
            var bestBitrate = -1

            // Prefer mp4/m4a for Demucs compatibility
            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                val mime = s.optString("mimeType", "")
                val bitrate = s.optInt("bitrate", 0)
                if (("mp4" in mime || "m4a" in mime) && bitrate > bestBitrate) {
                    bestBitrate = bitrate
                    bestUrl = s.getString("url")
                }
            }

            // Fallback: any format
            if (bestUrl.isBlank()) {
                for (i in 0 until streams.length()) {
                    val s = streams.getJSONObject(i)
                    val bitrate = s.optInt("bitrate", 0)
                    if (bitrate > bestBitrate) {
                        bestBitrate = bitrate
                        bestUrl = s.getString("url")
                    }
                }
            }

            if (bestUrl.isBlank()) throw Exception("No hay streams disponibles en $baseUrl")
            bestUrl
        }
    }

    private fun extractVideoId(url: String): String? =
        Regex("(?:v=|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
}
