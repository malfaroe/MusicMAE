package com.mae.musicmae

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class StemResult(val name: String, val url: String)

class ReplicateRepository(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val base = "https://api.replicate.com/v1"
    private val auth get() = "Bearer $apiKey"

    suspend fun uploadAudioFile(file: File): String = withContext(Dispatchers.IO) {
        val ext = file.extension.lowercase()
        val mime = when (ext) {
            "mp3"  -> "audio/mpeg"
            "wav"  -> "audio/wav"
            "flac" -> "audio/flac"
            "m4a"  -> "audio/mp4"
            else   -> "audio/mpeg"
        }
        val request = Request.Builder()
            .url("$base/files")
            .header("Authorization", auth)
            .header("Content-Type", mime)
            .header("Content-Disposition", "attachment; filename=\"${file.name}\"")
            .post(file.asRequestBody(mime.toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Upload error ${response.code}: ${response.body?.string()}")
            JSONObject(response.body!!.string()).getJSONObject("urls").getString("get")
        }
    }

    suspend fun runDemucs(audioUrl: String, onStatus: (String) -> Unit): List<StemResult> {
        val id = createPrediction(audioUrl)
        return poll(id, onStatus)
    }

    private suspend fun createPrediction(audioUrl: String): String = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject().apply {
            put("input", JSONObject().apply {
                put("audio", audioUrl)
                put("model", "htdemucs")
            })
        }
        val request = Request.Builder()
            .url("$base/models/cjwbw/demucs/predictions")
            .header("Authorization", auth)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Prediction error ${response.code}: ${response.body?.string()}")
            JSONObject(response.body!!.string()).getString("id")
        }
    }

    private suspend fun poll(id: String, onStatus: (String) -> Unit): List<StemResult> {
        while (true) {
            delay(3000)
            val json = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("$base/predictions/$id")
                    .header("Authorization", auth)
                    .get()
                    .build()
                client.newCall(request).execute().use { JSONObject(it.body!!.string()) }
            }
            when (val status = json.getString("status")) {
                "succeeded" -> {
                    val arr = json.getJSONArray("output")
                    return (0 until arr.length()).map { i ->
                        val url = arr.getString(i)
                        val filename = url.substringAfterLast("/").substringBeforeLast(".")
                        StemResult(stemLabel(filename), url)
                    }
                }
                "failed", "canceled" ->
                    throw Exception(json.optString("error", "Processing failed"))
                else -> onStatus(status)
            }
        }
    }

    suspend fun downloadStem(url: String, dest: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Download error ${response.code}")
            dest.outputStream().use { response.body!!.byteStream().copyTo(it) }
        }
    }

    private fun stemLabel(filename: String): String {
        val f = filename.lowercase()
        return when {
            "vocal" in f  -> "Voz"
            "drum"  in f  -> "Batería"
            "bass"  in f  -> "Bajo"
            "guitar" in f -> "Guitarra"
            "piano" in f  -> "Piano"
            "other" in f  -> "Otros"
            else          -> filename.replaceFirstChar { it.uppercaseChar() }
        }
    }
}
