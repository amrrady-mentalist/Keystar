package com.notesapp.offline.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.notesapp.offline.data.EffectType
import com.notesapp.offline.data.ForceListEngine
import com.notesapp.offline.data.InjectApiClient
import com.notesapp.offline.data.LockMode
import com.notesapp.offline.data.MagicRepository
import com.notesapp.offline.data.Note
import com.notesapp.offline.data.NotesRepository
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class LockScreenState { BLACKOUT, AMBIENT, PIN, HOME_SCREEN }

/** The literal placeholder token a MagicEffect's text fields can contain —
 *  see MagicEffect's own doc for the full behavior. */
const val INJECT_VALUE_TOKEN = "--value--"

class LockFlowViewModel(
    private val notesRepo: NotesRepository,
    private val magicRepo: MagicRepository,
    private val injectApiClient: InjectApiClient = InjectApiClient()
) : ViewModel() {

    private val _screen = MutableStateFlow(LockScreenState.BLACKOUT)
    val screen: StateFlow<LockScreenState> = _screen.asStateFlow()

    private val _pinDigits = MutableStateFlow("")
    val pinDigits: StateFlow<String> = _pinDigits.asStateFlow()

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _unlocking = MutableStateFlow(false)
    /** True for the brief window between the 4th PIN digit landing and
     *  [unlocked] actually flipping — PinScreen watches this to play an
     *  "unlocking" animation (padlock opening / keypad dissolving) instead
     *  of just hard-cutting to the note list the instant the digit lands. */
    val unlocking: StateFlow<Boolean> = _unlocking.asStateFlow()

    private val _lockBackgroundPath = MutableStateFlow<String?>(null)
    /** Path to the classic-lock background photo, if one's set in Magic
     *  Settings — read fresh every time the flow (re)starts via reset(). */
    val lockBackgroundPath: StateFlow<String?> = _lockBackgroundPath.asStateFlow()

    // ---- Home Screen disguise mode ----
    private val _hsWallpaperPath = MutableStateFlow<String?>(null)
    val hsWallpaperPath: StateFlow<String?> = _hsWallpaperPath.asStateFlow()

    private val _hsNotesIconPath = MutableStateFlow<String?>(null)
    val hsNotesIconPath: StateFlow<String?> = _hsNotesIconPath.asStateFlow()

    private val _hsIconOverrides = MutableStateFlow<Map<String, String>>(emptyMap())
    val hsIconOverrides: StateFlow<Map<String, String>> = _hsIconOverrides.asStateFlow()

    private val _hsNameOverrides = MutableStateFlow<Map<String, String>>(emptyMap())
    val hsNameOverrides: StateFlow<Map<String, String>> = _hsNameOverrides.asStateFlow()

    private val _hsWidgetProvider = MutableStateFlow<String?>(null)
    val hsWidgetProvider: StateFlow<String?> = _hsWidgetProvider.asStateFlow()

    private val _hsWidgetId = MutableStateFlow(-1)
    val hsWidgetId: StateFlow<Int> = _hsWidgetId.asStateFlow()

    private val _hsRequiredDigits = MutableStateFlow(2)
    /** How many swipe-pages the fake home screen needs — one digit per
     *  page, same rule the web app used: the longest configured Word-Force
     *  out code, or the List-Force's item-count digit width, minimum 2. */
    val hsRequiredDigits: StateFlow<Int> = _hsRequiredDigits.asStateFlow()

    // ---- Inject API (receive side) ----
    /** Kicked off the instant the first PIN digit lands (see
     *  [triggerInjectPrefetch]) rather than waiting until the PIN is fully
     *  entered — a real network round-trip needs however much of a head
     *  start it can get before resolveEffectFor() actually needs the
     *  result. Null once consumed/reset by [prepare]. */
    private var injectFetchDeferred: Deferred<String?>? = null

    /** Called by both PIN-entry paths (classic keypad's onPinKey, and the
     *  fake home screen's swipe handler) the moment their FIRST digit is
     *  entered/swiped — not on every digit, just the first, since that's
     *  the earliest possible moment to start the GET and the only one that
     *  matters for giving it a head start. Safe to call even when Inject
     *  Mode is off or no URL is set: the check happens inside the async
     *  block itself, so this is always a cheap, fire-and-forget call from
     *  the caller's perspective. */
    fun triggerInjectPrefetch() {
        if (injectFetchDeferred != null) return // already in flight for this PIN attempt
        injectFetchDeferred = viewModelScope.async(Dispatchers.IO) {
            val store = magicRepo.load()
            val url = store.apiUrl
            if (!store.injectModeOn || url.isNullOrBlank()) return@async null
            injectApiClient.fetchValue(url)
        }
    }

    fun onBlackoutDoubleTap() {
        _screen.value = LockScreenState.AMBIENT
    }

    fun onAmbientSwipeUp() {
        _screen.value = LockScreenState.PIN
    }

    fun onPinAbortDoubleTap() {
        _pinDigits.value = ""
        _screen.value = LockScreenState.BLACKOUT
    }

    /** The fake home screen's hidden "Lock" dock icon — double-tapping it
     *  bails out of the disguise entirely (no PIN resolved, no note
     *  touched) straight back to the real note list. Reuses the same
     *  `unlocked` signal the successful-trick path uses since both cases
     *  want the exact same navigation result. */
    fun abortHomeScreenFlow() {
        _unlocked.value = true
    }

    /** Called once the performer taps the disguised Notes icon on the fake
     *  home screen's final page — resolves the effect (same logic the
     *  classic keypad uses) and unlocks immediately once that's done, with
     *  no animation on this path at all. There WAS a zoom/fade transition
     *  here, but HomeScreenFlowScreen and the real note list are two
     *  entirely separate composables — switching between them is always a
     *  hard cut in Compose, no matter how precisely an animation on the
     *  fake-screen side is timed, since there's no cross-fade between the
     *  two composable trees. That mismatch was reading as a stutter/snap
     *  no matter how the timing was tuned. Matching the same instant,
     *  no-animation switch [abortHomeScreenFlow] already uses removes the
     *  thing that could desync in the first place. */
    fun resolveHomeScreenPin(pin: String) {
        viewModelScope.launch {
            resolveEffectFor(pin)
            _unlocked.value = true
        }
    }

    /**
     * Re-arms the flow for another performance without restarting the app.
     *
     * This is a suspend function (rather than firing a fire-and-forget
     * viewModelScope.launch internally) so the CALLER can await it before
     * ever navigating to the Lock screen. That matters: the old version set
     * `_screen.value = BLACKOUT` synchronously up front and only flipped it
     * to HOME_SCREEN once the async magicRepo.load() finished, which meant
     * a Home-Screen-mode performer always saw a flash of black screen for
     * a frame or two before the fake home screen appeared. Awaiting
     * prepare() first means _screen is only ever set ONCE, already holding
     * the correct final value, so nothing flashes.
     */
    suspend fun prepare() {
        _pinDigits.value = ""
        _unlocked.value = false
        _unlocking.value = false
        injectFetchDeferred = null

        val store = magicRepo.load()
        _lockBackgroundPath.value = store.lockBackgroundPath
        _hsWallpaperPath.value = store.homeWallpaperPath
        _hsNotesIconPath.value = store.notesIconPath
        _hsIconOverrides.value = store.appIconOverrides
        _hsNameOverrides.value = store.appNameOverrides
        _hsWidgetProvider.value = store.homeWidgetProvider
        _hsWidgetId.value = store.homeWidgetId

        val fx = store.enabledPinEffect
        _hsRequiredDigits.value = when {
            fx == null -> 2
            fx.type == EffectType.LIST -> ForceListEngine.codeDigits(fx.items)
            else -> (fx.outs.maxOfOrNull { it.code.length } ?: 2).coerceAtLeast(2)
        }

        _screen.value = if (store.lockMode == LockMode.HOME_SCREEN) {
            LockScreenState.HOME_SCREEN
        } else {
            LockScreenState.BLACKOUT
        }
    }

    /** Fire-and-forget variant of [prepare] for call sites that can't
     *  suspend. Prefer calling `prepare()` directly from a coroutine and
     *  awaiting it before navigating, to avoid the blackout flash. */
    fun reset() {
        viewModelScope.launch { prepare() }
    }

    fun onPinKey(key: String) {
        when (key) {
            "delete" -> _pinDigits.value = _pinDigits.value.dropLast(1)
            "empty" -> Unit
            else -> {
                if (_pinDigits.value.length < 4) {
                    _pinDigits.value += key
                    if (_pinDigits.value.length == 1) triggerInjectPrefetch()
                    if (_pinDigits.value.length == 4) resolvePin(_pinDigits.value)
                }
            }
        }
    }

    /**
     * Direct port of the web app's resolvePin(): ANY 4 digits resolve and
     * unlock — there's no "correct" PIN to fail on. The digits are only
     * ever used as positional/lookup input for whichever effect is active.
     *
     * - No active effect: unlocks with no note created (matches the JS,
     *   which only acts `if(fx)`).
     * - LIST effect: same force-list math as before, now keyed off the
     *   active effect specifically rather than a single global effect.
     * - WORD effect: looks up the out whose code matches the PIN's last
     *   N digits (N = the longest configured out code, min 2 — mirrors
     *   the JS's codeLen expansion), substitutes the matched word (or a
     *   "🧐" placeholder if nothing matches) into the body wherever
     *   "$$$$" appears, and creates a fresh note every time — the web
     *   app never reuses/updates a previous note for word effects, only
     *   for list effects.
     */
    private fun resolvePin(pin: String) {
        _unlocking.value = true
        viewModelScope.launch {
            resolveEffectFor(pin)

            // Give PinScreen's unlocking animation (padlock opening,
            // keypad fading/scaling away) time to actually play before the
            // screen gets swapped out from under it — the note work above
            // already happened, this delay is purely for the visual.
            kotlinx.coroutines.delay(UNLOCK_ANIM_MS)
            _unlocked.value = true
        }
    }

    /** The actual note-creation/lookup work shared by both unlock paths —
     *  kept separate from resolvePin's own post-resolve animation delay
     *  since only the classic PIN path needs one (see resolveHomeScreenPin,
     *  which unlocks immediately with no animation at all). */
    private suspend fun resolveEffectFor(pin: String) {
        val store = magicRepo.load()
        val fx = store.enabledPinEffect

        // INJECT_SUM/INJECT_PEEK are the "send" half of the Inject feature
        // — they're never triggered by PIN entry, only by opening a note
        // and firing their proximity/volume trigger (see NoteEditScreen),
        // so there's nothing for a PIN reveal to do here.
        if (fx == null) return

        // Await whatever triggerInjectPrefetch() kicked off on the first
        // PIN digit — giving it up to INJECT_FETCH_TIMEOUT_MS more here
        // (on top of however long the rest of the PIN entry already took)
        // before giving up. Off, no URL configured, or the fetch simply
        // failed/timed out all collapse to the same "no value" outcome —
        // injectValue() strips the token either way, per Inject Mode's
        // documented off-behavior.
        val injectedValue: String? = if (store.injectModeOn) {
            withTimeoutOrNull(INJECT_FETCH_TIMEOUT_MS) { injectFetchDeferred?.await() }
        } else null
        fun injectValue(text: String): String =
            if (injectedValue != null) text.replace(INJECT_VALUE_TOKEN, injectedValue)
            else text.replace(INJECT_VALUE_TOKEN, "")

        val codeLen = when (fx.type) {
            EffectType.LIST -> ForceListEngine.codeDigits(fx.items)
            else -> (fx.outs.maxOfOrNull { it.code.length } ?: 2).coerceAtLeast(2)
        }
        val lastDigits = if (pin.length >= codeLen) pin.takeLast(codeLen) else pin

        when (fx.type) {
            EffectType.LIST -> {
                val rawForceWord = fx.forceWord.trim()
                val forced = if (rawForceWord == INJECT_VALUE_TOKEN) {
                    // Force Item is literally --value-- — the spectator's
                    // word comes back from Inject and isn't necessarily one
                    // of the predefined items, so it can't be found by
                    // buildForcedList()'s exact-match search (that was the
                    // bug: nothing matched, so the list came back
                    // unforced). Insert it directly at the PIN-encoded
                    // position instead. Any literal "--value--" line left
                    // in the item list from the old placeholder-item
                    // workaround is dropped first so it doesn't linger
                    // alongside the real result.
                    val plain = fx.items.filterNot { it.trim() == INJECT_VALUE_TOKEN }
                    val relevant = ForceListEngine.relevantDigits(plain, lastDigits)
                    ForceListEngine.insertForcedValue(plain, injectValue(rawForceWord), relevant)
                } else {
                    val relevant = ForceListEngine.relevantDigits(fx.items, lastDigits)
                    // --value-- substitution happens BEFORE the force lookup —
                    // this lets a Force Item that merely CONTAINS --value--
                    // (e.g. as part of a longer string) still resolve, as long
                    // as the resulting text matches one of the items above.
                    // Only this local copy is substituted — the effect's own
                    // stored forceWord/items are never overwritten with a
                    // resolved value.
                    ForceListEngine.buildForcedList(fx.items.map(::injectValue), injectValue(rawForceWord), relevant)
                }

                val allNotes = notesRepo.loadAll()
                val existing = fx.linkedNoteId?.let { id -> allNotes.firstOrNull { it.id == id } }

                // A numbered plain-text list ("1 - Item"), matching
                // the web app's <ol><li> rendering — not an actual
                // checkbox checklist, which reads as a to-do list
                // rather than a forced sequence of items.
                val numbered = forced.mapIndexed { i, item -> "${i + 1} - $item" }.joinToString("\n")
                val note = (existing ?: Note(magicEffectId = fx.id)).copy(
                    title = injectValue(fx.title),
                    body = numbered,
                    checklist = emptyList(),
                    pinned = true,
                    archived = false,
                    updatedAt = System.currentTimeMillis()
                )
                notesRepo.upsert(note)
                if (existing == null) {
                    magicRepo.updateEffect(fx.copy(linkedNoteId = note.id))
                }
            }
            EffectType.WORD -> {
                val target = lastDigits.toIntOrNull()
                val match = fx.outs.firstOrNull { it.code.isNotEmpty() && it.code.toIntOrNull() == target }
                val word = if (match != null) match.word else "\uD83E\uDDD0" // 🧐 — matches the web app's fallback
                val note = Note(
                    title = injectValue(fx.title),
                    body = injectValue(fx.body.replace("$$$$", word)),
                    drawingPngBase64 = match?.drawingPngBase64,
                    pinned = true,
                    archived = false,
                    magicEffectId = fx.id
                )
                notesRepo.upsert(note)
            }
            else -> Unit // INJECT_SUM/INJECT_PEEK already returned above
        }
    }

    companion object {
        const val UNLOCK_ANIM_MS = 480L
        /** How much longer resolveEffectFor() will wait for the Inject API
         *  fetch kicked off on the first PIN digit, on top of however long
         *  the rest of PIN entry already took. */
        const val INJECT_FETCH_TIMEOUT_MS = 6000L
    }
}

class LockFlowViewModelFactory(
    private val notesRepo: NotesRepository,
    private val magicRepo: MagicRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return LockFlowViewModel(notesRepo, magicRepo) as T
    }
}
