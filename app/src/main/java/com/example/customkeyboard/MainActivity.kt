package com.example.customkeyboard

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var covertManager: CovertManager
    private var versionTapCount = 0
    private var lastVersionTapTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE)
        val savedTheme = prefs.getString("theme_override", "system")
        when (savedTheme) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }

        super.onCreate(savedInstanceState)
        covertManager = CovertManager(this)
        TriggerManager.init(this, covertManager)
        Dictionary.init(this)

        setContentView(R.layout.activity_main)

        setupPublicUi()
        setupStealthTriggers()
    }

    override fun onResume() {
        super.onResume()
        TriggerManager.syncTriggersState(this)
    }

    private fun setupPublicUi() {
        val btnEnable = findViewById<Button>(R.id.btnEnable)
        val btnSwitch = findViewById<Button>(R.id.btnSwitch)
        val themeGroup = findViewById<RadioGroup>(R.id.themeRadioGroup)
        val editSandbox = findViewById<EditText>(R.id.editSandbox)
        val btnClearSandbox = findViewById<Button>(R.id.btnClearSandbox)

        btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        btnSwitch.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        when (prefs.getString("theme_override", "system")) {
            "light" -> findViewById<RadioButton>(R.id.radioThemeLight).isChecked = true
            "dark" -> findViewById<RadioButton>(R.id.radioThemeDark).isChecked = true
            else -> findViewById<RadioButton>(R.id.radioThemeSystem).isChecked = true
        }

        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val (value, mode) = when (checkedId) {
                R.id.radioThemeLight -> "light" to AppCompatDelegate.MODE_NIGHT_NO
                R.id.radioThemeDark -> "dark" to AppCompatDelegate.MODE_NIGHT_YES
                else -> "system" to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            prefs.edit().putString("theme_override", value).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
            Toast.makeText(this, "Appearance updated", Toast.LENGTH_SHORT).show()
        }

        val heightGroup = findViewById<RadioGroup>(R.id.heightRadioGroup)
        when (prefs.getString("keyboard_height", "normal")) {
            "compact" -> findViewById<RadioButton>(R.id.radioHeightCompact).isChecked = true
            "tall" -> findViewById<RadioButton>(R.id.radioHeightTall).isChecked = true
            "extra_tall" -> findViewById<RadioButton>(R.id.radioHeightExtraTall).isChecked = true
            else -> findViewById<RadioButton>(R.id.radioHeightNormal).isChecked = true
        }
        heightGroup.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.radioHeightCompact -> "compact"
                R.id.radioHeightTall -> "tall"
                R.id.radioHeightExtraTall -> "extra_tall"
                else -> "normal"
            }
            prefs.edit().putString("keyboard_height", value).apply()
            Toast.makeText(this, "Keyboard height saved", Toast.LENGTH_SHORT).show()
        }

        val keySizeGroup = findViewById<RadioGroup>(R.id.keySizeRadioGroup)
        when (prefs.getString("key_font_size", "normal")) {
            "small" -> findViewById<RadioButton>(R.id.radioKeySmall).isChecked = true
            "large" -> findViewById<RadioButton>(R.id.radioKeyLarge).isChecked = true
            "extra_large" -> findViewById<RadioButton>(R.id.radioKeyExtraLarge).isChecked = true
            else -> findViewById<RadioButton>(R.id.radioKeyNormal).isChecked = true
        }
        keySizeGroup.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.radioKeySmall -> "small"
                R.id.radioKeyLarge -> "large"
                R.id.radioKeyExtraLarge -> "extra_large"
                else -> "normal"
            }
            prefs.edit().putString("key_font_size", value).apply()
            Toast.makeText(this, "Key size saved", Toast.LENGTH_SHORT).show()
        }

        btnClearSandbox.setOnClickListener {
            editSandbox.setText("")
        }

        // Secret code trigger in public sandbox
        editSandbox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString() ?: ""
                if (text.endsWith("*#0000#") || text.endsWith("*#covert#") || text.endsWith("..covert")) {
                    editSandbox.setText("")
                    openHiddenCovertMenu()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupStealthTriggers() {
        val headerLayout = findViewById<LinearLayout>(R.id.headerLayout)
        val tvTitle = findViewById<TextView>(R.id.tvTitle)
        val tvVersion = findViewById<TextView>(R.id.tvVersion)

        // 1. Long-press on Title or Header opens the Hidden Menu
        val longPressListener = {
            triggerStealthVibrate()
            openHiddenCovertMenu()
            true
        }
        headerLayout.setOnLongClickListener { longPressListener() }
        tvTitle.setOnLongClickListener { longPressListener() }

        // 2. Tapping the version label 5 times in rapid succession
        tvVersion.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastVersionTapTime < 600) {
                versionTapCount++
                if (versionTapCount >= 5) {
                    versionTapCount = 0
                    triggerStealthVibrate()
                    openHiddenCovertMenu()
                }
            } else {
                versionTapCount = 1
            }
            lastVersionTapTime = now
        }
    }

    private fun openHiddenCovertMenu() {
        val density = resources.displayMetrics.density
        val dialog = Dialog(this, R.style.Theme_CustomKeyboard_Dialog)
        dialog.setContentView(R.layout.dialog_hidden_covert_menu)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        val tvStatusTitle = dialog.findViewById<TextView>(R.id.tvCovertStatusTitle)
        val tvCapturedWordStatus = dialog.findViewById<TextView>(R.id.tvCapturedWordStatus)
        val switchMaster = dialog.findViewById<MaterialSwitch>(R.id.switchCovertMaster)
        val layoutCovertDetails = dialog.findViewById<LinearLayout>(R.id.layoutCovertDetails)
        val btnResetSession = dialog.findViewById<Button>(R.id.btnResetSession)
        val btnDisarmNow = dialog.findViewById<Button>(R.id.btnDisarmNow)

        val editCoverSentence = dialog.findViewById<EditText>(R.id.editCoverSentence)
        val btnSaveCoverSentence = dialog.findViewById<Button>(R.id.btnSaveCoverSentence)

        val radioGroupRevealPos = dialog.findViewById<android.widget.RadioGroup>(R.id.radioGroupRevealPos)
        val radioReveal1st = dialog.findViewById<android.widget.RadioButton>(R.id.radioReveal1st)
        val radioReveal2nd = dialog.findViewById<android.widget.RadioButton>(R.id.radioReveal2nd)
        val radioReveal3rd = dialog.findViewById<android.widget.RadioButton>(R.id.radioReveal3rd)
        val radioReveal4th = dialog.findViewById<android.widget.RadioButton>(R.id.radioReveal4th)

        val switchInjectApi = dialog.findViewById<MaterialSwitch>(R.id.switchInjectApi)
        val layoutInjectSettings = dialog.findViewById<LinearLayout>(R.id.layoutInjectSettings)
        val editInjectUrl = dialog.findViewById<EditText>(R.id.editInjectUrl)
        val editInjectKey = dialog.findViewById<EditText>(R.id.editInjectKey)
        val switchCovertSendInject = dialog.findViewById<MaterialSwitch>(R.id.switchCovertSendInject)
        val switchCovertSendNotif = dialog.findViewById<MaterialSwitch>(R.id.switchCovertSendNotif)
        val btnTestInjectApi = dialog.findViewById<Button>(R.id.btnTestInjectApi)

        val editSandbox = dialog.findViewById<EditText>(R.id.editCovertSandbox)
        val tvSandboxStatus = dialog.findViewById<TextView>(R.id.tvSandboxLiveStatus)
        val btnClearSandbox = dialog.findViewById<Button>(R.id.btnClearCovertSandbox)
        val btnClose = dialog.findViewById<Button>(R.id.btnCloseCovert)

        // Math Effect Views
        val switchMathMaster = dialog.findViewById<MaterialSwitch>(R.id.switchMathMaster)
        val tvMathStatusTitle = dialog.findViewById<TextView>(R.id.tvMathStatusTitle)
        val layoutMathDetails = dialog.findViewById<LinearLayout>(R.id.layoutMathDetails)
        val rgMathTargetMode = dialog.findViewById<android.widget.RadioGroup>(R.id.rgMathTargetMode)
        val rbMathModeTotal = dialog.findViewById<android.widget.RadioButton>(R.id.rbMathModeTotal)
        val rbMathModeLine = dialog.findViewById<android.widget.RadioButton>(R.id.rbMathModeLine)
        val layoutMathLineTarget = dialog.findViewById<LinearLayout>(R.id.layoutMathLineTarget)
        val tvMathTargetLineLabel = dialog.findViewById<TextView>(R.id.tvMathTargetLineLabel)
        val btnMathLine1 = dialog.findViewById<Button>(R.id.btnMathLine1)
        val btnMathLine2 = dialog.findViewById<Button>(R.id.btnMathLine2)
        val btnMathLine3 = dialog.findViewById<Button>(R.id.btnMathLine3)
        val btnMathLine4 = dialog.findViewById<Button>(R.id.btnMathLine4)
        val btnMathLine5 = dialog.findViewById<Button>(R.id.btnMathLine5)

        val layoutMathEquationSection = dialog.findViewById<LinearLayout>(R.id.layoutMathEquationSection)
        val editMathEquation = dialog.findViewById<EditText>(R.id.editMathEquation)
        val btnSaveMathEquation = dialog.findViewById<Button>(R.id.btnSaveMathEquation)
        val switchMathSendInject = dialog.findViewById<MaterialSwitch>(R.id.switchMathSendInject)
        val switchMathSendNotif = dialog.findViewById<MaterialSwitch>(R.id.switchMathSendNotif)
        val editMathSimulator = dialog.findViewById<EditText>(R.id.editMathSimulator)
        val tvMathSimResult = dialog.findViewById<TextView>(R.id.tvMathSimResult)

        val chipToken1st = dialog.findViewById<Button>(R.id.chipToken1st)
        val chipToken2nd = dialog.findViewById<Button>(R.id.chipToken2nd)
        val chipToken3rd = dialog.findViewById<Button>(R.id.chipToken3rd)
        val chipToken4th = dialog.findViewById<Button>(R.id.chipToken4th)
        val chipTokenPlus = dialog.findViewById<Button>(R.id.chipTokenPlus)
        val chipTokenMinus = dialog.findViewById<Button>(R.id.chipTokenMinus)
        val chipTokenMultiply = dialog.findViewById<Button>(R.id.chipTokenMultiply)
        val chipTokenDivide = dialog.findViewById<Button>(R.id.chipTokenDivide)
        val chipTokenOpenParen = dialog.findViewById<Button>(R.id.chipTokenOpenParen)
        val chipTokenCloseParen = dialog.findViewById<Button>(R.id.chipTokenCloseParen)

        // Delete Peek Effect Views
        val switchDeletePeekMaster = dialog.findViewById<MaterialSwitch>(R.id.switchDeletePeekMaster)
        val tvDeletePeekStatusTitle = dialog.findViewById<TextView>(R.id.tvDeletePeekStatusTitle)
        val layoutDeletePeekDetails = dialog.findViewById<LinearLayout>(R.id.layoutDeletePeekDetails)
        val tvLastDeletedWord = dialog.findViewById<TextView>(R.id.tvLastDeletedWord)
        val switchDeletePeekSendInject = dialog.findViewById<MaterialSwitch>(R.id.switchDeletePeekSendInject)
        val switchDeletePeekLocalNotif = dialog.findViewById<MaterialSwitch>(R.id.switchDeletePeekLocalNotif)
        val btnTestDeleteNotif = dialog.findViewById<Button>(R.id.btnTestDeleteNotification)
        val btnClearDeleteMemory = dialog.findViewById<Button>(R.id.btnClearDeleteMemory)

        // Any Word / Line Peek Effect Views
        val switchTextPeekMaster = dialog.findViewById<MaterialSwitch>(R.id.switchTextPeekMaster)
        val tvTextPeekStatusTitle = dialog.findViewById<TextView>(R.id.tvTextPeekStatusTitle)
        val layoutTextPeekDetails = dialog.findViewById<LinearLayout>(R.id.layoutTextPeekDetails)
        val rgTextPeekMode = dialog.findViewById<RadioGroup>(R.id.rgTextPeekMode)
        val rbTextPeekAll = dialog.findViewById<RadioButton>(R.id.rbTextPeekAll)
        val rbTextPeekLastWord = dialog.findViewById<RadioButton>(R.id.rbTextPeekLastWord)
        val rbTextPeekLine = dialog.findViewById<RadioButton>(R.id.rbTextPeekLine)
        val layoutTextPeekLineTarget = dialog.findViewById<LinearLayout>(R.id.layoutTextPeekLineTarget)
        val tvTextPeekTargetLineLabel = dialog.findViewById<TextView>(R.id.tvTextPeekTargetLineLabel)
        val btnTextPeekLine1 = dialog.findViewById<Button>(R.id.btnTextPeekLine1)
        val btnTextPeekLine2 = dialog.findViewById<Button>(R.id.btnTextPeekLine2)
        val btnTextPeekLine3 = dialog.findViewById<Button>(R.id.btnTextPeekLine3)
        val btnTextPeekLine4 = dialog.findViewById<Button>(R.id.btnTextPeekLine4)
        val switchTextPeekSendInject = dialog.findViewById<MaterialSwitch>(R.id.switchTextPeekSendInject)
        val switchTextPeekLocalNotif = dialog.findViewById<MaterialSwitch>(R.id.switchTextPeekLocalNotif)
        val btnTestTextPeekCapture = dialog.findViewById<Button>(R.id.btnTestTextPeekCapture)

        // API Text Replace Effect Views
        val switchTextReplaceMaster = dialog.findViewById<MaterialSwitch>(R.id.switchTextReplaceMaster)
        val tvTextReplaceStatusTitle = dialog.findViewById<TextView>(R.id.tvTextReplaceStatusTitle)
        val layoutTextReplaceDetails = dialog.findViewById<LinearLayout>(R.id.layoutTextReplaceDetails)
        val editReplacePlaceholder = dialog.findViewById<EditText>(R.id.editReplacePlaceholder)
        val chipTagDoubleDash = dialog.findViewById<Button>(R.id.chipTagDoubleDash)
        val chipTagCurly = dialog.findViewById<Button>(R.id.chipTagCurly)
        val chipTagBrackets = dialog.findViewById<Button>(R.id.chipTagBrackets)
        val rgReplaceSourceMode = dialog.findViewById<RadioGroup>(R.id.rgReplaceSourceMode)
        val rbReplaceSourceApi = dialog.findViewById<RadioButton>(R.id.rbReplaceSourceApi)
        val rbReplaceSourceCustom = dialog.findViewById<RadioButton>(R.id.rbReplaceSourceCustom)
        val editReplaceFallbackValue = dialog.findViewById<EditText>(R.id.editReplaceFallbackValue)
        val editReplaceApiUrl = dialog.findViewById<EditText>(R.id.editReplaceApiUrl)
        val tvReplaceApiFetchStatus = dialog.findViewById<TextView>(R.id.tvReplaceApiFetchStatus)
        val btnFetchApiValueNow = dialog.findViewById<Button>(R.id.btnFetchApiValueNow)
        val btnSaveReplaceSettings = dialog.findViewById<Button>(R.id.btnSaveReplaceSettings)

        // Hardware & Sensor Trigger Views
        val switchRequireTrigger = dialog.findViewById<MaterialSwitch>(R.id.switchRequireTrigger)
        val layoutTriggerDetails = dialog.findViewById<LinearLayout>(R.id.layoutTriggerDetails)
        val tvTriggerPendingStatus = dialog.findViewById<TextView>(R.id.tvTriggerPendingStatus)
        val tvTriggerProximityStatus = dialog.findViewById<TextView>(R.id.tvTriggerProximityStatus)
        val switchTriggerVolume = dialog.findViewById<MaterialSwitch>(R.id.switchTriggerVolume)
        val switchTriggerProximity = dialog.findViewById<MaterialSwitch>(R.id.switchTriggerProximity)
        val switchTriggerHaptic = dialog.findViewById<MaterialSwitch>(R.id.switchTriggerHaptic)
        val btnFireTriggerTest = dialog.findViewById<Button>(R.id.btnFireTriggerTest)
        val btnClearTriggerQueue = dialog.findViewById<Button>(R.id.btnClearTriggerQueue)

        fun updateStatusUi() {
            val isActive = covertManager.isCovertActive
            switchMaster.isChecked = isActive
            tvStatusTitle.text = if (isActive) "Covert Typing: ARMED" else "Covert Typing: DISARMED"
            layoutCovertDetails.visibility = if (isActive) View.VISIBLE else View.GONE

            val typedPrimary = TypedValue()
            val typedSecondary = TypedValue()
            theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedPrimary, true)
            theme.resolveAttribute(android.R.attr.textColorSecondary, typedSecondary, true)
            tvStatusTitle.setTextColor(if (isActive) typedPrimary.data else typedSecondary.data)

            val secret = covertManager.capturedSecretWord
            tvCapturedWordStatus.text = if (secret.isNotEmpty()) {
                "Captured Secret Word: \"$secret\""
            } else {
                "Captured Secret Word: (None yet - type word + double space)"
            }

            // Math Status
            switchMathMaster.isChecked = covertManager.isMathEnabled
            tvMathStatusTitle.text = if (covertManager.isMathEnabled) "Math Magic Effect: ACTIVE" else "Math Magic Effect: OFF"
            tvMathStatusTitle.setTextColor(if (covertManager.isMathEnabled) typedPrimary.data else typedSecondary.data)
            layoutMathDetails.visibility = if (covertManager.isMathEnabled) View.VISIBLE else View.GONE

            // Delete Peek Status
            switchDeletePeekMaster.isChecked = covertManager.isDeletePeekEnabled
            tvDeletePeekStatusTitle.text = if (covertManager.isDeletePeekEnabled) "Delete Peek Effect: ACTIVE" else "Delete Peek Effect: OFF"
            tvDeletePeekStatusTitle.setTextColor(if (covertManager.isDeletePeekEnabled) typedPrimary.data else typedSecondary.data)
            layoutDeletePeekDetails.visibility = if (covertManager.isDeletePeekEnabled) View.VISIBLE else View.GONE

            // Text Peek Status
            switchTextPeekMaster.isChecked = covertManager.isTextPeekEnabled
            tvTextPeekStatusTitle.text = if (covertManager.isTextPeekEnabled) "Any Word / Line Peek: ACTIVE" else "Any Word / Line Peek: OFF"
            tvTextPeekStatusTitle.setTextColor(if (covertManager.isTextPeekEnabled) typedPrimary.data else typedSecondary.data)
            layoutTextPeekDetails.visibility = if (covertManager.isTextPeekEnabled) View.VISIBLE else View.GONE

            // Text Replace Status
            switchTextReplaceMaster.isChecked = covertManager.isTextReplaceEnabled
            tvTextReplaceStatusTitle.text = if (covertManager.isTextReplaceEnabled) "API Text Replace: ACTIVE" else "API Text Replace: OFF"
            tvTextReplaceStatusTitle.setTextColor(if (covertManager.isTextReplaceEnabled) typedPrimary.data else typedSecondary.data)
            val effectiveVal = covertManager.getEffectiveReplacementValue()
            val sourceLabel = if (covertManager.replaceSourceMode == "custom") "Pre-saved Text" else "API Data"
            tvReplaceApiFetchStatus.text = "Active Replacement: \"$effectiveVal\" (Source: $sourceLabel)"
            layoutTextReplaceDetails.visibility = if (covertManager.isTextReplaceEnabled) View.VISIBLE else View.GONE

            val lastDel = DeletePeekMemory.lastDeletedWord
            tvLastDeletedWord.text = if (lastDel.isNotEmpty()) {
                "Last Deleted Word: \"$lastDel\""
            } else {
                "Last Deleted Word: (None yet - backspace text in any app)"
            }

            // Hardware & Sensor Trigger Status
            val requireTrig = TriggerManager.isRequireTriggerEnabled(this)
            switchRequireTrigger.isChecked = requireTrig
            layoutTriggerDetails.visibility = if (requireTrig) View.VISIBLE else View.GONE
            switchTriggerVolume.isChecked = TriggerManager.isVolumeTriggerEnabled(this)
            switchTriggerProximity.isChecked = TriggerManager.isProximityTriggerEnabled(this)
            switchTriggerHaptic.isChecked = TriggerManager.isHapticTriggerEnabled(this)
            tvTriggerPendingStatus.text = "Pending Queue: ${TriggerManager.getPendingSummary()}"

            // Inject API Status
            switchInjectApi.isChecked = covertManager.isInjectApiEnabled
            layoutInjectSettings.visibility = if (covertManager.isInjectApiEnabled) View.VISIBLE else View.GONE

            val isSelectedDefault = TriggerManager.isKeyboardSelectedAsDefault(this)
            val isAnyEffectActive = covertManager.isAnyMagicEffectActive()
            val triggersArmed = TriggerManager.shouldTriggersBeActive(this)

            if (!isSelectedDefault) {
                tvTriggerProximityStatus.text = "Status: Keyboard not set as default input method (Triggers Inactive)"
            } else if (!isAnyEffectActive) {
                tvTriggerProximityStatus.text = "Status: No magic effects active (Triggers Idle to save battery)"
            } else {
                tvTriggerProximityStatus.text = "Status: Triggers ARMED & ACTIVE (Default Keyboard + Magic Effect Active)"
            }

            TriggerManager.syncTriggersState(this)
        }

        fun updateMathSimulator() {
            val simText = editMathSimulator.text.toString()
            val isLineMode = covertManager.mathTargetMode == "line"
            if (isLineMode) {
                val targetLine = covertManager.mathTargetLine
                val payload = covertManager.extractMathPayload(simText)
                tvMathSimResult.text = if (payload != null) {
                    "Extracted Target (Line $targetLine): $payload"
                } else {
                    "Extracted Target (Line $targetLine): (Enter at least $targetLine lines of numbers)"
                }
            } else {
                val values = MathEquationEngine.lineValues(simText)
                val eq = editMathEquation.text.toString().trim()
                val result = MathEquationEngine.evaluate(eq, values)
                tvMathSimResult.text = if (result != null) {
                    "Calculated Result: $result (Values: $values)"
                } else if (values.isEmpty()) {
                    "Calculated Result: (Enter numbers on separate lines)"
                } else {
                    "Calculated Result: Invalid Equation"
                }
            }
        }

        // Initialize values
        editCoverSentence.setText(covertManager.coverSentence)

        when (covertManager.revealLetterPosition) {
            1 -> radioReveal2nd.isChecked = true
            2 -> radioReveal3rd.isChecked = true
            3 -> radioReveal4th.isChecked = true
            else -> radioReveal1st.isChecked = true
        }

        switchInjectApi.isChecked = covertManager.isInjectApiEnabled
        layoutInjectSettings.visibility = if (covertManager.isInjectApiEnabled) View.VISIBLE else View.GONE
        editInjectUrl.setText(covertManager.injectApiUrl)
        editInjectKey.setText(covertManager.injectApiKey)
        switchCovertSendInject.isChecked = covertManager.covertSendToInject
        switchCovertSendNotif.isChecked = covertManager.covertLocalNotification

        // Math init
        if (covertManager.mathTargetMode == "line") {
            rbMathModeLine.isChecked = true
            layoutMathLineTarget.visibility = View.VISIBLE
            layoutMathEquationSection.visibility = View.GONE
        } else {
            rbMathModeTotal.isChecked = true
            layoutMathLineTarget.visibility = View.GONE
            layoutMathEquationSection.visibility = View.VISIBLE
        }
        tvMathTargetLineLabel.text = "Target Line to Extract: Line ${covertManager.mathTargetLine}"
        editMathEquation.setText(covertManager.mathEquation)
        switchMathSendInject.isChecked = covertManager.mathSendToInject
        switchMathSendNotif.isChecked = covertManager.mathLocalNotification

        // Delete Peek init
        switchDeletePeekSendInject.isChecked = covertManager.deletePeekSendToInject
        switchDeletePeekLocalNotif.isChecked = covertManager.deletePeekLocalNotification

        // Any Word / Line Peek init
        when (covertManager.textPeekMode) {
            "last_word" -> rbTextPeekLastWord.isChecked = true
            "line" -> {
                rbTextPeekLine.isChecked = true
                layoutTextPeekLineTarget.visibility = View.VISIBLE
            }
            else -> rbTextPeekAll.isChecked = true
        }
        tvTextPeekTargetLineLabel.text = "Target Line Number: Line ${covertManager.textPeekTargetLine}"
        switchTextPeekSendInject.isChecked = covertManager.textPeekSendToInject
        switchTextPeekLocalNotif.isChecked = covertManager.textPeekLocalNotification

        // API Text Replace init
        editReplacePlaceholder.setText(covertManager.replacePlaceholder)
        editReplaceFallbackValue.setText(covertManager.replaceFallbackValue)
        editReplaceApiUrl.setText(covertManager.replaceApiUrl)
        if (covertManager.replaceSourceMode == "custom") {
            rbReplaceSourceCustom.isChecked = true
        } else {
            rbReplaceSourceApi.isChecked = true
        }

        updateStatusUi()
        updateMathSimulator()

        // Real-time listener for Delete Peek updates
        DeletePeekMemory.onDeletedWordChanged = { word ->
            runOnUiThread {
                tvLastDeletedWord.text = "Last Deleted Word: \"$word\""
            }
        }

        // Bind Listeners
        switchMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val sentence = editCoverSentence.text.toString().trim()
                covertManager.armCovert(if (sentence.isNotEmpty()) sentence else null)
            } else {
                covertManager.disarmCovert()
            }
            updateStatusUi()
        }

        btnSaveCoverSentence.setOnClickListener {
            val sentence = editCoverSentence.text.toString().trim()
            if (sentence.isNotEmpty()) {
                covertManager.coverSentence = sentence
                covertManager.armCovert(sentence)
                updateStatusUi()
                Toast.makeText(this, "Cover Sentence Saved & Armed!", Toast.LENGTH_SHORT).show()
            }
        }

        btnResetSession.setOnClickListener {
            covertManager.resetSession()
            updateStatusUi()
            Toast.makeText(this, "Session reset (Secret buffer & index cleared)", Toast.LENGTH_SHORT).show()
        }

        btnDisarmNow.setOnClickListener {
            covertManager.disarmCovert()
            updateStatusUi()
        }

        radioGroupRevealPos.setOnCheckedChangeListener { _, checkedId ->
            covertManager.revealLetterPosition = when (checkedId) {
                R.id.radioReveal2nd -> 1
                R.id.radioReveal3rd -> 2
                R.id.radioReveal4th -> 3
                else -> 0
            }
        }

        switchInjectApi.setOnCheckedChangeListener { _, isChecked ->
            covertManager.isInjectApiEnabled = isChecked
            layoutInjectSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        switchCovertSendInject.setOnCheckedChangeListener { _, isChecked ->
            covertManager.covertSendToInject = isChecked
        }

        switchCovertSendNotif.setOnCheckedChangeListener { _, isChecked ->
            covertManager.covertLocalNotification = isChecked
            if (isChecked && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Math listeners
        switchMathMaster.setOnCheckedChangeListener { _, isChecked ->
            covertManager.isMathEnabled = isChecked
            updateStatusUi()
        }

        rgMathTargetMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbMathModeLine) {
                covertManager.mathTargetMode = "line"
                layoutMathLineTarget.visibility = View.VISIBLE
                layoutMathEquationSection.visibility = View.GONE
            } else {
                covertManager.mathTargetMode = "total"
                layoutMathLineTarget.visibility = View.GONE
                layoutMathEquationSection.visibility = View.VISIBLE
            }
            updateMathSimulator()
        }

        fun selectMathLine(lineNum: Int) {
            covertManager.mathTargetLine = lineNum
            tvMathTargetLineLabel.text = "Target Line to Extract: Line $lineNum"
            updateMathSimulator()
            Toast.makeText(this, "Set Math Extraction to Line $lineNum", Toast.LENGTH_SHORT).show()
        }

        btnMathLine1.setOnClickListener { selectMathLine(1) }
        btnMathLine2.setOnClickListener { selectMathLine(2) }
        btnMathLine3.setOnClickListener { selectMathLine(3) }
        btnMathLine4.setOnClickListener { selectMathLine(4) }
        btnMathLine5.setOnClickListener { selectMathLine(5) }

        btnSaveMathEquation.setOnClickListener {
            val eq = editMathEquation.text.toString().trim()
            covertManager.mathEquation = eq
            updateMathSimulator()
            Toast.makeText(this, "Math Equation Saved!", Toast.LENGTH_SHORT).show()
        }

        switchMathSendInject.setOnCheckedChangeListener { _, isChecked ->
            covertManager.mathSendToInject = isChecked
        }

        switchMathSendNotif.setOnCheckedChangeListener { _, isChecked ->
            covertManager.mathLocalNotification = isChecked
            if (isChecked && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        // Quick chip token insert helper
        val insertToken = { token: String ->
            val cursor = editMathEquation.selectionStart.coerceAtLeast(0)
            val current = editMathEquation.text.toString()
            val updated = current.substring(0, cursor) + token + current.substring(cursor)
            editMathEquation.setText(updated)
            editMathEquation.setSelection((cursor + token.length).coerceAtMost(updated.length))
            updateMathSimulator()
        }

        chipToken1st.setOnClickListener { insertToken("1st") }
        chipToken2nd.setOnClickListener { insertToken("2nd") }
        chipToken3rd.setOnClickListener { insertToken("3rd") }
        chipToken4th.setOnClickListener { insertToken("4th") }
        chipTokenPlus.setOnClickListener { insertToken("+") }
        chipTokenMinus.setOnClickListener { insertToken("-") }
        chipTokenMultiply.setOnClickListener { insertToken("*") }
        chipTokenDivide.setOnClickListener { insertToken("/") }
        chipTokenOpenParen.setOnClickListener { insertToken("(") }
        chipTokenCloseParen.setOnClickListener { insertToken(")") }

        editMathEquation.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateMathSimulator()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        editMathSimulator.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateMathSimulator()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Delete Peek listeners
        switchDeletePeekMaster.setOnCheckedChangeListener { _, isChecked ->
            covertManager.isDeletePeekEnabled = isChecked
            updateStatusUi()
        }

        switchDeletePeekSendInject.setOnCheckedChangeListener { _, isChecked ->
            covertManager.deletePeekSendToInject = isChecked
        }

        switchDeletePeekLocalNotif.setOnCheckedChangeListener { _, isChecked ->
            covertManager.deletePeekLocalNotification = isChecked
            if (isChecked && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        btnTestDeleteNotif.setOnClickListener {
            val testWord = DeletePeekMemory.lastDeletedWord.ifEmpty { "Magic Secret" }
            DeletePeekMemory.showPushNotification(this, testWord)
            Toast.makeText(this, "Sent test notification: \"$testWord\"", Toast.LENGTH_SHORT).show()
        }

        btnClearDeleteMemory.setOnClickListener {
            DeletePeekMemory.clearBuffer()
            updateStatusUi()
            Toast.makeText(this, "Deleted words buffer cleared", Toast.LENGTH_SHORT).show()
        }

        // Any Word / Line Peek listeners
        switchTextPeekMaster.setOnCheckedChangeListener { _, isChecked ->
            covertManager.isTextPeekEnabled = isChecked
            updateStatusUi()
        }

        rgTextPeekMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbTextPeekLastWord -> {
                    covertManager.textPeekMode = "last_word"
                    layoutTextPeekLineTarget.visibility = View.GONE
                }
                R.id.rbTextPeekLine -> {
                    covertManager.textPeekMode = "line"
                    layoutTextPeekLineTarget.visibility = View.VISIBLE
                }
                else -> {
                    covertManager.textPeekMode = "all"
                    layoutTextPeekLineTarget.visibility = View.GONE
                }
            }
        }

        fun selectTextPeekLine(lineNum: Int) {
            covertManager.textPeekTargetLine = lineNum
            tvTextPeekTargetLineLabel.text = "Target Line Number: Line $lineNum"
            Toast.makeText(this, "Set Peek Extraction to Line $lineNum", Toast.LENGTH_SHORT).show()
        }

        btnTextPeekLine1.setOnClickListener { selectTextPeekLine(1) }
        btnTextPeekLine2.setOnClickListener { selectTextPeekLine(2) }
        btnTextPeekLine3.setOnClickListener { selectTextPeekLine(3) }
        btnTextPeekLine4.setOnClickListener { selectTextPeekLine(4) }

        switchTextPeekSendInject.setOnCheckedChangeListener { _, isChecked ->
            covertManager.textPeekSendToInject = isChecked
        }

        switchTextPeekLocalNotif.setOnCheckedChangeListener { _, isChecked ->
            covertManager.textPeekLocalNotification = isChecked
            if (isChecked && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        btnTestTextPeekCapture.setOnClickListener {
            val sampleText = editSandbox.text.toString().ifEmpty { "First spectator wrote Sam\nSecond spectator wrote Tom Hanks\nThird line" }
            val extracted = covertManager.extractTextPeekPayload(sampleText) ?: "No text captured"
            if (covertManager.textPeekLocalNotification) {
                DeletePeekMemory.showPushNotification(this, extracted)
            }
            if (covertManager.textPeekSendToInject && covertManager.isInjectApiEnabled) {
                covertManager.dispatchInjectApi(extracted)
            }
            Toast.makeText(this, "Peek Captured (${covertManager.textPeekMode}): \"$extracted\"", Toast.LENGTH_SHORT).show()
        }

        // API Text Replace listeners
        switchTextReplaceMaster.setOnCheckedChangeListener { _, isChecked ->
            covertManager.isTextReplaceEnabled = isChecked
            updateStatusUi()
        }

        chipTagDoubleDash.setOnClickListener {
            editReplacePlaceholder.setText("--value--")
            covertManager.replacePlaceholder = "--value--"
        }
        chipTagCurly.setOnClickListener {
            editReplacePlaceholder.setText("{{value}}")
            covertManager.replacePlaceholder = "{{value}}"
        }
        chipTagBrackets.setOnClickListener {
            editReplacePlaceholder.setText("[VALUE]")
            covertManager.replacePlaceholder = "[VALUE]"
        }

        rgReplaceSourceMode.setOnCheckedChangeListener { _, checkedId ->
            covertManager.replaceSourceMode = if (checkedId == R.id.rbReplaceSourceCustom) "custom" else "api"
            updateStatusUi()
        }

        btnSaveReplaceSettings.setOnClickListener {
            val placeholder = editReplacePlaceholder.text.toString().trim()
            val fallback = editReplaceFallbackValue.text.toString().trim()
            val url = editReplaceApiUrl.text.toString().trim()
            if (placeholder.isNotEmpty()) covertManager.replacePlaceholder = placeholder
            if (fallback.isNotEmpty()) {
                covertManager.replaceFallbackValue = fallback
            }
            covertManager.replaceApiUrl = url
            covertManager.replaceSourceMode = if (rbReplaceSourceCustom.isChecked) "custom" else "api"
            updateStatusUi()
            val sourceLabel = if (covertManager.replaceSourceMode == "custom") "Pre-saved Text" else "API Data"
            Toast.makeText(this, "Replacement saved! Replaces \"${covertManager.replacePlaceholder}\" with $sourceLabel (\"${covertManager.getEffectiveReplacementValue()}\")", Toast.LENGTH_LONG).show()
        }

        btnFetchApiValueNow.setOnClickListener {
            Toast.makeText(this, "Fetching latest API value...", Toast.LENGTH_SHORT).show()
            covertManager.fetchLatestApiValue { success, result ->
                runOnUiThread {
                    updateStatusUi()
                    Toast.makeText(this, if (success) "Fetched API Value: \"$result\"" else "API Fetch Result: $result", Toast.LENGTH_LONG).show()
                }
            }
        }

        // Hardware & Sensor Trigger Listeners
        switchRequireTrigger.setOnCheckedChangeListener { _, isChecked ->
            TriggerManager.setRequireTriggerEnabled(this, isChecked)
            layoutTriggerDetails.visibility = if (isChecked) View.VISIBLE else View.GONE
            updateStatusUi()
        }

        switchTriggerVolume.setOnCheckedChangeListener { _, isChecked ->
            TriggerManager.setVolumeTriggerEnabled(this, isChecked)
        }

        switchTriggerProximity.setOnCheckedChangeListener { _, isChecked ->
            TriggerManager.setProximityTriggerEnabled(this, isChecked)
            if (isChecked) {
                tvTriggerProximityStatus.text = "Proximity Sensor: Monitoring Active"
            } else {
                tvTriggerProximityStatus.text = "Proximity Sensor: Disabled"
            }
        }

        switchTriggerHaptic.setOnCheckedChangeListener { _, isChecked ->
            TriggerManager.setHapticTriggerEnabled(this, isChecked)
        }

        btnFireTriggerTest.setOnClickListener {
            TriggerManager.fireTrigger("Manual Test Button", this)
            updateStatusUi()
        }

        btnClearTriggerQueue.setOnClickListener {
            TriggerManager.pendingDeletedWord = null
            TriggerManager.pendingMathPayload = null
            TriggerManager.pendingCovertWord = null
            TriggerManager.pendingTextPeekPayload = null
            updateStatusUi()
            Toast.makeText(this, "Pending trigger queue cleared", Toast.LENGTH_SHORT).show()
        }

        // Real-time callbacks for triggers in dialog
        TriggerManager.onPendingStateChanged = {
            runOnUiThread {
                tvTriggerPendingStatus.text = "Pending Queue: ${TriggerManager.getPendingSummary()}"
            }
        }

        TriggerManager.onProximityChanged = { isNear ->
            runOnUiThread {
                tvTriggerProximityStatus.text = if (isNear) {
                    "Proximity Sensor: NEAR (Hand Wave / Covered!)"
                } else {
                    "Proximity Sensor: FAR (Ready)"
                }
            }
        }

        TriggerManager.onTriggerFired = { source, summary ->
            runOnUiThread {
                updateStatusUi()
            }
        }

        editInjectUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                covertManager.injectApiUrl = s?.toString() ?: ""
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        editInjectKey.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                covertManager.injectApiKey = s?.toString() ?: ""
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnTestInjectApi.setOnClickListener {
            val testWord = covertManager.capturedSecretWord.ifEmpty { "elephant" }
            Toast.makeText(this, "Dispatching '$testWord' to Inject API...", Toast.LENGTH_SHORT).show()
            covertManager.dispatchInjectApi(testWord) { success, msg ->
                runOnUiThread {
                    Toast.makeText(this, if (success) "Inject API Success ($msg)" else "Inject API Response: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }

        editSandbox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val txt = s?.toString() ?: ""
                val lines = txt.split('\n').filter { it.trim().isNotEmpty() }
                val secret = covertManager.capturedSecretWord
                tvSandboxStatus.text = when {
                    lines.isEmpty() -> "Status: Ready on Line 1"
                    lines.size == 1 -> "Line 1: Covert input (${if (covertManager.isSecretWordCaptured) "Captured '$secret'" else "Typing covert sentence..."})"
                    else -> {
                        val spectatorNum = lines.size - 1
                        val letterIdx = spectatorNum - 1
                        val forcedChar = if (letterIdx < secret.length) "'${secret[letterIdx]}'" else "None (Done)"
                        "Line ${lines.size}: Spectator $spectatorNum (Letter: $forcedChar)"
                    }
                }
                updateStatusUi()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearSandbox.setOnClickListener {
            editSandbox.setText("")
            covertManager.resetSession()
            updateStatusUi()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            TriggerManager.startActiveSession(this)
        }

        dialog.setOnDismissListener {
            TriggerManager.onPendingStateChanged = null
            TriggerManager.onProximityChanged = null
            TriggerManager.onTriggerFired = null
            DeletePeekMemory.onDeletedWordChanged = null
        }

        dialog.show()
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (TriggerManager.isVolumeTriggerEnabled(this) &&
            (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val fired = TriggerManager.fireTrigger("Volume Hardware Key (Activity)", this)
            if (fired) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (TriggerManager.isVolumeTriggerEnabled(this) &&
            (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun triggerStealthVibrate() {
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(50, 200))
        } catch (_: Exception) {}
    }
}
