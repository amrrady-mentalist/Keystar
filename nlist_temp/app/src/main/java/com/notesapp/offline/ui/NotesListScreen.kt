package com.notesapp.offline.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.notesapp.offline.data.DeletePeekMemory
import com.notesapp.offline.data.InjectApiClient
import com.notesapp.offline.data.MagicStore
import com.notesapp.offline.data.MagicRepository
import com.notesapp.offline.data.Note
import com.notesapp.offline.data.NoteColor
import com.notesapp.offline.ui.theme.AccentA
import com.notesapp.offline.ui.theme.GlassRadius
import com.notesapp.offline.ui.theme.glassPanel
import com.notesapp.offline.ui.theme.richTextPreview
import com.notesapp.offline.ui.theme.toComposeColor
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun NotesListScreen(
    viewModel: NotesViewModel,
    magicRepo: MagicRepository,
    isDarkTheme: Boolean,
    onOpenNote: (String?) -> Unit,
    onOpenMagicSettings: () -> Unit,
    onToggleTheme: () -> Unit,
    onEnterLockFlow: () -> Unit
) {
    val notes by viewModel.notes.collectAsState()
    val filter by viewModel.filter.collectAsState()
    val query by viewModel.query.collectAsState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var deletePeekEnabled by remember { mutableStateOf(false) }
    var magicStore by remember { mutableStateOf(MagicStore()) }
    val context = LocalContext.current
    val injectApiClient = remember { InjectApiClient() }

    LaunchedEffect(deletePeekEnabled) {
        val s = magicRepo.load()
        magicStore = s
        deletePeekEnabled = s.deletePeek.enabled
    }

    fun fireDeletePeekTrigger() {
        if (!deletePeekEnabled) return
        val deletedWord = DeletePeekMemory.lastDeletedWord
        if (deletedWord.isBlank()) return
        val config = magicStore.deletePeek
        if (config.localPushNotification) {
            DeletePeekMemory.showPushNotification(context, deletedWord)
        }
        val url = magicStore.apiUrl
        if (config.sendToInject && magicStore.injectModeOn && !url.isNullOrBlank()) {
            scope.launch { injectApiClient.sendValue(url, deletedWord) }
        }
    }

    DisposableEffect(deletePeekEnabled, magicStore) {
        val config = magicStore.deletePeek
        var sensorManager: SensorManager? = null
        var proximityListener: SensorEventListener? = null

        if (deletePeekEnabled && config.triggerProximity) {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sensor = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            if (sm != null && sensor != null) {
                sensorManager = sm
                var wasNear = false
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event == null) return
                        val distance = event.values.firstOrNull() ?: return
                        val isNear = distance < sensor.maximumRange
                        if (isNear && !wasNear) {
                            fireDeletePeekTrigger()
                        }
                        wasNear = isNear
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                proximityListener = listener
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            }
        }

        if (deletePeekEnabled && config.triggerVolumeButton) {
            VolumeTriggerBus.arm { fireDeletePeekTrigger() }
        } else {
            VolumeTriggerBus.disarm()
        }

        onDispose {
            proximityListener?.let { sensorManager?.unregisterListener(it) }
            VolumeTriggerBus.disarm()
        }
    }

    var isSearchExpanded by remember { mutableStateOf(false) }
    var isGridView by remember { mutableStateOf(true) }
    var showOptionsMenu by remember { mutableStateOf(false) }
    var selectedNoteIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val isSelectionMode = selectedNoteIds.isNotEmpty()
    val searchFocusRequester = remember { FocusRequester() }

    val bgColor = if (isDarkTheme) Color.Black else Color.White
    val fgColor = if (isDarkTheme) Color.White else Color.Black

    BackHandler(enabled = isSelectionMode) {
        selectedNoteIds = emptySet()
    }

    // Hidden settings entry: typing "magic" in the search bar opens Magic
    // Settings and clears the query, instead of a visible gear icon.
    LaunchedEffect(query) {
        if (query.trim().equals("magic", ignoreCase = true)) {
            viewModel.setQuery("")
            isSearchExpanded = false
            onOpenMagicSettings()
        }
    }

    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            searchFocusRequester.requestFocus()
        }
    }

    val noteCountText = "${notes.size} ${if (notes.size == 1) "note" else "notes"}"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            if (isSelectionMode) {
                // Top Header in Selection Mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Cancel",
                        color = Color(0xFFEEA000),
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { selectedNoteIds = emptySet() }
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                    val isAllSelected = selectedNoteIds.size == notes.size && notes.isNotEmpty()
                    Text(
                        text = if (isAllSelected) "Deselect all" else "Select all",
                        color = Color(0xFFEEA000),
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                selectedNoteIds = if (isAllSelected) emptySet() else notes.map { it.id }.toSet()
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "${selectedNoteIds.size} selected",
                        color = fgColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 34.sp,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                }
            } else {
                // Top action buttons row (Search and 3-dots Menu)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        isSearchExpanded = !isSearchExpanded
                        if (!isSearchExpanded) viewModel.setQuery("")
                    }) {
                        Icon(
                            imageVector = if (isSearchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = fgColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Box {
                        IconButton(onClick = { showOptionsMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More options",
                                tint = fgColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showOptionsMenu,
                            onDismissRequest = { showOptionsMenu = false },
                            shape = RoundedCornerShape(16.dp),
                            containerColor = if (isDarkTheme) Color(0xFF222226) else Color(0xFFF4F4F8),
                            tonalElevation = 0.dp,
                            shadowElevation = 8.dp,
                            border = BorderStroke(1.dp, fgColor.copy(alpha = 0.12f)),
                            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isDarkTheme) "Light Theme" else "Dark Theme", color = fgColor, fontSize = 14.5.sp, fontWeight = FontWeight.Medium) },
                                onClick = {
                                    showOptionsMenu = false
                                    onToggleTheme()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isGridView) "List View" else "Grid View", color = fgColor, fontSize = 14.5.sp, fontWeight = FontWeight.Medium) },
                                onClick = {
                                    isGridView = !isGridView
                                    showOptionsMenu = false
                                }
                            )
                        }
                    }
                }

                // Main Title & Note Count Header
                // Double-tap on "Notes" still triggers the performer lock flow!
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Notes",
                        color = fgColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 38.sp,
                        letterSpacing = (-0.5).sp,
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { onEnterLockFlow() })
                        }
                    )
                    Text(
                        text = noteCountText,
                        color = fgColor.copy(alpha = 0.45f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
                    )
                }

                // Inline Search Bar (when expanded)
                AnimatedVisibility(visible = isSearchExpanded, enter = fadeIn(), exit = fadeOut()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 6.dp)
                            .glassPanel(radius = GlassRadius.md, tint = fgColor)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (query.isEmpty()) {
                                Text("Search notes...", color = fgColor.copy(alpha = 0.34f), fontSize = 15.sp)
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = viewModel::setQuery,
                                singleLine = true,
                                textStyle = TextStyle(color = fgColor, fontSize = 15.sp),
                                cursorBrush = SolidColor(fgColor),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocusRequester)
                            )
                        }
                    }
                }

                // Categories / Filter horizontal bar
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 12.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Layout Toggle Pill (Icon)
                    item {
                        val pillBg = if (isDarkTheme) Color(0xFF262626) else Color(0xFFE8E8E8)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(pillBg)
                                .clickable { isGridView = !isGridView }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(17.dp, 16.dp)) {
                                if (isGridView) {
                                    val w = size.width
                                    val h = size.height
                                    drawRoundRect(
                                        color = fgColor.copy(alpha = 0.9f),
                                        topLeft = Offset(0f, 0f),
                                        size = androidx.compose.ui.geometry.Size(w, h),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx())
                                    )
                                    drawLine(
                                        color = fgColor.copy(alpha = 0.9f),
                                        start = Offset(w * 0.36f, 0f),
                                        end = Offset(w * 0.36f, h),
                                        strokeWidth = 1.6.dp.toPx()
                                    )
                                } else {
                                    val w = size.width
                                    val h = size.height
                                    drawRoundRect(
                                        color = fgColor.copy(alpha = 0.9f),
                                        topLeft = Offset(0f, 0f),
                                        size = androidx.compose.ui.geometry.Size(w, h),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx(), 3.dp.toPx()),
                                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx())
                                    )
                                    drawLine(
                                        color = fgColor.copy(alpha = 0.9f),
                                        start = Offset(w / 2f, 0f),
                                        end = Offset(w / 2f, h),
                                        strokeWidth = 1.6.dp.toPx()
                                    )
                                    drawLine(
                                        color = fgColor.copy(alpha = 0.9f),
                                        start = Offset(0f, h / 2f),
                                        end = Offset(w, h / 2f),
                                        strokeWidth = 1.6.dp.toPx()
                                    )
                                }
                            }
                        }
                    }

                    // Filter items styled as modern rounded rectangle pills
                    val filterItems = listOf(
                        NoteFilter.ALL to "All notes",
                        NoteFilter.PINNED to "Favorites",
                        NoteFilter.CHECKLISTS to "Checklists",
                        NoteFilter.DRAWINGS to "Drawings",
                        NoteFilter.ARCHIVED to "Archived"
                    )

                    items(filterItems) { (f, label) ->
                        val active = filter == f
                        val pillBg = if (active) {
                            if (isDarkTheme) Color(0xFF383838) else Color(0xFFD6D6D6)
                        } else {
                            if (isDarkTheme) Color(0xFF262626) else Color(0xFFE8E8E8)
                        }
                        val textColor = if (active) fgColor else fgColor.copy(alpha = 0.65f)
                        val isAllNotes = f == NoteFilter.ALL

                        val pillModifier = if (isAllNotes) {
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .then(
                                    if (deletePeekEnabled) {
                                        Modifier.border(2.dp, AccentA, RoundedCornerShape(16.dp))
                                    } else {
                                        Modifier
                                    }
                                )
                                .background(pillBg)
                                .combinedClickable(
                                    onClick = { viewModel.setFilter(f) },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        scope.launch {
                                            val newStatus = magicRepo.toggleDeletePeek()
                                            deletePeekEnabled = newStatus
                                        }
                                    }
                                )
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        } else {
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(pillBg)
                                .clickable { viewModel.setFilter(f) }
                                .padding(horizontal = 18.dp, vertical = 10.dp)
                        }

                        Box(
                            modifier = pillModifier
                        ) {
                            Text(
                                text = label,
                                color = textColor,
                                fontSize = 14.5.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            if (notes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No notes yet — tap + to create one", color = fgColor.copy(alpha = 0.45f))
                }
            } else if (isGridView) {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalItemSpacing = 12.dp
                ) {
                    items(notes, key = { it.id }) { note ->
                        val isSelected = note.id in selectedNoteIds
                        NoteCard(
                            note = note,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            fgColor = fgColor,
                            bgColor = bgColor,
                            onClick = {
                                if (isSelectionMode) {
                                    selectedNoteIds = if (isSelected) selectedNoteIds - note.id else selectedNoteIds + note.id
                                } else {
                                    onOpenNote(note.id)
                                }
                            },
                            onLongClick = {
                                selectedNoteIds = if (isSelectionMode) {
                                    if (isSelected) selectedNoteIds - note.id else selectedNoteIds + note.id
                                } else {
                                    setOf(note.id)
                                }
                            },
                            onTogglePin = { viewModel.togglePin(note.id) }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp, 4.dp, 14.dp, 110.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(notes, key = { it.id }) { note ->
                        val isSelected = note.id in selectedNoteIds
                        NoteCard(
                            note = note,
                            isSelectionMode = isSelectionMode,
                            isSelected = isSelected,
                            fgColor = fgColor,
                            bgColor = bgColor,
                            onClick = {
                                if (isSelectionMode) {
                                    selectedNoteIds = if (isSelected) selectedNoteIds - note.id else selectedNoteIds + note.id
                                } else {
                                    onOpenNote(note.id)
                                }
                            },
                            onLongClick = {
                                selectedNoteIds = if (isSelectionMode) {
                                    if (isSelected) selectedNoteIds - note.id else selectedNoteIds + note.id
                                } else {
                                    setOf(note.id)
                                }
                            },
                            onTogglePin = { viewModel.togglePin(note.id) }
                        )
                    }
                }
            }
        }

        if (isSelectionMode) {
            val selectedNotes = notes.filter { it.id in selectedNoteIds }
            val allPinned = selectedNotes.isNotEmpty() && selectedNotes.all { it.pinned }
            val allArchived = selectedNotes.isNotEmpty() && selectedNotes.all { it.archived }
            val barBg = if (isDarkTheme) Color(0xFF1E1E22) else Color(0xFFF2F2F6)

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(barBg)
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. Pin / Unpin Action (Icon only)
                    IconButton(
                        onClick = {
                            viewModel.togglePinMany(selectedNoteIds)
                            selectedNoteIds = emptySet()
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (allPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (allPinned) "Unpin" else "Pin",
                            tint = fgColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // 2. Archive / Unarchive Action (Icon only)
                    IconButton(
                        onClick = {
                            viewModel.toggleArchiveMany(selectedNoteIds)
                            selectedNoteIds = emptySet()
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (allArchived) Icons.Filled.Archive else Icons.Outlined.Archive,
                            contentDescription = if (allArchived) "Unarchive" else "Archive",
                            tint = fgColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    // 3. Delete Action (Icon only)
                    IconButton(
                        onClick = {
                            viewModel.deleteMany(selectedNoteIds)
                            selectedNoteIds = emptySet()
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "Delete",
                            tint = fgColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        } else {
            IconButton(
                onClick = { onOpenNote(null) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New note", tint = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: Note,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    fgColor: Color,
    bgColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTogglePin: () -> Unit
) {
    val cardBg = if (bgColor == Color.Black) Color(0xFF1E1E22) else Color(0xFFF2F2F7)

    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cardBg)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(14.dp)
        ) {
            if (note.color != NoteColor.NONE) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(note.color.toComposeColor())
                )
            }

            note.drawingPngBase64?.let { b64 ->
                val bmp = remember(b64) { decodeThumb(b64) }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note.title.ifBlank { "Untitled" },
                    color = fgColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (isSelectionMode) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) Color(0xFFEEA000) else Color.Transparent)
                            .then(
                                if (!isSelected) Modifier.border(1.8.dp, fgColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp)) else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                } else {
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = if (note.pinned) "Unpin" else "Pin",
                            tint = if (note.pinned) fgColor else fgColor.copy(alpha = 0.25f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (note.isChecklist) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    note.checklist.take(5).forEach { item ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (item.done) AccentA else fgColor.copy(alpha = 0.15f)
                                    )
                            )
                            Text(
                                text = item.text,
                                color = fgColor.copy(alpha = if (item.done) 0.4f else 0.75f),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            } else if (note.body.isNotBlank()) {
                Text(
                    text = richTextPreview(note.body, note.styleRuns),
                    color = fgColor.copy(alpha = 0.56f),
                    fontSize = 13.sp,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private fun decodeThumb(base64: String) = runCatching {
    val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

