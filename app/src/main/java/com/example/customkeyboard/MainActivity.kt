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

        setContentView(R.layout.activity_main)

        setupPublicUi()
        setupStealthTriggers()
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
        val btnResetSession = dialog.findViewById<Button>(R.id.btnResetSession)
        val btnDisarmNow = dialog.findViewById<Button>(R.id.btnDisarmNow)

        val editCoverSentence = dialog.findViewById<EditText>(R.id.editCoverSentence)
        val btnSaveCoverSentence = dialog.findViewById<Button>(R.id.btnSaveCoverSentence)
        val chipGroupCoverPresets = dialog.findViewById<ChipGroup>(R.id.chipGroupCoverPresets)

        val radioGroupRevealPos = dialog.findViewById<android.widget.RadioGroup>(R.id.radioGroupRevealPos)
        val radioReveal1st = dialog.findViewById<android.widget.RadioButton>(R.id.radioReveal1st)
        val radioReveal2nd = dialog.findViewById<android.widget.RadioButton>(R.id.radioReveal2nd)
        val radioReveal3rd = dialog.findViewById<android.widget.RadioButton>(R.id.radioReveal3rd)
        val radioReveal4th = dialog.findViewById<android.widget.RadioButton>(R.id.radioReveal4th)

        val switchInjectApi = dialog.findViewById<MaterialSwitch>(R.id.switchInjectApi)
        val layoutInjectSettings = dialog.findViewById<LinearLayout>(R.id.layoutInjectSettings)
        val editInjectUrl = dialog.findViewById<EditText>(R.id.editInjectUrl)
        val editInjectKey = dialog.findViewById<EditText>(R.id.editInjectKey)
        val btnTestInjectApi = dialog.findViewById<Button>(R.id.btnTestInjectApi)

        val editSandbox = dialog.findViewById<EditText>(R.id.editCovertSandbox)
        val tvSandboxStatus = dialog.findViewById<TextView>(R.id.tvSandboxLiveStatus)
        val btnClearSandbox = dialog.findViewById<Button>(R.id.btnClearCovertSandbox)
        val btnClose = dialog.findViewById<Button>(R.id.btnCloseCovert)

        fun updateStatusUi() {
            val isActive = covertManager.isCovertActive
            switchMaster.isChecked = isActive
            tvStatusTitle.text = if (isActive) "Covert Typing: ARMED" else "Covert Typing: DISARMED"

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
        }

        fun refreshPresetChips() {
            chipGroupCoverPresets.removeAllViews()
            val presets = covertManager.getPresets()
            presets.forEach { preset ->
                val chip = Chip(this).apply {
                    text = "${preset.label}: ${preset.text.take(30)}..."
                    isCheckable = false
                    isClickable = true
                    setChipBackgroundColorResource(R.color.edit_bg)
                    setTextColor(resources.getColor(R.color.edit_text_color, theme))
                    setChipStrokeColorResource(R.color.edit_stroke)
                    chipStrokeWidth = 1f * density
                    setOnClickListener {
                        if (preset.category == "Secret Words") {
                            covertManager.capturedSecretWord = preset.text
                            Toast.makeText(this@MainActivity, "Secret Word set to: ${preset.text}", Toast.LENGTH_SHORT).show()
                        } else {
                            covertManager.coverSentence = preset.text
                            editCoverSentence.setText(preset.text)
                            covertManager.armCovert(preset.text)
                            Toast.makeText(this@MainActivity, "Cover sentence applied & armed", Toast.LENGTH_SHORT).show()
                        }
                        updateStatusUi()
                    }
                }
                chipGroupCoverPresets.addView(chip)
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

        updateStatusUi()
        refreshPresetChips()

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

        dialog.show()
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
