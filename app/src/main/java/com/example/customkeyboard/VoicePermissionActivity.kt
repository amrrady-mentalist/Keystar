package com.example.customkeyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class VoicePermissionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_SPEECH_INTENT = "extra_start_speech_intent"
    }

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(this, "Microphone enabled", Toast.LENGTH_SHORT).show()
            val startSpeechIntent = intent.getBooleanExtra(EXTRA_START_SPEECH_INTENT, false)
            if (startSpeechIntent) {
                launchSpeechRecognizerIntent()
            } else {
                CustomKeyboardService.activeInstance?.startVoiceTyping()
                finish()
            }
        } else {
            Toast.makeText(this, "Microphone permission is required for voice typing", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val speechResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val spokenText = matches[0]
                CustomKeyboardService.activeInstance?.commitVoiceText(spokenText)
            }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val startSpeechIntent = intent.getBooleanExtra(EXTRA_START_SPEECH_INTENT, false)

        if (!hasAudioPermission) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (startSpeechIntent) {
            launchSpeechRecognizerIntent()
        } else {
            CustomKeyboardService.activeInstance?.startVoiceTyping()
            finish()
        }
    }

    private fun launchSpeechRecognizerIntent() {
        try {
            val isArabic = CustomKeyboardService.activeInstance?.isArabicLanguage() == true
            val langCode = if (isArabic) "ar-SA" else "en-US"
            val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langCode)
                putExtra(RecognizerIntent.EXTRA_PROMPT, if (isArabic) "تكلّم الآن..." else "Speak now...")
            }
            speechResultLauncher.launch(speechIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice typing is not available on this device", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
