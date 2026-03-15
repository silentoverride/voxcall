# VoxCall

Prototype Android app that relays microphone audio through ElevenLabs speech-to-speech and plays transformed output in realtime.

## Important limitation
Android does **not** let third-party apps directly replace your microphone stream in standard carrier calls. This project demonstrates a relay approach suitable for speakerphone workflows or custom VoIP stacks.

## Features
- Stream live mic audio to ElevenLabs speech-to-speech and hear transformed output.
- Search ElevenLabs voices with a lookup text field and filters that mirror Voice Library usage modes.
- Filter by **language**, **accent**, and usage categories: **conversational**, **narration**, **characters**, **social media**, **educational**, **advertisement**, and **entertainment**.


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
5. Enter voice lookup text and optional filters (language/accent + usage categories) to choose an ElevenLabs voice.
6. Start a phone call on speaker or use a VoIP app, then tap **Start relay**.

## ElevenLabs endpoints
The app currently uses:

- `GET https://api.elevenlabs.io/v1/voices` to search and score voices by lookup text, language, accent, and usage-category filters.
- `wss://api.elevenlabs.io/v1/speech-to-speech/{voiceId}/stream` for low-latency speech relay.

If ElevenLabs updates protocol requirements, adjust request payload/parsing in `ElevenLabsVoiceBridge.kt`.

## Ethical and legal use
Always disclose voice modification where required by law and obtain consent from call participants.


## Local release build command
After installing the SDK and setting `ANDROID_SDK_ROOT`/`ANDROID_HOME`, build a release APK with:

```bash
gradle :app:assembleRelease
```

## Build APKs for GitHub Releases
This repo includes `.github/workflows/release-apk.yml`.

- Push a tag like `v1.0.0` to trigger a release build and publish `voxcall-release.apk` on the GitHub Releases page.
- You can also run the workflow manually from the **Actions** tab (`workflow_dispatch`).

### Signing setup (recommended for installable updates)
Add these repository secrets so release APKs are signed with a stable key:

- `ANDROID_KEYSTORE_BASE64` (base64-encoded keystore file)
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

If secrets are missing on non-tag runs, the workflow still succeeds and uploads `app-release-unsigned.apk` as a workflow artifact.
For tag builds (`v*`), signing secrets are required and the workflow fails if they are missing, ensuring published releases are always signed.
To support in-place updates for users, configure secrets so `voxcall-release.apk` is signed with your persistent keystore.
