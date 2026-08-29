package com.example.customkeyboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var magicManager: MagicManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        magicManager = MagicManager(this)
        val prefs = getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE)

        setupActivationAndTheme(prefs)
        setupLiveScratchpad()
        setupForceTyping()
        setupPresetNotes()
        setupShortcuts()
    }

    override fun onResume() {
        super.onResume()
        refreshForceUi()
        refreshPresetNotes()
        refreshShortcuts()
    }

    // ---------- Live Scratchpad ----------
    private fun setupLiveScratchpad() {
        val editSandbox = findViewById<EditText>(R.id.editSandbox)
        val btnClear = findViewById<Button>(R.id.btnClearSandbox)
        btnClear.setOnClickListener {
            editSandbox.setText("")
        }
    }

    // ---------- Force Typing Setup ----------
    private fun setupForceTyping() {
        val switchForce = findViewById<MaterialSwitch>(R.id.switchForceActive)
        val editForceText = findViewById<EditText>(R.id.editForceText)
        val btnSaveForceText = findViewById<Button>(R.id.btnSaveForceText)
        val btnResetForceIndex = findViewById<Button>(R.id.btnResetForceIndex)
        val chkTriggerSpace = findViewById<CheckBox>(R.id.chkTriggerSpace)

        switchForce.isChecked = magicManager.isForceEnabled
        editForceText.setText(magicManager.forceText)
        chkTriggerSpace.isChecked = magicManager.triggerOnSpaceLongPress

        switchForce.setOnCheckedChangeListener { _, isChecked ->
            magicManager.isForceEnabled = isChecked
            if (isChecked) magicManager.resetForceIndex()
            refreshForceUi()
        }

        btnSaveForceText.setOnClickListener {
            val text = editForceText.text.toString().trim()
            if (text.isNotEmpty()) {
                magicManager.forceText = text
                magicManager.resetForceIndex()
                refreshForceUi()
                Toast.makeText(this, "Target Force text saved!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter target text", Toast.LENGTH_SHORT).show()
            }
        }

        btnResetForceIndex.setOnClickListener {
            magicManager.resetForceIndex()
            refreshForceUi()
            Toast.makeText(this, "Force progress reset to character 0", Toast.LENGTH_SHORT).show()
        }

        chkTriggerSpace.setOnCheckedChangeListener { _, isChecked ->
            magicManager.triggerOnSpaceLongPress = isChecked
        }

        refreshForceUi()
    }

    private fun refreshForceUi() {
        val switchForce = findViewById<MaterialSwitch>(R.id.switchForceActive)
        val textForceStatus = findViewById<TextView>(R.id.textForceStatus)
        switchForce.isChecked = magicManager.isForceEnabled

        val total = magicManager.forceText.length
        val current = magicManager.forceIndex
        textForceStatus.text = "Progress: Char $current / $total"
    }

    // ---------- Preset Notes Setup ----------
    private fun setupPresetNotes() {
        val btnAdd = findViewById<Button>(R.id.btnAddPresetNote)
        btnAdd.setOnClickListener {
            showNoteDialog(null)
        }
        refreshPresetNotes()
    }

    private fun refreshPresetNotes() {
        val container = findViewById<LinearLayout>(R.id.containerPresetNotes)
        container.removeAllViews()

        val notes = magicManager.getPresetNotes()
        if (notes.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No preset notes yet. Tap '+ Add Note' to create your first prediction."
                alpha = 0.6f
                setPadding(0, 16, 0, 16)
            }
            container.addView(emptyTv)
            return
        }

        notes.forEach { note ->
            val card = MaterialCardView(this).apply {
                radius = 24f
                strokeWidth = 1
                strokeColor = 0x33888888
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 12 }
            }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 20, 24, 20)
            }

            val titleTv = TextView(this).apply {
                text = note.title
                textSize = 15f
                paint.isFakeBoldText = true
            }

            val contentTv = TextView(this).apply {
                text = note.content
                textSize = 13f
                alpha = 0.8f
                setPadding(0, 4, 0, 12)
            }

            val buttonsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }

            val armBtn = Button(this).apply {
                text = "⚡ Arm as Force"
                textSize = 11f
                setOnClickListener {
                    magicManager.forceText = note.content
                    magicManager.resetForceIndex()
                    magicManager.isForceEnabled = true
                    findViewById<EditText>(R.id.editForceText).setText(note.content)
                    refreshForceUi()
                    Toast.makeText(this@MainActivity, "Armed: \"${note.title}\"", Toast.LENGTH_SHORT).show()
                }
            }

            val editBtn = Button(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Edit"
                textSize = 11f
                setOnClickListener {
                    showNoteDialog(note)
                }
            }

            val deleteBtn = Button(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Delete"
                textSize = 11f
                setTextColor(0xFFFF5252.toInt())
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete Preset")
                        .setMessage("Delete \"${note.title}\"?")
                        .setPositiveButton("Delete") { _, _ ->
                            magicManager.deletePresetNote(note.id)
                            refreshPresetNotes()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }

            buttonsRow.addView(armBtn)
            buttonsRow.addView(editBtn)
            buttonsRow.addView(deleteBtn)

            inner.addView(titleTv)
            inner.addView(contentTv)
            inner.addView(buttonsRow)
            card.addView(inner)
            container.addView(card)
        }
    }

    private fun showNoteDialog(existing: MagicManager.PresetNote?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val titleInput = EditText(this).apply {
            hint = "Title / Label (e.g. Card Prediction)"
            setText(existing?.title ?: "")
        }

        val contentInput = EditText(this).apply {
            hint = "Secret Note / Prediction content to inject"
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            setText(existing?.content ?: "")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
        }

        layout.addView(titleInput)
        layout.addView(contentInput)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New Preset Note" else "Edit Preset Note")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text.toString().trim()
                val content = contentInput.text.toString().trim()
                if (title.isNotEmpty() && content.isNotEmpty()) {
                    if (existing != null) {
                        magicManager.updatePresetNote(existing.id, title, content)
                    } else {
                        magicManager.addPresetNote(title, content)
                    }
                    refreshPresetNotes()
                } else {
                    Toast.makeText(this, "Title and Content cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Shortcuts Setup ----------
    private fun setupShortcuts() {
        val btnAdd = findViewById<Button>(R.id.btnAddShortcut)
        btnAdd.setOnClickListener {
            showShortcutDialog(null)
        }
        refreshShortcuts()
    }

    private fun refreshShortcuts() {
        val container = findViewById<LinearLayout>(R.id.containerShortcuts)
        container.removeAllViews()

        val shortcuts = magicManager.getShortcuts()
        if (shortcuts.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No shortcuts yet. Tap '+ Shortcut' to create instant keyword triggers."
                alpha = 0.6f
                setPadding(0, 16, 0, 16)
            }
            container.addView(emptyTv)
            return
        }

        shortcuts.forEach { sc ->
            val card = MaterialCardView(this).apply {
                radius = 24f
                strokeWidth = 1
                strokeColor = 0x33888888
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 10 }
            }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(24, 16, 24, 16)
            }

            val infoLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val shortcutTv = TextView(this).apply {
                text = "Trigger: \"${sc.shortcut}\""
                textSize = 14f
                paint.isFakeBoldText = true
            }

            val expansionTv = TextView(this).apply {
                text = "→ \"${sc.expansion}\""
                textSize = 13f
                alpha = 0.8f
                setPadding(0, 2, 0, 0)
            }

            infoLayout.addView(shortcutTv)
            infoLayout.addView(expansionTv)

            val editBtn = Button(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "Edit"
                textSize = 11f
                setOnClickListener {
                    showShortcutDialog(sc)
                }
            }

            val deleteBtn = Button(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = "✕"
                textSize = 14f
                setTextColor(0xFFFF5252.toInt())
                setOnClickListener {
                    magicManager.deleteShortcut(sc.id)
                    refreshShortcuts()
                }
            }

            inner.addView(infoLayout)
            inner.addView(editBtn)
            inner.addView(deleteBtn)
            card.addView(inner)
            container.addView(card)
        }
    }

    private fun showShortcutDialog(existing: MagicManager.ShortcutItem?) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        val shortcutInput = EditText(this).apply {
            hint = "Trigger keyword (e.g. .force or 3h)"
            setText(existing?.shortcut ?: "")
        }

        val expansionInput = EditText(this).apply {
            hint = "Expanded replacement text (e.g. 3 of Hearts ♥)"
            setText(existing?.expansion ?: "")
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 }
        }

        layout.addView(shortcutInput)
        layout.addView(expansionInput)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New Shortcut" else "Edit Shortcut")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val shortcut = shortcutInput.text.toString().trim()
                val expansion = expansionInput.text.toString().trim()
                if (shortcut.isNotEmpty() && expansion.isNotEmpty()) {
                    if (existing != null) {
                        magicManager.updateShortcut(existing.id, shortcut, expansion)
                    } else {
                        magicManager.addShortcut(shortcut, expansion)
                    }
                    refreshShortcuts()
                } else {
                    Toast.makeText(this, "Shortcut trigger and Expansion cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- Activation and Theme ----------
    private fun setupActivationAndTheme(prefs: android.content.SharedPreferences) {
        findViewById<Button>(R.id.btnEnable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }

        findViewById<Button>(R.id.btnSwitch).setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        val themeGroup = findViewById<RadioGroup>(R.id.themeGroup)
        when (prefs.getString("theme_override", "system")) {
            "light" -> themeGroup.check(R.id.radioLight)
            "dark" -> themeGroup.check(R.id.radioDark)
            else -> themeGroup.check(R.id.radioSystem)
        }

        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.radioLight -> "light"
                R.id.radioDark -> "dark"
                else -> "system"
            }
            prefs.edit().putString("theme_override", value).apply()
            Toast.makeText(this, "Keyboard theme preference updated", Toast.LENGTH_SHORT).show()
        }
    }
}
