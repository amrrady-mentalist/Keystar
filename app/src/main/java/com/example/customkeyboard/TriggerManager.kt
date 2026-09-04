package com.example.customkeyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Universal Hardware & Sensor Trigger Manager for covert mentalism effects.
 *
 * Restricts dispatching secrets (Delete Peek, Math calculations, and Covert Typing)
 * to API or discreet notification until armed triggers are activated:
 * 1) Hardware Volume Buttons (Volume Up / Down)
 * 2) Proximity Sensor (Hand wave / covering top of phone)
 */
object TriggerManager {

    private const val PREFS_NAME = "magic_trigger_prefs"
    private const val KEY_REQUIRE_TRIGGER = "key_require_trigger_dispatch"
    private const val KEY_VOL_TRIGGER = "key_volume_trigger_enabled"
    private const val KEY_PROX_TRIGGER = "key_proximity_trigger_enabled"
    private const val KEY_HAPTIC_TRIGGER = "key_haptic_trigger_enabled"
    private const val KEY_PROX_SENSITIVITY = "key_proximity_sensitivity"

    const val SENSITIVITY_LOW = "low"
    const val SENSITIVITY_MEDIUM = "medium"
    const val SENSITIVITY_HIGH = "high"

    private var appContextRef: WeakReference<Context>? = null
    private var covertManagerRef: WeakReference<CovertManager>? = null

    // Pending payloads waiting for trigger activation
    @Volatile
    var pendingDeletedWord: String? = null
    @Volatile
    var pendingMathPayload: String? = null
    @Volatile
    var pendingCovertWord: String? = null
    @Volatile
    var pendingTextPeekPayload: String? = null

    // Real-time status callbacks for UI and IME integration
    var onTriggerFired: ((source: String, summary: String) -> Unit)? = null
    var onProximityChanged: ((isNear: Boolean) -> Unit)? = null
    var onPendingStateChanged: (() -> Unit)? = null

    // Direct IME hooks for active input field manipulation
    var onExecuteTextReplacement: ((Context, CovertManager) -> Boolean)? = null
    var onCaptureLiveText: (() -> String)? = null
    var onCaptureLiveCursorContext: (() -> Pair<String, String>)? = null

    // Sensor & Audio State
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityListener: SensorEventListener? = null
    private var isProximityRegistered = false
    private var lastProximityNearTime = 0L
    private var wasProximityNear = false

    private var audioManager: AudioManager? = null
    private var volumeObserver: ContentObserver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var isVolumeObserverRegistered = false
    private var lastObservedVolume = -1
    private var lastTriggerTime = 0L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isRequireTriggerEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_REQUIRE_TRIGGER, true)
    }

    fun setRequireTriggerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_REQUIRE_TRIGGER, enabled).apply()
        onPendingStateChanged?.invoke()
    }

    fun isVolumeTriggerEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VOL_TRIGGER, true)
    }

    fun isProximityTriggerEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PROX_TRIGGER, true)
    }

    private var isSessionActive = false

    fun isHapticTriggerEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAPTIC_TRIGGER, true)
    }

    fun setHapticTriggerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HAPTIC_TRIGGER, enabled).apply()
    }

    fun setProximityTriggerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PROX_TRIGGER, enabled).apply()
        syncTriggersState(context)
    }

    fun setVolumeTriggerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VOL_TRIGGER, enabled).apply()
        syncTriggersState(context)
    }

    fun getProximitySensitivity(context: Context): String {
        return getPrefs(context).getString(KEY_PROX_SENSITIVITY, SENSITIVITY_MEDIUM) ?: SENSITIVITY_MEDIUM
    }

    fun setProximitySensitivity(context: Context, level: String) {
        getPrefs(context).edit().putString(KEY_PROX_SENSITIVITY, level).apply()
        stopSensors()
        startSensors(context)
    }

    /**
     * Checks Condition 1: Is this keyboard currently selected as the default/main input method on the device?
     */
    fun isKeyboardSelectedAsDefault(context: Context): Boolean {
        return try {
            val defaultIme = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            ) ?: ""
            val myPackage = context.packageName
            defaultIme.contains(myPackage)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks Condition 2: Is ANY magic effect that uses triggers currently active?
     */
    fun isAnyMagicEffectActive(context: Context): Boolean {
        val cm = covertManagerRef?.get() ?: CovertManager(context)
        return cm.isAnyMagicEffectActive()
    }

    /**
     * Determines whether hardware sensors and observers are allowed to run.
     * Active if at least one magic effect is enabled AND the keyboard is actively in use
     * (either active session/switched to, or selected as default keyboard).
     */
    fun shouldTriggersBeActive(context: Context): Boolean {
        val hasEffect = isAnyMagicEffectActive(context)
        val isServiceActive = isSessionActive || isKeyboardSelectedAsDefault(context)
        return isServiceActive && hasEffect
    }

    /**
     * Evaluates trigger active conditions and starts or stops sensors/observers accordingly.
     */
    fun syncTriggersState(context: Context) {
        val appCtx = context.applicationContext
        if (shouldTriggersBeActive(appCtx)) {
            if (isProximityTriggerEnabled(appCtx)) {
                startSensors(appCtx)
            } else {
                stopSensors()
            }
            if (isVolumeTriggerEnabled(appCtx)) {
                startVolumeObserver(appCtx)
            } else {
                stopVolumeObserver(appCtx)
            }
        } else {
            // Either keyboard is not the default typing method, or NO magic effect is enabled: release sensors
            stopSensors()
            stopVolumeObserver(appCtx)
        }
    }

    /**
     * Initializes TriggerManager with application context and CovertManager instance.
     */
    fun init(context: Context, covertManager: CovertManager? = null) {
        val appCtx = context.applicationContext
        appContextRef = WeakReference(appCtx)
        if (covertManager != null) {
            covertManagerRef = WeakReference(covertManager)
        }
        syncTriggersState(appCtx)
    }

    /**
     * Starts or synchronizes active trigger session according to prerequisites.
     */
    fun startActiveSession(context: Context) {
        isSessionActive = true
        val appCtx = context.applicationContext
        appContextRef = WeakReference(appCtx)
        syncTriggersState(appCtx)
    }

    /**
     * Stops triggers when the service/activity is destroyed.
     */
    fun stopActiveSession(context: Context) {
        isSessionActive = false
        stopSensors()
        stopVolumeObserver(context)
    }

    fun setCovertManager(covertManager: CovertManager) {
        covertManagerRef = WeakReference(covertManager)
    }

    /**
     * Queues a deleted word from Delete Peek effect.
     */
    fun queueDeletedWord(word: String, context: Context, covertManager: CovertManager) {
        if (word.isBlank()) return
        // Clear conflicting stale payloads from other effects
        pendingMathPayload = null
        pendingCovertWord = null

        if (isRequireTriggerEnabled(context)) {
            pendingDeletedWord = word
            onPendingStateChanged?.invoke()
        } else {
            // Immediate mode: dispatch right away
            if (covertManager.isDeletePeekEnabled) {
                if (covertManager.deletePeekLocalNotification) {
                    DeletePeekMemory.showPushNotification(context, word)
                }
                if (covertManager.deletePeekSendToInject && covertManager.isInjectApiEnabled) {
                    covertManager.dispatchInjectApi(word)
                }
            }
        }
    }

    /**
     * Queues a calculated math total or specific line payload from Math Magic effect.
     */
    fun queueMathPayload(payload: String, context: Context, covertManager: CovertManager) {
        if (payload.isBlank()) return
        // Clear conflicting stale payloads from other effects
        pendingDeletedWord = null
        pendingCovertWord = null

        if (isRequireTriggerEnabled(context)) {
            pendingMathPayload = payload
            onPendingStateChanged?.invoke()
        } else {
            // Immediate mode: dispatch right away if enabled
            if (covertManager.isMathEnabled) {
                if (covertManager.mathLocalNotification) {
                    DeletePeekMemory.showPushNotification(context, payload)
                }
                if (covertManager.mathSendToInject && covertManager.isInjectApiEnabled) {
                    covertManager.dispatchInjectApi(payload)
                }
            }
        }
    }

    /**
     * Queues and immediately transmits a secret covert word from Covert Typing effect after double-space.
     */
    fun queueCovertWord(word: String, context: Context, covertManager: CovertManager) {
        if (word.isBlank()) return
        // Clear conflicting stale payloads from other effects
        pendingDeletedWord = null
        pendingMathPayload = null
        pendingTextPeekPayload = null

        if (isRequireTriggerEnabled(context)) {
            pendingCovertWord = word
            onPendingStateChanged?.invoke()
        } else {
            // Immediately transmit to Local Notification after double space if enabled
            if (covertManager.covertLocalNotification) {
                DeletePeekMemory.showPushNotification(context, word)
            }

            // Immediately transmit to Inject API after double space if enabled
            if (covertManager.covertSendToInject && covertManager.isInjectApiEnabled) {
                covertManager.dispatchInjectApi(word)
            }
        }
    }

    /**
     * Queues a captured text / word / line from the Any Word / Line Peek effect.
     */
    fun queueTextPeek(payload: String, context: Context, covertManager: CovertManager) {
        if (payload.isBlank()) return
        // Clear conflicting stale payloads from other effects
        pendingDeletedWord = null
        pendingMathPayload = null
        pendingCovertWord = null

        if (isRequireTriggerEnabled(context)) {
            pendingTextPeekPayload = payload
            onPendingStateChanged?.invoke()
        } else {
            // Immediate mode: dispatch right away
            if (covertManager.isTextPeekEnabled) {
                if (covertManager.textPeekLocalNotification) {
                    DeletePeekMemory.showPushNotification(context, payload)
                }
                if (covertManager.textPeekSendToInject && covertManager.isInjectApiEnabled) {
                    covertManager.dispatchInjectApi(payload)
                }
            }
        }
    }

    /**
     * Fires trigger from any source (Volume button, Proximity sensor, or Manual test button).
     * When Text Replacement from API is enabled, checks the API for the latest information
     * immediately upon trigger activation and performs replacement with the newest data.
     */
    @Synchronized
    fun fireTrigger(source: String, customContext: Context? = null): Boolean {
        val context = customContext ?: appContextRef?.get() ?: return false
        val covertManager = covertManagerRef?.get() ?: CovertManager(context)

        val now = System.currentTimeMillis()
        val sensitivity = getProximitySensitivity(context)
        val minInterval = when (sensitivity) {
            SENSITIVITY_LOW -> 1400L
            SENSITIVITY_HIGH -> 650L
            else -> 950L
        }
        if (now - lastTriggerTime < minInterval) {
            // Debounce rapid multiple triggers based on configured sensitivity
            return false
        }
        lastTriggerTime = now

        // If an effect receives info from API (like API Text Replacement), check the API for the LATEST info right after trigger
        if (covertManager.isTextReplaceEnabled && covertManager.replaceSourceMode == "api") {
            CoroutineScope(Dispatchers.IO).launch {
                // Fetch latest live data directly from the API endpoint
                covertManager.fetchLatestApiValueSync()
                withContext(Dispatchers.Main) {
                    dispatchTriggerPayloads(source, context, covertManager)
                }
            }
        } else {
            dispatchTriggerPayloads(source, context, covertManager)
        }

        return true
    }

    private fun dispatchTriggerPayloads(source: String, context: Context, covertManager: CovertManager) {
        val dispatchedItems = mutableListOf<String>()

        // 1. If API Text Replace is enabled, trigger live replacement in active input field
        if (covertManager.isTextReplaceEnabled) {
            val replaced = onExecuteTextReplacement?.invoke(context, covertManager) ?: false
            if (replaced) {
                val placeholder = covertManager.replacePlaceholder.trim()
                val replacement = covertManager.getEffectiveReplacementValue()
                val sourceLabel = if (covertManager.replaceSourceMode == "custom") "Pre-saved Text" else "API Data"
                val targetDesc = if (placeholder.isEmpty()) "ALL text in writing area" else "\"$placeholder\""
                dispatchedItems.add("Replaced $targetDesc with \"$replacement\" ($sourceLabel)")
            }
        }

        // 2. If Text Peek is active and pending is null, attempt to capture live text from current input
        if (covertManager.isTextPeekEnabled && pendingTextPeekPayload == null) {
            val cursorContext = onCaptureLiveCursorContext?.invoke()
            val extracted = if (cursorContext != null) {
                covertManager.extractTextPeekPayload(cursorContext.first, cursorContext.second)
            } else {
                val liveText = onCaptureLiveText?.invoke() ?: ""
                covertManager.extractTextPeekPayload(liveText)
            }
            if (!extracted.isNullOrBlank()) {
                pendingTextPeekPayload = extracted
            }
        }

        // Dispatch the newest active effect payload (priority: Text Peek -> Covert -> Math -> Delete Peek)
        val textPeekPayload = pendingTextPeekPayload
        val covertWord = pendingCovertWord
        val mathPayload = pendingMathPayload
        val delWord = pendingDeletedWord

        if (!textPeekPayload.isNullOrBlank()) {
            if (covertManager.isTextPeekEnabled) {
                if (covertManager.textPeekLocalNotification) {
                    DeletePeekMemory.showPushNotification(context, textPeekPayload)
                }
                if (covertManager.textPeekSendToInject && covertManager.isInjectApiEnabled) {
                    covertManager.dispatchInjectApi(textPeekPayload)
                }
                val modeLabel = when (covertManager.textPeekMode) {
                    "cursor_line", "last_word" -> "Current Cursor Line"
                    "line" -> "Line ${covertManager.textPeekTargetLine}"
                    else -> "Full Text"
                }
                dispatchedItems.add("Text Peek ($modeLabel): \"$textPeekPayload\"")
            }
        } else if (!covertWord.isNullOrBlank()) {
            if (covertManager.covertLocalNotification) {
                DeletePeekMemory.showPushNotification(context, covertWord)
            }
            if (covertManager.covertSendToInject && covertManager.isInjectApiEnabled) {
                covertManager.dispatchInjectApi(covertWord)
            }
            dispatchedItems.add("Covert Word: \"$covertWord\"")
        } else if (!mathPayload.isNullOrBlank()) {
            if (covertManager.isMathEnabled) {
                if (covertManager.mathLocalNotification) {
                    DeletePeekMemory.showPushNotification(context, mathPayload)
                }
                if (covertManager.mathSendToInject && covertManager.isInjectApiEnabled) {
                    covertManager.dispatchInjectApi(mathPayload)
                }
                val label = if (covertManager.mathTargetMode == "line") "Math Line ${covertManager.mathTargetLine}" else "Math Total"
                dispatchedItems.add("$label: $mathPayload")
            }
        } else if (!delWord.isNullOrBlank()) {
            if (covertManager.isDeletePeekEnabled) {
                if (covertManager.deletePeekLocalNotification) {
                    DeletePeekMemory.showPushNotification(context, delWord)
                }
                if (covertManager.deletePeekSendToInject && covertManager.isInjectApiEnabled) {
                    covertManager.dispatchInjectApi(delWord)
                }
                dispatchedItems.add("Delete Peek: \"$delWord\"")
            }
        }

        // Clear all pending items so old values can never trigger again
        pendingDeletedWord = null
        pendingMathPayload = null
        pendingCovertWord = null
        pendingTextPeekPayload = null

        val summary = if (dispatchedItems.isNotEmpty()) {
            dispatchedItems.joinToString(" | ")
        } else {
            "Trigger fired (Queue was empty)"
        }

        if (isHapticTriggerEnabled(context)) {
            triggerStealthHaptic(context)
        }

        onPendingStateChanged?.invoke()
        onTriggerFired?.invoke(source, summary)
    }

    fun getPendingSummary(): String {
        val items = mutableListOf<String>()
        pendingTextPeekPayload?.let { items.add("Text Peek: \"$it\"") }
        pendingDeletedWord?.let { items.add("Delete Peek: \"$it\"") }
        pendingMathPayload?.let { items.add("Math: $it") }
        pendingCovertWord?.let { items.add("Covert: \"$it\"") }
        return if (items.isEmpty()) "None (Ready)" else items.joinToString(" | ")
    }

    private fun triggerStealthHaptic(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator != null && vibrator.hasVibrator()) {
                // Discreet tactile confirmation buzz (50ms)
                vibrator.vibrate(VibrationEffect.createOneShot(50, 160))
            }
        } catch (_: Exception) {}
    }

    // ---------- Proximity Sensor Management ----------

    fun startSensors(context: Context) {
        if (!isProximityTriggerEnabled(context)) return

        try {
            val appCtx = context.applicationContext
            sensorManager = appCtx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sm = sensorManager ?: return

            // Proximity sensor setup
            if (!isProximityRegistered || proximityListener == null) {
                if (proximitySensor == null) {
                    proximitySensor = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
                }

                if (proximitySensor != null) {
                    wasProximityNear = false
                    proximityListener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent?) {
                            if (event == null || event.sensor.type != Sensor.TYPE_PROXIMITY) return
                            val distance = event.values[0]
                            val maxRange = event.sensor.maximumRange

                            val sensitivity = getProximitySensitivity(context)
                            val (thresholdDistance, debounceMs) = when (sensitivity) {
                                SENSITIVITY_LOW -> Pair(1.5f, 1500L)
                                SENSITIVITY_HIGH -> Pair(4.5f, 650L)
                                else -> Pair(3.0f, 1000L)
                            }

                            // Strict proximity detection:
                            // Binary sensors report 0 for near, or maxRange for far.
                            // Analog distance sensors report distance in centimeters.
                            val isNear = if (maxRange <= 1.0f) {
                                (distance == 0.0f) || (distance < maxRange / 2.0f)
                            } else {
                                (distance == 0.0f) || (distance <= thresholdDistance && distance < maxRange)
                            }

                            val enteredNearState = isNear && !wasProximityNear
                            wasProximityNear = isNear

                            onProximityChanged?.invoke(isNear)

                            // Edge Trigger: ONLY fire once when transitioning from FAR to NEAR (wave or cover arrival)
                            // Never repeatedly fire while hand remains near!
                            if (enteredNearState) {
                                val now = System.currentTimeMillis()
                                if (now - lastProximityNearTime > debounceMs) {
                                    lastProximityNearTime = now
                                    if (isProximityTriggerEnabled(context)) {
                                        fireTrigger("Proximity Sensor (Wave/Cover)", context)
                                    }
                                }
                            }
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                    }

                    val reg = sm.registerListener(
                        proximityListener,
                        proximitySensor,
                        SensorManager.SENSOR_DELAY_UI
                    )
                    isProximityRegistered = reg
                }
            }
        } catch (_: Exception) {}
    }

    fun stopSensors() {
        try {
            if (isProximityRegistered && proximityListener != null) {
                sensorManager?.unregisterListener(proximityListener)
                proximityListener = null
                isProximityRegistered = false
            }
            wasProximityNear = false
        } catch (_: Exception) {}
    }

    // ---------- Global Volume Button Observer & Receiver ----------

    private fun startVolumeObserver(context: Context) {
        if (isVolumeObserverRegistered) return
        val appCtx = context.applicationContext
        try {
            audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            lastObservedVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1

            volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
                    if (currentVol != lastObservedVolume && currentVol != -1) {
                        lastObservedVolume = currentVol
                        if (isVolumeTriggerEnabled(appCtx)) {
                            fireTrigger("Volume Hardware Button", appCtx)
                        }
                    }
                }
            }

            appCtx.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver!!
            )

            // Also register BroadcastReceiver for volume changes
            if (volumeReceiver == null) {
                volumeReceiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
                            if (currentVol != lastObservedVolume && currentVol != -1) {
                                lastObservedVolume = currentVol
                            }
                            if (isVolumeTriggerEnabled(appCtx)) {
                                fireTrigger("Volume Hardware Button", appCtx)
                            }
                        }
                    }
                }
                val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appCtx.registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appCtx.registerReceiver(volumeReceiver, filter)
                }
            }

            isVolumeObserverRegistered = true
        } catch (_: Exception) {}
    }

    fun stopVolumeObserver(context: Context) {
        val appCtx = context.applicationContext
        try {
            if (isVolumeObserverRegistered && volumeObserver != null) {
                appCtx.contentResolver.unregisterContentObserver(volumeObserver!!)
                volumeObserver = null
            }
        } catch (_: Exception) {}
        try {
            if (volumeReceiver != null) {
                appCtx.unregisterReceiver(volumeReceiver)
                volumeReceiver = null
            }
        } catch (_: Exception) {}
        isVolumeObserverRegistered = false
    }
}
