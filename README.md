# Tagalong — Android app

On-device alternative for SnapBridge: keeps the camera's clock and GPS fresh over BLE, and
browses/downloads photos over the camera's Wi-Fi. Kotlin + Jetpack Compose, package
`cl.bartsch.tagalong`.

> **Only tested with a Nikon D780.** The BLE protocol (LSS service), the PTP/IP photo access and the
> NEF layout were reverse-engineered from SnapBridge and verified live on one D780 only. Other Nikon
> bodies that use SnapBridge may work, but nothing here has been tried on them — expect to debug.
> Personal interop with the owner's own camera; no Nikon binaries are redistributed.

## Features
- **Connect & Sync** — scan, authenticate, write UTC time and the phone's GPS location once.
- **Auto-sync** — a foreground service holds the BLE link and refreshes time + location every
  1 s – 5 min (⋮ menu → interval). Uses GPS, network and fused location, so it also works indoors.
  Reconnects with 10 → 30 → 60 s backoff, e.g. after the camera auto-powers off.
- **Photos (Wi-Fi)** — decrypts the camera's Wi-Fi credentials over BLE, starts its AP, joins it and opens
  a thumbnail grid. Tap to view (640×424 instantly, then the 1620×1080 JPEG embedded in the NEF);
  long-press to select.
- **Download** — streams straight to disk (no whole file in memory), so many files can be copied:
  - *JPEG*: the full-size 6048×4024 JPEG embedded in each NEF (~1 MB, with EXIF rebuilt from the NEF,
    since the embedded JPEG has none) or the camera's own JPEG → `Pictures/<profile name>`.
  - *NEF*: the untouched original → `Download/<profile name>`.
- **Camera profiles** — one profile per camera (name, Bluetooth name, MAC, its own client id). Switch,
  add and delete from ⋮ → Cameras…; ⋮ → Camera info shows the active camera's details. One camera at a
  time.
- **Pair camera (BT)** — shown only while the camera isn't bonded; until then the other actions are
  greyed out. It does BLE auth + time/location first (registers the client id), then the classic-BT bond.

## Build / run
1. Open `android/` in Android Studio (AGP 9.5, Kotlin 2.2, Gradle 9.6; SDK 34 is fetched on sync).
2. Run on the phone with USB debugging, or from the CLI: `./gradlew.bat :app:installDebug`.
3. Grant Bluetooth, Location and Notification permissions.
4. **Disable SnapBridge first** — it holds the BLE link and the camera stops advertising:
   `adb shell pm disable-user com.nikon.snapbridge.cmru` (re-enable with `pm enable`).
5. Put the camera in pairing mode and tap **Pair camera (BT)** the first time.

Unit tests (auth vectors, session-cipher KAT, location range): `./gradlew.bat :app:testDebugUnitTest`.
Live log: `adb logcat -v time Tagalong:I "*:S"` (the on-screen log is timestamped too).

Notes:
- The camera advertises with a **rotating random BLE address**, so the app always scans (by the profile's
  Bluetooth name) and never connects to a fixed MAC.
- The Wi-Fi join pops a system dialog (`WifiNetworkSpecifier`) that must be approved each time.
- Camera auto power-off drops the BLE link; auto-sync reconnects when it wakes.

## Modules
| File | Role |
|---|---|
| `LssConstants.kt` | Blowfish key material (from birdcam's `nikon/ls_sec/data.h`) |
| `LssCrypto.kt` | auth proof + the 64-bit session cipher (decrypts the Wi-Fi credentials) |
| `LocationEncoder.kt` | time (0x2006) + location (0x2007) bytes, byte-exact |
| `Profiles.kt` / `Identity.kt` | per-camera profiles; client id delegates to the active profile |
| `TagalongScanner.kt` | BLE scan, classic-BT pairing, bond lookup/removal |
| `TagalongBle.kt` | GATT: connect, auth, time + location, Wi-Fi config read, start AP |
| `SyncService.kt` | foreground auto-sync service (`FixTracker` = GPS/network/fused fixes) |
| `WifiCamera.kt` | join the camera AP, find its host |
| `PtpIp.kt` | PTP/IP client: list, thumbs, partial/streaming object reads |
| `NefPreview.kt` / `ExifBuilder.kt` | find embedded JPEGs in a NEF by ranged reads; rebuild EXIF for them |
| `CameraSession.kt` | one live Wi-Fi/PTP session: serialised ops, thumbnails, streaming save to the gallery |
| `PhotosScreen.kt` | photo grid + viewer (stateless composables with `@Preview`s) |
| `Dialogs.kt` | stateless dialogs, each with a `@Preview` |
| `MainActivity.kt` | main screen and wiring |

New views are stateless composables with a `@Preview`; state and side effects live in the host.

## Known gaps
- Streaming save and the rebuilt EXIF are compiled but not yet verified end to end on the camera.
- Thumbnail orientation for portrait shots is unchecked.
- The photo list reads each object's info one by one (~25 s for 650 photos).
- No cancel button for downloads; cancelling mid-transfer leaves the PTP link unusable until Photos is
  reopened.
- MakerNote (shutter count, focus data) is not copied into saved JPEGs; the NEF keeps it.

## Legal and provenance
- **Unofficial.** This project is not affiliated with, endorsed by or supported by Nikon Corporation.
  Nikon, SnapBridge and D780 are trademarks of Nikon Corporation, used here only to name the camera and
  app this interoperates with.
- **Purpose.** Interoperability with the author's own camera: set its clock and location over BLE and
  fetch photos over its Wi-Fi. No Nikon service or account is involved.
- **What was analysed.** Only what is needed to talk to the camera: the BLE "LSS" GATT protocol and its
  challenge-response authentication, the Wi-Fi credential cipher, and the PTP/IP and NEF/TIFF layouts.
  The analysis was done by a licensed user of SnapBridge, in the EU, for interoperability with an
  independently written program (Directive 2009/24/EC, Art. 6). This is stated for transparency and is
  not legal advice.
- **No Nikon code.** The source contains no SnapBridge code and no Nikon binaries, and none may be added:
  `apk/`, `native/`, `decompiled/` and `capture/` in the author's working tree are not part of any
  release. The code is written independently; protocol facts (UUIDs, opcodes, byte layouts) are
  documented behaviour of the camera, not copied expression.
- **Third-party material.** The authentication key tables (`LssConstants.kt`, from birdcam's `nikon/ls_sec/data.h`)
  come from [attilaolah/birdcam](https://github.com/attilaolah/birdcam) (`nikon/ls_sec`), © 2022 Attila
  Oláh, MIT License — its copyright and permission notice must stay with those files. AndroidX, Jetpack
  Compose and kotlinx.coroutines are Apache-2.0.
- **Use at your own risk.** Provided as is, without warranty. It talks to your camera's firmware in ways
  Nikon does not document; you are responsible for using it only with equipment you own and in line with
  the law and any terms that apply to you. Only a D780 has been tested.
- **License.** Apache-2.0, see `LICENSE` and `NOTICE`; third-party notices (birdcam under MIT, Apache-2.0 libraries) are in `THIRD_PARTY.md`.
