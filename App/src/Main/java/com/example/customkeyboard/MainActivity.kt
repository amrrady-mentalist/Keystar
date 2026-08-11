package com.example.customkeyboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE)

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
        }
    }
}
