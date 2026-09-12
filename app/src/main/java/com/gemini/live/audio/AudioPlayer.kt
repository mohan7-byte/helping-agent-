package com.gemini.live.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-low RAM native AudioPlayer.
 * Plays 24kHz 16-bit Mono streaming PCM received from Gemini Live API.
 * Supports instant interrupt/barge-in clearing.
 */
class AudioPlayer(
    private val onPlaybackStarted: () -> Unit,
    private val onPlaybackFinished: () -> Unit
) {
    private val sampleRate = 24000
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var playbackThread: Thread? = null

    private fun ensureAudioTrack(): AudioTrack? {
        val current = audioTrack
        if (current != null && current.state == AudioTrack.STATE_INITIALIZED) {
            return current
        }
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = Math.max(minBufferSize * 2, 4096)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(audioFormat)
            .build()

        return try {
            AudioTrack(
                audioAttributes,
                format,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            ).also { audioTrack = it }
        } catch (e: Exception) {
            android.util.Log.e("AudioPlayer", "Failed to create AudioTrack: ${e.message}", e)
            null
        }
    }

    fun start() {
        if (isPlaying.get()) return
        val track = ensureAudioTrack() ?: return
        isPlaying.set(true)
        try {
            track.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        playbackThread = Thread({
            var activePlaying = false
            while (isPlaying.get()) {
                try {
                    val chunk = audioQueue.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk != null && chunk.isNotEmpty()) {
                        if (!activePlaying) {
                            activePlaying = true
                            onPlaybackStarted()
                        }
                        track.write(chunk, 0, chunk.size)
                    } else {
                        if (activePlaying && audioQueue.isEmpty()) {
                            activePlaying = false
                            onPlaybackFinished()
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (activePlaying) onPlaybackFinished()
        }, "Voice-AudioPlayer").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun enqueueBase64Audio(base64: String) {
        try {
            val pcmBytes = Base64.decode(base64, Base64.DEFAULT)
            audioQueue.offer(pcmBytes)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun interrupt() {
        audioQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (ignored: Exception) {}
        onPlaybackFinished()
    }

    fun stop() {
        if (!isPlaying.compareAndSet(true, false)) {
            // Already stopped or stopping
            audioQueue.clear()
            return
        }
        audioQueue.clear()
        val threadToStop = playbackThread
        val trackToRelease = audioTrack
        playbackThread = null
        audioTrack = null

        // Offload blocking audio driver stop/release to background thread to NEVER freeze UI
        Thread({
            try {
                threadToStop?.interrupt()
                trackToRelease?.stop()
                trackToRelease?.release()
            } catch (ignored: Exception) {}
        }, "Voice-AudioPlayer-Teardown").start()
    }
}
