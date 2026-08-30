package com.notesapp.offline.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * One "code → word" outcome inside a Word-type effect — a direct port of
 * the web app's `out` object ({id, code, word, drawing}). [drawing] mirrors
 * the note-editor's own drawing storage: a base64 PNG, or null.
 */
@Serializable
data class EffectOut(
    val id: String = UUID.randomUUID().toString(),
    val code: String = "",
    val word: String = "",
    val drawingPngBase64: String? = null
)

@Serializable
enum class EffectType { WORD, LIST, INJECT_SUM, INJECT_PEEK }

/**
 * A single magic effect. Mirrors the web app's effect object exactly:
 * - WORD effects reveal a note whose title/body are built from [body],
 *   substituting `$$$$` with whichever [outs] entry's code matches the
 *   PIN's last digits (falling back to a "🧐" placeholder word if none
 *   match — same as the web app).
 * - LIST effects reveal/refresh a checklist note built from [items], with
 *   [forceWord] silently reordered to the position the PIN digits encode
 *   (see ForceListEngine — that logic is unchanged from before).
 * - INJECT_SUM / INJECT_PEEK don't touch the PIN/note-reveal flow at all —
 *   they're the "send" half of the Inject API feature. While one of these
 *   is the active effect (and Inject Mode is on in Magic Settings), opening
 *   any note arms whichever of [sendUseProximity]/[sendUseVolumeButton]
 *   this effect has enabled; firing that trigger reads whatever's
 *   currently on that note's screen and POSTs it to the configured API —
 *   the numeric total of the note's lines for INJECT_SUM, or the raw text
 *   as-is for INJECT_PEEK.
 *
 * Any text field below — [title], [body], [forceWord], each line of
 * [items] — can contain the literal token `--value--`. Whenever the PIN
 * reveal for a WORD/LIST effect resolves, that token is swapped for
 * whatever the Inject API most recently returned (fetched the instant the
 * first PIN digit was entered/swiped, so the network round-trip has the
 * whole rest of the PIN entry to finish) if Inject Mode is on, or removed
 * entirely (never shown, never left as literal "--value--" text) if
 * Inject Mode is off or nothing came back in time.
 *
 * [linkedNoteId] is only meaningful for LIST effects — the web app keeps
 * one persistent note per list effect and rewrites its content in place
 * (so re-triggering doesn't spam duplicate notes). WORD effects create a
 * fresh note every time they're triggered, same as the web app.
 */
@Serializable
data class MagicEffect(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val type: EffectType = EffectType.WORD,
    /** Whether this effect is switched on from the main Magic Settings
     *  screen. For LIST (Force List) and WORD (Multiple Outs) — the two
     *  PIN-reveal types — at most one of the two may be enabled at once
     *  (enforced by MagicRepository.setEffectEnabled, same mutual-exclusion
     *  rule as the Pin Code / Home Screen input-method toggle). PEEK and
     *  SUM (Math) have no such restriction and can be enabled independently
     *  of everything else. */
    val enabled: Boolean = false,
    // Word-type fields
    val title: String = "",
    val body: String = "",
    val outs: List<EffectOut> = emptyList(),
    // List-type fields
    val forceWord: String = "",
    val items: List<String> = emptyList(),
    val linkedNoteId: String? = null,
    // Inject-reveal (send mode) fields — only meaningful for INJECT_SUM/INJECT_PEEK.
    /** Waving a hand over / covering the proximity sensor fires the send —
     *  matches "phone face down on the table or held to the spectator's
     *  chest", where reaching a volume button isn't practical. */
    val sendUseProximity: Boolean = true,
    /** A volume button press fires the send instead of/in addition to
     *  proximity — doesn't change the actual volume while armed. Useful as
     *  a backup, or when proximity isn't practical for a given routine. */
    val sendUseVolumeButton: Boolean = false,
    /** Whether the send-to-API behavior is armed for this effect. Applies
     *  to WORD (Multiple Outs — sends whatever word $$$$ resolved to),
     *  INJECT_PEEK, and INJECT_SUM (Math). Ignored for LIST, which is
     *  receive-only. */
    val injectSendOn: Boolean = true,
    /** Whether the receive-from-API behavior is armed for this effect.
     *  Applies to INJECT_PEEK and INJECT_SUM (Math): firing the trigger on
     *  an empty note fills it with the latest API value; firing it on a
     *  note that contains the literal --value-- token replaces that token
     *  with the latest API value instead. */
    val injectReceiveOn: Boolean = false,
    /** Math (INJECT_SUM) only. An equation referencing each line of the
     *  note by position — "1st", "2nd", "3rd", "4th", "5th", ... "10th",
     *  combined with +, -, *, /, and parentheses, e.g. "(1st+2nd)-(3rd+4th)".
     *  Blank means "sum every line", matching the original behavior. */
    val mathEquation: String = ""
)

@Serializable
enum class CovertLetterPosition {
    FIRST, SECOND, THIRD, LAST
}

@Serializable
data class CovertTypingConfig(
    val enabled: Boolean = false,
    val preSavedSentence: String = "Don't forget to pick up groceries and water for the team.",
    val sendToInject: Boolean = true,
    val revealOnSubsequentLines: Boolean = true,
    val targetLetterPosition: CovertLetterPosition = CovertLetterPosition.FIRST
)

@Serializable
data class DeletePeekConfig(
    val enabled: Boolean = false,
    val sendToInject: Boolean = true,
    val localPushNotification: Boolean = true,
    val triggerVolumeButton: Boolean = true,
    val triggerProximity: Boolean = true
)

@Serializable
enum class LockMode { CLASSIC, HOME_SCREEN }

/**
 * Everything the magic settings screen manages, persisted as one JSON blob
 * — mirrors the web app's single `magic` object (effects, activeEffectId,
 * lockMode) rather than the earlier single-effect model.
 */
@Serializable
data class MagicStore(
    val effects: List<MagicEffect> = emptyList(),
    val activeEffectId: String? = null,
    val lockMode: LockMode = LockMode.CLASSIC,
    /** Path to a copied-in background image file for the classic PIN lock screen. */
    val lockBackgroundPath: String? = null,
    /** Path to a copied-in wallpaper image file for the (future) home-screen disguise. */
    val homeWallpaperPath: String? = null,
    /** Path to a copied-in override icon for the notes app itself, in home-screen mode. */
    val notesIconPath: String? = null,
    /** Fake-app key (e.g. "phone", "messages") -> overridden icon file path. */
    val appIconOverrides: Map<String, String> = emptyMap(),
    /** Fake-app original name -> overridden display name shown on the fake home screen. */
    val appNameOverrides: Map<String, String> = emptyMap(),
    /** A real Android widget the user picked to sit in the top widget slot
     *  on the fake home screen's first page (replaced the old hand-drawn
     *  "Google search bar" look). Flattened ComponentName string, e.g.
     *  "com.google.android.googlequicksearchbox/.SearchWidgetProvider". */
    val homeWidgetProvider: String? = null,
    /** The AppWidgetHost-allocated id bound to [homeWidgetProvider]. -1 = none picked. */
    val homeWidgetId: Int = -1,
    /** The Inject API endpoint — same URL used for both directions: GET to
     *  receive the latest `--value--` (see MagicEffect's doc), POST to send
     *  an INJECT_SUM/INJECT_PEEK effect's result. */
    val apiUrl: String? = null,
    /** Master switch for the whole Inject feature. Off means: never poll
     *  the API for `--value--` (and strip that token from any effect text
     *  that has it, rather than showing it literally), and never arm the
     *  proximity/volume send triggers no matter what the active effect is. */
    val injectModeOn: Boolean = false,
    /** Covert Typing effect configuration. */
    val covertTyping: CovertTypingConfig = CovertTypingConfig(),
    /** Delete Peek effect configuration. */
    val deletePeek: DeletePeekConfig = DeletePeekConfig()
) {
    val activeEffect: MagicEffect? get() = effects.firstOrNull { it.id == activeEffectId }

    /** The one PIN-reveal effect currently in play — Force List (LIST) or
     *  Multiple Outs (WORD), whichever is enabled. Mutually exclusive by
     *  construction (MagicRepository.setEffectEnabled enforces it), so at
     *  most one of these ever comes back non-null. */
    val enabledPinEffect: MagicEffect?
        get() = effects.firstOrNull { it.enabled && (it.type == EffectType.LIST || it.type == EffectType.WORD) }

    fun effectOfType(type: EffectType): MagicEffect? = effects.firstOrNull { it.type == type }
}

/** Fixed slugs for the 4 fixed effect slots — used to look effects up by
 *  role rather than by a user-editable name. */
object EffectNames {
    const val FORCE_LIST = "Force List"
    const val MULTIPLE_OUTS = "Multiple Outs"
    const val PEEK = "Peek"
    const val MATH = "Math"
}
