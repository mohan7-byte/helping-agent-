package com.gemini.live.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-performance, bulletproof native AudioRecorder for Android & Samsung devices.
 * Supports hardware native sample rates (16kHz, 48kHz, 44.1kHz) with real-time downsampling.
 * Features speech amplitude detection, source fallbacks, and zero WebView overhead.
 */
class AudioRecorder(
    private val onSpeechDetected: (() -> Unit)? = null,
    private val onAudioChunk: (String) -> Unit,
    private val onError: ((String) -> Unit)? = null
) {
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    var isMuted: Boolean = false
    var echoGuardActive: Boolean = false

    private var activeSampleRate: Int = 16000

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording.get()) return

        // Priority sample rates: 16000 first (zero CPU downsampling), then 48000 (native Samsung HAL 3:1), then 44100
        val sampleRatesToTry = intArrayOf(16000, 48000, 44100)
        val sourcesToTry = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.DEFAULT
        )

        var initializedRecord: AudioRecord? = null
        var selectedRate = 16000

        outer@ for (rate in sampleRatesToTry) {
            val minBufferSize = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) continue

            val bufferSize = Math.max(minBufferSize * 2, 8192)

            for (src in sourcesToTry) {
                try {
                    val candidate = AudioRecord(
                        src,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        initializedRecord = candidate
                        selectedRate = rate
                        android.util.Log.d("AudioRecorder", "AudioRecord initialized: rate=$rate, source=$src")
                        break@outer
                    } else {
                        candidate.release()
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AudioRecorder", "Failed src $src at $rate: ${e.message}")
                }
            }
        }

        if (initializedRecord == null || initializedRecord.state != AudioRecord.STATE_INITIALIZED) {
            val err = "Failed to initialize microphone with any audio source or sample rate!"
            android.util.Log.e("AudioRecorder", err)
            onError?.invoke(err)
            return
        }

        audioRecord = initializedRecord
        activeSampleRate = selectedRate

        try {
            audioRecord?.startRecording()
            isRecording.set(true)
            android.util.Log.d("AudioRecorder", "Recording started at $activeSampleRate Hz")

            recordingThread = Thread({
                // Size read buffer depending on sample rate (~60-70ms chunks)
                val readChunkSize = when (activeSampleRate) {
                    48000 -> 6144 // 48000 * 2 bytes * 0.064s = 6144 bytes
                    44100 -> 5644
                    else -> 2048 // 16000 * 2 bytes * 0.064s = 2048 bytes
                }
                val readBuffer = ByteArray(readChunkSize)
                var zeroBufferCount = 0

                while (isRecording.get()) {
                    val read = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0
                    if (read < 0) {
                        android.util.Log.w("AudioRecorder", "AudioRecord read error code: $read")
                        try { Thread.sleep(20) } catch (ignored: Exception) {}
                        continue
                    }
                    if (read == 0) continue

                    if (isMuted || echoGuardActive) {
                        continue
                    }

                    // Convert to 16kHz PCM 16-bit Mono
                    val pcm16kBytes: ByteArray = when (activeSampleRate) {
                        48000 -> downsample48kTo16k(readBuffer, read)
                        44100 -> resampleLinear(readBuffer, read, 44100, 16000)
                        else -> {
                            if (read == readBuffer.size) readBuffer
                            else readBuffer.copyOf(read)
                        }
                    }

                    // Amplitude speech detection
                    var maxSample = 0
                    for (i in 0 until pcm16kBytes.size - 1 step 2) {
                        val sample = Math.abs(((pcm16kBytes[i + 1].toInt() shl 8) or (pcm16kBytes[i].toInt() and 0xFF)).toShort().toInt())
                        if (sample > maxSample) maxSample = sample
                    }

                    if (maxSample == 0) {
                        zeroBufferCount++
                        if (zeroBufferCount == 50) {
                            android.util.Log.w("AudioRecorder", "Warning: 50 consecutive zero-amplitude audio buffers received from microphone")
                        }
                    } else {
                        zeroBufferCount = 0
                    }

                    // Speech threshold (300 amplitude is noticeable speech)
                    if (maxSample > 300) {
                        onSpeechDetected?.invoke()
                    }

                    val base64 = Base64.encodeToString(pcm16kBytes, Base64.NO_WRAP)
                    onAudioChunk(base64)
                }
            }, "Voice-AudioRecorder").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        } catch (e: Exception) {
            val err = "Error starting recording: ${e.message}"
            android.util.Log.e("AudioRecorder", err, e)
            onError?.invoke(err)
        }
    }

    private fun downsample48kTo16k(input: ByteArray, bytesRead: Int): ByteArray {
        val totalSamples = bytesRead / 2
        val outSamples = totalSamples / 3
        val output = ByteArray(outSamples * 2)
        var inIdx = 0
        var outIdx = 0

        for (i in 0 until outSamples) {
            val s0 = ((input[inIdx + 1].toInt() shl 8) or (input[inIdx].toInt() and 0xFF)).toShort().toInt()
            val s1 = ((input[inIdx + 3].toInt() shl 8) or (input[inIdx + 2].toInt() and 0xFF)).toShort().toInt()
            val s2 = ((input[inIdx + 5].toInt() shl 8) or (input[inIdx + 4].toInt() and 0xFF)).toShort().toInt()

            val avg = ((s0 + s1 + s2) / 3).coerceIn(-32768, 32767).toShort()
            output[outIdx] = (avg.toInt() and 0xFF).toByte()
            output[outIdx + 1] = ((avg.toInt() shr 8) and 0xFF).toByte()

            inIdx += 6
            outIdx += 2
        }
        return output
    }

    private fun resampleLinear(input: ByteArray, bytesRead: Int, inRate: Int, outRate: Int): ByteArray {
        val inSamples = bytesRead / 2
        val ratio = inRate.toDouble() / outRate.toDouble()
        val outSamples = (inSamples / ratio).toInt()
        val output = ByteArray(outSamples * 2)

        var outIdx = 0
        for (i in 0 until outSamples) {
            val inPos = i * ratio
            val inIndex = inPos.toInt()
            val frac = inPos - inIndex

            val s0 = if (inIndex * 2 + 1 < bytesRead) {
                ((input[inIndex * 2 + 1].toInt() shl 8) or (input[inIndex * 2].toInt() and 0xFF)).toShort().toDouble()
            } else 0.0

            val s1 = if ((inIndex + 1) * 2 + 1 < bytesRead) {
                ((input[(inIndex + 1) * 2 + 1].toInt() shl 8) or (input[(inIndex + 1) * 2].toInt() and 0xFF)).toShort().toDouble()
            } else s0

            val sample = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
            output[outIdx] = (sample.toInt() and 0xFF).toByte()
            output[outIdx + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
            outIdx += 2
        }
        return output
    }

    fun stop() {
        if (!isRecording.compareAndSet(true, false)) return
        val threadToStop = recordingThread
        val recordToRelease = audioRecord
        recordingThread = null
        audioRecord = null

        Thread({
            try {
                threadToStop?.interrupt()
                recordToRelease?.stop()
                recordToRelease?.release()
            } catch (ignored: Exception) {}
        }, "Voice-AudioRecorder-Teardown").start()
    }
}
