# VoxCall

Prototype Android app that relays microphone audio through ElevenLabs speech-to-speech and plays transformed output in realtime.

## Important limitation
Android does **not** let third-party apps directly replace your microphone stream in standard carrier calls. This project demonstrates a relay approach suitable for speakerphone workflows or custom VoIP stacks.

## Features
- Stream live mic audio to ElevenLabs speech-to-speech and hear transformed output.
- Set preferred **gender** and **age**.
- Auto-select the closest available ElevenLabs voice from your account voice catalog based on those traits.


## Environment setup (this container)
To install Android SDK components used by this project in this environment:

```bash
./scripts/install_android_sdk.sh
export ANDROID_SDK_ROOT=/opt/android-sdk
export ANDROID_HOME=/opt/android-sdk
```

## Setup
1. Open in Android Studio (Hedgehog+).
2. Let Gradle sync.
3. Run on a physical Android device.
4. Paste your ElevenLabs API key.
5. Optionally paste a specific Voice ID, or enable auto-select by gender/age.
6. Start a phone call on speaker or use a VoIP app, then tap **Start relay**.

## ElevenLabs endpoints
The app currently uses:

- `GET https://api.elevenlabs.io/v1/voices` to find the closest voice for selected gender/age.
- `wss://api.elevenlabs.io/v1/speech-to-speech/{voiceId}/stream` for low-latency speech relay.

If ElevenLabs updates protocol requirements, adjust request payload/parsing in `ElevenLabsVoiceBridge.kt`.

## Ethical and legal use
Always disclose voice modification where required by law and obtain consent from call participants.
