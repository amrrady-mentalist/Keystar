package com.notesapp.offline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notesapp.offline.data.EffectType
import com.notesapp.offline.data.ForceListEngine
import com.notesapp.offline.data.MagicEffect
import com.notesapp.offline.data.MagicRepository
import com.notesapp.offline.data.MagicStore
import com.notesapp.offline.data.NotesRepository
import com.notesapp.offline.ui.theme.AccentB
import com.notesapp.offline.ui.theme.GlassRadius
import com.notesapp.offline.ui.theme.glassPanel
import kotlinx.coroutines.launch

/**
 * Dedicated Force Lists screen displaying all created force lists, their active
 * states, item counts, and allowing the performer to adjust, toggle, or add new lists.
 */
@Composable
fun ForceListsScreen(
    repo: MagicRepository,
    notesRepo: NotesRepository,
    isDarkTheme: Boolean,
    onBack: () -> Unit,
    onOpenEffect: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val bgColor = if (isDarkTheme) Color.Black else Color.White
    val fgColor = if (isDarkTheme) Color.White else Color.Black

    var store by remember { mutableStateOf(MagicStore()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        store = repo.load()
        loaded = true
    }

    if (!loaded) return

    val listEffects = store.effects.filter { it.type == EffectType.LIST }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .statusBarsPadding()
            .testTag("force_lists_screen")
    ) {
        // Top App Bar
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
                    .testTag("force_lists_back_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = fgColor)
            }
            Text(
                "Force Lists",
                color = fgColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 18.dp)
            )
            Box(modifier = Modifier.size(40.dp))
        }

        // Subtitle / explanation
        Text(
            "Reads the position a PIN encodes and forces an item into it. Only one PIN effect across Force Lists and Multiple Outs can be active at a time.",
            color = fgColor.copy(alpha = 0.34f),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    SectionLabel("All Lists (${listEffects.size})", fgColor)
                }
            }

            items(listEffects, key = { it.id }) { fx ->
                ForceListRow(
                    fx = fx,
                    fgColor = fgColor,
                    onToggle = { enabled ->
                        scope.launch {
                            store = activatePinEffect(repo, notesRepo, fx, enabled)
                        }
                    },
                    onClick = { onOpenEffect(fx.id) },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }

            item {
                GlassTextPill(
                    label = "+ Add Force List",
                    fgColor = fgColor,
                    modifier = Modifier
                        .padding(start = 20.dp, top = 8.dp, bottom = 8.dp)
                        .testTag("add_force_list_button")
                ) {
                    scope.launch {
                        val count = store.effects.count { it.type == EffectType.LIST }
                        val created = repo.createEffect(
                            EffectType.LIST,
                            if (count == 0) "Force List" else "Force List ${count + 1}"
                        )
                        store = repo.load()
                        onOpenEffect(created.id)
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(60.dp)) }
        }
    }
}

@Composable
private fun ForceListRow(
    fx: MagicEffect,
    fgColor: Color,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val itemCount = ForceListEngine.actualItems(fx.items).size
    val itemsSummary = "$itemCount " + if (itemCount == 1) "item" else "items"
    val forceSummary = if (fx.forceWord.isNotBlank()) " · Force: \"${fx.forceWord}\"" else ""
    val titleSummary = if (fx.title.isNotBlank()) " · Note: \"${fx.title}\"" else ""
    val subtitle = "$itemsSummary$forceSummary$titleSummary"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(radius = GlassRadius.md, tint = fgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("force_list_row_${fx.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fx.name.ifBlank { "Force List" },
                    color = fgColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                if (fx.enabled) {
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
        }
        ToggleSwitch(
            checked = fx.enabled,
            fgColor = fgColor,
            onToggle = onToggle,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}
