# Hardware acceptance checklist

Target: UA Volt 1, mono input channel 0, PCM 24-bit packed, 48,000 Hz.

## Before recording

- Volt 1 is detected as a USB input device.
- 48 kHz is either advertised by the endpoint or accepted by the active capture path.
- The app has RECORD_AUDIO permission.
- Volt 1 has stable USB power.

## Capture-path acceptance

The app must refuse or abort capture if any of the following is true:

- actual routed device is not the selected Volt 1;
- client format is not PCM24 packed / 48 kHz / mono;
- hardware-interface sample rate is not 48 kHz;
- hardware-interface encoding is PCM16 or lower;
- Android reports active preprocessing effects;
- Android silences the client;
- USB audio device is disconnected.

## Bench test

1. Feed a stable 1 kHz sine into Volt 1 at approximately -12 dBFS.
2. Record at least 60 seconds.
3. Verify the WAV header: PCM, mono, 48,000 Hz, 24 bits.
4. Inspect the waveform/spectrum for discontinuities.
5. Repeat for 30 minutes.
6. Repeat with the Android screen off.
7. Unplug Volt 1 during capture and confirm the pending WAV is discarded.
8. Stop normally and confirm the completed WAV appears under Music/Volt1 Recorder/.

Record the phone/tablet model, Android build, USB topology and whether external 5 V power was used.
