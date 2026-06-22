package com.example.leoshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * FloatingTriggerService.kt
 *
 * A Foreground Service that draws a small draggable overlay button using
 * TYPE_APPLICATION_OVERLAY. Tapping it triggers screen capture via
 * [SnippetAccessibilityService.captureScreen] — no MediaProjection dialog needed.
 *
 * Requires:
 *  - SYSTEM_ALERT_WINDOW permission (checked at start)
 *  - SnippetAccessibilityService enabled in Accessibility Settings
 */
class FloatingTriggerService : Service() {

    companion object {
        private const val CHANNEL_ID   = "snippet_trigger"
        private const val NOTIFICATION_ID = 1002

        /** Sent from MainActivity or the notification to stop the overlay. */
        const val ACTION_STOP = "com.example.leoshare.ACTION_STOP_TRIGGER"
    }

    // ── WindowManager overlay ─────────────────────────────────────────────────
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var overlayParams: WindowManager.LayoutParams

    // ── Drag tracking ─────────────────────────────────────────────────────────
    private var initialX      = 0
    private var initialY      = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging    = false

    // ── Auto-fade after idle ──────────────────────────────────────────────────
    private val fadeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val fadeRunnable = Runnable {
        overlayView?.animate()
            ?.alpha(0.15f)
            ?.setDuration(600)
            ?.start()
    }
    private val FADE_DELAY_MS = 3000L  // go transparent after 3 s of no touch

    /** Cancels any pending fade and schedules a fresh one. */
    private fun resetFadeTimer() {
        fadeHandler.removeCallbacks(fadeRunnable)
        fadeHandler.postDelayed(fadeRunnable, FADE_DELAY_MS)
    }

    /** Instantly restores full opacity and resets the fade countdown. */
    private fun wakeButton() {
        overlayView?.animate()?.cancel()
        overlayView?.alpha = 1f
        resetFadeTimer()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        showOverlay()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        fadeHandler.removeCallbacks(fadeRunnable)
        removeOverlay()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Overlay
    // ─────────────────────────────────────────────────────────────────────────

    private fun showOverlay() {
        if (overlayView != null) return

        val density = resources.displayMetrics.density
        val sizePx  = (28 * density).toInt()   // 28dp — half the previous size

        // ── Circular gradient background ──────────────────────────────────────
        val circleBg = android.graphics.drawable.GradientDrawable().apply {
            shape         = android.graphics.drawable.GradientDrawable.OVAL
            colors        = intArrayOf(
                android.graphics.Color.parseColor("#FF5E35B1"), // deep purple
                android.graphics.Color.parseColor("#FF1976D2")  // blue
            )
            gradientType  = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
            orientation   = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
        }

        // ── Container ─────────────────────────────────────────────────────────
        val container = android.widget.FrameLayout(this).apply {
            background = circleBg
            elevation  = 14f
        }

        // ── Scissors label ────────────────────────────────────────────────────
        val label = android.widget.TextView(this).apply {
            text      = "✂"
            textSize  = 11f   // scaled down to match 28dp button
            setTextColor(android.graphics.Color.WHITE)
            gravity   = android.view.Gravity.CENTER
            typeface  = android.graphics.Typeface.DEFAULT_BOLD
        }

        container.addView(
            label,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        overlayParams = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = 350
        }

        container.setOnTouchListener(dragAndTapListener())
        windowManager.addView(container, overlayParams)
        overlayView = container

        // Start the first fade countdown immediately after showing
        resetFadeTimer()
    }


    private fun removeOverlay() {
        overlayView?.let { v ->
            runCatching { windowManager.removeView(v) }
            overlayView = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Touch: short tap = capture, drag = reposition
    // ─────────────────────────────────────────────────────────────────────────

    private fun dragAndTapListener() = View.OnTouchListener { _, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Any touch: snap back to full opacity + reset fade timer
                wakeButton()
                initialX      = overlayParams.x
                initialY      = overlayParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging    = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()
                if (!isDragging && (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8)) {
                    isDragging = true
                }
                if (isDragging) {
                    overlayParams.x = initialX + dx
                    overlayParams.y = initialY + dy
                    runCatching { windowManager.updateViewLayout(overlayView, overlayParams) }
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) triggerCapture()
                isDragging = false
                // After tap/drag ends, restart the fade countdown
                resetFadeTimer()
                true
            }

            else -> false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Capture flow — uses AccessibilityService, no MediaProjection dialog
    // ─────────────────────────────────────────────────────────────────────────

    private fun triggerCapture() {
        // Guard: accessibility service must be enabled
        if (!SnippetAccessibilityService.isReady()) {
            Toast.makeText(
                this,
                "Enable SnippetShare in Accessibility Settings first",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Hide the overlay briefly so it doesn't appear in the screenshot
        overlayView?.visibility = View.INVISIBLE

        android.os.Handler(mainLooper).postDelayed({
            SnippetAccessibilityService.captureScreen { bitmap: Bitmap? ->
                // Always restore overlay visibility
                overlayView?.visibility = View.VISIBLE

                if (bitmap == null) {
                    Toast.makeText(
                        this,
                        "Capture failed — is Accessibility permission still enabled?",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@captureScreen
                }

                // Write bitmap to cache so CroppingActivity can read it
                val cacheFile = java.io.File(cacheDir, "snippets/raw_capture.png")
                    .also { it.parentFile?.mkdirs() }

                try {
                    java.io.FileOutputStream(cacheFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        out.flush()
                    }
                } finally {
                    bitmap.recycle()
                }

                // Launch the crop UI
                val intent = Intent(this, CroppingActivity::class.java).apply {
                    putExtra(CroppingActivity.EXTRA_BITMAP_PATH, cacheFile.absolutePath)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            }
        }, 150L) // 150ms — enough time for the overlay to disappear from the frame
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Floating Trigger", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "SnippetShare overlay button"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingTriggerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SnippetShare overlay active")
            .setContentText("Tap the floating ✂️ button to capture a snippet")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .addAction(android.R.drawable.ic_delete, "Stop overlay", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
