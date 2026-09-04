package com.example.customkeyboard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Covert accessibility companion service:
 * Automatically finds and clicks the WhatsApp checkmark (✓) or send button
 * in WhatsApp, Telegram, Notes, and other messengers when a covert replacement or trigger fires.
 */
class CovertAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CovertAccessService"

        @Volatile
        var instance: CovertAccessibilityService? = null
            private set

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""
                val expected = "${context.packageName}/${CovertAccessibilityService::class.java.canonicalName}"
                val expectedShort = "${context.packageName}/.CovertAccessibilityService"
                enabledServices.contains(expected) || enabledServices.contains(expectedShort)
            } catch (_: Exception) {
                false
            }
        }

        fun clickActiveConfirmationButton(): Boolean {
            val s = instance ?: return false
            return s.performAutoConfirmationClick()
        }

        /**
         * Schedules confirmation clicks with delayed retries to allow target apps (like WhatsApp)
         * to receive committed text and update their UI state.
         */
        fun scheduleConfirmationClicks() {
            val s = instance ?: return
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({ s.performAutoConfirmationClick() }, 70L)
            handler.postDelayed({ s.performAutoConfirmationClick() }, 180L)
            handler.postDelayed({ s.performAutoConfirmationClick() }, 320L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "CovertAccessibilityService connected and ready")
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun performAutoConfirmationClick(): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: ""
        val isWhatsApp = pkg.contains("whatsapp")

        // 1. Try finding by known WhatsApp view IDs (Send / Confirm Edit button)
        val knownIds = listOf(
            "com.whatsapp:id/send",
            "com.whatsapp.w4b:id/send",
            "com.whatsapp:id/done",
            "com.whatsapp:id/ok",
            "com.whatsapp:id/confirm",
            "com.whatsapp:id/save",
            "com.whatsapp:id/action_done",
            "com.whatsapp:id/btn_send",
            "com.whatsapp:id/menuitem_send"
        )
        for (id in knownIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (clickNodeOrDispatch(node)) {
                        Log.d(TAG, "Clicked WhatsApp confirmation by view ID: $id")
                        return true
                    }
                }
            }
        }

        // 2. Try by content description or text keywords across English and Arabic
        val keywords = listOf(
            "send", "done", "save", "update", "check", "ok", "confirm", "edit",
            "تعديل", "تم", "إرسال", "حفظ", "موافق", "صح"
        )
        for (kw in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (clickNodeOrDispatch(node)) {
                        Log.d(TAG, "Clicked confirmation button by keyword: $kw")
                        return true
                    }
                }
            }
        }

        // 3. WhatsApp specific: Find sibling of the active focused EditText
        if (isWhatsApp) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val parent = focused.parent
                if (parent != null) {
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChild(i) ?: continue
                        if (child != focused && (child.isClickable || child.className?.contains("Image") == true)) {
                            if (clickNodeOrDispatch(child)) {
                                Log.d(TAG, "Clicked sibling action button in WhatsApp")
                                return true
                            }
                        }
                    }
                }
            }
        }

        // 4. Fallback: Scan interactive elements for the circular checkmark / send icon
        if (isWhatsApp) {
            val candidate = findLikelySendOrCheckButton(root)
            if (candidate != null && clickNodeOrDispatch(candidate)) {
                Log.d(TAG, "Clicked likely checkmark button in WhatsApp")
                return true
            }
        }

        return false
    }

    private fun clickNodeOrDispatch(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        var count = 0
        while (target != null && !target.isClickable && count < 3) {
            target = target.parent
            count++
        }
        val clickable = target ?: node

        // Try direct accessibility click action
        if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // If direct action failed, dispatch physical touch tap at the view's center
        val rect = Rect()
        clickable.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            val x = rect.centerX().toFloat()
            val y = rect.centerY().toFloat()
            dispatchTap(x, y)
            return true
        }
        return false
    }

    private fun findLikelySendOrCheckButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val clickableViews = mutableListOf<AccessibilityNodeInfo>()
        fun scan(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.isClickable && n.className != "android.widget.EditText") {
                clickableViews.add(n)
            }
            for (i in 0 until n.childCount) {
                scan(n.getChild(i))
            }
        }
        scan(root)

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        for (v in clickableViews.reversed()) {
            val rect = Rect()
            v.getBoundsInScreen(rect)
            val desc = v.contentDescription?.toString()?.lowercase() ?: ""
            if (desc.contains("send") || desc.contains("done") || desc.contains("save") ||
                desc.contains("تعديل") || desc.contains("تم") || desc.contains("إرسال")) {
                return v
            }
            // Checkmark button in WhatsApp edit mode sits on the right side of the text field
            if (rect.centerX() > screenWidth * 0.70f && rect.centerY() > screenHeight * 0.25f) {
                val className = v.className?.toString() ?: ""
                if (className.contains("Image") || className.contains("Button") || className.contains("FrameLayout")) {
                    return v
                }
            }
        }
        return null
    }

    private fun dispatchTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 40)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
