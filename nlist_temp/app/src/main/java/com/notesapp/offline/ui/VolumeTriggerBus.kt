package com.notesapp.offline.ui

/**
 * Bridges a hardware volume-button press (only visible to MainActivity's
 * key event handling — Compose has no first-class way to intercept those)
 * to whichever note-editing session has currently armed the Inject
 * feature's volume trigger.
 *
 * A plain top-level singleton rather than threading a callback through the
 * whole navigation stack: at most one note can be open/armed at a time, so
 * there's never more than one legitimate listener, and the alternative
 * (passing this down through Screen/NavHost/ViewModel layers just for one
 * hardware-key concern) would add more ceremony than this simple arm/fire
 * contract does.
 */
object VolumeTriggerBus {
    private var handler: (() -> Unit)? = null

    /** Called once when a note-editing session becomes eligible (Inject
     *  Mode on, a Send-type effect active, that effect's volume trigger
     *  enabled) — replaces any previous handler outright rather than
     *  stacking, since only the current screen should ever be armed. */
    fun arm(onTrigger: () -> Unit) {
        handler = onTrigger
    }

    /** Called when that session ends (note closed, conditions stop
     *  holding) — always safe to call even if nothing was armed. */
    fun disarm() {
        handler = null
    }

    /** Called from MainActivity's key event handling. Returns true if a
     *  listener was armed and got the event (the caller should consume it,
     *  suppressing the real volume change) — false if nothing was armed,
     *  so the caller falls through to normal volume behavior. */
    fun fire(): Boolean {
        val h = handler ?: return false
        h()
        return true
    }
}
