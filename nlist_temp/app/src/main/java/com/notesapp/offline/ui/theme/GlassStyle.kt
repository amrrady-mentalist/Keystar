package com.notesapp.offline.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object GlassRadius {
    val lg = 28.dp
    val md = 20.dp
    val sm = 14.dp
}

/**
 * The frosted-glass panel look used everywhere in the original app (cards,
 * the search bar, popovers, the PIN keypad backdrop): a translucent fill,
 * a soft highlight border, and a corner-to-corner light gradient sheen.
 * Compose's Modifier.blur() only blurs the content it's applied to, not
 * what's *behind* it (no real backdrop-filter equivalent without extra
 * plumbing), so the frosted effect here comes from layered translucency
 * plus the blurred blobs sitting behind everything — visually equivalent
 * against the void background this app always sits on.
 */
fun Modifier.glassPanel(
    radius: Dp = GlassRadius.md,
    tint: Color = Color.White,
    fill: Color = if (tint == Color.Black) Color(0xFFF2F2F7) else Color(0xFF1E1E22),
    borderColor: Color = Color.Transparent
): Modifier = this
    .clip(RoundedCornerShape(radius))
    .background(fill)
    .then(
        if (borderColor != Color.Transparent) Modifier.border(1.dp, borderColor, RoundedCornerShape(radius))
        else Modifier
    )

/**
 * The soft, out-of-focus color blobs that sit behind the whole app on a
 * black void background — the signature of the "liquid glass" look.
 * Purely decorative; render this once behind everything with Box's
 * default (bottom) z-order.
 */
@Composable
fun BlobBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .size(280.dp)
                .offset(x = (-60).dp, y = (-40).dp)
                .blur(90.dp)
                .background(BlobViolet.copy(alpha = 0.55f), RoundedCornerShape(50))
        )
        Box(
            Modifier
                .size(240.dp)
                .offset(x = 220.dp, y = 60.dp)
                .blur(90.dp)
                .background(BlobRose.copy(alpha = 0.45f), RoundedCornerShape(50))
        )
        Box(
            Modifier
                .size(260.dp)
                .offset(x = (-40).dp, y = 520.dp)
                .blur(100.dp)
                .background(BlobTeal.copy(alpha = 0.40f), RoundedCornerShape(50))
        )
        Box(
            Modifier
                .size(220.dp)
                .offset(x = 200.dp, y = 700.dp)
                .blur(90.dp)
                .background(BlobAmber.copy(alpha = 0.35f), RoundedCornerShape(50))
        )
    }
}

/** Maps a NoteColor to its swatch color for tag dots / editor picker. */
fun com.notesapp.offline.data.NoteColor.toComposeColor(): Color = when (this) {
    com.notesapp.offline.data.NoteColor.NONE -> Color.Transparent
    com.notesapp.offline.data.NoteColor.VIOLET -> BlobViolet
    com.notesapp.offline.data.NoteColor.ROSE -> BlobRose
    com.notesapp.offline.data.NoteColor.TEAL -> BlobTeal
    com.notesapp.offline.data.NoteColor.AMBER -> BlobAmber
    com.notesapp.offline.data.NoteColor.SKY -> Color(0xFF38BDF8)
    com.notesapp.offline.data.NoteColor.LIME -> Color(0xFF4ADE80)
    com.notesapp.offline.data.NoteColor.CORAL -> Color(0xFFFF6B6B)
}
