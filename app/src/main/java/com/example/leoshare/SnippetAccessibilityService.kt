package com.example.leoshare

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * SnippetAccessibilityService.kt
 *
 * Replaces the MediaProjection approach entirely.
 *
 * Why:
 *  - MediaProjection requires a user dialog on EVERY service restart (Android 14+).
 *  - AccessibilityService.takeScreenshot() (Android 11 / API 30+) requires ONE-TIME
 *    enablement in Settings → Accessibility, then works silently forever after.
 *  - This mirrors how "Screen Master", "Assistive Touch" and similar overlay apps work.
 *
 * Usage:
 *  From any component in the same process, call:
 *      SnippetAccessibilityService.captureScreen { bitmap -> ... }
 *
 *  The companion object holds a reference to the live service instance — a standard
 *  Android pattern for accessibility services since they cannot be bound to like
 *  regular services.
 */
class SnippetAccessibilityService : AccessibilityService() {

    companion object {
        /**
         * Live reference to the running service. Non-null when the user has enabled
         * GhostShare in Accessibility Settings and the service is connected.
         */
        @Volatile
        var instance: SnippetAccessibilityService? = null
            private set

        /** True if the service is currently connected and ready to take screenshots. */
        fun isReady(): Boolean = instance != null

        /**
         * Captures the current screen and delivers the result via [callback].
         *
         * Must be called from any thread — callback is posted to the main thread.
         * Requires API 30+. On older APIs, [callback] is invoked with null.
         *
         * @param callback Receives the captured [Bitmap], or null on failure.
         */
        fun captureScreen(callback: (Bitmap?) -> Unit) {
            val svc = instance
            if (svc == null) {
                callback(null)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                svc.doCapture(callback)
            } else {
                // API < 30: takeScreenshot not available
                callback(null)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // We don't process accessibility events — we only use the screenshot API.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    // ─────────────────────────────────────────────────────────────────────────
    // Screenshot capture — API 30+
    // ─────────────────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doCapture(callback: (Bitmap?) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    // Wrap HardwareBuffer into a software Bitmap so we can crop it
                    val hwBitmap = Bitmap.wrapHardwareBuffer(
                        result.hardwareBuffer,
                        result.colorSpace
                    )
                    result.hardwareBuffer.close()

                    // Copy to software config — required for Bitmap.createBitmap(crop)
                    val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    hwBitmap?.recycle()

                    callback(softBitmap)
                }

                override fun onFailure(errorCode: Int) {
                    callback(null)
                }
            }
        )
    }
}
