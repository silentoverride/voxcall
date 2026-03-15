package com.voxcall

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
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
    private var cachedApiKey: String = ""
    private var cachedVoiceOptions: List<ElevenLabsVoiceBridge.VoiceOption> = emptyList()

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

        val apiKeyInput = findViewById<android.widget.EditText>(R.id.apiKeyInput)
        val voiceSearchInput = findViewById<AutoCompleteTextView>(R.id.voiceSearchInput)
        val languageFilterInput = findViewById<AutoCompleteTextView>(R.id.languageFilterInput)
        val accentFilterInput = findViewById<AutoCompleteTextView>(R.id.accentFilterInput)
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

        val emptyDropdownAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>())
        languageFilterInput.setAdapter(emptyDropdownAdapter)
        accentFilterInput.setAdapter(emptyDropdownAdapter)
        voiceSearchInput.setAdapter(emptyDropdownAdapter)

        languageFilterInput.threshold = 0
        accentFilterInput.threshold = 0
        voiceSearchInput.threshold = 0

        val selectionWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateVoiceSearchSuggestions(voiceSearchInput, languageFilterInput, accentFilterInput)
            }
        }

        voiceSearchInput.addTextChangedListener(selectionWatcher)
        languageFilterInput.addTextChangedListener(selectionWatcher)
        accentFilterInput.addTextChangedListener(selectionWatcher)

        fun showDropDownWithRefresh(field: AutoCompleteTextView) {
            field.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    uiScope.launch {
                        loadVoiceOptionsIfNeeded(apiKeyInput, statusText, languageFilterInput, accentFilterInput, voiceSearchInput)
                        field.showDropDown()
                    }
                }
            }
            field.setOnClickListener {
                uiScope.launch {
                    loadVoiceOptionsIfNeeded(apiKeyInput, statusText, languageFilterInput, accentFilterInput, voiceSearchInput)
                    field.showDropDown()
                }
            }
        }

        showDropDownWithRefresh(languageFilterInput)
        showDropDownWithRefresh(accentFilterInput)
        showDropDownWithRefresh(voiceSearchInput)

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

    private suspend fun loadVoiceOptionsIfNeeded(
        apiKeyInput: android.widget.EditText,
        statusText: TextView,
        languageFilterInput: AutoCompleteTextView,
        accentFilterInput: AutoCompleteTextView,
        voiceSearchInput: AutoCompleteTextView
    ) {
        val apiKey = apiKeyInput.text.toString().trim()
        if (apiKey.isBlank()) {
            statusText.text = "Enter ElevenLabs API key to load voice list."
            return
        }

        if (apiKey == cachedApiKey && cachedVoiceOptions.isNotEmpty()) {
            return
        }

        statusText.text = "Loading voices..."
        val voiceOptions = voiceBridge.fetchVoiceOptions(apiKey)

        if (voiceOptions.isEmpty()) {
            statusText.text = "Unable to load voices with current API key."
            return
        }

        cachedApiKey = apiKey
        cachedVoiceOptions = voiceOptions
        statusText.text = "Loaded ${voiceOptions.size} voices."

        val languageOptions = voiceOptions.map { it.language }.filter { it.isNotBlank() }.distinct().sorted()
        val accentOptions = voiceOptions.map { it.accent }.filter { it.isNotBlank() }.distinct().sorted()

        languageFilterInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, languageOptions)
        )
        accentFilterInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, accentOptions)
        )

        updateVoiceSearchSuggestions(voiceSearchInput, languageFilterInput, accentFilterInput)
    }

    private fun updateVoiceSearchSuggestions(
        voiceSearchInput: AutoCompleteTextView,
        languageFilterInput: AutoCompleteTextView,
        accentFilterInput: AutoCompleteTextView
    ) {
        val query = voiceSearchInput.text.toString().trim().lowercase()
        val selectedLanguage = languageFilterInput.text.toString().trim().lowercase()
        val selectedAccent = accentFilterInput.text.toString().trim().lowercase()

        val filteredVoiceNames = cachedVoiceOptions
            .asSequence()
            .filter {
                selectedLanguage.isBlank() || it.language.lowercase().contains(selectedLanguage)
            }
            .filter {
                selectedAccent.isBlank() || it.accent.lowercase().contains(selectedAccent)
            }
            .filter {
                query.isBlank() || it.searchableText.contains(query)
            }
            .map { it.displayName }
            .distinct()
            .toList()

        voiceSearchInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, filteredVoiceNames)
        )
        if (voiceSearchInput.hasFocus()) {
            voiceSearchInput.showDropDown()
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
