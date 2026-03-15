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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class ElevenLabsVoiceBridge(private val context: Context) {
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
        voiceId: String,
        preferredGender: String,
        preferredAge: Int,
        autoSelectVoice: Boolean
    ): String = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext "Voice transform already running."

        val selectedVoiceId = if (autoSelectVoice) {
            findBestVoiceId(apiKey, preferredGender, preferredAge)
                ?: return@withContext "No ElevenLabs voice matched gender/age preference."
        } else {
            voiceId
        }

        if (selectedVoiceId.isBlank()) {
            return@withContext "Enter a Voice ID or enable auto-select."
        }

        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (bufferSize <= 0) return@withContext "Device does not support required audio configuration."

        val audioManager = context.getSystemService(AudioManager::class.java)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

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

        return@withContext if (autoSelectVoice) {
            "Streaming with auto-selected voice $selectedVoiceId ($preferredGender, age $preferredAge)."
        } else {
            "Streaming mic input to ElevenLabs. Use speakerphone for your call."
        }
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

    private fun findBestVoiceId(apiKey: String, preferredGender: String, preferredAge: Int): String? {
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
        var bestVoiceId: String? = null
        var bestScore = Int.MIN_VALUE

        for (index in 0 until voices.length()) {
            val voice = voices.optJSONObject(index) ?: continue
            val labels = voice.optJSONObject("labels")
            val genderLabel = labels?.optString("gender").orEmpty().lowercase()
            val ageLabel = labels?.optString("age").orEmpty().lowercase()

            var score = 0
            if (genderLabel == preferredGender.lowercase()) score += 3

            val ageDistance = when (ageLabel) {
                "young" -> abs(preferredAge - 20)
                "middle_aged" -> abs(preferredAge - 40)
                "old" -> abs(preferredAge - 65)
                else -> 25
            }
            score += (30 - ageDistance).coerceAtLeast(0)

            if (score > bestScore) {
                bestScore = score
                bestVoiceId = voice.optString("voice_id")
            }
        }

        return bestVoiceId
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
