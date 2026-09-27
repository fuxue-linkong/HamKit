# HamKit

[中文](README.md)丨English

An all-in-one console for amateur radio enthusiasts — zone positioning, satellite pass prediction, CW Morse training, transit alerts, and more.

Official website: https://hamkit.click

## Roadmap

### Completed

- **CW Trainer** (Morse code learning)
- **SSTV (Slow-Scan Television)** (in-house pure-Kotlin DSP; PD / Robot / Martin families, 8 modes)
- **Satellite Positioning & Tracking** (in-house SGP4/SDP4 engine, Look4Sat-style radar view, SatNOGS transponder frequency database)
- **AMSAT Satellite Status**
- **Calendar Transit Alerts**
- **Home Time Card** (real-time weather temperature, UTC time, custom background image cropping)

### Short-term

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
- Supports **PD-120 / 180 / 240, Robot 24 / 36 / 72, Martin 1 / 2** (8 modes)
- **Line sync detection + least-squares slant correction**: automatically compensates for clock mismatch (±1% drift verified)
- **Mistuning compensation** from the average frequency of all sync pulses, preserving correct levels under Doppler
- Live line-by-line preview, with automatic saving once a frame completes
- Pairs with satellite tracking: ARISS ISS SSTV events use **PD-120** at 145.800 MHz

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

Please report bugs via [Issues](https://github.com/fuxue-linkong/HamKit/issues) or email fuxuelingkong@outlook.com.

Feature requests are also welcome (though feasibility is not guaranteed).

## License

[MIT License](LICENSE)

## Disclaimer

I am not a licensed HAM operator — just someone with a casual interest in amateur radio. This project was inspired by fellow enthusiasts around me. It has also been a great opportunity to learn the ropes of Android development.
