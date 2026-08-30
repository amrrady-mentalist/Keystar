package com.notesapp.offline

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.notesapp.offline.data.LockMode
import com.notesapp.offline.data.MagicRepository
import com.notesapp.offline.data.NotesRepository
import com.notesapp.offline.data.ThemeMode
import com.notesapp.offline.data.ThemeRepository
import com.notesapp.offline.ui.CovertTypingScreen
import com.notesapp.offline.ui.DeletePeekScreen
import com.notesapp.offline.ui.DrawingScreen
import com.notesapp.offline.ui.EffectEditorScreen
import com.notesapp.offline.ui.ForceListsScreen
import com.notesapp.offline.ui.InputMethodEditorScreen
import com.notesapp.offline.ui.LockFlowHost
import com.notesapp.offline.ui.LockFlowViewModel
import com.notesapp.offline.ui.LockFlowViewModelFactory
import com.notesapp.offline.ui.LockScreenState
import com.notesapp.offline.ui.MagicSettingsScreen
import com.notesapp.offline.ui.MultipleOutsScreen
import com.notesapp.offline.ui.NoteEditScreen
import com.notesapp.offline.ui.NotesListScreen
import com.notesapp.offline.ui.NotesViewModel
import com.notesapp.offline.ui.NotesViewModelFactory
import com.notesapp.offline.ui.VolumeTriggerBus
import com.notesapp.offline.ui.theme.NotesNativeTheme
import com.notesapp.offline.ui.theme.resolveDarkTheme
import kotlinx.coroutines.launch

/**
 * Screen state machine for the whole app. Opens straight to List — the
 * lock/blackout flow is no longer the cold-start screen; it's entered on
 * demand by double-tapping the "Notes" title, and LockFlowViewModel.prepare()
 * re-arms it each time so it can be used more than once per app session.
 *
 * Still a plain mutableState<Screen> rather than Navigation-Compose, same
 * reasoning as earlier phases: the graph is simple and linear enough that
 * a real nav library would add ceremony without adding safety here.
 */
sealed class Screen {
    data object List : Screen()
    data object Lock : Screen()
    data class Edit(val noteId: String?) : Screen()
    data class Drawing(val noteId: String) : Screen()
    data object MagicSettings : Screen()
    data object ForceLists : Screen()
    data object MultipleOuts : Screen()
    data object CovertTyping : Screen()
    data object DeletePeek : Screen()
    data class InputMethodEditor(val mode: LockMode) : Screen()
    data class EffectEditor(val effectId: String, val fromScreen: Screen = Screen.MagicSettings) : Screen()
    data class OutSketch(val effectId: String, val outId: String, val fromScreen: Screen = Screen.MultipleOuts) : Screen()
}

/** Screens that show the (transparent) status bar; everything else goes immersive.
 *  Screen.Lock is handled separately (see [LockScreenState.showsSystemBars]) since
 *  whether its status bar shows depends on which state the lock flow is in. */
private fun Screen.showsSystemBars(): Boolean =
    this is Screen.List || this is Screen.Edit || this is Screen.MagicSettings ||
        this is Screen.ForceLists || this is Screen.MultipleOuts || this is Screen.CovertTyping ||
        this is Screen.DeletePeek ||
        this is Screen.Drawing || this is Screen.EffectEditor || this is Screen.OutSketch ||
        this is Screen.InputMethodEditor

/** Within the lock flow, only the Ambient (AOD-style) clock screen shows the
 *  status bar — matches a real always-on-display, which still shows signal/
 *  battery. Blackout, PIN entry, and the fake home screen all stay fully
 *  immersive so nothing gives away that this isn't the device's real lock
 *  screen / launcher. */
private fun LockScreenState.showsSystemBars(): Boolean = this == LockScreenState.AMBIENT

class MainActivity : ComponentActivity() {

    private val notesRepo by lazy { NotesRepository(applicationContext) }
    private val magicRepo by lazy { MagicRepository(applicationContext) }
    private val themeRepo by lazy { ThemeRepository(applicationContext) }

    private val notesViewModel: NotesViewModel by viewModels {
        NotesViewModelFactory(notesRepo)
    }
    private val lockFlowViewModel: LockFlowViewModel by viewModels {
        LockFlowViewModelFactory(notesRepo, magicRepo)
    }

    /** Volume buttons only get intercepted here when a note-editing
     *  session has actually armed VolumeTriggerBus (see its own doc) —
     *  everywhere else, ACTION_DOWN falls through to super and the volume
     *  keys behave completely normally. Only ACTION_DOWN is checked (not
     *  onKeyUp too) so a press fires exactly once. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (VolumeTriggerBus.fire()) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {        CrashLog.install(this)
        installSplashScreen()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.DARK) }
            LaunchedEffect(Unit) { themeMode = themeRepo.load() }

            val isDark = resolveDarkTheme(themeMode)
            NotesNativeTheme(darkTheme = isDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // If the last session ended in a crash, show the report
                    // instead of the app — this is the whole point of
                    // CrashLog: readable diagnostics with no computer/adb
                    // needed. Dismissing clears it and continues as normal.
                    var crashReport by remember { mutableStateOf(CrashLog.read(this@MainActivity)) }
                    val report = crashReport
                    if (report != null) {
                        CrashReportScreen(
                            report = report,
                            onDismiss = {
                                CrashLog.clear(this@MainActivity)
                                crashReport = null
                            }
                        )
                    } else {
                        NotesApp(
                            notesViewModel = notesViewModel,
                            lockFlowViewModel = lockFlowViewModel,
                            notesRepo = notesRepo,
                            magicRepo = magicRepo,
                            themeRepo = themeRepo,
                            isDarkTheme = isDark,
                            onToggleTheme = {
                                themeMode = if (isDark) ThemeMode.LIGHT else ThemeMode.DARK
                                lifecycleScope.launch { themeRepo.save(themeMode) }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Full-screen, selectable/copyable crash report — read it or copy it
 *  straight from the phone, no adb or computer needed. */
@Composable
private fun CrashReportScreen(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "App crashed last time — here's the report:",
                style = MaterialTheme.typography.titleMedium
            )
            Box(
                Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            Button(onClick = {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("Crash report", report))
            }) {
                Text("Copy to clipboard")
            }
            Button(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp)) {
                Text("Dismiss and continue")
            }
        }
    }
}

@Composable
private fun NotesApp(
    notesViewModel: NotesViewModel,
    lockFlowViewModel: LockFlowViewModel,
    notesRepo: NotesRepository,
    magicRepo: MagicRepository,
    themeRepo: ThemeRepository,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    var editVersion by remember { mutableStateOf(0) }
    var effectVersion by remember { mutableStateOf(0) }
    val unlocked by lockFlowViewModel.unlocked.collectAsState()
    val lockScreenState by lockFlowViewModel.screen.collectAsState()
    val scope = rememberCoroutineScope()

    // The lock flow writes notes straight to NotesRepository, bypassing
    // NotesViewModel's in-memory cache (it can run before that ViewModel's
    // ever loaded anything for this screen). Force a reload right when it
    // hands control back, so a note an effect just created/updated is
    // actually visible on the list instead of waiting for some unrelated
    // trigger to refresh it.
    LaunchedEffect(unlocked) {
        if (unlocked) notesViewModel.refresh()
    }

    // Immersive mode everywhere except the "real app chrome" screens — and,
    // within the lock flow itself, everywhere except the Ambient clock
    // screen (see showsSystemBars()/LockScreenState.showsSystemBars()).
    val view = LocalView.current
    LaunchedEffect(screen, lockScreenState, isDarkTheme) {
        val activity = view.context as? Activity ?: return@LaunchedEffect
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, view)
        val showBars = if (screen is Screen.Lock) lockScreenState.showsSystemBars() else screen.showsSystemBars()
        if (showBars) {
            controller.show(WindowInsetsCompat.Type.statusBars())
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            // The lock flow's own screens (ambient clock included) are
            // always rendered on a dark/photo background, so the status
            // bar icons should stay light regardless of the app's own
            // light/dark theme setting — matches every other lock-flow
            // screen and avoids dark-on-dark icons on light theme.
            controller.isAppearanceLightStatusBars = if (screen is Screen.Lock) false else !isDarkTheme
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
    }

    if (screen is Screen.Lock) {
        if (unlocked) {
            screen = Screen.List
        } else {
            LockFlowHost(lockFlowViewModel)
            return
        }
    }

    BackHandler(enabled = screen !is Screen.List && screen !is Screen.Lock) {
        screen = when (val s = screen) {
            is Screen.Edit -> Screen.List
            is Screen.Drawing -> Screen.Edit(s.noteId)
            is Screen.MagicSettings -> Screen.List
            is Screen.ForceLists -> Screen.MagicSettings
            is Screen.MultipleOuts -> Screen.MagicSettings
            is Screen.CovertTyping -> Screen.MagicSettings
            is Screen.DeletePeek -> Screen.MagicSettings
            is Screen.InputMethodEditor -> Screen.MagicSettings
            is Screen.EffectEditor -> s.fromScreen
            is Screen.OutSketch -> Screen.EffectEditor(s.effectId, s.fromScreen)
            else -> Screen.List
        }
    }

    when (val s = screen) {
        is Screen.Lock -> Unit // handled above
        is Screen.List -> NotesListScreen(
            viewModel = notesViewModel,
            magicRepo = magicRepo,
            isDarkTheme = isDarkTheme,
            onOpenNote = { id -> screen = Screen.Edit(id) },
            onOpenMagicSettings = { screen = Screen.MagicSettings },
            onToggleTheme = onToggleTheme,
            onEnterLockFlow = {
                // Await prepare() BEFORE switching to Screen.Lock, so the
                // lock flow's screen state (blackout vs. straight to the
                // fake home screen) is already resolved by the time
                // LockFlowHost ever composes — this is what removes the
                // one-frame flash of black that used to show while
                // reset()'s async magicRepo.load() was still in flight.
                scope.launch {
                    lockFlowViewModel.prepare()
                    screen = Screen.Lock
                }
            }
        )
        is Screen.Edit -> key(s.noteId, editVersion) {
            NoteEditScreen(
                viewModel = notesViewModel,
                magicRepo = magicRepo,
                noteId = s.noteId,
                isDarkTheme = isDarkTheme,
                onBack = { screen = Screen.List },
                onOpenDrawing = { noteId -> screen = Screen.Drawing(noteId) }
            )
        }
        is Screen.Drawing -> {
            val note = notesViewModel.getNote(s.noteId)
            DrawingScreen(
                initialPngBase64 = note?.drawingPngBase64,
                isDarkTheme = isDarkTheme,
                onSave = { base64 ->
                    notesViewModel.saveDrawing(s.noteId, base64)
                    editVersion++ // force NoteEditScreen to re-read the fresh note on return
                    screen = Screen.Edit(s.noteId)
                },
                onBack = {
                    editVersion++
                    screen = Screen.Edit(s.noteId)
                }
            )
        }
        is Screen.MagicSettings -> MagicSettingsScreen(
            repo = magicRepo,
            notesRepo = notesRepo,
            themeRepo = themeRepo,
            isDarkTheme = isDarkTheme,
            onBack = { screen = Screen.List },
            onOpenEffect = { effectId -> screen = Screen.EffectEditor(effectId, Screen.MagicSettings) },
            onOpenForceLists = { screen = Screen.ForceLists },
            onOpenMultipleOuts = { screen = Screen.MultipleOuts },
            onOpenCovertTyping = { screen = Screen.CovertTyping },
            onOpenDeletePeek = { screen = Screen.DeletePeek },
            onOpenInputMethod = { mode -> screen = Screen.InputMethodEditor(mode) }
        )
        is Screen.ForceLists -> ForceListsScreen(
            repo = magicRepo,
            notesRepo = notesRepo,
            isDarkTheme = isDarkTheme,
            onBack = { screen = Screen.MagicSettings },
            onOpenEffect = { effectId -> screen = Screen.EffectEditor(effectId, Screen.ForceLists) }
        )
        is Screen.MultipleOuts -> MultipleOutsScreen(
            repo = magicRepo,
            notesRepo = notesRepo,
            isDarkTheme = isDarkTheme,
            onBack = { screen = Screen.MagicSettings },
            onOpenEffect = { effectId -> screen = Screen.EffectEditor(effectId, Screen.MultipleOuts) }
        )
        is Screen.CovertTyping -> CovertTypingScreen(
            repo = magicRepo,
            isDarkTheme = isDarkTheme,
            onBack = { screen = Screen.MagicSettings }
        )
        is Screen.DeletePeek -> DeletePeekScreen(
            repo = magicRepo,
            isDarkTheme = isDarkTheme,
            onBack = { screen = Screen.MagicSettings }
        )
        is Screen.InputMethodEditor -> InputMethodEditorScreen(
            repo = magicRepo,
            mode = s.mode,
            isDarkTheme = isDarkTheme,
            onBack = { screen = Screen.MagicSettings }
        )
        is Screen.EffectEditor -> key(s.effectId, effectVersion) {
            EffectEditorScreen(
                repo = magicRepo,
                notesViewModel = notesViewModel,
                effectId = s.effectId,
                isDarkTheme = isDarkTheme,
                onBack = { screen = s.fromScreen },
                onOpenSketch = { outId -> screen = Screen.OutSketch(s.effectId, outId, s.fromScreen) }
            )
        }
        is Screen.OutSketch -> {
            var initialPng by remember(s.effectId, s.outId) { mutableStateOf<String?>(null) }
            var loadedOut by remember(s.effectId, s.outId) { mutableStateOf(false) }
            val sketchScope = rememberCoroutineScope()

            LaunchedEffect(s.effectId, s.outId) {
                val store = magicRepo.load()
                val fx = store.effects.firstOrNull { it.id == s.effectId }
                initialPng = fx?.outs?.firstOrNull { it.id == s.outId }?.drawingPngBase64
                loadedOut = true
            }

            if (loadedOut) {
                DrawingScreen(
                    initialPngBase64 = initialPng,
                    isDarkTheme = isDarkTheme,
                    onSave = { base64 ->
                        sketchScope.launch {
                            val store = magicRepo.load()
                            val fx = store.effects.firstOrNull { it.id == s.effectId }
                            if (fx != null) {
                                val updated = fx.copy(
                                    outs = fx.outs.map { out ->
                                        if (out.id == s.outId) out.copy(drawingPngBase64 = base64) else out
                                    }
                                )
                                magicRepo.updateEffect(updated)
                            }
                            effectVersion++ // force EffectEditorScreen to re-read the fresh effect on return
                            screen = Screen.EffectEditor(s.effectId, s.fromScreen)
                        }
                    },
                    onBack = {
                        effectVersion++
                        screen = Screen.EffectEditor(s.effectId, s.fromScreen)
                    }
                )
            }
        }
    }
}
