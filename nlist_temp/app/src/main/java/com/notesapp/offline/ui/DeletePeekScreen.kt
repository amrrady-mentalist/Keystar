package com.notesapp.offline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notesapp.offline.data.DeletePeekConfig
import com.notesapp.offline.data.DeletePeekMemory
import com.notesapp.offline.data.MagicRepository
import com.notesapp.offline.data.MagicStore
import com.notesapp.offline.ui.theme.AccentA
import com.notesapp.offline.ui.theme.AccentB
import com.notesapp.offline.ui.theme.GlassRadius
import com.notesapp.offline.ui.theme.glassPanel
import kotlinx.coroutines.launch

@Composable
fun DeletePeekScreen(
    repo: MagicRepository,
    isDarkTheme: Boolean,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var store by remember { mutableStateOf(MagicStore()) }
    var config by remember { mutableStateOf(DeletePeekConfig()) }
    var loaded by remember { mutableStateOf(false) }

    val bgColor = if (isDarkTheme) Color.Black else Color.White
    val fgColor = if (isDarkTheme) Color.White else Color.Black

    LaunchedEffect(Unit) {
        val s = repo.load()
        store = s
        config = s.deletePeek
        loaded = true
    }

    fun saveConfig(newConfig: DeletePeekConfig) {
        config = newConfig
        scope.launch {
            repo.updateDeletePeek(newConfig)
        }
    }

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
                "Delete Peek",
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
                            "Delete Peek",
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
                        "Captures any word deleted in any note and transmits it when triggered.",
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

            // Quick toggle info card
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
                        "Quick Toggle on Home Screen",
                        color = AccentB,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Long-press the 'All notes' filter pill on the main notes screen to quickly activate or deactivate Delete Peek. An accent border appears around 'All notes' when active.",
                        color = fgColor.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Memory Status Card
            SectionLabel("Captured Memory", fgColor, topPadding = 24.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .glassPanel(radius = GlassRadius.sm, tint = fgColor)
                    .padding(16.dp)
            ) {
                Column {
                    Text("Last Deleted Word in Memory:", color = fgColor.copy(alpha = 0.45f), fontSize = 12.sp)
                    val memoryWord = DeletePeekMemory.lastDeletedWord
                    Text(
                        text = if (memoryWord.isNotBlank()) memoryWord else "(No word deleted yet)",
                        color = if (memoryWord.isNotBlank()) AccentA else fgColor.copy(alpha = 0.35f),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Output Methods Section
            SectionLabel("Delivery Methods", fgColor, topPadding = 24.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .glassPanel(radius = GlassRadius.sm, tint = fgColor)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Local Push Notification", color = fgColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Sends a subtle local notification to your phone containing the deleted word upon trigger.",
                        color = fgColor.copy(alpha = 0.45f),
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                ToggleSwitch(
                    checked = config.localPushNotification,
                    fgColor = fgColor,
                    onToggle = { saveConfig(config.copy(localPushNotification = it)) }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .glassPanel(radius = GlassRadius.sm, tint = fgColor)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Send to Inject API", color = fgColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "POSTs the deleted word to the configured Inject API endpoint.",
                        color = fgColor.copy(alpha = 0.45f),
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                ToggleSwitch(
                    checked = config.sendToInject,
                    fgColor = fgColor,
                    onToggle = { saveConfig(config.copy(sendToInject = it)) }
                )
            }

            // Trigger Triggers Section
            SectionLabel("Physical Triggers", fgColor, topPadding = 24.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .glassPanel(radius = GlassRadius.sm, tint = fgColor)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Volume Button Trigger", color = fgColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Pressing Volume Up/Down while editing a note or viewing notes triggers the delivery.",
                        color = fgColor.copy(alpha = 0.45f),
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                ToggleSwitch(
                    checked = config.triggerVolumeButton,
                    fgColor = fgColor,
                    onToggle = { saveConfig(config.copy(triggerVolumeButton = it)) }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .glassPanel(radius = GlassRadius.sm, tint = fgColor)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Proximity Sensor Trigger", color = fgColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Covering or waving over the phone's proximity sensor triggers the delivery.",
                        color = fgColor.copy(alpha = 0.45f),
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                ToggleSwitch(
                    checked = config.triggerProximity,
                    fgColor = fgColor,
                    onToggle = { saveConfig(config.copy(triggerProximity = it)) }
                )
            }

            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}
