package com.voxcall

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ElevenLabsVoiceBridge(private val context: Context) {
    data class VoiceSearchFilters(
        val searchText: String,
        val language: String,
        val accent: String,
        val conversational: Boolean,
        val narration: Boolean,
        val characters: Boolean,
        val socialMedia: Boolean,
        val educational: Boolean,
        val advertisement: Boolean,
        val entertainment: Boolean
    )

    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val running = AtomicBoolean(false)
    private val ioScope = CoroutineScope(Dispatchers.IO + Job())

    suspend fun start(
        apiKey: String,
        filters: VoiceSearchFilters
    ): String = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext "Voice transform already running."

        val selectedVoice = findBestVoice(apiKey = apiKey, filters = filters)
            ?: return@withContext "No ElevenLabs voice matched your lookup filters."

        val selectedVoiceId = selectedVoice.optString("voice_id")
        val selectedVoiceName = selectedVoice.optString("name").ifBlank { selectedVoiceId }

        if (selectedVoiceId.isBlank()) {
            return@withContext "No valid voice ID returned by ElevenLabs."
        }

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (bufferSize <= 0) return@withContext "Device does not support required audio configuration."

        val audioManager = context.getSystemService(AudioManager::class.java)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        run {
            audioManager.isSpeakerphoneOn = true
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            encoding,
            bufferSize * 2
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize * 2)
            .build()

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("wss://api.elevenlabs.io/v1/speech-to-speech/$selectedVoiceId/stream")
            .addHeader("xi-api-key", apiKey)
            .build()

        webSocket = client.newWebSocket(request, VoiceListener())
        running.set(true)

        ioScope.launch {
            val recorder = audioRecord ?: return@launch
            recorder.startRecording()
            while (running.get()) {
                val bytes = ByteArray(bufferSize)
                val read = recorder.read(bytes, 0, bytes.size)
                if (read > 0) {
                    val payload = JSONObject()
                        .put("audio", Base64.encodeToString(bytes.copyOf(read), Base64.NO_WRAP))
                        .put("mime_type", "audio/pcm;rate=16000")
                    webSocket?.send(payload.toString())
                }
            }
        }

        "Streaming with voice: $selectedVoiceName ($selectedVoiceId)."
    }

    fun stop() {
        running.set(false)
        webSocket?.close(1000, "stop")
        webSocket = null

        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        audioTrack?.apply {
            stop()
            release()
        }
        audioTrack = null
    }

    private fun findBestVoice(apiKey: String, filters: VoiceSearchFilters): JSONObject? {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/voices")
            .addHeader("xi-api-key", apiKey)
            .build()

        val response: Response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            return null
        }

        val json = response.body?.string().orEmpty()
        response.close()

        val voices = runCatching { JSONObject(json).optJSONArray("voices") }.getOrNull() ?: return null

        var bestVoice: JSONObject? = null
        var bestScore = Int.MIN_VALUE

        for (index in 0 until voices.length()) {
            val voice = voices.optJSONObject(index) ?: continue
            val score = scoreVoice(voice, filters)
            if (score > bestScore) {
                bestScore = score
                bestVoice = voice
            }
        }

        return if (bestScore >= 0) bestVoice else null
    }

    private fun scoreVoice(voice: JSONObject, filters: VoiceSearchFilters): Int {
        val labels = voice.optJSONObject("labels")
        val languageLabel = (
            labels?.optString("language")
                ?: labels?.optString("lang")
                ?: labels?.optString("locale")
            ).normalize()
        val accentLabel = (
            labels?.optString("accent")
                ?: labels?.optString("dialect")
            ).normalize()

        val searchableBlob = buildString {
            append(voice.optString("name"))
            append(' ')
            append(voice.optString("description"))
            append(' ')
            append(voice.optString("category"))
            append(' ')
            if (labels != null) append(labels.toString())
        }.normalize()

        var score = 0
        val normalizedSearchText = filters.searchText.normalize()
        if (normalizedSearchText.isNotBlank()) {
            if (!searchableBlob.contains(normalizedSearchText)) return -1
            score += 12
        }

        val normalizedLanguage = filters.language.normalize()
        if (normalizedLanguage.isNotBlank()) {
            if (!languageLabel.contains(normalizedLanguage) && !searchableBlob.contains(normalizedLanguage)) return -1
            score += 8
        }

        val normalizedAccent = filters.accent.normalize()
        if (normalizedAccent.isNotBlank()) {
            if (!accentLabel.contains(normalizedAccent) && !searchableBlob.contains(normalizedAccent)) return -1
            score += 8
        }

        if (filters.conversational && !containsAny(searchableBlob, listOf("conversational", "conversation", "chat"))) return -1
        if (filters.narration && !containsAny(searchableBlob, listOf("narration", "narrator", "audiobook", "story"))) return -1
        if (filters.characters && !containsAny(searchableBlob, listOf("character", "characters", "roleplay", "cartoon", "anime"))) return -1
        if (filters.socialMedia && !containsAny(searchableBlob, listOf("social media", "tiktok", "youtube", "instagram", "short form"))) return -1
        if (filters.educational && !containsAny(searchableBlob, listOf("education", "educational", "training", "explainer", "teacher"))) return -1
        if (filters.advertisement && !containsAny(searchableBlob, listOf("advertisement", "ad", "promo", "commercial", "marketing"))) return -1
        if (filters.entertainment && !containsAny(searchableBlob, listOf("entertainment", "gaming", "fun", "podcast", "streamer"))) return -1

        if (filters.conversational) score += 4
        if (filters.narration) score += 4
        if (filters.characters) score += 4
        if (filters.socialMedia) score += 4
        if (filters.educational) score += 4
        if (filters.advertisement) score += 4
        if (filters.entertainment) score += 4

        return score
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it.normalize()) }
    }

    private fun String?.normalize(): String {
        return this.orEmpty().trim().lowercase(Locale.ROOT)
    }

    private inner class VoiceListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val audioBase64 = runCatching {
                JSONObject(text).optString("audio")
            }.getOrNull()

            if (!audioBase64.isNullOrBlank()) {
                val pcm = Base64.decode(audioBase64, Base64.DEFAULT)
                audioTrack?.apply {
                    if (playState != AudioTrack.PLAYSTATE_PLAYING) {
                        play()
                    }
                    write(pcm, 0, pcm.size)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            running.set(false)
        }
    }
}
