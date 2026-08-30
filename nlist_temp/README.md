# Notes — Native Kotlin (Phase 1: Core Foundation)

This replaces the Capacitor/WebView version with a real native Android app,
same approach as Keystar (pure Kotlin, no XML layouts for the UI — Jetpack
Compose instead).

## What's in this phase

- `data/Note.kt` — the note model (id, title, body, timestamps, pinned flag)
- `data/NotesRepository.kt` — file-based JSON storage at
  `filesDir/notes.json`, the native equivalent of the old app's
  `localStorage` calls. No database yet, per your call — easy to read,
  easy to back up.
- `ui/NotesViewModel.kt` — holds the in-memory list as a `StateFlow`,
  talks to the repository on `Dispatchers.IO`.
- `ui/NotesListScreen.kt` — list of notes, tap to open, tap the pin icon to
  pin/unpin, long-press to delete.
- `ui/NoteEditScreen.kt` — create/edit a note, autosaves as you type.
- `MainActivity.kt` — single Activity, Compose-only, simple two-screen
  navigation (no Navigation-Compose dependency yet — trivial to swap in
  once the lock-screen flow needs a real nav graph).

This is deliberately plain Material 3 styling, not the liquid-glass look —
that's the next phase (UI shell) so we're not fighting styling bugs while
still validating that CRUD + storage actually works.

## Building it

Open the root folder in Android Studio (Koala/Ladybug or newer) and let it
sync — that's the easiest path since it'll handle the Gradle wrapper for
you automatically.

To build from the command line instead (e.g. CI), the included
`.github/workflows/build-apk.yml` installs Gradle directly and runs
`gradle assembleDebug` — no wrapper jar needed, no Node/Capacitor.

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

## What's next (in order, per what we agreed)

1. **This phase — core notes (done):** CRUD, list, file storage.
2. **Lock-screen / PIN concealment flow** — blackout → double-tap →
   ambient clock → swipe up → PIN → blackout, plus the force-list logic
   tied to PIN digits, ported from the web app's gesture handlers.
3. **UI shell** — liquid-glass panels, blob background, dark/light
   theming, swipe-between-tabs, checklist + rich text + drawing canvas
   inside notes, adaptive launcher icon layers.

Each phase builds on this one without breaking it — `Note` is written so
new fields (checklist items, rich text spans, drawing data) can be added
with defaults, and the repository's JSON format won't need a migration
step for phase 1 → phase 2.
