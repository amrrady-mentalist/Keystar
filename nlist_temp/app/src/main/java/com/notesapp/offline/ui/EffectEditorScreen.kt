package com.notesapp.offline.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notesapp.offline.data.EffectOut
import com.notesapp.offline.data.EffectType
import com.notesapp.offline.data.ForceListEngine
import com.notesapp.offline.data.MagicEffect
import com.notesapp.offline.data.MagicRepository
import com.notesapp.offline.data.Note
import com.notesapp.offline.ui.theme.AccentA
import com.notesapp.offline.ui.theme.AccentB
import com.notesapp.offline.ui.theme.Danger
import com.notesapp.offline.ui.theme.GlassRadius
import com.notesapp.offline.ui.theme.glassPanel
import kotlinx.coroutines.launch

/**
 * Effect editor — direct functional port of the web app's #effectEditor
 * panel: name, active toggle, Word/List Force type switch, and the
 * type-specific fields (outs list for Word, items list for List Force).
 * Every field autosaves on change, same as the web app's per-input
 * `saveMagic()` calls, rather than a single "Save" button.
 */
@Composable
fun EffectEditorScreen(
    repo: MagicRepository,
    notesViewModel: NotesViewModel,
    effectId: String,
    isDarkTheme: Boolean,
    onBack: () -> Unit,
    onOpenSketch: (outId: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val bgColor = if (isDarkTheme) Color.Black else Color.White
    val fgColor = if (isDarkTheme) Color.White else Color.Black

    var effect by remember { mutableStateOf<MagicEffect?>(null) }
    var itemsText by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(effectId) {
        val store = repo.load()
        effect = store.effects.firstOrNull { it.id == effectId }
        itemsText = effect?.items?.joinToString("\n") ?: ""
        loaded = true
    }

    fun persist(updated: MagicEffect) {
        effect = updated
        scope.launch { repo.updateEffect(updated) }
    }

    val current = effect
    if (!loaded || current == null) return

    // Peek and Math stay fixed singletons with fixed labels; Force List and
    // Multiple Outs are unlimited, user-created instances, so those get an
    // editable name (shown in the top bar) and a delete button instead.
    val isFixedSingleton = current.type == EffectType.INJECT_PEEK || current.type == EffectType.INJECT_SUM
    val screenTitle = when (current.type) {
        EffectType.INJECT_PEEK -> "Peek"
        EffectType.INJECT_SUM -> "Math"
        else -> if (current.name.isBlank()) {
            if (current.type == EffectType.LIST) "Force List" else "Multiple Outs"
        } else current.name
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
            // Without this, the keyboard just overlaps the bottom of the
            // screen instead of the layout shrinking to make room for it —
            // this is what was hiding the last field(s) until the keyboard
            // was dismissed.
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp).glassPanel(radius = GlassRadius.lg, tint = fgColor)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = fgColor)
            }
            Text(screenTitle, color = fgColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (isFixedSingleton) {
                Box(modifier = Modifier.size(40.dp))
            } else {
                IconButton(
                    onClick = {
                        // deleteEffect() is a suspend IO write; wait for it
                        // to finish before navigating back — firing
                        // onBack() right alongside scope.launch (instead of
                        // inside it) tore down this screen's
                        // rememberCoroutineScope and cancelled the delete
                        // before its file write completed, so the effect
                        // was still there when Magic Settings reloaded.
                        scope.launch {
                            repo.deleteEffect(current.id)
                            onBack()
                        }
                    },
                    modifier = Modifier.size(40.dp).glassPanel(radius = GlassRadius.lg, tint = fgColor)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = fgColor)
                }
            }
        }

        if (!isFixedSingleton) {
            BasicTextField(
                value = current.name,
                onValueChange = { persist(current.copy(name = it)) },
                textStyle = TextStyle(color = fgColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                cursorBrush = SolidColor(fgColor),
                decorationBox = { inner ->
                    if (current.name.isEmpty()) {
                        Text(
                            if (current.type == EffectType.LIST) "Name this list \u2014 e.g. \"Card trick\"" else "Name this effect",
                            color = fgColor.copy(alpha = 0.34f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    inner()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)
        ) {
            when (current.type) {
                EffectType.WORD -> {
                item {
                    FieldLabel("Note title", fgColor)
                    GlassInput(
                        value = current.title,
                        onValueChange = { persist(current.copy(title = it)) },
                        placeholder = "Title for the created note (optional)",
                        fgColor = fgColor
                    )

                    FieldLabel("Note body", fgColor, topPadding = 16.dp)
                    GlassInput(
                        value = current.body,
                        onValueChange = { persist(current.copy(body = it)) },
                        placeholder = "You will choose the word: \$\$\$\$",
                        fgColor = fgColor,
                        minHeight = 110.dp,
                        singleLine = false
                    )

                    FieldLabel("Outs \u2014 code \u2192 word or sketch", fgColor, topPadding = 16.dp)
                }

                items(current.outs, key = { it.id }) { out ->
                    OutRow(
                        out = out,
                        fgColor = fgColor,
                        onCodeChange = { newCode ->
                            val digits = newCode.filter { it.isDigit() }
                            persist(current.copy(outs = current.outs.map { if (it.id == out.id) it.copy(code = digits) else it }))
                        },
                        onWordChange = { newWord ->
                            persist(current.copy(outs = current.outs.map { if (it.id == out.id) it.copy(word = newWord) else it }))
                        },
                        onRemove = {
                            persist(current.copy(outs = current.outs.filterNot { it.id == out.id }))
                        },
                        onAddSketch = { onOpenSketch(out.id) },
                        onRemoveSketch = {
                            persist(current.copy(outs = current.outs.map { if (it.id == out.id) it.copy(drawingPngBase64 = null) else it }))
                        }
                    )
                }

                item {
                    GlassTextPill("+ Add out", fgColor, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)) {
                        persist(current.copy(outs = current.outs + EffectOut()))
                    }

                    Text(
                        "--value-- substitutes automatically whenever the PIN reveals this note \u2014 no toggle needed for that. \"Send to Inject\" below is separate: it sends whatever word $$$$ resolved to (or was hand-edited to) back out to your API.",
                        color = fgColor.copy(alpha = 0.34f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    FieldLabel("Inject", fgColor)
                    DirectionToggleRow(
                        label = "Send to Inject",
                        sublabel = "Reads whatever's on screen and posts it to your Inject API when the trigger below fires.",
                        checked = current.injectSendOn,
                        fgColor = fgColor,
                        onToggle = { persist(current.copy(injectSendOn = it)) }
                    )
                    if (current.injectSendOn) {
                        FieldLabel("Trigger", fgColor, topPadding = 10.dp)
                        TriggerToggleRow(
                            label = "Proximity sensor",
                            sublabel = "Wave a hand over it, or set the phone face down / against a chest",
                            checked = current.sendUseProximity,
                            fgColor = fgColor,
                            onToggle = { persist(current.copy(sendUseProximity = it)) }
                        )
                        TriggerToggleRow(
                            label = "Volume button",
                            sublabel = "Press either volume button \u2014 it won't actually change the volume while armed",
                            checked = current.sendUseVolumeButton,
                            fgColor = fgColor,
                            onToggle = { persist(current.copy(sendUseVolumeButton = it)) },
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                    }
                }
                }
                EffectType.LIST -> {
                item {
                    Text(
                        "Force List only ever receives from Inject. Set the Force item to \u2013\u2013value\u2013\u2013 and whatever Inject returns is placed straight into the encoded position \u2014 it doesn't need to already be one of the items below. Otherwise, the Force item must match one of the items below exactly, and that item gets moved into position.",
                        color = fgColor.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    FieldLabel("Note title", fgColor)
                    GlassInput(
                        value = current.title,
                        onValueChange = { persist(current.copy(title = it)) },
                        placeholder = "Title for the created list (optional)",
                        fgColor = fgColor
                    )

                    FieldLabel("Force item", fgColor, topPadding = 16.dp)
                    GlassInput(
                        value = current.forceWord,
                        onValueChange = { persist(current.copy(forceWord = it)) },
                        placeholder = "Paste one of the items below, or \u2013\u2013value\u2013\u2013 to force whatever Inject returns",
                        fgColor = fgColor
                    )

                    val digits = ForceListEngine.codeDigits(current.items)
                    val nonBlank = ForceListEngine.actualItems(current.items).size
                    val hint = if (nonBlank == 0) {
                        "Enter PIN while active. Add items below to set this up."
                    } else {
                        "Reads the last $digits digits to move the force item into position (${"0".repeat(digits)} = last item)."
                    }
                    Text(hint, color = fgColor.copy(alpha = 0.34f), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))

                    FieldLabel("Items \u2014 up to 1000 (one per line)", fgColor, topPadding = 16.dp)
                    GlassInput(
                        value = itemsText,
                        onValueChange = { text ->
                            itemsText = text
                            persist(current.copy(items = text.split("\n")))
                        },
                        placeholder = "Item 1\nItem 2\nItem 3\n...",
                        fgColor = fgColor,
                        minHeight = 220.dp,
                        singleLine = false
                    )

                    val n = ForceListEngine.actualItems(itemsText.split("\n")).size
                    Text(
                        "$n " + (if (n == 1) "item" else "items"),
                        color = fgColor.copy(alpha = 0.34f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
                    )
                }
                }
                EffectType.INJECT_PEEK -> {
                item {
                    Text(
                        "Peek can send to Inject, receive from it, or both \u2014 same trigger either way.",
                        color = fgColor.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    FieldLabel("Inject", fgColor)
                    DirectionToggleRow(
                        label = "Send to Inject",
                        sublabel = "Reads whatever's on screen, as-is, and posts it \u2014 e.g. a name your spectator wrote down.",
                        checked = current.injectSendOn,
                        fgColor = fgColor,
                        onToggle = { persist(current.copy(injectSendOn = it)) }
                    )
                    DirectionToggleRow(
                        label = "Receive from Inject",
                        sublabel = "Empty note \u2192 fills it with the latest value. Note with \u2013\u2013value\u2013\u2013 in it \u2192 replaces just that token.",
                        checked = current.injectReceiveOn,
                        fgColor = fgColor,
                        onToggle = { persist(current.copy(injectReceiveOn = it)) },
                        modifier = Modifier.padding(top = 10.dp)
                    )

                    FieldLabel("Trigger", fgColor, topPadding = 20.dp)
                    TriggerToggleRow(
                        label = "Proximity sensor",
                        sublabel = "Wave a hand over it, or set the phone face down / against a chest",
                        checked = current.sendUseProximity,
                        fgColor = fgColor,
                        onToggle = { persist(current.copy(sendUseProximity = it)) }
                    )
                    TriggerToggleRow(
                        label = "Volume button",
                        sublabel = "Press either volume button \u2014 it won't actually change the volume while armed",
                        checked = current.sendUseVolumeButton,
                        fgColor = fgColor,
                        onToggle = { persist(current.copy(sendUseVolumeButton = it)) },
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
                }
                EffectType.INJECT_SUM -> {
                item {
                    Text(
                        "Math reads the numbers on screen (one per line), runs your equation, and can send the result to Inject, receive a value the same way Peek does, or both.",
                        color = fgColor.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    FieldLabel("Equation", fgColor)
                    GlassInput(
                        value = current.mathEquation,
                        onValueChange = { persist(current.copy(mathEquation = it)) },
                        placeholder = "Blank = sum every line. Or: (1st+2nd)-(3rd+4th)",
                        fgColor = fgColor
                    )
                    Text(
                        "Refer to each line by position \u2014 1st, 2nd, 3rd, 4th... \u2014 combined with + \u2212 \u00d7 \u00f7 and parentheses. Example: line 1 = 455, line 2 = 677, line 3 = 111, line 4 = 898 \u2014 \"(1st+2nd)-(3rd+4th)\" gives 123.",
                        color = fgColor.copy(alpha = 0.34f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
                    )

                    FieldLabel("Offline peek", fgColor)
                    Text(
                        "Tap the Bold (B) button on any note to show the equation's result in that note's title \u2014 no network, nothing sent. Tap it again to hide it. Works even with Inject Mode off, as long as Math is enabled.",
                        color = fgColor.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(bottom = 20.dp)
                    )

                    FieldLabel("Inject", fgColor)
                    DirectionToggleRow(
                        label = "Send to Inject",
                        sublabel = "Sends the equation's result to your Inject API when the trigger below fires \u2014 only when the note actually has numbers on it, so this stays quiet on notes meant for Peek instead.",
                        checked = current.injectSendOn,
                        fgColor = fgColor,
                        onToggle = { persist(current.copy(injectSendOn = it)) }
                    )
                    DirectionToggleRow(
                        label = "Receive from Inject",
                        sublabel = "Empty note \u2192 fills it with the latest value. Note with \u2013\u2013value\u2013\u2013 in it \u2192 replaces just that token.",
                        checked = current.injectReceiveOn,
                        fgColor = fgColor,
                        onToggle = { persist(current.copy(injectReceiveOn = it)) },
                        modifier = Modifier.padding(top = 10.dp)
                    )

                    FieldLabel("Trigger", fgColor, topPadding = 20.dp)
                    TriggerToggleRow(
                        label = "Proximity sensor",
                        sublabel = "Wave a hand over it, or set the phone face down / against a chest",
                        checked = current.sendUseProximity,
                        fgColor = fgColor,
                        onToggle = { persist(current.copy(sendUseProximity = it)) }
                    )
                    TriggerToggleRow(
                        label = "Volume button",
                        sublabel = "Press either volume button \u2014 it won't actually change the volume while armed",
                        checked = current.sendUseVolumeButton,
                        fgColor = fgColor,
                        onToggle = { persist(current.copy(sendUseVolumeButton = it)) },
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
                }
            }
        }
    }
}

@Composable
private fun DirectionToggleRow(
    label: String,
    sublabel: String,
    checked: Boolean,
    fgColor: Color,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) = TriggerToggleRow(label, sublabel, checked, fgColor, onToggle, modifier)

@Composable
private fun TriggerToggleRow(
    label: String,
    sublabel: String,
    checked: Boolean,
    fgColor: Color,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(radius = GlassRadius.sm, tint = fgColor)
            .clickable { onToggle(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = fgColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                sublabel,
                color = fgColor.copy(alpha = 0.4f),
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Box(
            modifier = Modifier
                .padding(start = 12.dp)
                .size(width = 44.dp, height = 26.dp)
                .clip(RoundedCornerShape(100))
                .background(
                    if (checked) androidx.compose.ui.graphics.Brush.linearGradient(listOf(AccentA, AccentB))
                    else androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(fgColor.copy(alpha = 0.16f), fgColor.copy(alpha = 0.16f))
                    )
                )
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (checked) Color(0xFF0A0A12) else fgColor.copy(alpha = 0.7f))
            )
        }
    }
    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
private fun TypePill(label: String, selected: Boolean, fgColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .then(
                if (selected) {
                    Modifier
                        .clip(RoundedCornerShape(100))
                        .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(AccentA, AccentB)))
                } else {
                    Modifier.glassPanel(radius = 100.dp, tint = fgColor)
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (selected) Color(0xFF0A0A12) else fgColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FieldLabel(text: String, fgColor: Color, topPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(
        text.uppercase(),
        color = fgColor.copy(alpha = 0.56f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = Modifier.padding(top = topPadding, bottom = 10.dp)
    )
}

@Composable
private fun GlassInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    fgColor: Color,
    minHeight: androidx.compose.ui.unit.Dp = 0.dp,
    singleLine: Boolean = true
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .glassPanel(radius = GlassRadius.sm, tint = fgColor)
            // For multi-line fields, the BasicTextField itself only
            // measures as tall as its current text — one line when empty —
            // so most of this visually tall box wasn't actually clickable.
            // This catches taps anywhere else in the box and focuses the
            // field directly; taps that land on the field's own (small)
            // bounds are handled by it first, same as before.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                focusRequester.requestFocus()
                keyboardController?.show()
            }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = fgColor.copy(alpha = 0.34f), fontSize = 15.sp, lineHeight = 24.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(color = fgColor, fontSize = 15.sp, lineHeight = 24.sp),
            cursorBrush = SolidColor(fgColor),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
        )
    }
}

@Composable
fun GlassTextPill(label: String, fgColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .glassPanel(radius = 100.dp, tint = fgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label, color = fgColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OutRow(
    out: EffectOut,
    fgColor: Color,
    onCodeChange: (String) -> Unit,
    onWordChange: (String) -> Unit,
    onRemove: () -> Unit,
    onAddSketch: () -> Unit,
    onRemoveSketch: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .width(62.dp)
                    .glassPanel(radius = GlassRadius.sm, tint = fgColor)
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicTextField(
                    value = out.code,
                    onValueChange = onCodeChange,
                    singleLine = true,
                    textStyle = TextStyle(color = fgColor, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    cursorBrush = SolidColor(fgColor),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text("\u2192", color = fgColor.copy(alpha = 0.5f), fontSize = 16.sp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .glassPanel(radius = GlassRadius.sm, tint = fgColor)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                if (out.word.isEmpty()) {
                    Text("Word (optional)", color = fgColor.copy(alpha = 0.34f), fontSize = 14.sp)
                }
                BasicTextField(
                    value = out.word,
                    onValueChange = onWordChange,
                    singleLine = true,
                    textStyle = TextStyle(color = fgColor, fontSize = 14.sp),
                    cursorBrush = SolidColor(fgColor),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Remove out", tint = fgColor.copy(alpha = 0.6f))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 10.dp)
        ) {
            val bmp = remember(out.drawingPngBase64) { out.drawingPngBase64?.let { decodeBase64ToBitmap(it) } }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF111111)),
                contentAlignment = Alignment.Center
            ) {
                if (bmp != null) {
                    Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                } else {
                    PencilGlyphIcon(fgColor.copy(alpha = 0.7f))
                }
            }
            Text(
                if (out.drawingPngBase64 != null) "Edit sketch" else "Add sketch",
                color = AccentB,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onAddSketch)
            )
            if (out.drawingPngBase64 != null) {
                Text(
                    "Remove sketch",
                    color = Danger,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = onRemoveSketch)
                )
            }
        }

        androidx.compose.material3.HorizontalDivider(
            color = fgColor.copy(alpha = 0.14f),
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}

@Composable
private fun PencilGlyphIcon(tint: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 1.4.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
            join = androidx.compose.ui.graphics.StrokeJoin.Round
        )
        val outline = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.08f, h * 0.92f)
            lineTo(w * 0.219f, h * 0.602f)
            lineTo(w * 0.741f, h * 0.08f)
            lineTo(w * 0.92f, h * 0.259f)
            lineTo(w * 0.398f, h * 0.781f)
            close()
        }
        drawPath(outline, color = tint, style = stroke)
    }
}
