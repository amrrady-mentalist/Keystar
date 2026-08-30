package com.notesapp.offline.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image as ImageIcon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import com.notesapp.offline.data.BackupRepository
import com.notesapp.offline.data.EffectType
import com.notesapp.offline.data.EffectNames
import com.notesapp.offline.data.ForceListEngine
import com.notesapp.offline.data.HsWidgetHost
import com.notesapp.offline.data.InjectApiClient
import com.notesapp.offline.data.InjectFetchDebugResult
import com.notesapp.offline.data.InvalidBackupException
import com.notesapp.offline.data.LockMode
import com.notesapp.offline.data.MagicEffect
import com.notesapp.offline.data.MagicRepository
import com.notesapp.offline.data.MagicStore
import com.notesapp.offline.data.Note
import com.notesapp.offline.data.NotesRepository
import com.notesapp.offline.data.ThemeRepository
import com.notesapp.offline.ui.theme.AccentA
import com.notesapp.offline.ui.theme.AccentB
import com.notesapp.offline.ui.theme.Danger
import com.notesapp.offline.ui.theme.GlassRadius
import com.notesapp.offline.ui.theme.glassPanel
import kotlinx.coroutines.launch
import java.io.File

/**
 * The hidden "Magic Settings" screen — direct functional + visual port of
 * the web app's #magicSettings panel: Lock Method, the classic lock's
 * background photo, the Home Screen disguise's wallpaper/Notes-icon/decoy
 * app customization, and the Effects list (with the same glass-card /
 * ACTIVE-pill styling as the original, pulled from its CSS).
 */
@Composable
fun MagicSettingsScreen(
    repo: MagicRepository,
    notesRepo: NotesRepository,
    themeRepo: ThemeRepository,
    isDarkTheme: Boolean,
    onBack: () -> Unit,
    onOpenEffect: (String) -> Unit,
    onOpenForceLists: () -> Unit,
    onOpenMultipleOuts: () -> Unit,
    onOpenCovertTyping: () -> Unit,
    onOpenDeletePeek: () -> Unit,
    onOpenInputMethod: (LockMode) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bgColor = if (isDarkTheme) Color.Black else Color.White
    val fgColor = if (isDarkTheme) Color.White else Color.Black
    val backupRepo = remember(repo, notesRepo, themeRepo) {
        BackupRepository(notesRepo, repo, themeRepo)
    }

    var store by remember { mutableStateOf(MagicStore()) }
    var loaded by remember { mutableStateOf(false) }
    val injectApiClient = remember { InjectApiClient() }
    var injectTestResult by remember { mutableStateOf<InjectFetchDebugResult?>(null) }
    var injectTesting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        store = repo.load()
        loaded = true
    }

    fun persist(updated: MagicStore) {
        store = updated
        scope.launch { repo.save(updated) }
    }

    // ---- Backup & Restore ---------------------------------------------
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupIsError by remember { mutableStateOf(false) }
    // Set the instant a backup file is picked to restore from — shown as a
    // confirmation dialog before anything is actually overwritten, since
    // restoring replaces every note and every effect currently saved.
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> backupRepo.export(out) }
                    ?: error("couldn't open the destination file")
            }.isSuccess
            backupIsError = !ok
            backupMessage = if (ok) "Backup saved." else "Couldn't save the backup — try again."
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingRestoreUri = uri
    }

    fun runRestore(uri: Uri) {
        scope.launch {
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input -> backupRepo.import(input) }
                    ?: throw InvalidBackupException("Couldn't open that file.")
            }
            result.fold(
                onSuccess = { summary ->
                    backupIsError = false
                    store = repo.load() // this screen's own copy — the restore just overwrote it on disk
                    backupMessage = buildString {
                        append("Restored ${summary.noteCount} note${if (summary.noteCount == 1) "" else "s"} and ")
                        append("${summary.effectCount} effect${if (summary.effectCount == 1) "" else "s"}.")
                        if (summary.widgetNeedsRepick) append(" You'll need to re-pick your home screen widget.")
                    }
                },
                onFailure = { e ->
                    backupIsError = true
                    backupMessage = e.message ?: "Couldn't restore that backup."
                }
            )
        }
    }

    if (!loaded) return

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
    ) {
        // Top bar — back arrow, centered title, symmetric spacer (matches
        // the web app's icon-btn / h1 / 40px-spacer layout).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .glassPanel(radius = GlassRadius.lg, tint = fgColor)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = fgColor)
            }
            Text(
                "Settings",
                color = fgColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(start = 18.dp)
            )
            Box(modifier = Modifier.size(40.dp))
        }

        Text(
            "Each effect's body can use \$\$\$\$ as a placeholder — it's swapped for the word tied to whatever code you enter on the lock screen.",
            color = fgColor.copy(alpha = 0.34f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
        )

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    SectionLabel("Input Method", fgColor)
                    Text(
                        "Only one can be active at a time \u2014 turning one on switches the other off. Tap a row to customize it.",
                        color = fgColor.copy(alpha = 0.34f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    InputMethodRow(
                        label = "Pin Code",
                        checked = store.lockMode == LockMode.CLASSIC,
                        fgColor = fgColor,
                        onToggle = { persist(store.copy(lockMode = LockMode.CLASSIC)) },
                        onClick = { onOpenInputMethod(LockMode.CLASSIC) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    InputMethodRow(
                        label = "Home Screen",
                        checked = store.lockMode == LockMode.HOME_SCREEN,
                        fgColor = fgColor,
                        onToggle = { persist(store.copy(lockMode = LockMode.HOME_SCREEN)) },
                        onClick = { onOpenInputMethod(LockMode.HOME_SCREEN) }
                    )
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    SectionLabel("Backup & Restore", fgColor, topPadding = 24.dp)
                    Text(
                        "Save everything — notes, drawings, effects, and their photos — to one file, or restore from one. Handy before reinstalling, since app data doesn't survive an uninstall.",
                        color = fgColor.copy(alpha = 0.34f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassPill("Export backup", fgColor) {
                            exportLauncher.launch(defaultBackupFileName())
                        }
                        GlassPill("Restore backup", fgColor) {
                            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        }
                    }
                    backupMessage?.let { msg ->
                        Text(
                            msg,
                            color = if (backupIsError) Danger else fgColor.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }

                    SectionLabel("Inject", fgColor, topPadding = 24.dp)
                    Text(
                        "One URL, used both ways: fetched to fill in \u2013\u2013value\u2013\u2013 wherever it appears in an effect, and posted to when an Inject Reveal (Sum/Peek) effect's trigger fires.",
                        color = fgColor.copy(alpha = 0.34f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    ApiUrlField(
                        value = store.apiUrl ?: "",
                        onValueChange = { persist(store.copy(apiUrl = it)) },
                        fgColor = fgColor
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .glassPanel(radius = GlassRadius.sm, tint = fgColor)
                            .clickable { persist(store.copy(injectModeOn = !store.injectModeOn)) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Inject Mode", color = fgColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Off: \u2013\u2013value\u2013\u2013 is stripped out and nothing is ever sent, no matter what's set below.",
                                color = fgColor.copy(alpha = 0.4f),
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(top = 2.dp, end = 10.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(width = 44.dp, height = 26.dp)
                                .clip(RoundedCornerShape(100))
                                .background(
                                    if (store.injectModeOn) Brush.linearGradient(listOf(AccentA, AccentB))
                                    else Brush.linearGradient(listOf(fgColor.copy(alpha = 0.16f), fgColor.copy(alpha = 0.16f)))
                                )
                                .padding(3.dp),
                            contentAlignment = if (store.injectModeOn) Alignment.CenterEnd else Alignment.CenterStart
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(if (store.injectModeOn) Color(0xFF0A0A12) else fgColor.copy(alpha = 0.7f))
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .glassPanel(radius = GlassRadius.sm, tint = fgColor)
                            .clickable(enabled = !injectTesting) {
                                injectTesting = true
                                injectTestResult = null
                                val url = store.apiUrl.orEmpty()
                                scope.launch {
                                    injectTestResult = injectApiClient.fetchDebug(url)
                                    injectTesting = false
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (injectTesting) "Testing\u2026" else "Test Connection",
                            color = fgColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "GET now",
                            color = fgColor.copy(alpha = 0.4f),
                            fontSize = 12.sp
                        )
                    }
                    injectTestResult?.let { result ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .glassPanel(radius = GlassRadius.sm, tint = fgColor)
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            if (result.error != null) {
                                Text(
                                    "Failed: ${result.error}",
                                    color = Danger,
                                    fontSize = 12.5.sp,
                                    lineHeight = 17.sp
                                )
                            } else {
                                Text(
                                    "Got value: \"${result.parsedValue}\"",
                                    color = fgColor,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 17.sp
                                )
                            }
                            result.httpCode?.let {
                                Text(
                                    "HTTP $it",
                                    color = fgColor.copy(alpha = 0.4f),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            result.rawBody?.let {
                                Text(
                                    it.take(400),
                                    color = fgColor.copy(alpha = 0.34f),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    SectionLabel("Effects", fgColor, topPadding = 24.dp)
                    Text(
                        "Force Lists and Multiple Outs share the PIN — tap either to view all lists/outs and adjust settings. Peek and Math are independent.",
                        color = fgColor.copy(alpha = 0.34f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                }
            }

            // Force Lists Navigation Card
            item {
                val listEffects = store.effects.filter { it.type == EffectType.LIST }
                val activeList = listEffects.firstOrNull { it.enabled }
                val countText = "${listEffects.size} " + if (listEffects.size == 1) "list" else "lists"
                SettingsNavigationCard(
                    title = "Force Lists",
                    subtitle = "Reads the position a PIN encodes and forces an item into it",
                    countText = countText,
                    activeName = activeList?.name?.ifBlank { "Force List" },
                    fgColor = fgColor,
                    onClick = onOpenForceLists,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            // Multiple Outs Navigation Card
            item {
                val wordEffects = store.effects.filter { it.type == EffectType.WORD }
                val activeWord = wordEffects.firstOrNull { it.enabled }
                val countText = "${wordEffects.size} " + if (wordEffects.size == 1) "effect" else "effects"
                SettingsNavigationCard(
                    title = "Multiple Outs",
                    subtitle = "Multiple code \u2192 word/sketch outs, revealed by the PIN's last digits",
                    countText = countText,
                    activeName = activeWord?.name?.ifBlank { "Multiple Outs" },
                    fgColor = fgColor,
                    onClick = onOpenMultipleOuts,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            // Covert Typing Navigation Card
            item {
                val isCovertActive = store.covertTyping.enabled
                SettingsNavigationCard(
                    title = "Covert Typing",
                    subtitle = "Secret keystroke capture displaying a pre-saved cover sentence",
                    countText = if (isCovertActive) "Enabled" else "Disabled",
                    activeName = if (isCovertActive) "Hold Italic (I) to Arm" else null,
                    fgColor = fgColor,
                    onClick = onOpenCovertTyping,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            // Delete Peek Navigation Card
            item {
                val isDeletePeekActive = store.deletePeek.enabled
                SettingsNavigationCard(
                    title = "Delete Peek",
                    subtitle = "Captures deleted words and transmits them via API or local notification",
                    countText = if (isDeletePeekActive) "Enabled" else "Disabled",
                    activeName = if (isDeletePeekActive) "Hold 'All notes' to Toggle" else null,
                    fgColor = fgColor,
                    onClick = onOpenDeletePeek,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    SectionLabel("Peek & Math", fgColor, topPadding = 16.dp)
                }
            }
            listOf(
                EffectType.INJECT_PEEK to ("Peek" to "Send/receive whatever's on a note's screen via Inject"),
                EffectType.INJECT_SUM to ("Math" to "Runs an equation over a note's numbers via Inject")
            ).forEach { (type, labelAndSubtitle) ->
                val (label, subtitle) = labelAndSubtitle
                item {
                    val fx = store.effects.firstOrNull { it.type == type }
                    if (fx != null) {
                        EffectToggleRow(
                            label = label,
                            subtitle = subtitle,
                            checked = fx.enabled,
                            fgColor = fgColor,
                            onToggle = { enabled ->
                                scope.launch {
                                    repo.setEffectEnabled(fx.id, enabled)
                                    store = repo.load()
                                }
                            },
                            onClick = { onOpenEffect(fx.id) },
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(100.dp)) }
        }
    }
    }

    pendingRestoreUri?.let { uri ->
        Dialog(onDismissRequest = { pendingRestoreUri = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(bgColor)
                    .padding(20.dp)
            ) {
                Text("Restore this backup?", color = fgColor, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "This replaces every note and every effect currently saved with what's in the backup file. This can't be undone.",
                    color = fgColor.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassPill("Cancel", fgColor) { pendingRestoreUri = null }
                    GlassPill("Restore", Danger) {
                        pendingRestoreUri = null
                        runRestore(uri)
                    }
                }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, fgColor: Color, topPadding: androidx.compose.ui.unit.Dp = 0.dp) {
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
private fun LockModePill(label: String, selected: Boolean, fgColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
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
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF0A0A12) else fgColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** A row in the "Input Method" section — tapping the label/subtitle area
 *  navigates into that input method (selecting it along the way, since
 *  there's nothing to customize for a method that isn't selected); the
 *  switch on the right toggles it on/off directly, same gesture as an
 *  effect's toggle below. Turning one input method on is what turns the
 *  other off — LockMode is a single field, so selecting one IS
 *  deselecting the other. */
@Composable
private fun InputMethodRow(
    label: String,
    checked: Boolean,
    fgColor: Color,
    onToggle: () -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(radius = GlassRadius.sm, tint = fgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = fgColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        ToggleSwitch(checked = checked, fgColor = fgColor, onToggle = { onToggle() })
    }
}

/** A row in the "Effects" section — tapping the label/subtitle area opens
 *  that effect's own customization page; the switch on the right enables
 *  or disables it from here directly, without opening that page. */
@Composable
private fun EffectToggleRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    fgColor: Color,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(radius = GlassRadius.md, tint = fgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = fgColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = fgColor.copy(alpha = 0.4f),
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        ToggleSwitch(checked = checked, fgColor = fgColor, onToggle = onToggle, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
fun ToggleSwitch(checked: Boolean, fgColor: Color, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(100))
            .background(
                if (checked) androidx.compose.ui.graphics.Brush.linearGradient(listOf(AccentA, AccentB))
                else androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(fgColor.copy(alpha = 0.16f), fgColor.copy(alpha = 0.16f))
                )
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggle(!checked) }
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

@Composable
private fun ApiUrlField(value: String, onValueChange: (String) -> Unit, fgColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(radius = GlassRadius.sm, tint = fgColor)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (value.isEmpty()) {
            Text("https://your-api.example.com/...", color = fgColor.copy(alpha = 0.34f), fontSize = 14.sp)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = fgColor, fontSize = 14.sp),
            cursorBrush = SolidColor(fgColor),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun GlassPill(label: String, textColor: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .glassPanel(radius = 100.dp, tint = textColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    ) {
        Text(label, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun PhotoThumb(path: String?, fgColor: Color) {
    val bmp = remember(path) {
        path?.let { p -> runCatching { android.graphics.BitmapFactory.decodeFile(p) }.getOrNull() }
    }
    Box(
        modifier = Modifier
            .size(64.dp)
            .glassPanel(radius = 16.dp, tint = fgColor),
        contentAlignment = Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
            )
        } else {
            Icon(Icons.Filled.ImageIcon, contentDescription = null, tint = fgColor.copy(alpha = 0.34f))
        }
    }
}

/** One row in the (expandable) Decoy Apps list — a small icon thumbnail
 *  the user can tap to re-skin, plus an editable name field, mirroring
 *  the same icon/rename controls the Notes-icon and wallpaper slots use. */
@Composable
fun DecoyAppRow(
    app: HsDecoyApp,
    iconPath: String?,
    displayName: String,
    fgColor: Color,
    onPickIcon: () -> Unit,
    onNameChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val bmp = remember(iconPath) {
        iconPath?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it) }.getOrNull() }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(radius = GlassRadius.md, tint = fgColor)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (bmp != null) Color.Transparent else app.color)
                .clickable(onClick = onPickIcon),
            contentAlignment = Alignment.Center
        ) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(displayName.take(2).uppercase(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        BasicTextField(
            value = displayName,
            onValueChange = onNameChange,
            textStyle = TextStyle(color = fgColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            cursorBrush = SolidColor(fgColor),
            singleLine = true,
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp)
        )
        GlassPill("Icon", fgColor, onClick = onPickIcon)
    }
}

@Composable
private fun EffectCard(
    effect: MagicEffect,
    isActive: Boolean,
    fgColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val metaText = when (effect.type) {
        EffectType.LIST -> {
            val n = effect.items.count { it.isNotBlank() }
            "List Force · $n " + if (n == 1) "item" else "items"
        }
        EffectType.WORD -> {
            val n = effect.outs.size
            "Word · $n " + if (n == 1) "out" else "outs"
        }
        EffectType.INJECT_SUM, EffectType.INJECT_PEEK -> {
            val label = if (effect.type == EffectType.INJECT_SUM) "Inject: Sum" else "Inject: Peek"
            val triggers = buildList {
                if (effect.sendUseProximity) add("proximity")
                if (effect.sendUseVolumeButton) add("volume")
            }
            if (triggers.isEmpty()) "$label · no trigger set" else "$label · " + triggers.joinToString(" + ")
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(radius = GlassRadius.md, tint = fgColor)
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                effect.name.ifBlank { "Untitled effect" },
                color = fgColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (isActive) {
                Text(
                    "ACTIVE",
                    color = AccentB,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.7.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(100))
                        .border(1.dp, AccentB, RoundedCornerShape(100))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
        }
        Text(
            metaText,
            color = fgColor.copy(alpha = 0.34f),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** Copies a picked gallery image into the app's own storage so it survives
 *  even if the original content:// URI's permission grant doesn't.
 *
 *  Bug this fixes: the previous version called dest.absolutePath
 *  unconditionally after the copy block — if openInputStream(uri) ever
 *  returned null (no exception, just null; it happens), the ?.use{} block
 *  silently did nothing, no file was ever written, and this still reported
 *  success with a path pointing at a file that doesn't exist. Every later
 *  BitmapFactory.decodeFile() on that path then fails silently too — no
 *  crash anywhere, the background (and the settings-screen thumbnail) just
 *  quietly never appears. Now it explicitly checks the stream opened and
 *  the resulting file is non-empty before calling it a success. */
/** "n-list-backup-2026-08-21.zip" — used as the suggested filename when
 *  the Storage Access Framework's CreateDocument picker opens. */
private fun defaultBackupFileName(): String {
    val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    return "n-list-backup-$date.zip"
}

suspend fun copyImageToInternal(context: Context, uri: Uri, dir: File, prefix: String): String? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val dest = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
            val input = context.contentResolver.openInputStream(uri)
                ?: return@runCatching null
            input.use { stream ->
                dest.outputStream().use { output -> stream.copyTo(output) }
            }
            if (dest.exists() && dest.length() > 0L) dest.absolutePath else {
                dest.delete()
                null
            }
        }.getOrNull()
    }

/** Flattens an installed app's launcher icon (handles adaptive icons same
 *  as the fake home screen's own Notes-icon fallback) and saves it as a
 *  PNG under the app's own storage, same durability reasoning as
 *  [copyImageToInternal] — the icon needs to survive independent of
 *  whether the source app stays installed. */
suspend fun savePackageIconToInternal(context: Context, packageName: String, dir: File, prefix: String): String? =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val bmp = context.packageManager.getApplicationIcon(packageName).toBitmap()
            val dest = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
            dest.outputStream().use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
            if (dest.exists() && dest.length() > 0L) dest.absolutePath else {
                dest.delete()
                null
            }
        }.getOrNull()
    }

/** One installed, launchable app — just enough to list and preview it. */
private data class InstalledAppEntry(val packageName: String, val label: String, val icon: android.graphics.drawable.Drawable)

/** Every app that shows up in a real launcher (has a MAIN/LAUNCHER
 *  activity) — the same pool a user would recognize icons from. */
private fun queryLaunchableApps(context: Context): List<InstalledAppEntry> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val pm = context.packageManager
    return pm.queryIntentActivities(intent, 0)
        .distinctBy { it.activityInfo.packageName }
        .map { info ->
            InstalledAppEntry(
                packageName = info.activityInfo.packageName,
                label = info.loadLabel(pm).toString(),
                icon = info.loadIcon(pm)
            )
        }
        .sortedBy { it.label.lowercase() }
}

/** Small "where from?" step shown before every icon change — Photos (an
 *  image from your gallery) or an icon borrowed from one of your already-
 *  installed apps. True third-party icon-pack support (reading another
 *  launcher's appfilter.xml) isn't implemented — these two cover the
 *  common cases without that extra integration. */
@Composable
fun IconSourceChooserDialog(
    fgColor: Color,
    onPickPhotos: () -> Unit,
    onPickApp: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (fgColor == Color.White) Color(0xFF1C1C1E) else Color.White)
                .padding(20.dp)
        ) {
            Text(
                "Choose icon from",
                color = fgColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 14.dp)
            )
            ChooserRow("Photos", "Pick any image from your gallery", fgColor, onPickPhotos)
            Box(Modifier.padding(vertical = 6.dp))
            ChooserRow("An installed app", "Borrow the icon of an app already on your phone", fgColor, onPickApp)
        }
    }
}

@Composable
private fun ChooserRow(title: String, subtitle: String, fgColor: Color, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(title, color = fgColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = fgColor.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Full-screen searchable-by-scroll list of every launchable app, each
 *  with its real icon — tapping one uses that icon for whichever slot
 *  (wallpaper/notes/decoy app) triggered the picker. */
@Composable
fun InstalledAppPickerDialog(
    fgColor: Color,
    bgColor: Color,
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            queryLaunchableApps(context)
        }
    }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps else apps.filter { it.label.contains(query, ignoreCase = true) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .clip(RoundedCornerShape(20.dp))
                .background(bgColor)
                .padding(16.dp)
        ) {
            Text(
                "Choose an app icon",
                color = fgColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(fgColor.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = TextStyle(color = fgColor, fontSize = 14.sp),
                    cursorBrush = SolidColor(fgColor),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text("Search apps…", color = fgColor.copy(alpha = 0.4f), fontSize = 14.sp)
                        }
                        inner()
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            if (apps.isEmpty()) {
                Text("Loading…", color = fgColor.copy(alpha = 0.5f), fontSize = 13.sp)
            } else if (filtered.isEmpty()) {
                Text("No apps match \"$query\"", color = fgColor.copy(alpha = 0.5f), fontSize = 13.sp)
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPicked(app.packageName) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                bitmap = remember(app.packageName) { app.icon.toBitmap().asImageBitmap() },
                                contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                            )
                            Text(
                                app.label,
                                color = fgColor,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(start = 14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


/** One installed widget provider — enough to list and preview it. */
data class InstalledWidgetEntry(
    val info: android.appwidget.AppWidgetProviderInfo,
    val label: String,
    val icon: android.graphics.drawable.Drawable?,
    val hostAppLabel: String
)

fun queryInstalledWidgets(context: Context): List<InstalledWidgetEntry> {
    val mgr = android.appwidget.AppWidgetManager.getInstance(context)
    val pm = context.packageManager
    return mgr.installedProviders.mapNotNull { info ->
        val hostAppLabel = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
        }.getOrDefault(info.provider.packageName)
        InstalledWidgetEntry(
            info = info,
            label = runCatching { info.loadLabel(pm) }.getOrDefault(hostAppLabel),
            icon = runCatching { info.loadIcon(context, android.util.DisplayMetrics.DENSITY_DEFAULT) }.getOrNull(),
            hostAppLabel = hostAppLabel
        )
    }.sortedBy { it.label.lowercase() }
}

/** Full list of every real widget available on the phone (across every
 *  app that publishes one), each with its actual icon — replaces the old
 *  hand-drawn "Google search bar" with an actual widget the user embeds. */
@Composable
fun WidgetPickerDialog(
    fgColor: Color,
    bgColor: Color,
    onPicked: (android.appwidget.AppWidgetProviderInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var widgets by remember { mutableStateOf<List<InstalledWidgetEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        widgets = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            queryInstalledWidgets(context)
        }
    }
    val filtered = remember(widgets, query) {
        if (query.isBlank()) widgets else widgets.filter {
            it.label.contains(query, ignoreCase = true) || it.hostAppLabel.contains(query, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .clip(RoundedCornerShape(20.dp))
                .background(bgColor)
                .padding(16.dp)
        ) {
            Text(
                "Choose a widget",
                color = fgColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "Some widgets ask for a one-time permission, or open their own small setup screen, right after you pick them — that's normal.",
                color = fgColor.copy(alpha = 0.4f),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(fgColor.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = TextStyle(color = fgColor, fontSize = 14.sp),
                    cursorBrush = SolidColor(fgColor),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text("Search widgets…", color = fgColor.copy(alpha = 0.4f), fontSize = 14.sp)
                        }
                        inner()
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            if (widgets.isEmpty()) {
                Text("Loading…", color = fgColor.copy(alpha = 0.5f), fontSize = 13.sp)
            } else if (filtered.isEmpty()) {
                Text("No widgets match \"$query\"", color = fgColor.copy(alpha = 0.5f), fontSize = 13.sp)
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filtered, key = { it.info.provider.flattenToString() }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPicked(entry.info) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val icon = entry.icon
                            if (icon != null) {
                                Image(
                                    bitmap = remember(entry.info.provider) { icon.toBitmap().asImageBitmap() },
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                                )
                            } else {
                                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(fgColor.copy(alpha = 0.12f)))
                            }
                            Column(modifier = Modifier.padding(start = 14.dp)) {
                                Text(entry.label, color = fgColor, fontSize = 14.sp)
                                Text(entry.hostAppLabel, color = fgColor.copy(alpha = 0.45f), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsNavigationCard(
    title: String,
    subtitle: String,
    countText: String,
    activeName: String?,
    fgColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(radius = GlassRadius.md, tint = fgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = fgColor, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                if (activeName != null) {
                    Text(
                        "ACTIVE",
                        color = AccentB,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.7.sp,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(100))
                            .border(1.dp, AccentB, RoundedCornerShape(100))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                subtitle,
                color = fgColor.copy(alpha = 0.4f),
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Text(
                    countText,
                    color = fgColor.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                if (activeName != null) {
                    Text(
                        " · Active: \"$activeName\"",
                        color = AccentB.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Open $title",
            tint = fgColor.copy(alpha = 0.4f),
            modifier = Modifier
                .padding(start = 12.dp)
                .size(20.dp)
        )
    }
}

