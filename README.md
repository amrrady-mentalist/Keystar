# Custom Keyboard (native Android IME)

A real native input method — not a WebView wrapper. Written in Kotlin, renders its own
views directly, compiles through Gradle like any standard Android app.

## What it does

- English (QWERTY) and Arabic layouts, switchable with the 🌐 key
- Always-visible number row
- Shift (tap) / Caps Lock (double-tap) for English
- Symbols page (?123) with common punctuation and math symbols
- Emoji panel (🙂)
- Clipboard history panel (⧉) — shows your recent copies, tap to paste, swipe/tap ✕ to remove one, or clear all. Stored only on-device.
- Light / dark theme that follows the system automatically, with a manual override (Settings → Appearance)
- Settings screen to jump straight to Android's input method settings and the keyboard picker

## How to build it

1. Create a new GitHub repository.
2. Upload every file in this folder, keeping the structure (including the hidden `.github` folder).
3. Go to the repo's **Actions** tab — a build should start automatically (or hit "Run workflow").
4. When it's green, open the run → **Artifacts** → download `app-debug-apk`. Unzip it to get `app-debug.apk`.
5. Install it on your device (allow "install from unknown sources" if prompted).

## Enabling the keyboard on your phone

Installing the APK does **not** turn it on automatically — this is an Android requirement for every
keyboard app, not something specific to this one:

1. Open the app once. Tap **"Open Keyboard Settings"** → toggle on "Custom Keyboard".
2. Tap **"Switch Keyboard Now"** (or tap the keyboard icon on the system nav bar) and select it.

## Before you customize

- Package id is `com.example.customkeyboard` in `app/build.gradle` and `AndroidManifest.xml` — change this to your own if you plan to publish it or run it alongside other apps.
- Colors live in `res/values/colors.xml`.
- Key layouts (which letters go where) live in `KeyboardLayoutData.kt`.
- All the actual key-rendering and typing logic is in `CustomKeyboardService.kt`.

## Known limitations (by design, to keep this a clean starting point)

- No predictive text / word suggestions (that needs a language model or dictionary — a much bigger addition, happy to help you build one if that's useful)
- No voice input
- Long-press-for-alternate-characters (e.g. holding "e" for "é") isn't wired up yet

This hasn't been run through an actual Gradle build on my end (no network access in my sandbox), so
if the first Actions run throws an error, send me the log and I'll fix it — same as we've done before.
