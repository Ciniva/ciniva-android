# Ciniva for Android

The Android app for your [Ciniva](https://github.com/Ciniva/ciniva) server, for phones and Android TV (Google TV, Shield, Chromecast with Google TV).

- **Library:** Home (continue watching, recently added), Movies, Shows, Search, movie and show pages with seasons and episodes.
- **Playback (Media3/ExoPlayer):** files play as they are when the device's decoders, audio output (passthrough to a soundbar or receiver) and screen (HDR) handle them. Anything else is converted by Ciniva to HLS, which copies whatever the device does decode. If direct play fails, the app switches to the server's conversion at the same position.
- **In the player:** resume, progress synced with the server, audio track, quality, subtitles, skip credits and next episode.
- **Android TV:** the same APK, with a side rail, remote navigation and focus highlights.

## Install

Download the APK from [Releases](https://github.com/Ciniva/ciniva-android/releases) and open it on the device (allow installs from your browser or file manager). On Android TV, use a file manager or `adb install`.

Sign in with your server's address (for example `192.168.1.10:8484`) and your Ciniva username and password.

## Build

JDK 17 and the Android SDK (platform 36, build-tools 36) are needed.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The version lives in `VERSION`.

## Releases

Bump `VERSION`, merge to `main` and push the tag `v<VERSION>`. The Release workflow then builds the APK, signs it with the Ciniva release key and publishes it, once the run is approved in the `release` environment. Every release is signed with the same key, so updates install over the previous version.
