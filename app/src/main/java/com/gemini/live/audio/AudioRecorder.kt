package com.gemini.live.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ultra-low RAM native AudioRecorder.
 * Directly records 16kHz 16-bit Mono PCM and delivers Base64 chunks.
 * Zero browser/WebAudio overhead!
 */
class AudioRecorder(
    private val onAudioChunk: (String) -> Unit
) {
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    var isMuted: Boolean = false
    var echoGuardActive: Boolean = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording.get()) return

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = Math.max(minBufferSize * 2, 4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
            }

            audioRecord?.startRecording()
            isRecording.set(true)

            recordingThread = Thread({
                val buffer = ByteArray(2048)
                while (isRecording.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        if (isMuted || echoGuardActive) {
                            // Suppress mic capture during model speaking or muted
                            continue
                        }

                        // Calculate RMS for speech detection
                        var sum = 0.0
                        for (i in 0 until read step 2) {
                            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                            sum += sample * sample
                        }

                        val base64 = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP)
                        onAudioChunk(base64)
                    }
                }
            }, "Voice-AudioRecorder").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stop() {
        if (!isRecording.compareAndSet(true, false)) return
        val threadToStop = recordingThread
        val recordToRelease = audioRecord
        recordingThread = null
        audioRecord = null

        // Offload blocking AudioRecord stop and release to background thread
        Thread({
            try {
                threadToStop?.interrupt()
                recordToRelease?.stop()
                recordToRelease?.release()
            } catch (ignored: Exception) {}
        }, "Voice-AudioRecorder-Teardown").start()
    }
}
