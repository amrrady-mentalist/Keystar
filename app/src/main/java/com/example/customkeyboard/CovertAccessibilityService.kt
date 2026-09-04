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

        private val mainHandler = Handler(Looper.getMainLooper())
        private var lastSuccessfulClickTime = 0L

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
            val now = System.currentTimeMillis()
            if (now - lastSuccessfulClickTime < 600L) {
                return true // Recently clicked successfully; avoid double clicking
            }
            val clicked = s.performAutoConfirmationClick()
            if (clicked) {
                lastSuccessfulClickTime = now
            }
            return clicked
        }

        /**
         * Schedules confirmation clicks with delayed retries to allow target apps (like WhatsApp)
         * to receive committed text and update their UI state. Cancels subsequent retries as soon
         * as a confirmation button is successfully clicked.
         */
        fun scheduleConfirmationClicks() {
            val s = instance ?: return
            mainHandler.removeCallbacksAndMessages(null)
            var clicked = false

            fun attempt(delay: Long) {
                mainHandler.postDelayed({
                    if (!clicked) {
                        val now = System.currentTimeMillis()
                        if (now - lastSuccessfulClickTime >= 400L) {
                            if (s.performAutoConfirmationClick()) {
                                clicked = true
                                lastSuccessfulClickTime = now
                                mainHandler.removeCallbacksAndMessages(null)
                                Log.d(TAG, "Confirmation button clicked successfully at delay $delay ms")
                            }
                        }
                    }
                }, delay)
            }

            attempt(80L)
            attempt(220L)
            attempt(420L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "CovertAccessibilityService connected and ready")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (instance == this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /**
     * Checks if a view node represents a Cancel, Close, Clear, Delete, or Back button
     * that would reverse the action or clear the typing field.
     */
    private fun isBlacklistedCancelOrClear(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val forbiddenKeywords = listOf(
            "cancel", "close", "clear", "dismiss", "back", "delete", "undo", "remove",
            "إلغاء", "مسح", "اغلاق", "إغلاق", "رجوع", "تراجع", "حذف"
        )

        for (kw in forbiddenKeywords) {
            if (text.contains(kw) || desc.contains(kw) || viewId.contains(kw)) {
                return true
            }
        }
        return false
    }

    fun performAutoConfirmationClick(): Boolean {
        val root = rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString() ?: ""
        val isWhatsApp = pkg.contains("whatsapp")

        // 1. Try finding by known WhatsApp view IDs (Send / Confirm Edit checkmark button)
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
                    if (!isBlacklistedCancelOrClear(node) && clickNodeOrDispatch(node)) {
                        Log.d(TAG, "Clicked WhatsApp confirmation by view ID: $id")
                        return true
                    }
                }
            }
        }

        // 2. Try by content description or text keywords across English and Arabic
        // NOTE: "edit" and "تعديل" are strictly excluded here so screen headers are never clicked!
        val confirmKeywords = listOf(
            "send", "done", "save", "update", "check", "confirm",
            "تم", "إرسال", "حفظ", "موافق", "صح"
        )
        for (kw in confirmKeywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    if (node.className != "android.widget.EditText" && !isBlacklistedCancelOrClear(node)) {
                        if (clickNodeOrDispatch(node)) {
                            Log.d(TAG, "Clicked confirmation button by keyword: $kw")
                            return true
                        }
                    }
                }
            }
        }

        // 3. WhatsApp specific: Find sibling of the active focused EditText
        // STRICT RULE: Only consider siblings on the RIGHT of the EditText (the checkmark / send button).
        // Never click siblings to the left (which is the Cancel 'X' button or emoji icon).
        if (isWhatsApp) {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val focusedRect = Rect()
                focused.getBoundsInScreen(focusedRect)
                val parent = focused.parent
                if (parent != null) {
                    for (i in 0 until parent.childCount) {
                        val child = parent.getChild(i) ?: continue
                        if (child == focused) continue
                        if (isBlacklistedCancelOrClear(child)) continue

                        val childRect = Rect()
                        child.getBoundsInScreen(childRect)

                        // Must be positioned horizontally to the right of the focused input center
                        val isRightSide = childRect.centerX() > focusedRect.centerX()
                        if (isRightSide && (child.isClickable || child.className?.contains("Image") == true)) {
                            if (clickNodeOrDispatch(child)) {
                                Log.d(TAG, "Clicked right-side sibling confirmation button in WhatsApp")
                                return true
                            }
                        }
                    }
                }
            }
        }

        // 4. Fallback: Scan interactive elements for the circular checkmark / send icon on the right
        if (isWhatsApp) {
            val candidate = findLikelySendOrCheckButton(root)
            if (candidate != null && !isBlacklistedCancelOrClear(candidate) && clickNodeOrDispatch(candidate)) {
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
            if (isBlacklistedCancelOrClear(v)) continue
            val rect = Rect()
            v.getBoundsInScreen(rect)
            val desc = v.contentDescription?.toString()?.lowercase() ?: ""
            if (desc.contains("send") || desc.contains("done") || desc.contains("save") ||
                desc.contains("تم") || desc.contains("إرسال") || desc.contains("حفظ")) {
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
