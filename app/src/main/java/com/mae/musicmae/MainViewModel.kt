package com.mae.musicmae

import android.app.Application
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

sealed class UiState {
    object Idle : UiState()
    data class Processing(val message: String) : UiState()
    data class Ready(val stems: List<StemResult>, val title: String) : UiState()
    data class Err(val message: String) : UiState()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val state = MutableLiveData<UiState>(UiState.Idle)
    val stemPlayer = StemPlayer(app.applicationContext)
    val prefs = PrefsManager(app.applicationContext)

    private var processJob: Job? = null

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    fun processUri(uri: Uri, displayName: String) {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) { state.value = UiState.Err("Configura tu token en ⚙"); return }
        processJob = viewModelScope.launch {
            try {
                state.value = UiState.Processing("Copiando archivo...")
                val file = copyUriToCache(uri, displayName)

                state.value = UiState.Processing("Subiendo a Replicate...")
                val repo = ReplicateRepository(apiKey)
                val audioUrl = repo.uploadAudioFile(file)

                state.value = UiState.Processing("Separando pistas...")
                val stems = repo.runDemucs(audioUrl) { status ->
                    state.postValue(UiState.Processing("Replicate: $status..."))
                }
                downloadAndLoad(stems, repo, displayName)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                state.postValue(UiState.Err(e.message ?: "Error desconocido"))
            }
        }
    }

    fun processYouTube(url: String) {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) { state.value = UiState.Err("Configura tu token en ⚙"); return }
        processJob = viewModelScope.launch {
            try {
                state.value = UiState.Processing("Obteniendo stream de YouTube...")
                val streamUrl = try {
                    YouTubeExtractor.getAudioDownloadUrl(url)
                } catch (e: Exception) {
                    throw Exception("YouTube falló:\n${e.message}\n\nAlternativa: descarga el audio como MP3 y cárgalo con 'Abrir Archivo'.")
                }

                state.value = UiState.Processing("Descargando audio...")
                val audioFile = downloadToCache(streamUrl, "yt_audio.mp3")

                state.value = UiState.Processing("Subiendo a Replicate...")
                val repo = ReplicateRepository(apiKey)
                val uploadedUrl = repo.uploadAudioFile(audioFile)

                state.value = UiState.Processing("Separando pistas...")
                val stems = repo.runDemucs(uploadedUrl) { status ->
                    state.postValue(UiState.Processing("Replicate: $status..."))
                }

                val videoId = url.substringAfter("v=").take(11).ifBlank { "youtube" }
                downloadAndLoad(stems, repo, videoId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                state.postValue(UiState.Err(e.message ?: "Error desconocido"))
            }
        }
    }

    fun cancel() {
        processJob?.cancel()
        state.value = UiState.Idle
    }

    private suspend fun downloadAndLoad(stems: List<StemResult>, repo: ReplicateRepository, title: String) {
        val ctx = getApplication<Application>().applicationContext
        val files = stems.mapIndexed { i, stem ->
            state.postValue(UiState.Processing("Descargando ${stem.name}..."))
            val dest = File(ctx.cacheDir, "stem_$i.mp3")
            repo.downloadStem(stem.url, dest)
            dest
        }
        withContext(Dispatchers.Main) {
            stemPlayer.load(files)
            state.value = UiState.Ready(stems, title)
        }
    }

    private suspend fun copyUriToCache(uri: Uri, name: String): File = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>().applicationContext
        val ext = MimeTypeMap.getFileExtensionFromUrl(name).ifBlank {
            ctx.contentResolver.getType(uri)?.let {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
            } ?: "mp3"
        }
        val dest = File(ctx.cacheDir, "upload.$ext")
        ctx.contentResolver.openInputStream(uri)!!.use { it.copyTo(dest.outputStream()) }
        dest
    }

    private suspend fun downloadToCache(url: String, filename: String): File = withContext(Dispatchers.IO) {
        val dest = File(getApplication<Application>().cacheDir, filename)
        val request = Request.Builder().url(url).build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Error descargando audio: ${response.code}")
            response.body!!.byteStream().copyTo(dest.outputStream())
        }
        dest
    }

    override fun onCleared() {
        super.onCleared()
        stemPlayer.release()
    }
}
