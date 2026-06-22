package com.example.leoshare

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CroppingActivity.kt
 *
 * A transparent, full-screen Activity that hosts the [CroppingOverlay] Compose UI.
 *
 * Declared in the manifest with:
 *   android:theme="@android:style/Theme.Translucent.NoTitleBar.Fullscreen"
 *   android:excludeFromRecents="true"
 *
 * This means it floats seamlessly over whatever the user was doing, and doesn't
 * pollute the recent-apps list.
 *
 * Flow:
 *   1. Receives the bitmap file path via [EXTRA_BITMAP_PATH] in the launch intent.
 *   2. Decodes the bitmap on the IO dispatcher (never on Main).
 *   3. Shows [CroppingOverlay] for the user to draw a crop region.
 *   4. On confirm → calls [ShareUtils.cropAndShare], then finishes.
 *   5. On cancel → finishes immediately.
 */
class CroppingActivity : ComponentActivity() {

    companion object {
        /** Absolute path to the cached PNG written by [FloatingTriggerService] */
        const val EXTRA_BITMAP_PATH = "extra_bitmap_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bitmapPath = intent?.getStringExtra(EXTRA_BITMAP_PATH)
        if (bitmapPath == null) {
            finish()
            return
        }

        setContent {
            // ── State ─────────────────────────────────────────────────────────
            var bitmap by remember { mutableStateOf<Bitmap?>(null) }

            // ── Load bitmap off the main thread ───────────────────────────────
            LaunchedEffect(bitmapPath) {
                bitmap = withContext(Dispatchers.IO) {
                    loadBitmapFromPath(bitmapPath)
                }
                if (bitmap == null) finish()
            }

            // ── Show the crop UI as soon as the bitmap is ready ───────────────
            bitmap?.let { bmp ->
                CroppingOverlay(
                    bitmap = bmp,
                    onShare = { cropRect: Rect ->
                        ShareUtils.cropAndShare(
                            context = applicationContext,
                            sourceBitmap = bmp,
                            cropRect = cropRect
                        )
                        finish()
                    },
                    onDismiss = {
                        finish()
                    }
                )
            }
        }
    }

    override fun finish() {
        super.finish()
        // No slide animation — snap out cleanly like a system overlay
        overridePendingTransition(0, 0)
    }
}
