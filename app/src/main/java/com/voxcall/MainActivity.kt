package com.voxcall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var voiceBridge: ElevenLabsVoiceBridge

    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val status = findViewById<TextView>(R.id.statusText)
            status.text = if (granted) {
                "Microphone permission granted."
            } else {
                "Microphone permission required to stream transformed voice."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val apiKeyInput = findViewById<EditText>(R.id.apiKeyInput)
        val voiceIdInput = findViewById<EditText>(R.id.voiceIdInput)
        val genderSpinner = findViewById<Spinner>(R.id.genderSpinner)
        val ageInput = findViewById<EditText>(R.id.ageInput)
        val languageSpinner = findViewById<Spinner>(R.id.languageSpinner)
        val dialectSpinner = findViewById<Spinner>(R.id.dialectSpinner)
        val autoSelectCheck = findViewById<CheckBox>(R.id.autoSelectCheck)
        val statusText = findViewById<TextView>(R.id.statusText)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        voiceBridge = ElevenLabsVoiceBridge(applicationContext)

        startButton.setOnClickListener {
            ensureAudioPermission()
            val apiKey = apiKeyInput.text.toString().trim()
            val voiceId = voiceIdInput.text.toString().trim()
            val preferredGender = genderSpinner.selectedItem.toString().trim().lowercase()
            val preferredAge = ageInput.text.toString().toIntOrNull()
            val preferredLanguage = languageSpinner.selectedItem.toString().trim().lowercase()
            val preferredDialect = dialectSpinner.selectedItem.toString().trim().lowercase()
            val autoSelect = autoSelectCheck.isChecked

            if (apiKey.isBlank()) {
                statusText.text = "Enter ElevenLabs API key first."
                return@setOnClickListener
            }

            if (!autoSelect && voiceId.isBlank()) {
                statusText.text = "Enter a Voice ID or enable auto-select by profile traits."
                return@setOnClickListener
            }

            if (preferredAge == null || preferredAge !in 10..100) {
                statusText.text = "Enter a valid target age between 10 and 100."
                return@setOnClickListener
            }

            uiScope.launch {
                statusText.text = "Starting live transform..."
                val result = voiceBridge.start(
                    apiKey = apiKey,
                    voiceId = voiceId,
                    preferredGender = preferredGender,
                    preferredAge = preferredAge,
                    preferredLanguage = preferredLanguage,
                    preferredDialect = preferredDialect,
                    autoSelectVoice = autoSelect
                )
                statusText.text = result
            }
        }

        stopButton.setOnClickListener {
            uiScope.launch {
                voiceBridge.stop()
                statusText.text = "Stopped."
            }
        }
    }

    private fun ensureAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
        voiceBridge.stop()
    }
}
