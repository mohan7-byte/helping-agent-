package com.gemini.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * ScreenCaptureService.
 * CRITICAL RULE #4:
 * 1. Captures 1:1 crisp display resolution (widthPixels, heightPixels) so text is 100% legible.
 * 2. Compresses frame to JPEG.
 * 3. IMMEDIATELY calls virtualDisplay.release() and imageReader.close() so Samsung status bar casting icon disappears!
 */
class ScreenCaptureService : Service() {

    companion object {
        var instance: ScreenCaptureService? = null
    }

    private var mediaProjection: MediaProjection? = null
    private var resultCode = 0
    private var resultData: Intent? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        if (intent != null && intent.hasExtra("resultCode") && intent.hasExtra("data")) {
            this.resultCode = intent.getIntExtra("resultCode", 0)
            this.resultData = intent.getParcelableExtra("data")
            initProjection()
        }
        return START_STICKY
    }

    private fun initProjection() {
        try {
            if (mediaProjection == null && resultData != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                mediaProjection = mpm?.getMediaProjection(resultCode, resultData!!)
            }
        } catch (ignored: Exception) {}
    }

    fun captureScreenBase64(): String? {
        if (mediaProjection == null) {
            initProjection()
            if (mediaProjection == null) return null
        }

        var virtualDisplay: VirtualDisplay? = null
        var imageReader: ImageReader? = null
        var image: Image? = null
        var bitmap: Bitmap? = null

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val dm = DisplayMetrics()
            if (wm != null) {
                wm.defaultDisplay.getRealMetrics(dm)
            } else {
                val displayMetrics = resources.displayMetrics
                dm.widthPixels = displayMetrics.widthPixels
                dm.heightPixels = displayMetrics.heightPixels
                dm.densityDpi = displayMetrics.densityDpi
            }

            val width = dm.widthPixels
            val height = dm.heightPixels

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "VoiceShot",
                width,
                height,
                dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                null
            )

            for (i in 0 until 8) {
                SystemClock.sleep(50)
                image = imageReader.acquireLatestImage()
                if (image != null) break
            }

            if (image == null) return null

            val planes = image.planes
            val buffer: ByteBuffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 75, baos)
            cropped.recycle()

            return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        } catch (e: Exception) {
            return null
        } finally {
            bitmap?.recycle()
            image?.close()
            // CRITICAL: Immediately release virtualDisplay so Samsung status bar casting icon dismisses!
            virtualDisplay?.release()
            imageReader?.close()
        }
    }

    private fun startForegroundNotification() {
        val channelId = "voice_screen_capture"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            val chan = NotificationChannel(
                channelId,
                "Voice Screen Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(chan)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notif = builder
            .setContentTitle("Voice Vision")
            .setContentText("Screen engine active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1001, notif)
        }
    }

    override fun onDestroy() {
        instance = null
        try {
            mediaProjection?.stop()
        } catch (ignored: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
