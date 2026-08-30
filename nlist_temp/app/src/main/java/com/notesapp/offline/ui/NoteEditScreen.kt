package com.notesapp.offline.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notesapp.offline.data.ChecklistItem
import com.notesapp.offline.data.CovertSessionState
import com.notesapp.offline.data.CovertTypingConfig
import com.notesapp.offline.data.CovertTypingEngine
import com.notesapp.offline.data.DeletePeekConfig
import com.notesapp.offline.data.DeletePeekMemory
import com.notesapp.offline.data.EffectType
import com.notesapp.offline.data.InjectApiClient
import com.notesapp.offline.data.MagicEffect
import com.notesapp.offline.data.MagicRepository
import com.notesapp.offline.data.MathEquationEngine
import com.notesapp.offline.data.Note
import com.notesapp.offline.data.NoteColor
import com.notesapp.offline.ui.theme.AccentA
import com.notesapp.offline.ui.theme.RunsVisualTransformation
import com.notesapp.offline.ui.theme.applyEditToRuns
import com.notesapp.offline.ui.theme.computeTextEdit
import com.notesapp.offline.ui.theme.toComposeColor
import kotlinx.coroutines.launch

/** Package-visible (not file-private) so other screens in this package —
 *  e.g. the effect editor's out-sketch thumbnails — can reuse it too. */
fun decodeBase64ToBitmap(base64: String) = runCatching {
    val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

/**
 * Handles create (noteId == null), plain-text edit, checklist edit, and the
 * drawing entry point. No top app bar, no bordered fields — everything
 * blends into the solid theme background. The formatting/checklist/sketch
 * toolbar is the LAST item in the main Column (real layout space, not an
 * overlay), so content never gets hidden behind it; imePadding() on the
 * whole Column means it rides up together with the keyboard and eases back
 * down when it closes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteEditScreen(
    viewModel: NotesViewModel,
    magicRepo: MagicRepository,
    noteId: String?,
    isDarkTheme: Boolean,
    onBack: () -> Unit,
    onOpenDrawing: (String) -> Unit
) {
    val existing = remember(noteId) { noteId?.let { viewModel.getNote(it) } }
    var current by remember(noteId) { mutableStateOf(existing ?: Note()) }
    var everPersisted by remember(noteId) { mutableStateOf(existing != null) }
    var showColorPicker by remember { mutableStateOf(false) }
    var bodyField by remember(noteId) { mutableStateOf(TextFieldValue(existing?.body ?: "")) }
    var activeBold by remember(noteId) { mutableStateOf(false) }
    var activeItalic by remember(noteId) { mutableStateOf(false) }
    var activeUnderline by remember(noteId) { mutableStateOf(false) }
    var isAaExpanded by remember { mutableStateOf(false) }
    var isPlusExpanded by remember { mutableStateOf(false) }

    fun dismissSubBars() {
        isAaExpanded = false
        isPlusExpanded = false
    }

    val bgColor = if (isDarkTheme) Color.Black else Color.White
    val fgColor = if (isDarkTheme) Color.White else Color.Black

    // ---- Inject send/receive trigger arming ----
    // Whichever Inject-capable effect governs THIS note gets checked once
    // per note opened — Peek and Math (Sum) can each independently arm
    // their own send and/or receive behavior; Multiple Outs (WORD) can
    // additionally arm a send-only trigger. If more than one is enabled at
    // once, the note's own magicEffectId link wins; otherwise Peek takes
    // priority over Math over Multiple Outs, since only one physical
    // trigger (proximity/volume) fires per note session.

    fun isEmpty(note: Note) =
        note.title.isBlank() && note.body.isBlank() && note.checklist.isEmpty() && note.drawingPngBase64 == null

    fun persist(note: Note) {
        current = note
        if (isEmpty(note)) return // don't write a completely empty note to disk
        everPersisted = true
        viewModel.save(note)
    }

    /** If this note ended up empty — either it never had content, or had
     *  content that got fully deleted — remove it instead of leaving a
     *  blank entry behind. Covers both a fresh note that's still blank
     *  (never persisted, nothing to delete) and one that was typed into
     *  and then fully cleared (was persisted, needs an actual delete). */
    fun handleBack() {
        if (isEmpty(current) && everPersisted) {
            viewModel.delete(current.id)
        }
        onBack()
    }

    // ---- Inject send/receive trigger arming & effect states ----
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val injectApiClient = remember { InjectApiClient() }
    var peekEffect by remember(noteId) { mutableStateOf<MagicEffect?>(null) }
    var mathEffect by remember(noteId) { mutableStateOf<MagicEffect?>(null) }
    var wordSendEffect by remember(noteId) { mutableStateOf<MagicEffect?>(null) }
    var covertConfig by remember(noteId) { mutableStateOf(CovertTypingConfig()) }
    val covertState = remember(noteId) { CovertSessionState() }
    var deletePeekConfig by remember(noteId) { mutableStateOf(DeletePeekConfig()) }
    var apiUrl by remember(noteId) { mutableStateOf<String?>(null) }
    var injectModeOn by remember(noteId) { mutableStateOf(false) }
    var mathPeekOn by remember(noteId) { mutableStateOf(false) }
    var titleBeforeMathPeek by remember(noteId) { mutableStateOf<String?>(null) }

    LaunchedEffect(noteId) {
        val store = magicRepo.load()
        injectModeOn = store.injectModeOn
        apiUrl = store.apiUrl
        covertConfig = store.covertTyping
        deletePeekConfig = store.deletePeek
        peekEffect = store.effectOfType(EffectType.INJECT_PEEK)?.takeIf { it.enabled }
        mathEffect = store.effectOfType(EffectType.INJECT_SUM)?.takeIf { it.enabled }
        // Multiple Outs can have many instances, but at most one is ever
        // enabled at a time (enforced where it's toggled on), so this
        // still resolves to a single effect.
        wordSendEffect = store.effects.firstOrNull { it.type == EffectType.WORD && it.enabled && it.injectSendOn }
    }

    /** Routes every body-text mutation (typing or toolbar-triggered) through the same
     *  diff engine so styleRuns always stay correctly shifted, regardless of source. */
    fun updateBody(newValue: TextFieldValue, applyActiveStyle: Boolean = true, processCovert: Boolean = true) {
        // Track deletions for Delete Peek
        val oldText = bodyField.text
        val newText = newValue.text
        if (oldText != newText) {
            val edit = computeTextEdit(oldText, newText)
            DeletePeekMemory.recordEdit(oldText, newText, edit.start, edit.oldEnd, edit.newEnd)
        }

        val processedValue = if (processCovert && covertConfig.enabled && (covertState.isArmed || covertState.hasCapturedWord)) {
            CovertTypingEngine.processEdit(
                oldValue = bodyField,
                newValue = newValue,
                config = covertConfig,
                state = covertState,
                onWordCaptured = { secretWord ->
                    val url = apiUrl
                    if (covertConfig.sendToInject && injectModeOn && !url.isNullOrBlank()) {
                        scope.launch { injectApiClient.sendValue(url, secretWord) }
                    }
                }
            )
        } else {
            newValue
        }

        val newRuns = applyEditToRuns(
            current.styleRuns,
            bodyField.text,
            processedValue.text,
            activeBold = applyActiveStyle && activeBold,
            activeItalic = applyActiveStyle && activeItalic,
            activeUnderline = applyActiveStyle && activeUnderline
        )
        bodyField = processedValue
        persist(current.copy(body = processedValue.text, styleRuns = newRuns))
    }

    // rememberUpdatedState so the sensor/volume callbacks below (set up
    // once by the DisposableEffect and not recreated on every keystroke)
    // always read the LATEST note body when they actually fire, rather
    // than whatever it was at the moment the listener was registered.
    val latestBody by rememberUpdatedState(bodyField.text)

    fun sendMath() {
        val fx = mathEffect ?: return
        val url = apiUrl
        if (!injectModeOn || url.isNullOrBlank() || !fx.injectSendOn) return
        val values = MathEquationEngine.lineValues(latestBody)
        // No numbers on this note at all — it isn't a Math note right now
        // (could just be a Peek-style name, or empty), so stay quiet
        // instead of sending a meaningless "0".
        if (values.isEmpty()) return
        val total = MathEquationEngine.evaluate(fx.mathEquation, values) ?: return
        scope.launch { injectApiClient.sendValue(url, total.toString()) }
    }

    fun sendPeek() {
        val fx = peekEffect ?: return
        val url = apiUrl
        if (!injectModeOn || url.isNullOrBlank() || !fx.injectSendOn) return
        val value = latestBody.trim()
        if (value.isBlank()) return
        scope.launch { injectApiClient.sendValue(url, value) }
    }

    fun sendWord() {
        val fx = wordSendEffect ?: return
        val url = apiUrl
        if (!injectModeOn || url.isNullOrBlank()) return
        val value = latestBody.trim()
        if (value.isBlank()) return
        scope.launch { injectApiClient.sendValue(url, value) }
    }

    fun sendDeletePeek() {
        if (!deletePeekConfig.enabled) return
        val deletedWord = DeletePeekMemory.lastDeletedWord
        if (deletedWord.isBlank()) return

        if (deletePeekConfig.localPushNotification) {
            DeletePeekMemory.showPushNotification(context, deletedWord)
        }
        val url = apiUrl
        if (deletePeekConfig.sendToInject && injectModeOn && !url.isNullOrBlank()) {
            scope.launch { injectApiClient.sendValue(url, deletedWord) }
        }
    }

    /** Fetches the latest Inject value and drops it into this note — empty
     *  note gets it as-is, a note holding --value-- gets just that token
     *  swapped, anything else is left alone. Returns whether it actually
     *  changed the note, so a second receiver checking right after can
     *  tell the note's no longer blank/token-bearing and skip. */
    suspend fun receiveInto(fx: MagicEffect?): Boolean {
        if (fx == null) return false
        val url = apiUrl
        if (!injectModeOn || url.isNullOrBlank() || !fx.injectReceiveOn) return false
        val bodyText = bodyField.text
        if (bodyText.isNotBlank() && !bodyText.contains(INJECT_VALUE_TOKEN)) return false

        val value = injectApiClient.fetchValue(url) ?: return false
        val resolvedText = if (bodyText.isBlank()) value else bodyText.replace(INJECT_VALUE_TOKEN, value)
        updateBody(TextFieldValue(resolvedText, selection = androidx.compose.ui.text.TextRange(resolvedText.length)), applyActiveStyle = false)
        return true
    }

    fun fireInjectTrigger() {
        // Receive first, Math then Peek, sequentially — so if Math fills a
        // blank note, Peek's own check right after sees it's no longer
        // blank and skips, instead of both racing to fill the same note.
        scope.launch {
            receiveInto(mathEffect)
            receiveInto(peekEffect)
        }
        sendMath()
        sendPeek()
        sendWord()
        sendDeletePeek()
    }

    DisposableEffect(peekEffect, mathEffect, wordSendEffect, deletePeekConfig, injectModeOn, apiUrl) {
        val effects = listOfNotNull(peekEffect, mathEffect, wordSendEffect)
        val eligible = injectModeOn && !apiUrl.isNullOrBlank() &&
            effects.any { it.injectSendOn || it.injectReceiveOn }

        val deletePeekActive = deletePeekConfig.enabled
        val useProximity = (eligible && effects.any { it.sendUseProximity }) ||
            (deletePeekActive && deletePeekConfig.triggerProximity)
        val useVolume = (eligible && effects.any { it.sendUseVolumeButton }) ||
            (deletePeekActive && deletePeekConfig.triggerVolumeButton)

        var sensorManager: SensorManager? = null
        var proximityListener: SensorEventListener? = null

        if (useProximity) {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            if (proximitySensor != null) {
                var wasFar = true
                proximityListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val near = event.values.isNotEmpty() && event.values[0] < proximitySensor.maximumRange
                        // Only fire on the far->near transition (a wave, or
                        // setting the phone face down/against a chest) —
                        // not continuously while it stays covered.
                        if (near && wasFar) fireInjectTrigger()
                        wasFar = !near
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sensorManager.registerListener(proximityListener, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }

        if (useVolume) {
            VolumeTriggerBus.arm { fireInjectTrigger() }
        } else {
            VolumeTriggerBus.disarm()
        }

        onDispose {
            proximityListener?.let { sensorManager?.unregisterListener(it) }
            VolumeTriggerBus.disarm()
        }
    }

    val isImeOpen = WindowInsets.isImeVisible

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
            .imePadding()
    ) {
        // Intercepts the system back gesture too, not just the on-screen
        // arrow, so an emptied-out note gets cleaned up either way.
        BackHandler(onBack = {
            if (isAaExpanded || isPlusExpanded) {
                dismissSubBars()
            } else {
                handleBack()
            }
        })

        // Minimal top row — no AppBar chrome, just icons on the solid background.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { handleBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = fgColor)
            }
            Box(modifier = Modifier.weight(1f))
            if (existing != null) {
                Text(
                    text = if (current.archived) "Unarchive" else "Archive",
                    color = fgColor,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(horizontal = 10.dp)
                        .clickable {
                            viewModel.toggleArchive(current.id)
                            onBack()
                        }
                )
                IconButton(onClick = {
                    viewModel.delete(current.id)
                    onBack()
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = fgColor)
                }
            }
        }

        if (showColorPicker) {
            ColorPickerRow(
                selected = current.color,
                fgColor = fgColor,
                onSelect = {
                    showColorPicker = false
                    persist(current.copy(color = it))
                }
            )
        }

        // Drawing preview + body/checklist share a single scroll region, so
        // scrolling to read/write the note also scrolls past the preview.
        val bodyScrollState = rememberScrollState()
        val density = LocalDensity.current
        val maxImageHeight = 260.dp
        val minImageHeight = 64.dp
        val imageHeight = if (current.drawingPngBase64 != null) {
            with(density) {
                val maxPx = maxImageHeight.toPx()
                val minPx = minImageHeight.toPx()
                (maxPx - bodyScrollState.value).coerceIn(minPx, maxPx).toDp()
            }
        } else 0.dp

        var isAaExpanded by remember { mutableStateOf(false) }
        var isPlusExpanded by remember { mutableStateOf(false) }
        val dismissSubBars = {
            if (isAaExpanded || isPlusExpanded) {
                isAaExpanded = false
                isPlusExpanded = false
            }
        }

        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        var textFieldTopInColumn by remember { mutableFloatStateOf(0f) }

        // Automatically ensure the line currently being typed/cursor position is visible above keyboard and floating toolbar
        LaunchedEffect(bodyField.selection, bodyField.text, textLayoutResult, isImeOpen) {
            val layout = textLayoutResult ?: return@LaunchedEffect
            val cursorOffset = bodyField.selection.end.coerceIn(0, bodyField.text.length)
            val lineIndex = layout.getLineForOffset(cursorOffset)
            val lineBottom = layout.getLineBottom(lineIndex)
            val lineTop = layout.getLineTop(lineIndex)

            val cursorBottomInScroll = textFieldTopInColumn + lineBottom
            val cursorTopInScroll = textFieldTopInColumn + lineTop

            val viewportHeight = bodyScrollState.viewportSize
            if (viewportHeight > 0) {
                // Floating toolbar height plus comfortable padding
                val bottomToolbarHeightPx = with(density) { 76.dp.toPx() }
                val visibleBottom = bodyScrollState.value + viewportHeight - bottomToolbarHeightPx
                val visibleTop = bodyScrollState.value

                if (cursorBottomInScroll > visibleBottom) {
                    val target = (cursorBottomInScroll - viewportHeight + bottomToolbarHeightPx + with(density) { 24.dp.toPx() }).toInt()
                    bodyScrollState.animateScrollTo(target.coerceIn(0, bodyScrollState.maxValue))
                } else if (cursorTopInScroll < visibleTop) {
                    val target = (cursorTopInScroll - with(density) { 16.dp.toPx() }).toInt()
                    bodyScrollState.animateScrollTo(target.coerceIn(0, bodyScrollState.maxValue))
                }
            }
        }

        // Main content area with truly floating toolbar overlaid on top
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val bodyFocusRequester = remember { FocusRequester() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        dismissSubBars()
                        if (!current.isChecklist) bodyFocusRequester.requestFocus()
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(bodyScrollState)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            dismissSubBars()
                            if (!current.isChecklist) bodyFocusRequester.requestFocus()
                        }
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        if (current.title.isEmpty()) {
                            Text("Title", color = fgColor.copy(alpha = 0.32f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                        BasicTextField(
                            value = current.title,
                            onValueChange = {
                                dismissSubBars()
                                if (current.title != it) {
                                    val edit = computeTextEdit(current.title, it)
                                    DeletePeekMemory.recordEdit(current.title, it, edit.start, edit.oldEnd, edit.newEnd)
                                }
                                persist(current.copy(title = it))
                            },
                            singleLine = true,
                            textStyle = TextStyle(color = fgColor, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                            cursorBrush = SolidColor(fgColor),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    current.drawingPngBase64?.let { b64 ->
                        val bmp = remember(b64) { decodeBase64ToBitmap(b64) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Drawing",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(imageHeight)
                                    .padding(top = 12.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        dismissSubBars()
                                        onOpenDrawing(current.id)
                                    }
                            )
                        }
                    }

                    if (current.isChecklist) {
                        ChecklistEditor(
                            items = current.checklist,
                            fgColor = fgColor,
                            onChange = {
                                dismissSubBars()
                                persist(current.copy(checklist = it))
                            }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (bodyField.text.isEmpty()) {
                                Text("Note...", color = fgColor.copy(alpha = 0.28f), fontSize = 16.sp, modifier = Modifier.padding(top = 8.dp))
                            }
                            BasicTextField(
                                value = bodyField,
                                onValueChange = {
                                    dismissSubBars()
                                    updateBody(it)
                                },
                                onTextLayout = { textLayoutResult = it },
                                visualTransformation = RunsVisualTransformation(current.styleRuns),
                                textStyle = TextStyle(color = fgColor, fontSize = 16.sp, lineHeight = 25.sp),
                                cursorBrush = SolidColor(fgColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 200.dp)
                                    .padding(top = 8.dp, bottom = 24.dp)
                                    .focusRequester(bodyFocusRequester)
                                    .onGloballyPositioned { coords ->
                                        textFieldTopInColumn = coords.positionInParent().y
                                    }
                            )
                        }
                    }

                    // Spacer at the bottom so the last item in a long list can scroll above the floating pill and keyboard
                    Spacer(modifier = Modifier.height(140.dp))
                }
            }

            RichTextToolbar(
                showFormatting = !current.isChecklist,
                isChecklist = current.isChecklist,
                isBoldActive = activeBold,
                isItalicActive = activeItalic,
                isUnderlineActive = activeUnderline,
                isKeyboardOpen = isImeOpen,
                onToggleBold = {
                    activeBold = !activeBold
                    val mfx = mathEffect
                    if (mfx != null) {
                        if (!mathPeekOn) {
                            val values = MathEquationEngine.lineValues(bodyField.text)
                            val total = MathEquationEngine.evaluate(mfx.mathEquation, values)
                            if (total != null) {
                                titleBeforeMathPeek = current.title
                                mathPeekOn = true
                                persist(current.copy(title = total.toString()))
                            }
                        } else {
                            mathPeekOn = false
                            persist(current.copy(title = titleBeforeMathPeek ?: ""))
                            titleBeforeMathPeek = null
                        }
                    }
                },
                onToggleItalic = { activeItalic = !activeItalic },
                onLongPressItalic = {
                    if (covertConfig.enabled) {
                        covertState.isArmed = !covertState.isArmed
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onToggleUnderline = { activeUnderline = !activeUnderline },
                onBulletLine = { updateBody(applyBulletLine(bodyField), applyActiveStyle = false) },
                onNumberedLine = { updateBody(applyNumberedLine(bodyField), applyActiveStyle = false) },
                onToggleChecklist = {
                    if (current.isChecklist) {
                        persist(current.copy(checklist = emptyList()))
                    } else if (current.checklist.isEmpty()) {
                        persist(current.copy(checklist = listOf(ChecklistItem())))
                    } else {
                        persist(current.copy(checklist = current.checklist + ChecklistItem()))
                    }
                },
                onSketch = {
                    viewModel.save(current)
                    onOpenDrawing(current.id)
                },
                selectedColor = current.color,
                onSelectColor = { c ->
                    persist(current.copy(color = c))
                },
                isDarkTheme = isDarkTheme,
                tint = fgColor,
                accent = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                isAaExpanded = isAaExpanded,
                onToggleAa = {
                    isAaExpanded = !isAaExpanded
                    if (isAaExpanded) isPlusExpanded = false
                },
                isPlusExpanded = isPlusExpanded,
                onTogglePlus = {
                    isPlusExpanded = !isPlusExpanded
                    if (isPlusExpanded) isAaExpanded = false
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun ColorPickerRow(selected: NoteColor, fgColor: Color, onSelect: (NoteColor) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        NoteColor.entries.forEach { c ->
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (c == NoteColor.NONE) fgColor.copy(alpha = 0.15f) else c.toComposeColor())
                    .then(
                        if (c == selected) Modifier.border(2.dp, fgColor, CircleShape) else Modifier
                    )
                    .clickable { onSelect(c) }
            )
        }
    }
}

@Composable
private fun ChecklistEditor(items: List<ChecklistItem>, fgColor: Color, onChange: (List<ChecklistItem>) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (item.done) AccentA else Color.Transparent)
                        .border(1.5.dp, if (item.done) AccentA else fgColor.copy(alpha = 0.35f), CircleShape)
                        .clickable {
                            onChange(items.map { if (it.id == item.id) it.copy(done = !it.done) else it })
                        }
                )
                BasicTextField(
                    value = item.text,
                    onValueChange = { text ->
                        onChange(items.map { if (it.id == item.id) it.copy(text = text) else it })
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = fgColor,
                        fontSize = 16.sp,
                        textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None
                    ),
                    cursorBrush = SolidColor(fgColor),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                )
                IconButton(onClick = { onChange(items.filterNot { it.id == item.id }) }) {
                    Text("\u00D7", fontSize = 20.sp, color = fgColor)
                }
            }
        }
        Text(
            text = "+ Add item",
            color = fgColor.copy(alpha = 0.6f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, bottom = 24.dp)
                .clickable { onChange(items + ChecklistItem()) }
        )
    }
}
