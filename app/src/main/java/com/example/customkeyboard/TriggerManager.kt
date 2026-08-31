package com.example.customkeyboard

import android.content.Context
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

    private var appContextRef: WeakReference<Context>? = null
    private var covertManagerRef: WeakReference<CovertManager>? = null

    // Pending payloads waiting for trigger activation
    @Volatile
    var pendingDeletedWord: String? = null
    @Volatile
    var pendingMathPayload: String? = null
    @Volatile
    var pendingCovertWord: String? = null

    // Real-time status callbacks for UI
    var onTriggerFired: ((source: String, summary: String) -> Unit)? = null
    var onProximityChanged: ((isNear: Boolean) -> Unit)? = null
    var onPendingStateChanged: (() -> Unit)? = null

    // Sensor & Audio State
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityListener: SensorEventListener? = null
    private var isProximityRegistered = false
    private var lastProximityNearTime = 0L

    private var audioManager: AudioManager? = null
    private var volumeObserver: ContentObserver? = null
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
        if (isSessionActive) {
            if (enabled) startSensors(context) else stopSensors()
        }
    }

    fun setVolumeTriggerEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VOL_TRIGGER, enabled).apply()
        if (isSessionActive) {
            if (enabled) startVolumeObserver(context) else stopVolumeObserver(context)
        }
    }

    /**
     * Initializes TriggerManager with application context and CovertManager instance.
     * Does NOT start hardware sensors immediately to conserve battery.
     */
    fun init(context: Context, covertManager: CovertManager? = null) {
        appContextRef = WeakReference(context.applicationContext)
        if (covertManager != null) {
            covertManagerRef = WeakReference(covertManager)
        }
    }

    /**
     * Starts triggers ONLY when the keyboard is actively presented on screen or during explicit testing.
     */
    fun startActiveSession(context: Context) {
        isSessionActive = true
        appContextRef = WeakReference(context.applicationContext)
        if (isProximityTriggerEnabled(context)) {
            startSensors(context)
        }
        if (isVolumeTriggerEnabled(context)) {
            startVolumeObserver(context)
        }
    }

    /**
     * Stops triggers immediately when the keyboard is hidden / closed to save battery.
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
     * Queues a secret covert word from Covert Typing effect.
     */
    fun queueCovertWord(word: String, context: Context, covertManager: CovertManager) {
        if (word.isBlank()) return
        if (isRequireTriggerEnabled(context)) {
            pendingCovertWord = word
            onPendingStateChanged?.invoke()
        } else {
            // Immediate mode: dispatch right away
            if (covertManager.covertLocalNotification) {
                DeletePeekMemory.showPushNotification(context, word)
            }
            if (covertManager.covertSendToInject && covertManager.isInjectApiEnabled) {
                covertManager.dispatchInjectApi(word)
            }
        }
    }

    /**
     * Fires trigger from any source (Volume button, Proximity sensor, or Manual test button).
     * Dispatches all armed and pending payloads to their configured destinations (API / Push Notification).
     */
    @Synchronized
    fun fireTrigger(source: String, customContext: Context? = null): Boolean {
        val context = customContext ?: appContextRef?.get() ?: return false
        val covertManager = covertManagerRef?.get() ?: CovertManager(context)

        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 600L) {
            // Debounce rapid multiple triggers within 600ms
            return false
        }
        lastTriggerTime = now

        val dispatchedItems = mutableListOf<String>()

        // 1. Dispatch Pending Delete Peek
        val delWord = pendingDeletedWord
        if (!delWord.isNullOrBlank()) {
            if (covertManager.isDeletePeekEnabled) {
                if (covertManager.deletePeekLocalNotification) {
                    DeletePeekMemory.showPushNotification(context, delWord)
                }
                if (covertManager.deletePeekSendToInject && covertManager.isInjectApiEnabled) {
                    covertManager.dispatchInjectApi(delWord)
                }
                dispatchedItems.add("Delete Peek: \"$delWord\"")
            }
            pendingDeletedWord = null
        }

        // 2. Dispatch Pending Math Payload (Total or Specific Line)
        val mathPayload = pendingMathPayload
        if (!mathPayload.isNullOrBlank()) {
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
            pendingMathPayload = null
        }

        // 3. Dispatch Pending Covert Word
        val covertWord = pendingCovertWord
        if (!covertWord.isNullOrBlank()) {
            if (covertManager.covertLocalNotification) {
                DeletePeekMemory.showPushNotification(context, covertWord)
            }
            if (covertManager.covertSendToInject && covertManager.isInjectApiEnabled) {
                covertManager.dispatchInjectApi(covertWord)
            }
            dispatchedItems.add("Covert Word: \"$covertWord\"")
            pendingCovertWord = null
        }

        // Fallback: If no pending item in queue, but there is active memory, send the latest state
        if (dispatchedItems.isEmpty()) {
            if (covertManager.isDeletePeekEnabled && DeletePeekMemory.lastDeletedWord.isNotBlank()) {
                val latest = DeletePeekMemory.lastDeletedWord
                if (covertManager.deletePeekLocalNotification) {
                    DeletePeekMemory.showPushNotification(context, latest)
                }
                if (covertManager.deletePeekSendToInject && covertManager.isInjectApiEnabled) {
                    covertManager.dispatchInjectApi(latest)
                }
                dispatchedItems.add("Delete Peek (Latest): \"$latest\"")
            } else if (covertManager.isCovertActive && covertManager.capturedSecretWord.isNotBlank()) {
                val secret = covertManager.capturedSecretWord
                if (covertManager.covertLocalNotification) {
                    DeletePeekMemory.showPushNotification(context, secret)
                }
                if (covertManager.covertSendToInject && covertManager.isInjectApiEnabled) {
                    covertManager.dispatchInjectApi(secret)
                }
                dispatchedItems.add("Covert Word (Latest): \"$secret\"")
            }
        }

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
        return true
    }

    fun getPendingSummary(): String {
        val items = mutableListOf<String>()
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
        if (isProximityRegistered) return

        try {
            if (sensorManager == null) {
                sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            }
            if (proximitySensor == null) {
                proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            }

            if (proximitySensor != null && proximityListener == null) {
                proximityListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event == null || event.sensor.type != Sensor.TYPE_PROXIMITY) return
                        val distance = event.values[0]
                        val maxRange = event.sensor.maximumRange
                        val isNear = distance < 4.5f && distance < maxRange

                        onProximityChanged?.invoke(isNear)

                        if (isNear) {
                            val now = System.currentTimeMillis()
                            if (now - lastProximityNearTime > 800L) {
                                lastProximityNearTime = now
                                if (isProximityTriggerEnabled(context)) {
                                    fireTrigger("Proximity Sensor (Near/Cover)", context)
                                }
                            }
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }

                sensorManager?.registerListener(
                    proximityListener,
                    proximitySensor,
                    SensorManager.SENSOR_DELAY_UI
                )
                isProximityRegistered = true
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
        } catch (_: Exception) {}
    }

    // ---------- Global Volume Button Observer ----------

    private fun startVolumeObserver(context: Context) {
        if (isVolumeObserverRegistered) return
        try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            lastObservedVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1

            volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    super.onChange(selfChange)
                    val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
                    if (currentVol != lastObservedVolume && currentVol != -1) {
                        lastObservedVolume = currentVol
                        if (isVolumeTriggerEnabled(context)) {
                            fireTrigger("Volume Hardware Button", context)
                        }
                    }
                }
            }

            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver!!
            )
            isVolumeObserverRegistered = true
        } catch (_: Exception) {}
    }

    fun stopVolumeObserver(context: Context) {
        try {
            if (isVolumeObserverRegistered && volumeObserver != null) {
                context.contentResolver.unregisterContentObserver(volumeObserver!!)
                volumeObserver = null
                isVolumeObserverRegistered = false
            }
        } catch (_: Exception) {}
    }
}
