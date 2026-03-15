package com.voxcall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
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
        val voiceSearchInput = findViewById<EditText>(R.id.voiceSearchInput)
        val languageFilterInput = findViewById<EditText>(R.id.languageFilterInput)
        val accentFilterInput = findViewById<EditText>(R.id.accentFilterInput)
        val conversationalSpinner = findViewById<Spinner>(R.id.conversationalSpinner)
        val narrationSpinner = findViewById<Spinner>(R.id.narrationSpinner)
        val charactersSpinner = findViewById<Spinner>(R.id.charactersSpinner)
        val socialMediaSpinner = findViewById<Spinner>(R.id.socialMediaSpinner)
        val educationalSpinner = findViewById<Spinner>(R.id.educationalSpinner)
        val advertisementSpinner = findViewById<Spinner>(R.id.advertisementSpinner)
        val entertainmentSpinner = findViewById<Spinner>(R.id.entertainmentSpinner)
        val statusText = findViewById<TextView>(R.id.statusText)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)

        voiceBridge = ElevenLabsVoiceBridge(applicationContext)

        startButton.setOnClickListener {
            ensureAudioPermission()
            val apiKey = apiKeyInput.text.toString().trim()
            val searchText = voiceSearchInput.text.toString().trim()
            val language = languageFilterInput.text.toString().trim()
            val accent = accentFilterInput.text.toString().trim()

            if (apiKey.isBlank()) {
                statusText.text = "Enter ElevenLabs API key first."
                return@setOnClickListener
            }

            val filters = ElevenLabsVoiceBridge.VoiceSearchFilters(
                searchText = searchText,
                language = language,
                accent = accent,
                conversational = conversationalSpinner.selectedItem.toString() == "required",
                narration = narrationSpinner.selectedItem.toString() == "required",
                characters = charactersSpinner.selectedItem.toString() == "required",
                socialMedia = socialMediaSpinner.selectedItem.toString() == "required",
                educational = educationalSpinner.selectedItem.toString() == "required",
                advertisement = advertisementSpinner.selectedItem.toString() == "required",
                entertainment = entertainmentSpinner.selectedItem.toString() == "required"
            )

            uiScope.launch {
                statusText.text = "Looking up voices and starting live transform..."
                val result = voiceBridge.start(
                    apiKey = apiKey,
                    filters = filters
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
