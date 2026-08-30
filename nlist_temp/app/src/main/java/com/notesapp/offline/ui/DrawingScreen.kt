package com.notesapp.offline.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notesapp.offline.ui.theme.BlobAmber
import com.notesapp.offline.ui.theme.BlobRose
import com.notesapp.offline.ui.theme.BlobTeal
import com.notesapp.offline.ui.theme.BlobViolet
import java.io.ByteArrayOutputStream

private data class DrawnStroke(val points: List<Offset>, val color: Color, val widthPx: Float)

private val palette = listOf(
    Color.White,
    Color.Black,
    BlobViolet,
    BlobRose,
    BlobTeal,
    BlobAmber,
    Color(0xFFFF6B6B),
    Color(0xFFFF9F45),
    Color(0xFFFFE45E),
    Color(0xFF4ADE80),
    Color(0xFF38BDF8),
    Color(0xFF6366F1),
    Color(0xFFE879F9),
    Color(0xFFA3A3A3)
)

private fun decodeBitmap(base64: String) = runCatching {
    val bytes = Base64.decode(base64, Base64.NO_WRAP)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

@Composable
fun DrawingScreen(
    initialPngBase64: String?,
    isDarkTheme: Boolean,
    onSave: (String?) -> Unit,
    onBack: () -> Unit
) {
    val bgColor = if (isDarkTheme) Color.Black else Color.White
    val fgColor = if (isDarkTheme) Color.White else Color.Black
    val accent = MaterialTheme.colorScheme.primary

    val strokes = remember { mutableStateOf(emptyList<DrawnStroke>()) }
    val redoStack = remember { mutableStateOf(emptyList<DrawnStroke>()) }
    var backgroundCleared by remember { mutableStateOf(false) }
    val backgroundBitmap = remember(initialPngBase64) { initialPngBase64?.let { decodeBitmap(it) } }

    var currentColor by remember { mutableStateOf(if (isDarkTheme) Color.White else Color.Black) }
    var brushSize by remember { mutableFloatStateOf(8f) }
    var canvasSizePx by remember { mutableStateOf(Offset(1f, 1f)) }

    // Back arrow behaves exactly like the checkmark — always save on exit,
    // no separate "discard" path. If there's nothing to show (no strokes,
    // and either no background or it was explicitly cleared via the trash
    // button) this reports null instead of rasterizing a blank canvas, so
    // the note goes back to having no drawing at all rather than a solid
    // blank thumbnail that still displays a preview.
    fun saveAndExit() {
        val hasContent = strokes.value.isNotEmpty() || (!backgroundCleared && backgroundBitmap != null)
        if (!hasContent) {
            onSave(null)
            return
        }
        val bitmap = rasterize(
            strokes = strokes.value,
            size = canvasSizePx,
            background = if (backgroundCleared) null else backgroundBitmap
        )
        onSave(bitmapToBase64(bitmap))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
    ) {
        // Matches the on-screen back arrow: system back gesture also saves
        // and returns to the note, rather than skipping past it to the list.
        BackHandler(onBack = { saveAndExit() })

        // Minimal top row, matching NoteEditScreen — no separate AppBar tint.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { saveAndExit() }) {
                BackArrowIcon(tint = fgColor)
            }
            Text("Sketch", color = fgColor, fontSize = 18.sp)
            Row {
                IconButton(onClick = {
                    redoStack.value = emptyList()
                    strokes.value = emptyList()
                    backgroundCleared = true
                }) {
                    TrashIcon(tint = fgColor)
                }
                IconButton(onClick = {
                    if (strokes.value.isNotEmpty()) {
                        redoStack.value = redoStack.value + strokes.value.last()
                        strokes.value = strokes.value.dropLast(1)
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = fgColor)
                }
                IconButton(onClick = {
                    if (redoStack.value.isNotEmpty()) {
                        strokes.value = strokes.value + redoStack.value.last()
                        redoStack.value = redoStack.value.dropLast(1)
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = fgColor)
                }
                IconButton(onClick = { saveAndExit() }) {
                    CheckIcon(tint = fgColor)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(bgColor)
                .pointerInput(Unit) {
                    var current = mutableListOf<Offset>()
                    detectDragGestures(
                        onDragStart = { offset ->
                            current = mutableListOf(offset)
                            redoStack.value = emptyList() // new stroke invalidates redo history
                            strokes.value = strokes.value + DrawnStroke(current.toList(), currentColor, brushSize)
                        },
                        onDrag = { change, _ ->
                            current.add(change.position)
                            strokes.value = strokes.value.dropLast(1) +
                                DrawnStroke(current.toList(), currentColor, brushSize)
                        },
                        onDragEnd = {
                            if (current.isNotEmpty()) {
                                strokes.value = strokes.value.dropLast(1) +
                                    DrawnStroke(current.toList(), currentColor, brushSize)
                            }
                        }
                    )
                }
        ) {
            if (!backgroundCleared && backgroundBitmap != null) {
                Image(
                    bitmap = backgroundBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Canvas(modifier = Modifier.fillMaxSize()) {
                canvasSizePx = Offset(size.width, size.height)
                strokes.value.forEach { stroke ->
                    if (stroke.points.size < 2) return@forEach
                    val path = Path().apply {
                        moveTo(stroke.points.first().x, stroke.points.first().y)
                        stroke.points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(
                        path = path,
                        color = stroke.color,
                        style = Stroke(width = stroke.widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            palette.forEach { c ->
                val selected = c == currentColor
                Box(
                    modifier = Modifier
                        .size(if (selected) 38.dp else 32.dp)
                        .clip(CircleShape)
                        .then(
                            if (selected) Modifier.border(2.dp, accent, CircleShape).padding(3.dp)
                            else Modifier
                        )
                        .clip(CircleShape)
                        .background(c)
                        .then(
                            if (c == bgColor) Modifier.border(1.dp, fgColor.copy(alpha = 0.3f), CircleShape)
                            else Modifier
                        )
                        .clickable { currentColor = c }
                )
            }
        }
        Slider(
            value = brushSize,
            onValueChange = { brushSize = it },
            valueRange = 2f..40f,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )
    }
}

/**
 * Rasterizes just the strokes (plus a copied-in background photo, if any)
 * onto a bitmap — deliberately with NO solid color fill behind them when
 * there's no photo background. ARGB_8888 bitmaps start fully transparent,
 * so leaving that alone means the saved PNG carries real transparency in
 * every area that was never drawn on.
 *
 * This used to fill the empty canvas with whatever the app's bgColor was
 * AT SAVE TIME (dark or light), baking that color permanently into the
 * PNG's pixels. The result: a drawing made in dark mode stayed on a black
 * background forever, even after switching the whole app to light theme,
 * because by the time it was reopened the "background" wasn't the live
 * theme color anymore — it was solid black pixels baked into the image
 * itself. Every screen that displays a saved drawing (this screen, the
 * note editor's preview, the notes list thumbnail) already renders it on
 * top of a themed background, so leaving the PNG transparent here is all
 * that's needed for it to pick up whichever theme is active when viewed.
 */
private fun rasterize(strokes: List<DrawnStroke>, size: Offset, background: Bitmap?): Bitmap {
    val w = size.x.toInt().coerceAtLeast(1)
    val h = size.y.toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)
    if (background != null) {
        val src = android.graphics.Rect(0, 0, background.width, background.height)
        val dst = android.graphics.Rect(0, 0, w, h)
        canvas.drawBitmap(background, src, dst, null)
    }
    // else: leave the bitmap as-is — fully transparent, no baked-in fill.
    val paint = AndroidPaint().apply {
        isAntiAlias = true
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
    }
    strokes.forEach { stroke ->
        if (stroke.points.size < 2) return@forEach
        paint.color = android.graphics.Color.argb(
            (stroke.color.alpha * 255).toInt(),
            (stroke.color.red * 255).toInt(),
            (stroke.color.green * 255).toInt(),
            (stroke.color.blue * 255).toInt()
        )
        paint.strokeWidth = stroke.widthPx
        val path = android.graphics.Path()
        path.moveTo(stroke.points.first().x, stroke.points.first().y)
        stroke.points.drop(1).forEach { path.lineTo(it.x, it.y) }
        canvas.drawPath(path, paint)
    }
    return bitmap
}

private fun bitmapToBase64(bitmap: Bitmap): String {
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}

// --- Small hand-drawn icons, avoiding any external icon-library risk ---

@Composable
private fun BackArrowIcon(tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.75f, h * 0.15f)
            lineTo(w * 0.3f, h * 0.5f)
            lineTo(w * 0.75f, h * 0.85f)
        }
        drawPath(path, color = tint, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun CheckIcon(tint: Color) {
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.18f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.75f)
            lineTo(w * 0.85f, h * 0.25f)
        }
        drawPath(path, color = tint, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun TrashIcon(tint: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        // Lid
        drawLine(tint, Offset(w * 0.15f, h * 0.28f), Offset(w * 0.85f, h * 0.28f), strokeWidth = stroke.width)
        drawLine(tint, Offset(w * 0.38f, h * 0.28f), Offset(w * 0.42f, h * 0.12f), strokeWidth = stroke.width)
        drawLine(tint, Offset(w * 0.62f, h * 0.28f), Offset(w * 0.58f, h * 0.12f), strokeWidth = stroke.width)
        drawLine(tint, Offset(w * 0.42f, h * 0.12f), Offset(w * 0.58f, h * 0.12f), strokeWidth = stroke.width)
        // Body
        val bodyPath = Path().apply {
            moveTo(w * 0.22f, h * 0.32f)
            lineTo(w * 0.28f, h * 0.88f)
            lineTo(w * 0.72f, h * 0.88f)
            lineTo(w * 0.78f, h * 0.32f)
        }
        drawPath(bodyPath, color = tint, style = stroke)
        drawLine(tint, Offset(w * 0.4f, h * 0.42f), Offset(w * 0.42f, h * 0.78f), strokeWidth = stroke.width)
        drawLine(tint, Offset(w * 0.6f, h * 0.42f), Offset(w * 0.58f, h * 0.78f), strokeWidth = stroke.width)
    }
}

// Undo/redo now use the platform's own Icons.AutoMirrored.Filled.Undo/Redo
// (see the top bar above) rather than a hand-drawn arc — two attempts at
// drawing a custom curved-arrow glyph both came out visually off, so this
// swaps in the real, pre-tested icon instead of trying a third time.
