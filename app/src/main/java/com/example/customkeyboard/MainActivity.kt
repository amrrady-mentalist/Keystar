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
        val tvProgress = dialog.findViewById<TextView>(R.id.tvCovertProgress)
        val switchMaster = dialog.findViewById<MaterialSwitch>(R.id.switchCovertMaster)
        val btnResetIndex = dialog.findViewById<Button>(R.id.btnResetIndex)
        val btnDisarmNow = dialog.findViewById<Button>(R.id.btnDisarmNow)

        val editTarget = dialog.findViewById<EditText>(R.id.editCovertTarget)
        val btnSaveAndArm = dialog.findViewById<Button>(R.id.btnSaveAndArm)
        val chipGroup = dialog.findViewById<ChipGroup>(R.id.chipGroupPresets)
        val btnAddPreset = dialog.findViewById<Button>(R.id.btnAddCustomPreset)

        val switchAutoDisarm = dialog.findViewById<MaterialSwitch>(R.id.switchAutoDisarm)
        val switchSpacebar = dialog.findViewById<MaterialSwitch>(R.id.switchSpacebarTrigger)
        val switchHaptics = dialog.findViewById<MaterialSwitch>(R.id.switchHaptics)

        val editSandbox = dialog.findViewById<EditText>(R.id.editCovertSandbox)
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
            
            tvProgress.text = "Target: \"${covertManager.targetText}\" (Index: ${covertManager.currentIndex}/${covertManager.targetText.length})"
        }

        fun refreshPresetChips() {
            chipGroup.removeAllViews()
            val presets = covertManager.getPresets()
            val density = resources.displayMetrics.density
            presets.forEach { preset ->
                val chip = Chip(this).apply {
                    text = "${preset.label}: ${preset.text}"
                    isCheckable = false
                    isClickable = true
                    setChipBackgroundColorResource(R.color.edit_bg)
                    setTextColor(resources.getColor(R.color.edit_text_color, theme))
                    setChipStrokeColorResource(R.color.edit_stroke)
                    chipStrokeWidth = 1f * density
                    setOnClickListener {
                        covertManager.targetText = preset.text
                        covertManager.armCovert(preset.text)
                        editTarget.setText(preset.text)
                        updateStatusUi()
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    setOnLongClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Delete Preset")
                            .setMessage("Remove '${preset.label}' from presets?")
                            .setPositiveButton("Delete") { _, _ ->
                                covertManager.deletePreset(preset.id)
                                refreshPresetChips()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }
                }
                chipGroup.addView(chip)
            }
        }

        // Initialize values
        editTarget.setText(covertManager.targetText)
        switchAutoDisarm.isChecked = covertManager.autoDisarmOnFinish
        switchSpacebar.isChecked = covertManager.stealthSpacebarTrigger
        switchHaptics.isChecked = covertManager.stealthHapticFeedback
        updateStatusUi()
        refreshPresetChips()

        // Bind Listeners
        switchMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val txt = editTarget.text.toString().trim()
                covertManager.armCovert(if (txt.isNotEmpty()) txt else null)
            } else {
                covertManager.disarmCovert()
            }
            updateStatusUi()
        }

        btnSaveAndArm.setOnClickListener {
            val txt = editTarget.text.toString().trim()
            if (txt.isNotEmpty()) {
                covertManager.armCovert(txt)
                updateStatusUi()
                Toast.makeText(this, "Covert Text Armed!", Toast.LENGTH_SHORT).show()
            }
        }

        btnResetIndex.setOnClickListener {
            covertManager.resetIndex()
            updateStatusUi()
            Toast.makeText(this, "Progress reset to character 0", Toast.LENGTH_SHORT).show()
        }

        btnDisarmNow.setOnClickListener {
            covertManager.disarmCovert()
            updateStatusUi()
        }

        btnAddPreset.setOnClickListener {
            val currentText = editTarget.text.toString().trim()
            if (currentText.isEmpty()) {
                Toast.makeText(this, "Enter text in the target box first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val input = EditText(this).apply {
                hint = "Label (e.g. Card Force, PIN)"
                setText(currentText.take(15))
                setTextColor(resources.getColor(R.color.edit_text_color, theme))
                setHintTextColor(resources.getColor(R.color.edit_hint_color, theme))
                background = getDrawable(R.drawable.bg_edit_text)
                setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            }
            val container = LinearLayout(this).apply {
                setPadding((20 * density).toInt(), (10 * density).toInt(), (20 * density).toInt(), (10 * density).toInt())
                addView(input)
            }
            AlertDialog.Builder(this)
                .setTitle("Save as Preset")
                .setView(container)
                .setPositiveButton("Save") { _, _ ->
                    val label = input.text.toString().trim().ifEmpty { "Custom" }
                    covertManager.addPreset("Custom", label, currentText)
                    refreshPresetChips()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        switchAutoDisarm.setOnCheckedChangeListener { _, isChecked ->
            covertManager.autoDisarmOnFinish = isChecked
        }

        switchSpacebar.setOnCheckedChangeListener { _, isChecked ->
            covertManager.stealthSpacebarTrigger = isChecked
        }

        switchHaptics.setOnCheckedChangeListener { _, isChecked ->
            covertManager.stealthHapticFeedback = isChecked
        }

        btnClearSandbox.setOnClickListener {
            editSandbox.setText("")
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
