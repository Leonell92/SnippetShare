package com.example.leoshare

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * ShareUtils.kt — Zero-clutter instant share utility.
 *
 * All writes go to [Context.getCacheDir]/snippets/ which is:
 *  - Private to the app (not visible in MediaStore or Google Photos)
 *  - Automatically cleared when the system needs space
 *  - Shareable via a secure FileProvider content:// URI
 */
object ShareUtils {

    private const val AUTHORITY = "com.example.leoshare.fileprovider"
    private const val SNIPPETS_DIR = "snippets"

    /**
     * Crops [sourceBitmap] to [cropRect], saves it as a temporary PNG in the
     * app's cache directory, then launches the system share sheet via an
     * ACTION_SEND intent.
     *
     * @param context     Any context — application context is fine.
     * @param sourceBitmap The full captured screen bitmap.
     * @param cropRect    The rectangle (in bitmap pixel coordinates) to crop.
     */
    fun cropAndShare(context: Context, sourceBitmap: Bitmap, cropRect: Rect) {
        // ── 1. Clamp the rect to bitmap bounds to avoid IllegalArgumentException ──
        val safeRect = Rect(
            cropRect.left.coerceAtLeast(0),
            cropRect.top.coerceAtLeast(0),
            cropRect.right.coerceAtMost(sourceBitmap.width),
            cropRect.bottom.coerceAtMost(sourceBitmap.height)
        )

        if (safeRect.isEmpty || safeRect.width() <= 0 || safeRect.height() <= 0) return

        // ── 2. Crop the bitmap in-memory (never touches MediaStore) ───────────
        val cropped = Bitmap.createBitmap(
            sourceBitmap,
            safeRect.left,
            safeRect.top,
            safeRect.width(),
            safeRect.height()
        )

        // ── 3. Write PNG to cache/snippets/ ───────────────────────────────────
        val snippetsDir = File(context.cacheDir, SNIPPETS_DIR).also { it.mkdirs() }
        val fileName = "snippet_${System.currentTimeMillis()}.png"
        val outFile = File(snippetsDir, fileName)

        FileOutputStream(outFile).use { fos ->
            cropped.compress(Bitmap.CompressFormat.PNG, 100, fos)
            fos.flush()
        }
        cropped.recycle()

        // ── 4. Generate a secure content:// URI via FileProvider ──────────────
        val contentUri = FileProvider.getUriForFile(context, AUTHORITY, outFile)

        // ── 5. Build and launch the system share sheet ────────────────────────
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            // Grant the receiving app permission to read this URI
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Optional: pre-populate a subject/title for apps that display it
            putExtra(Intent.EXTRA_SUBJECT, "SnippetShare capture")
        }

        val chooser = Intent.createChooser(shareIntent, "Share snippet via…").apply {
            // Required when starting from a non-Activity context (e.g., a Service)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(chooser)
    }

    /**
     * Deletes all previously cached snippet files.
     * Call this on app start or from a maintenance job if desired.
     */
    fun clearCache(context: Context) {
        File(context.cacheDir, SNIPPETS_DIR).listFiles()?.forEach { it.delete() }
    }
}
