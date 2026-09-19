# Volt 1 Recorder for Android

Android recorder dedicated to **Universal Audio Volt 1**.

Recording target:

- USB input: UA Volt 1
- PCM: signed 24-bit packed, little-endian
- sample rate: 48,000 Hz
- channels: mono, USB channel index 0
- container: uncompressed RIFF/WAV
- no ASIO
- no AAC/MP3
- no normalization or app DSP

## Fail-closed capture

Requesting 24-bit/48 kHz from AudioRecord is not enough: Android may resample or convert between the hardware interface and the client stream.

After capture starts, the service verifies the actual Volt 1 route, client PCM24/48 format, hardware-interface 48 kHz rate, hardware-interface sample format above PCM16, and absence of active preprocessing. The checks repeat while recording.

If the route or format becomes invalid, the recording stops. An incomplete recording is deleted instead of being published as a valid 24/48 WAV.

## Requirements

- Android 12 / API 31 or newer
- targetSdk 36
- JDK 17
- Android Gradle Plugin 8.13.2
- Kotlin 2.3.21
- Gradle 8.13

## Build

The repository currently contains text source files. Generate the Gradle wrapper once with installed Gradle 8.13:

    gradle wrapper --gradle-version 8.13
    ./gradlew assembleDebug

APK:

    app/build/outputs/apk/debug/app-debug.apk

## Use

1. Connect Volt 1 by USB-C/OTG.
2. If the phone cannot provide stable bus power, power Volt 1 externally from its 5 V DC input.
3. Open the app and grant microphone permission.
4. Press ЗАПИСЬ.
5. The WAV file is created only after the capture route passes validation.
6. Press СТОП.
7. Completed files are published to Music/Volt1 Recorder/.

## Hardware acceptance test

A successful build does not by itself prove a bit-perfect USB path on a particular Android device. For each target phone/tablet:

- record a stable 1 kHz test tone through Volt 1;
- verify WAV is PCM mono, 48,000 Hz, 24-bit;
- run at least a 30-minute dropout test;
- repeat with the screen off;
- unplug Volt 1 during capture and verify recording aborts;
- verify the app refuses a 16-bit or non-48-kHz hardware path.

Software cannot infer the analog converter's effective number of bits. It can verify the Android capture-path properties exposed by the platform and reject known 16-bit/resampled paths.

## Scope

This first version intentionally omits playback, editing, effects and format selection. The priority is reliable 24-bit / 48 kHz capture.
