package com.example.leoshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * ScreenCaptureService.kt
 *
 * A Foreground Service (foregroundServiceType="mediaProjection") that:
 *  1. Holds the MediaProjection token obtained from MainActivity.
 *  2. On demand, captures a single frame of the screen into a Bitmap via
 *     ImageReader + VirtualDisplay.
 *  3. Exposes [captureScreen] to the FloatingTriggerService via a local Binder.
 *
 * Android 14+ requirements satisfied:
 *  - Service is started in the foreground BEFORE acquiring the MediaProjection.
 *  - Persistent notification posted on the correct channel.
 *  - foregroundServiceType="mediaProjection" declared in the manifest.
 */
class ScreenCaptureService : Service() {

    // ── Local Binder ──────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    private val binder = LocalBinder()

    // ── MediaProjection state ─────────────────────────────────────────────────
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    // ── Background thread for ImageReader callbacks ───────────────────────────
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    // ── Screen dimensions ─────────────────────────────────────────────────────
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    // ── Notification ──────────────────────────────────────────────────────────
    companion object {
        const val CHANNEL_ID = "snippet_capture"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        // Resolve screen metrics
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // Start background handler thread for ImageReader
        handlerThread = HandlerThread("ScreenCapture").also { it.start() }
        handler = Handler(handlerThread.looper)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val resultData: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (resultCode != -1 && resultData != null) {
            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

            // Register callback BEFORE getMediaProjection to satisfy Android 14 ordering
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: return START_NOT_STICKY   // null-safe guard — token was already consumed

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    releaseCapture()
                }
            }, handler)
            mediaProjection = projection
        }


        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        releaseCapture()
        handlerThread.quitSafely()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — called by FloatingTriggerService via the bound Binder
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Captures the current screen into a [Bitmap] and delivers it via [callback]
     * on the main thread. Returns immediately; capture is async.
     *
     * If no [MediaProjection] token is held yet, [callback] is invoked with null.
     */
    fun captureScreen(callback: (Bitmap?) -> Unit) {
        val projection = mediaProjection ?: run {
            callback(null)
            return
        }

        // Tear down any previous capture session
        releaseCapture()

        // ImageReader with RGBA_8888 — one buffer is enough for a single frame
        val reader = ImageReader.newInstance(
            screenWidth, screenHeight,
            PixelFormat.RGBA_8888,
            /* maxImages = */ 2
        )
        imageReader = reader

        val display = projection.createVirtualDisplay(
            "SnippetCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        virtualDisplay = display

        // Listen for the first available image
        reader.setOnImageAvailableListener({ imageReader ->
            val image = imageReader.acquireLatestImage() ?: return@setOnImageAvailableListener

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // Crop off the row-padding on the right edge
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            bitmap.recycle()

            // Tear down the VirtualDisplay immediately — one shot capture
            releaseCapture()

            // Deliver result on main thread
            android.os.Handler(mainLooper).post { callback(cropped) }

        }, handler)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun releaseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen Capture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "SnippetShare is ready to capture your screen"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SnippetShare active")
            .setContentText("Tap the floating button to capture a snippet")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
