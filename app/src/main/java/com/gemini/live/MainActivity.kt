package com.gemini.live

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.gemini.live.audio.AudioPlayer
import com.gemini.live.audio.AudioRecorder
import com.gemini.live.databinding.ActivityMainBinding
import com.gemini.live.net.GeminiLiveClient
import com.gemini.live.tools.DeviceToolDispatcher
import com.gemini.live.ui.GlowingOrbView
import com.gemini.live.ui.SettingsDialog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * MainActivity:
 * CRITICAL RULE #1: setShowWhenLocked(true) & setTurnScreenOn(true). Zero Keyguard PIN prompt calls!
 * CRITICAL RULE #2: Pure Translucent Window. Tapping outside the capsule calls moveTaskToBack(true) smoothly.
 */
class MainActivity : AppCompatActivity(), GeminiLiveClient.Listener {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private var audioRecorder: AudioRecorder? = null
    private var audioPlayer: AudioPlayer? = null
    private var geminiClient: GeminiLiveClient? = null
    private lateinit var toolDispatcher: DeviceToolDispatcher

    private var inactivityTimer: Runnable? = null
    private var isMuted = false

    // CameraX Vision streaming
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor = Executors.newSingleThreadExecutor()
    private var isCameraActive = false
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var lastCameraFrameTime = 0L

    // Screen Projection Permission Launcher
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    // Permission Launcher
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val recordOk = grants[Manifest.permission.RECORD_AUDIO] == true
        if (recordOk) {
            startLiveSession()
        } else {
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // RULE #1: Lock Screen Bypass without triggering Samsung Keyguard PIN prompt!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("gemini_live_prefs", Context.MODE_PRIVATE)

        toolDispatcher = DeviceToolDispatcher(
            context = applicationContext,
            apiKeyProvider = { prefs.getString("api_key", "") ?: "" },
            sendVisualFrame = { b64 -> geminiClient?.sendVisualFrame(b64) }
        )

        geminiClient = GeminiLiveClient(lifecycleScope, toolDispatcher, this)

        initAudio()
        initViews()
        requestProjectionPermission()

        // Auto-connect on launch if enabled
        if (prefs.getBoolean("auto_connect", true)) {
            val key = prefs.getString("api_key", "") ?: ""
            if (key.isNotEmpty()) {
                mainHandler.postDelayed({ startLiveSession() }, 200)
            }
        }
    }

    private fun initAudio() {
        audioRecorder = AudioRecorder { pcmBase64 ->
            resetInactivityTimer()
            geminiClient?.sendAudioPcm16k(pcmBase64)
        }

        audioPlayer = AudioPlayer(
            onPlaybackStarted = {
                runOnUiThread {
                    binding.orbView.setState(GlowingOrbView.State.SPEAKING)
                    binding.capsuleStatus.text = "Speaking"
                    binding.capsuleSub.text = "Jarvis..."
                    audioRecorder?.echoGuardActive = true
                    resetInactivityTimer()
                }
            },
            onPlaybackFinished = {
                runOnUiThread {
                    audioRecorder?.echoGuardActive = false
                    binding.orbView.setState(GlowingOrbView.State.LISTENING)
                    binding.capsuleStatus.text = "Listening"
                    binding.capsuleSub.text = "Go ahead"
                    startInactivityCountdown()
                }
            }
        )
    }

    private fun initViews() {
        // RULE #2: Tapping the translucent backdrop minimizes without ending call
        binding.rootBackdrop.setOnClickListener {
            moveTaskToBack(true)
        }

        binding.connectBtn.setOnClickListener {
            checkPermissionsAndStart()
        }

        binding.disconnectBtn.setOnClickListener {
            endLiveSession()
        }

        binding.muteBtn.setOnClickListener {
            isMuted = !isMuted
            audioRecorder?.isMuted = isMuted
            binding.muteBtn.alpha = if (isMuted) 0.4f else 1.0f
        }

        binding.openSettingsBtn.setOnClickListener {
            SettingsDialog {}.show(supportFragmentManager, "settings")
        }

        binding.toggleCameraBtn.setOnClickListener {
            if (!isCameraActive) startCameraVision() else stopCameraVision()
        }

        binding.flipCameraBtn.setOnClickListener {
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            if (isCameraActive) startCameraVision()
        }
    }

    private fun requestProjectionPermission() {
        if (ScreenCaptureService.instance == null) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (mpm != null) {
                try {
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                } catch (ignored: Exception) {}
            }
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionsLauncher.launch(missing.toTypedArray())
        } else {
            startLiveSession()
        }
    }

    private fun startLiveSession() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }

        val prefs = getSharedPreferences("gemini_live_prefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) {
            SettingsDialog {}.show(supportFragmentManager, "settings")
            Toast.makeText(this, "Please enter your Gemini API Key", Toast.LENGTH_LONG).show()
            return
        }

        val model = prefs.getString("model", "models/gemini-3.1-flash-live-preview") ?: "models/gemini-3.1-flash-live-preview"
        val voice = prefs.getString("voice", "Aoede") ?: "Aoede"
        val prompt = prefs.getString("system_prompt", "") ?: ""

        binding.capsuleStatus.text = "Connecting..."
        binding.capsuleSub.text = "Initiating handshake"
        binding.orbView.setState(GlowingOrbView.State.WORKING)

        geminiClient?.connect(apiKey, model, voice, prompt)
    }

    private var isSessionEnding = false

    private fun endLiveSession() {
        if (isSessionEnding) return
        isSessionEnding = true

        try {
            geminiClient?.disconnect()
            audioRecorder?.stop()
            audioPlayer?.stop()
            stopCameraVision()
            resetInactivityTimer()

            binding.connectBtn.visibility = View.VISIBLE
            binding.liveControls.visibility = View.GONE
            binding.orbView.setState(GlowingOrbView.State.IDLE)
            binding.capsuleStatus.text = "Voice"
            binding.capsuleSub.text = "Ready"

            // Minimize after disconnect
            moveTaskToBack(true)
        } finally {
            isSessionEnding = false
        }
    }

    // CameraX 320x240 Live Vision
    private fun startCameraVision() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreviewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(320, 240))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val now = System.currentTimeMillis()
                if (now - lastCameraFrameTime > 1500) {
                    lastCameraFrameTime = now
                    val bitmap = imageProxy.toBitmap()
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                    val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    geminiClient?.sendVisualFrame(b64)
                }
                imageProxy.close()
            }

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
                binding.cameraBox.visibility = View.VISIBLE
                isCameraActive = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCameraVision() {
        cameraProvider?.unbindAll()
        binding.cameraBox.visibility = View.GONE
        isCameraActive = false
    }

    // Inactivity Auto-Disconnect
    private fun resetInactivityTimer() {
        inactivityTimer?.let { mainHandler.removeCallbacks(it) }
        inactivityTimer = null
    }

    private fun startInactivityCountdown() {
        resetInactivityTimer()
        val prefs = getSharedPreferences("gemini_live_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_disconnect", true)) return

        inactivityTimer = Runnable {
            endLiveSession()
        }
        mainHandler.postDelayed(inactivityTimer!!, 4000)
    }

    // GeminiLiveClient.Listener Callbacks
    override fun onConnected() {
        runOnUiThread {
            binding.connectBtn.visibility = View.GONE
            binding.liveControls.visibility = View.VISIBLE
            binding.orbView.setState(GlowingOrbView.State.LISTENING)
            binding.capsuleStatus.text = "Listening"
            binding.capsuleSub.text = "Go ahead"

            audioPlayer?.start()
            audioRecorder?.start()
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            endLiveSession()
        }
    }

    override fun onAudioData(base64Pcm24k: String) {
        audioPlayer?.enqueueBase64Audio(base64Pcm24k)
    }

    override fun onInterrupted() {
        audioPlayer?.interrupt()
        runOnUiThread {
            binding.orbView.setState(GlowingOrbView.State.LISTENING)
            binding.capsuleStatus.text = "Listening"
            binding.capsuleSub.text = "Go ahead"
            resetInactivityTimer()
        }
    }

    override fun onTurnComplete() {
        // audioPlayer handles onPlaybackFinished callback
    }

    override fun onStatusChanged(state: String, title: String, sub: String) {
        runOnUiThread {
            resetInactivityTimer()
            binding.capsuleStatus.text = title
            binding.capsuleSub.text = sub
            if (state == "working") {
                binding.orbView.setState(GlowingOrbView.State.WORKING)
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val prefs = getSharedPreferences("gemini_live_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("auto_connect", true)) {
            startLiveSession()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        audioRecorder?.stop()
        audioPlayer?.stop()
        geminiClient?.disconnect()
        cameraExecutor.shutdown()
        super.onDestroy()
    }
}
