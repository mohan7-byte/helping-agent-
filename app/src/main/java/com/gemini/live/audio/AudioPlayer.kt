package com.gemini.live.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-low RAM native AudioPlayer.
 * Plays 24kHz 16-bit Mono streaming PCM received from Gemini Live API.
 * Accurately tracks turn completion and supports instant interrupt/barge-in clearing.
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
    private val isTurnCompletePending = AtomicBoolean(false)
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var playbackThread: Thread? = null

    private fun ensureAudioTrack(): AudioTrack? {
        val current = audioTrack
        if (current != null && current.state == AudioTrack.STATE_INITIALIZED) {
            return current
        }
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = Math.max(minBufferSize * 4, 16384)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
        isTurnCompletePending.set(false)
        try {
            track.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        playbackThread = Thread({
            var activePlaying = false
            while (isPlaying.get()) {
                try {
                    val chunk = audioQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk != null && chunk.isNotEmpty()) {
                        if (!activePlaying) {
                            activePlaying = true
                            try {
                                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    track.play()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            onPlaybackStarted()
                        }
                        track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    } else {
                        // Only finish playback if Gemini signaled turnComplete AND queue is completely empty
                        if (activePlaying && isTurnCompletePending.get() && audioQueue.isEmpty()) {
                            // Short grace period (250ms) to ensure hardware buffer has emitted audio to speaker
                            try { Thread.sleep(250) } catch (ignored: Exception) {}
                            if (audioQueue.isEmpty()) {
                                activePlaying = false
                                isTurnCompletePending.set(false)
                                onPlaybackFinished()
                            }
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

    fun markTurnComplete() {
        isTurnCompletePending.set(true)
    }

    fun interrupt() {
        audioQueue.clear()
        isTurnCompletePending.set(false)
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.play()
        } catch (ignored: Exception) {}
        onPlaybackFinished()
    }

    fun stop() {
        if (!isPlaying.compareAndSet(true, false)) {
            audioQueue.clear()
            return
        }
        audioQueue.clear()
        isTurnCompletePending.set(false)
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
