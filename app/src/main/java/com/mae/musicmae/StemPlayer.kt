package com.mae.musicmae

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.math.pow

class StemPlayer(private val context: Context) {

    private val players = mutableListOf<ExoPlayer>()
    private val volumes = mutableListOf<Float>()
    private val muted = mutableListOf<Boolean>()

    var isPlaying = false
    var pitchSemitones = 0f
    var speedFactor = 1.0f

    fun load(stemFiles: List<File>) {
        release()
        stemFiles.forEach { file ->
            val player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(file.absolutePath))
                prepare()
            }
            players.add(player)
            volumes.add(1f)
            muted.add(false)
        }
        applyPlaybackParameters()
    }

    fun play() {
        if (players.isEmpty()) return
        isPlaying = true
        val pos = players[0].currentPosition
        players.forEach { it.seekTo(pos); it.play() }
    }

    fun pause() {
        isPlaying = false
        players.forEach { it.pause() }
    }

    fun stop() {
        isPlaying = false
        players.forEach { it.pause(); it.seekTo(0) }
    }

    fun seekTo(posMs: Long) = players.forEach { it.seekTo(posMs) }

    fun setMuted(index: Int, mute: Boolean) {
        if (index >= players.size) return
        muted[index] = mute
        players[index].volume = if (mute) 0f else volumes[index]
    }

    fun setVolume(index: Int, vol: Float) {
        if (index >= players.size) return
        volumes[index] = vol
        if (!muted[index]) players[index].volume = vol
    }

    fun setPitch(semitones: Float) {
        pitchSemitones = semitones
        applyPlaybackParameters()
    }

    fun setSpeed(speed: Float) {
        speedFactor = speed
        applyPlaybackParameters()
    }

    fun getDurationMs(): Long = players.firstOrNull()?.duration ?: 0L
    fun getPositionMs(): Long = players.firstOrNull()?.currentPosition ?: 0L
    fun isReady(): Boolean = players.isNotEmpty()

    private fun applyPlaybackParameters() {
        val pitch = 2f.pow(pitchSemitones / 12f)
        val params = PlaybackParameters(speedFactor, pitch)
        players.forEach { it.playbackParameters = params }
    }

    fun release() {
        players.forEach { it.release() }
        players.clear()
        volumes.clear()
        muted.clear()
        isPlaying = false
        pitchSemitones = 0f
        speedFactor = 1.0f
    }
}
