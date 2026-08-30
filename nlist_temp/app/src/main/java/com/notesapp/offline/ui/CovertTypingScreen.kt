package com.notesapp.offline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notesapp.offline.data.CovertLetterPosition
import com.notesapp.offline.data.CovertSessionState
import com.notesapp.offline.data.CovertTypingConfig
import com.notesapp.offline.data.CovertTypingEngine
import com.notesapp.offline.data.MagicRepository
import com.notesapp.offline.data.MagicStore
import com.notesapp.offline.ui.theme.AccentA
import com.notesapp.offline.ui.theme.AccentB
import com.notesapp.offline.ui.theme.GlassRadius
import com.notesapp.offline.ui.theme.glassPanel
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CovertTypingScreen(
    repo: MagicRepository,
    isDarkTheme: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var store by remember { mutableStateOf(MagicStore()) }
    var config by remember { mutableStateOf(CovertTypingConfig()) }
    var loaded by remember { mutableStateOf(false) }

    val bgColor = if (isDarkTheme) Color.Black else Color.White
    val fgColor = if (isDarkTheme) Color.White else Color.Black

    LaunchedEffect(Unit) {
        val s = repo.load()
        store = s
        config = s.covertTyping
        loaded = true
    }

    fun saveConfig(newConfig: CovertTypingConfig) {
        config = newConfig
        scope.launch {
            repo.updateCovertTyping(newConfig)
        }
    }

    // Sandbox test state for the interactive preview
    var sandboxText by remember { mutableStateOf(TextFieldValue("")) }
    val sandboxSessionState = remember { CovertSessionState(isArmed = true) }
    var capturedTestWord by remember { mutableStateOf("") }

    if (!loaded) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
            .imePadding()
    ) {
        // Top navigation bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = fgColor
                )
            }
            Text(
                "Covert Typing",
                color = fgColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 6.dp)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // Master toggle card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(radius = GlassRadius.md, tint = fgColor)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Covert Typing",
                            color = fgColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (config.enabled) {
                            Text(
                                "ACTIVE",
                                color = AccentA,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.7.sp,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clip(RoundedCornerShape(100))
                                    .border(1.dp, AccentA, RoundedCornerShape(100))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        "Invisibly inputs real secret words while the screen shows a pre-saved cover sentence.",
                        color = fgColor.copy(alpha = 0.45f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                ToggleSwitch(
                    checked = config.enabled,
                    fgColor = fgColor,
                    onToggle = { saveConfig(config.copy(enabled = it)) },
                    modifier = Modifier.padding(start = 12.dp)
                )
            }

            // How to trigger info card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AccentB.copy(alpha = 0.12f))
                    .border(1.dp, AccentB.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                    .padding(14.dp)
            ) {
                Column {
                    Text(
                        "How to Trigger Covert Mode",
                        color = AccentB,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "In any note, long-press the Italic (I) button on the bottom toolbar. This arms Covert Mode for that note.",
                        color = fgColor.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Pre-saved Sentence Section
            SectionLabel("Covert Sentence Line", fgColor, topPadding = 24.dp)
            Text(
                "When Covert Mode is active, every keyboard stroke outputs the next character from this sentence, while capturing what you actually type. Any leading blank or whitespace-only lines are ignored. Pressing Space twice (  ) or Enter finishes the secret word. When the sentence completes, a period (.) appears.",
                color = fgColor.copy(alpha = 0.45f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(radius = GlassRadius.sm, tint = fgColor)
                    .padding(14.dp)
            ) {
                BasicTextField(
                    value = config.preSavedSentence,
                    onValueChange = { saveConfig(config.copy(preSavedSentence = it)) },
                    textStyle = TextStyle(color = fgColor, fontSize = 14.sp, lineHeight = 20.sp),
                    cursorBrush = SolidColor(fgColor),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Character count & quick presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${config.preSavedSentence.length} characters (supports up to ${config.preSavedSentence.length} keystrokes)",
                    color = fgColor.copy(alpha = 0.35f),
                    fontSize = 11.sp
                )
            }

            Text("Quick Presets:", color = fgColor.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val presets = listOf(
                    "Grocery Note" to "Don't forget to pick up groceries and water for the team.",
                    "Meeting Notes" to "Meeting with marketing team tomorrow at 10 AM in the main room.",
                    "Reminder List" to "Please remember to submit the quarterly project reports by Friday."
                )
                presets.forEach { (name, text) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(100))
                            .background(fgColor.copy(alpha = 0.08f))
                            .clickable { saveConfig(config.copy(preSavedSentence = text)) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(name, color = fgColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // Action 1: Inject API Integration
            SectionLabel("Action 1: Inject API", fgColor, topPadding = 24.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(radius = GlassRadius.md, tint = fgColor)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Send secret word to Inject API",
                        color = fgColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Immediately transmits the hidden word to your configured Inject URL when you type double-space (  ).",
                        color = fgColor.copy(alpha = 0.4f),
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                ToggleSwitch(
                    checked = config.sendToInject,
                    fgColor = fgColor,
                    onToggle = { saveConfig(config.copy(sendToInject = it)) },
                    modifier = Modifier.padding(start = 12.dp)
                )
            }

            // Action 2: Multi-line Forced Word (Spectator Lines)
            SectionLabel("Action 2: Multi-Line Spectator Force", fgColor, topPadding = 24.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(radius = GlassRadius.md, tint = fgColor)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Force word across spectator lines",
                        color = fgColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "When spectators type random characters on subsequent lines, automatically forces the corresponding letters of the secret word.",
                        color = fgColor.copy(alpha = 0.4f),
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                ToggleSwitch(
                    checked = config.revealOnSubsequentLines,
                    fgColor = fgColor,
                    onToggle = { saveConfig(config.copy(revealOnSubsequentLines = it)) },
                    modifier = Modifier.padding(start = 12.dp)
                )
            }

            if (config.revealOnSubsequentLines) {
                Text(
                    "Target Forced Letter Position on each line:",
                    color = fgColor.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 8.dp)
                )

                val positions = listOf(
                    Triple(CovertLetterPosition.FIRST, "1st Letter", "e.g. Sjakiwie / Amjridi / Mskeooe"),
                    Triple(CovertLetterPosition.SECOND, "2nd Letter", "e.g. jSakiwie / mAjridi / sMkeooe"),
                    Triple(CovertLetterPosition.THIRD, "3rd Letter", "e.g. jaSkiwie / mrAjridi / skMeooe"),
                    Triple(CovertLetterPosition.LAST, "Last Letter", "e.g. jakiwiS / mjridA / skeooM")
                )

                positions.forEach { (pos, label, desc) ->
                    val isSelected = config.targetLetterPosition == pos
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) AccentB.copy(alpha = 0.18f) else fgColor.copy(alpha = 0.05f))
                            .border(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) AccentB else fgColor.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { saveConfig(config.copy(targetLetterPosition = pos)) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, if (isSelected) AccentB else fgColor.copy(alpha = 0.4f), CircleShape)
                                .padding(3.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(AccentB)
                                )
                            }
                        }
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(label, color = fgColor, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text(desc, color = fgColor.copy(alpha = 0.5f), fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // Interactive Practice Sandbox
            SectionLabel("Practice Sandbox", fgColor, topPadding = 24.dp)
            Text(
                "Test the effect below: Type a secret word (e.g. 'elephant' or 'Sam'), hit Space twice (  ), then press Enter and try typing on subsequent lines to see the forced letters appear!",
                color = fgColor.copy(alpha = 0.45f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(radius = GlassRadius.md, tint = fgColor)
                    .padding(14.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Interactive Test Pad", color = AccentB, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Reset",
                            color = fgColor.copy(alpha = 0.6f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable {
                                    sandboxText = TextFieldValue("")
                                    sandboxSessionState.secretBuffer = ""
                                    sandboxSessionState.capturedSecretWord = ""
                                    sandboxSessionState.hasCapturedWord = false
                                    sandboxSessionState.covertSentenceIndex = 0
                                    sandboxSessionState.consecutiveSpaces = 0
                                    sandboxSessionState.covertLineIndex = -1
                                    sandboxSessionState.isArmed = true
                                    capturedTestWord = ""
                                }
                                .padding(4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    BasicTextField(
                        value = sandboxText,
                        onValueChange = { newTfv ->
                            sandboxText = CovertTypingEngine.processEdit(
                                oldValue = sandboxText,
                                newValue = newTfv,
                                config = config,
                                state = sandboxSessionState,
                                onWordCaptured = { captured ->
                                    capturedTestWord = captured
                                }
                            )
                        },
                        textStyle = TextStyle(color = fgColor, fontSize = 14.sp, lineHeight = 22.sp),
                        cursorBrush = SolidColor(fgColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                    )

                    if (capturedTestWord.isNotBlank() || sandboxSessionState.capturedSecretWord.isNotBlank()) {
                        val word = capturedTestWord.ifBlank { sandboxSessionState.capturedSecretWord }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentA.copy(alpha = 0.15f))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "Secret Word Locked: \"$word\"",
                                color = AccentA,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}
