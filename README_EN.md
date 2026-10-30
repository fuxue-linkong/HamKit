# HamKit

[中文](README.md)丨English

An all-in-one console for amateur radio enthusiasts — zone positioning, satellite pass prediction, CW Morse training, transit alerts, an AI assistant, and more.

Official website: https://hamkit.click

## Roadmap

### Completed

- **CW Trainer** (Morse code learning)
- **SSTV (Slow-Scan Television)** (in-house pure-Kotlin DSP; PD / Robot / Martin / Scottie / Wraase families, 19 modes)
- **Satellite Positioning & Tracking** (in-house SGP4/SDP4 engine, Look4Sat-style radar view, SatNOGS transponder frequency database)
- **AMSAT Satellite Status**
- **Calendar Transit Alerts**
- **Home Time Card** (real-time weather temperature, UTC time, custom background image cropping)
- **AI Assistant (P0)** (third bottom-nav tab; OpenAI Chat Completions / Anthropic Messages / Gemini with streaming; bring your own API key, key and chat history encrypted with Android Keystore AES-GCM; intranet http and self-signed certificates supported)

### Short-term

- **AI Assistant P1** (tool calling: zone lookup, satellite passes/live/transmitters, SSTV receive hand-off; in-chat satellite cards)
- **FT8** refinement (referencing [FT8CN](https://github.com/BG7HIM/FT8CN))
- **APRS** refinement (referencing [aprsdroid](https://github.com/ge0rg/aprsdroid))

### Long-term

- **RTTY** development (signal processing from scratch)

### Future Plans

- QSO logging
- QSO export (XML)
- Radio repeater lookup (in cooperation with authorized parties)

## Core Features

### Zone Positioning
- One-tap GPS coordinate acquisition
- Real-time **CQ Zone**, **ITU Zone**, and 6-character **Maidenhead Grid** calculation
- Reverse geocoded address display (3-second debounce, exponential backoff on failure)

### Satellite Pass Prediction
- In-house **SGP4/SDP4** orbital calculation engine (predict4java used for differential verification only), parallel 48-hour pass prediction
- Supports **CelesTrak** TLE data sources (amateur / satnogs groups)
- Built-in **SatNOGS** transponder frequency database, with transmitter names and frequencies
- **Look4Sat-style radar view**, polar plot showing in-pass satellite azimuth and elevation in real time
- Real-time **AMSAT** status query (with continuation markers), BJT segmented timeline, in-pass countdown
- Favorite satellite support, sorted by favorites → in-pass → AOS

### CW Morse Trainer
- Complete **Koch** curriculum (26 lessons) + character groups / callsign / text training
- Real-time sine wave synthesis via AudioTrack, adjustable WPM, tone, and playback mode
- Training progress tracking

### SSTV (Slow-Scan Television)
- **In-house pure-Kotlin DSP**: quadrature FM demodulation (8th-order Butterworth low-pass + multi-sample phase differencing), no third-party dependencies
- **Automatic VIS header detection**; verified to lock correctly even with a ±120 Hz frequency offset, with manual mode override as fallback
- Supports **PD-50 / 90 / 120 / 160 / 180 / 240 / 290, Robot 8 / 12 / 24 B/W, Robot 24 / 36 / 72, Martin 1 / 2, Scottie 1 / 2 / DX, Wraase SC2-180** (19 modes, monochrome family included)
- **Line sync detection + least-squares slant correction**: automatically compensates for clock mismatch (±1% drift verified)
- **Mistuning compensation** from the average frequency of all sync pulses, preserving correct levels under Doppler
- **Line parity read from the signal**: Robot 24/36 Cr/Cb is chosen by the separator tone, so a recording that starts on an odd line no longer swaps red and blue
- Live line-by-line preview, with automatic saving once a frame completes
- **Offline decoding**: import WAV (8/16/24/32-bit int, 32/64-bit float) and **FLAC / MP3 / AAC / OGG** (platform decoder, no third-party dependencies)
- **Receive recording**: the raw audio is streamed to a WAV file (VIS header included) and can be re-decoded later
- **Signal waterfall**: a live frequency-vs-time view of the demodulated signal, useful for tuning and signal-quality checks
- **Blind scan**: when the VIS header is lost to noise, the mode is inferred from sync-pulse spacing, a detune histogram and black-level gap structure, with a ranked candidate list
- **Weak-signal handling**: SNR-driven adaptive integration window (strong signals behave exactly as before), zero-phase pixel-Nyquist low-pass, plus optional histogram stretch / median denoise / bad-line repair
- **Continuous reception**: after a frame completes, mode and mistuning are kept and the next frame is picked up automatically
- **Persistent reception**: no VIS header and no frame boundaries required — decoding starts at any moment and the preview keeps growing downwards like a paper roll; a two-stage frequency buffer keeps memory flat over hours of listening
- **Selectable audio source**: microphone or **playback capture** (Android 10+, decodes audio playing in other apps such as a player or a WebSDR page)
- **Transmit**: pick an image → encode to SSTV audio → play into the radio mic, or export a standard WAV for a line-in cable; optional callsign overlay and VIS header, fully streamed (even PD-290 fits in memory)
- **Gallery**: thumbnail grid, full-screen view, delete, share (FileProvider), export to the system gallery
- Pairs with satellite tracking: ARISS ISS SSTV events use **PD-120** at 145.800 MHz. The pass-reminder notification opens the decoder straight in receiving state (PD-120 preset, recording starts automatically), and back-to-back transmissions roll over to the next frame by themselves

### HF Fax (WEFAX weather charts)
- In-house pure-Kotlin decoder: **120 lines/min (0.5 s per line), 640 px wide, greyscale**, on a receive path fully separate from SSTV (WEFAX has neither VIS nor a per-line sync pulse)
- **300 Hz start-tone detection** finds the image start; "start immediately" skips it when recording mid-transmission
- **Automatic horizontal alignment** from the white margin (brightest column tracked across lines) and **automatic mistuning estimation**
- Shares the weak-signal post-processing (histogram stretch / median denoise / bad-line repair) and saves to the gallery
- ⚠️ **Not yet validated against a real WEFAX signal** — module-level self-loop tests only; vertical alignment is inherently free-running (no per-line sync in WEFAX)

### Transit Alerts
- AlarmManager exact alarms + WorkManager daily refresh
- Configurable lead-time reminders before AOS, daylight-only mode
- Auto-restore on reboot (BootReceiver)

### Home Time Card
- Integrates real-time weather temperature and UTC time
- Supports custom background images (built-in the_moon lunar image) and image cropping
- MaterialKolor auto-extracts color palette from the background image to generate the theme

## Screenshots

| Positioning | FT8 | APRS | CW Trainer |
| --- | --- | --- | --- |
| ![Positioning](images/定位页面.jpg) | ![FT8](images/FT8.jpg) | ![APRS](images/APRS.jpg) | ![CW Trainer](images/CW-教程练习.jpg) |

## Tech Stack

**Language & Framework**
- Kotlin 2.4.0
- Jetpack Compose (BOM 2026.05.01)
- Material 3 Expressive (1.5.0-alpha22) + Miuix KMP 0.9.3
- Navigation3 1.1.2
- Coroutines 1.11.0

**Data & Location**
- Room 2.7.0 / DataStore 1.1.4
- Google Play Services Location 21.3.0
- Amap Android SDK + Web services (maps / weather / reverse geocoding)
- OkHttp 5.3.2 / WorkManager 2.10.0

**Domain-specific**
- In-house SGP4/SDP4 engine (predict4java 1.3.1 for differential verification only)
- SatNOGS transponder frequency database
- Amap 3D SDK
- MPAndroidChart v3.1.0
- Coil Compose 2.7.0 / Palette 1.0.0
- MaterialKolor 4.1.1 (dynamic color extraction) / Commonmark 0.28.0 (Markdown rendering)

**Engineering**
- Gradle 9.4.1 / AGP 9.2.1 / KSP 2.3.10
- JaCoCo 0.8.12 / R8 ProGuard
- GitHub Actions CI/CD

## System Requirements

- Android 8.0 (API 26) and above
- targetSdk 37
- Location permission required

## Feedback

Please report bugs via [Issues](https://github.com/fuxue-linkong/HamKit/issues) or email liurun637@gmail.com.

Feature requests are also welcome (though feasibility is not guaranteed).

## License

[MIT License](LICENSE)