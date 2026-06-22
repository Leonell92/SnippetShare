package com.example.leoshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// Drag mode — determines what a new touch gesture does
// ─────────────────────────────────────────────────────────────────────────────
private enum class DragMode {
    /** Finger outside existing selection — draw a brand new rect */
    NEW_SELECTION,
    /** Finger inside selection body — move it */
    MOVE,
    /** Finger near a corner — resize from that corner */
    RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR
}

// ─────────────────────────────────────────────────────────────────────────────
// Normalized rect — always left≤right, top≤bottom (no negative dimensions)
// ─────────────────────────────────────────────────────────────────────────────
private data class NormalizedRect(
    val left: Float, val top: Float,
    val right: Float, val bottom: Float
) {
    val width:   Float   get() = (right - left).coerceAtLeast(0f)
    val height:  Float   get() = (bottom - top).coerceAtLeast(0f)
    val isEmpty: Boolean get() = width < 10f || height < 10f
    val centerX: Float   get() = (left + right) / 2f
    val centerY: Float   get() = (top + bottom) / 2f
}

/**
 * Determines what drag mode to enter based on where the finger landed
 * relative to an existing selection.
 *
 * Priority: corners > inside (move) > outside (new selection)
 *
 * @param offset      Touch-down position in canvas pixels
 * @param sel         Existing selection rect
 * @param hitPx       Side length of the square hit area around each corner
 */
private fun hitTest(offset: Offset, sel: NormalizedRect, hitPx: Float): DragMode {
    val h = hitPx / 2f

    fun nearCorner(cx: Float, cy: Float) =
        offset.x in (cx - h)..(cx + h) && offset.y in (cy - h)..(cy + h)

    return when {
        nearCorner(sel.left,  sel.top)    -> DragMode.RESIZE_TL
        nearCorner(sel.right, sel.top)    -> DragMode.RESIZE_TR
        nearCorner(sel.left,  sel.bottom) -> DragMode.RESIZE_BL
        nearCorner(sel.right, sel.bottom) -> DragMode.RESIZE_BR
        offset.x in sel.left..sel.right &&
        offset.y in sel.top..sel.bottom   -> DragMode.MOVE
        else                              -> DragMode.NEW_SELECTION
    }
}

/**
 * CroppingOverlay — full-screen interactive crop canvas.
 *
 * Gesture behaviour:
 *  • Drag on empty area       → draw a new selection rectangle
 *  • Drag inside selection    → move the whole rectangle
 *  • Drag on a corner handle  → resize from that corner only
 *  • Lift finger              → show Share / Cancel buttons
 */
@Composable
fun CroppingOverlay(
    bitmap: Bitmap,
    onShare: (Rect) -> Unit,
    onDismiss: () -> Unit
) {
    // ── State ─────────────────────────────────────────────────────────────────
    var selection   by remember { mutableStateOf<NormalizedRect?>(null) }
    var dragMode    by remember { mutableStateOf(DragMode.NEW_SELECTION) }
    var isDragging  by remember { mutableStateOf(false) }
    // Anchor point for NEW_SELECTION — the finger's initial touch position
    var selAnchor   by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        val density     = LocalDensity.current
        val canvasW     = with(density) { maxWidth.toPx() }
        val canvasH     = with(density) { maxHeight.toPx() }

        // Canvas-pixel → bitmap-pixel scale factors
        val scaleX = bitmap.width.toFloat()  / canvasW
        val scaleY = bitmap.height.toFloat() / canvasH

        // Corner handle sizes in pixels
        val hitAreaPx     = with(density) { 48.dp.toPx() }  // tap hit area
        val handleRadiusPx = with(density) { 9.dp.toPx() }  // visual dot
        val minSizePx     = with(density) { 40.dp.toPx() }  // minimum selection size

        // ── Interactive Canvas ────────────────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            val existing = selection
                            if (existing != null && !existing.isEmpty) {
                                dragMode = hitTest(offset, existing, hitAreaPx)
                            } else {
                                dragMode = DragMode.NEW_SELECTION
                            }
                            if (dragMode == DragMode.NEW_SELECTION) {
                                selAnchor = offset
                                selection = NormalizedRect(offset.x, offset.y, offset.x, offset.y)
                            }
                        },
                        onDrag = { change, dragAmount ->
                            val pos = change.position
                            val dx  = dragAmount.x
                            val dy  = dragAmount.y
                            val s   = selection ?: return@detectDragGestures

                            selection = when (dragMode) {

                                // ── New selection: anchor stays, end follows finger ─────────
                                DragMode.NEW_SELECTION -> NormalizedRect(
                                    left   = minOf(selAnchor.x, pos.x).coerceIn(0f, canvasW),
                                    top    = minOf(selAnchor.y, pos.y).coerceIn(0f, canvasH),
                                    right  = maxOf(selAnchor.x, pos.x).coerceIn(0f, canvasW),
                                    bottom = maxOf(selAnchor.y, pos.y).coerceIn(0f, canvasH)
                                )

                                // ── Move: shift all four edges by delta ────────────────────
                                DragMode.MOVE -> {
                                    val newLeft = (s.left + dx).coerceIn(0f, canvasW - s.width)
                                    val newTop  = (s.top  + dy).coerceIn(0f, canvasH - s.height)
                                    s.copy(
                                        left   = newLeft,
                                        top    = newTop,
                                        right  = newLeft + s.width,
                                        bottom = newTop  + s.height
                                    )
                                }

                                // ── Corner resize: move only the dragged corner ────────────
                                DragMode.RESIZE_TL -> s.copy(
                                    left = (s.left + dx).coerceIn(0f, s.right - minSizePx),
                                    top  = (s.top  + dy).coerceIn(0f, s.bottom - minSizePx)
                                )
                                DragMode.RESIZE_TR -> s.copy(
                                    right = (s.right + dx).coerceIn(s.left + minSizePx, canvasW),
                                    top   = (s.top   + dy).coerceIn(0f, s.bottom - minSizePx)
                                )
                                DragMode.RESIZE_BL -> s.copy(
                                    left   = (s.left   + dx).coerceIn(0f, s.right - minSizePx),
                                    bottom = (s.bottom + dy).coerceIn(s.top + minSizePx, canvasH)
                                )
                                DragMode.RESIZE_BR -> s.copy(
                                    right  = (s.right  + dx).coerceIn(s.left + minSizePx, canvasW),
                                    bottom = (s.bottom + dy).coerceIn(s.top  + minSizePx, canvasH)
                                )
                            }
                        },
                        onDragEnd    = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    )
                }
        ) {
            // ── 1. Captured bitmap ────────────────────────────────────────────
            drawImage(
                image   = bitmap.asImageBitmap(),
                dstSize = IntSize(size.width.toInt(), size.height.toInt())
            )

            // ── 2. Dark scrim ─────────────────────────────────────────────────
            drawRect(color = Color(0xBB000000), size = size)

            // ── 3. Selection rendering ────────────────────────────────────────
            val sel = selection
            if (sel != null && !sel.isEmpty) {
                val topLeft  = Offset(sel.left, sel.top)
                val rectSize = Size(sel.width, sel.height)

                // Transparent punch-through (reveals bitmap beneath scrim)
                drawRect(
                    color     = Color.Transparent,
                    topLeft   = topLeft,
                    size      = rectSize,
                    blendMode = BlendMode.Clear
                )

                // White border
                drawRect(
                    color   = Color.White,
                    topLeft = topLeft,
                    size    = rectSize,
                    style   = Stroke(width = 2.5.dp.toPx())
                )

                // Rule-of-thirds grid lines (subtle, inside the selection)
                val thirdW = sel.width / 3f
                val thirdH = sel.height / 3f
                val gridPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(80, 255, 255, 255)
                    strokeWidth = 1.dp.toPx()
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.apply {
                    drawLine(sel.left + thirdW, sel.top, sel.left + thirdW, sel.bottom, gridPaint)
                    drawLine(sel.left + thirdW * 2, sel.top, sel.left + thirdW * 2, sel.bottom, gridPaint)
                    drawLine(sel.left, sel.top + thirdH, sel.right, sel.top + thirdH, gridPaint)
                    drawLine(sel.left, sel.top + thirdH * 2, sel.right, sel.top + thirdH * 2, gridPaint)
                }

                // Corner handles — blue dot inside white ring
                listOf(
                    Offset(sel.left,  sel.top),
                    Offset(sel.right, sel.top),
                    Offset(sel.left,  sel.bottom),
                    Offset(sel.right, sel.bottom)
                ).forEach { corner ->
                    drawCircle(Color(0x66000000), handleRadiusPx + 3f, corner) // shadow
                    drawCircle(Color.White,       handleRadiusPx,       corner) // outer ring
                    drawCircle(Color(0xFF2979FF), handleRadiusPx - 4f,  corner) // blue fill
                }

                // Mid-edge handles (smaller)
                val midR = handleRadiusPx * 0.6f
                listOf(
                    Offset(sel.centerX, sel.top),
                    Offset(sel.centerX, sel.bottom),
                    Offset(sel.left,    sel.centerY),
                    Offset(sel.right,   sel.centerY)
                ).forEach { mid ->
                    drawCircle(Color.White,       midR,        mid)
                    drawCircle(Color(0xFFCCCCCC), midR - 2.5f, mid)
                }

                // Dimension label above selection
                if (!isDragging) {
                    val pxW    = (sel.width  * scaleX).toInt()
                    val pxH    = (sel.height * scaleY).toInt()
                    val labelY = (sel.top - 16f).coerceAtLeast(44f)
                    val nativePaint = android.graphics.Paint().apply {
                        color       = android.graphics.Color.WHITE
                        textSize    = 34f
                        isAntiAlias = true
                        setShadowLayer(5f, 1f, 1f, android.graphics.Color.BLACK)
                    }
                    drawContext.canvas.nativeCanvas.drawText(
                        "${pxW} × ${pxH}px",
                        sel.left + 8f,
                        labelY,
                        nativePaint
                    )
                }
            }
        }

        // ── Instruction hint ──────────────────────────────────────────────────
        if (selection == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0x99000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text("✂️  Drag to select • Drag corners to resize • Drag inside to move",
                    color    = Color.White,
                    fontSize = 13.sp
                )
            }
        }

        // ── Share / Cancel buttons ────────────────────────────────────────────
        val currentSel = selection
        if (currentSel != null && !currentSel.isEmpty && !isDragging) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 52.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape   = RoundedCornerShape(50),
                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(20.dp))
                Button(
                    onClick = {
                        val bitmapRect = Rect(
                            (currentSel.left   * scaleX).toInt(),
                            (currentSel.top    * scaleY).toInt(),
                            (currentSel.right  * scaleX).toInt(),
                            (currentSel.bottom * scaleY).toInt()
                        )
                        onShare(bitmapRect)
                    },
                    shape  = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF))
                ) {
                    Text("Share  ✂️", color = Color.White)
                }
            }
        }
    }
}

/** Decodes a [Bitmap] from an absolute file path. Call only from a background thread. */
fun loadBitmapFromPath(path: String): Bitmap? = BitmapFactory.decodeFile(path)
