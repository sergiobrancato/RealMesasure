<h1 align="center">RealMeasure</h1>

<p align="center">
  <strong>Pocket metrology. Zero cloud.</strong><br/>
  A native Android caliper that measures screws, bolts and hex keys with the camera — using any ISO/IEC 7810 ID-1 card as the scale reference.
</p>

<p align="center">
  <img alt="Kotlin 2.2" src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=flat-square&logo=kotlin&logoColor=white" />
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-38BDF8?style=flat-square" />
  <img alt="CameraX" src="https://img.shields.io/badge/CameraX-1.4-F59E0B?style=flat-square" />
  <img alt="OpenCV 4.10" src="https://img.shields.io/badge/OpenCV-4.10-0B0D10?style=flat-square&logo=opencv&logoColor=white" />
  <img alt="MIT License" src="https://img.shields.io/badge/License-MIT-34D399?style=flat-square" />
</p>

---

## Why RealMeasure?

A bank card is a precision object you already carry. ISO/IEC 7810 **ID-1** defines it as exactly **85.60 mm x 53.98 mm** — the same dimensions as a credit card, ID card or most transit cards. RealMeasure detects that rectangle in the camera frame, builds a metric homography from its four corners, and uses the resulting **pixels-per-millimetre** scale to measure hardware lying on the same flat surface.

Everything runs on-device: no account, no upload, no network permission is even requested.

## Highlights

- **Native Kotlin + Jetpack Compose** — single activity, MVVM with `StateFlow`, zero bridge friction.
- **Hands-free scanning** — a CameraX `ImageAnalysis` stream runs OpenCV zone checks at ~6-7 fps. Both frames turn emerald when aligned; after ~750 ms of stability the app captures and measures automatically. There is no shutter button.
- **Official OpenCV Android artifact** (`org.opencv:opencv:4.10.0`) via `OpenCVLoader.initLocal()` — no NDK setup, no `.so` copying, no JNI boilerplate.
- **Perspective-corrected measurement** — `getPerspectiveTransform` + `warpPerspective` remove phone tilt before contours are measured.
- **Head/shaft separation** via a longitudinal width profile and `minAreaRect`, so screw length is reported under the head.
- **Metric + Imperial identification** against ISO metric, UNC/UNF and SAE hex-key tables.
- **Guided workflow** — instruction pill, first-run tutorial, scan-failed modal with a concrete reason, and one-tap retry.
- **My Hardware** — saves a cropped JPEG plus metadata (`filesDir/history.json`) on-device, with thumbnails, dates, type badges and delete confirmation. No Room, no cloud.
- **JVM unit tests** for the catalogue snapping logic plus GitHub Actions CI that builds the APK on every push.
- **Dark workshop UI** — charcoal `#0B0D10`, amber `#F59E0B` alignment frames, emerald `#22C55E` confirmed zones and cyan measurement lines.

## How the scale calibration works

Given the four detected card corners in source-image pixels:

1. **Edge-profile detection:** four strong edge lines are located in narrow bands around the left guide. That works for dark cards on a bright desk *and* white ID cards on white paper, where a single closed contour would break apart.
2. **Side lengths:** `widthPx = mean(|TR-TL|, |BR-BL|)` and `heightPx = mean(|BL-TL|, |BR-TR|)`.
3. **Axis scales:** `scaleX = widthPx / 85.60` and `scaleY = heightPx / 53.98` (px/mm).
4. **Isotropic scale:** `pixelsPerMm = (scaleX + scaleY) / 2`.
5. **Homography:** the card corners are mapped to a metric rectangle `(0,0) -> (85.60, 53.98)`; `H` maps any source pixel on the table plane to millimetres.
6. **Rectification:** the cyan scan zone is projected through `H`, then warped with OpenCV so the analysis crop has a known `px/mm`.
7. **Measurement:** the fastener contour is reduced with `minAreaRect`; pixels are divided by the rectified scale.

If the horizontal and vertical scales disagree beyond `AppConfig.CardCv.MAX_SCALE_DISCREPANCY`, the card is bent or heavily tilted and the scan is rejected with a corrective hint.

## Project structure

```text
RealMeasure/
├─ app/src/main/java/com/realmeasure/app/
│  ├─ RealMeasureApp.kt              # OpenCVLoader.initLocal() bootstrap
│  ├─ MainActivity.kt                 # single activity, Compose host
│  ├─ config/AppConfig.kt             # ALL tuning: ISO card, guides, OpenCV, snap, palette
│  ├─ model/HardwareModels.kt         # enums + immutable data classes (JVM-pure)
│  ├─ data/
│  │  ├─ HardwareStandards.kt         # ISO metric, UNC/UNF, ISO/SAE hex-key tables
│  │  ├─ ScanHistoryItem.kt           # one saved scan
│  │  ├─ ScanHistoryRepository.kt     # JSON + JPEG persistence
│  │  └─ AppPreferences.kt            # onboarding opt-out
│  ├─ cv/
│  │  ├─ LiveZoneDetector.kt          # per-frame two-zone detection (~6-7 fps)
│  │  ├─ CardEdgeDetector.kt          # guide-aware ID-1 edge/profile detector
│  │  ├─ CardCalibrator.kt            # card quad, homography, card warp
│  │  ├─ FastenerAnalyzer.kt          # metric warp, binarisation, contours, head/shaft split
│  │  ├─ AnalysisRenderer.kt          # annotated result image + history crop
│  │  ├─ StandardMatcher.kt           # pure metric/imperial catalogue matcher
│  │  ├─ Geometry.kt                  # pure homography/caliper math
│  │  └─ MatPool.kt                   # deterministic OpenCV Mat release tracking
│  └─ ui/
│     ├─ RealMeasureApp.kt            # bottom nav shell + onboarding gate
│     ├─ ScannerViewModel.kt          # ScanUiState state machine + auto-capture pipeline
│     ├─ HistoryViewModel.kt          # My Hardware list/save/delete
│     ├─ theme/Theme.kt               # Material 3 dark workshop theme
│     ├─ components/ScannerOverlay.kt # amber/emerald Canvas HUD + stability bar
│     ├─ components/OnboardingModal.kt# tutorial + Canvas diagram
│     ├─ components/ResultModal.kt    # save / scan another
│     ├─ components/ErrorModal.kt     # scan failed + try again
│     ├─ components/Controls.kt      # pills, chips, score bars
│     └─ screens/ScannerScreen.kt / screens/HistoryScreen.kt
├─ app/src/main/res/
│  ├─ drawable/ic_launcher_background.xml / ic_launcher_foreground.xml
│  └─ mipmap-anydpi-v26/ic_launcher.xml / ic_launcher_round.xml
├─ app/src/test/java/com/realmeasure/app/StandardMatcherTest.kt
├─ .github/workflows/android-ci.yml
├─ LICENSE / README.md / .gitignore
└─ settings.gradle.kts / build.gradle.kts / gradle.properties
```

## Build & run

### Android Studio

1. Open the project folder (`RealMeasure/`) — Studio auto-imports the Gradle Kotlin DSL build.
2. Let it sync (it downloads Compose, CameraX and the OpenCV AAR on first sync).
3. Connect a phone with USB debugging, press **Run**.

### Terminal

```bash
./gradlew assembleDebug        # build the APK
./gradlew installDebug         # build + install on the connected device
./gradlew testDebugUnitTest    # JVM catalogue tests
```

Requirements: JDK 17+, Android SDK (the wrapper pins Gradle; AGP/Kotlin versions are declared in `build.gradle.kts`). No NDK is needed — OpenCV ships prebuilt native libraries inside its Maven artifact.

## Using the scanner

1. Open the app and read the short **How to scan** tutorial (reopen it any time with `?`).
2. **Lay a bank/ID card flat** inside the left box until its frame turns emerald.
3. **Place a screw, bolt or hex key** in the right box. The right frame turns emerald when the part is fully inside and detected.
4. Hold both boxes aligned for about **750 ms**. The status bar fills, the app captures automatically and performs the perspective-corrected measurement.
5. Review the **Scan Result** modal: identified size, alternative family, pitch, measured diameter/length and match error.
6. Tap **Save to My Hardware** to keep a cropped photo, or **Scan Another** to resume instantly.
7. Open **My Hardware** from the bottom bar to browse saved scans; tap the trash icon to delete an entry and its JPEG.

Torch: tap the flashlight button inside the viewfinder. In low light the guidance pill asks you to switch it on.

### Scan states

| State | What you see |
| --- | --- |
| `Scanning` | Amber/emerald frames, instruction pill, stability bar |
| `Analyzing` | Cyan pill, spinner, camera analysis paused |
| `Success` | Result modal with annotated image; scanning paused until dismissed |
| `Error` | "Scan Failed" modal with the specific cause; **Try Again** resumes |

The camera pipeline is also paused while the onboarding modal is open and unbound entirely when you switch to the My Hardware tab.

## Customization guide

### 1. Add or edit hardware standards

Open `app/src/main/java/com/realmeasure/app/data/HardwareStandards.kt`. The tables are plain Kotlin lists; the matcher, result cards and thread chips discover new entries automatically.

**Add an ISO metric fastener** (diameter, coarse pitch, fine pitch, standard lengths, hex socket size):

```kotlin
metric(
    20.0,   // M20 major diameter
    2.5,    // coarse pitch
    2.0,    // fine pitch (nullable)
    listOf(40.0, 50.0, 60.0, 70.0, 80.0, 100.0, 120.0, 150.0, 180.0, 200.0),
    17.0,   // socket size (mm)
),
```

**Add an imperial UNC/UNF fastener:**

```kotlin
imperial(
    "5/8\"-11",
    0.625,  // major diameter (inches)
    11, 18, // UNC / UNF threads per inch
    listOf(1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0),
    0.46875, // socket size (inches)
),
```

**Add hex keys:**

```kotlin
hexKey(12.0, 125.0, 220.0)               // metric: size, short arm, long arm
saeHexKey(0.4375, "7/16", 5.5, 9.0)      // SAE: inches + display label
```

If you extend beyond the current **M16 / 10 mm hex key** range, also widen the physical limits in `AppConfig.FastenerCv` (`MIN_DIAMETER_MM`, `MAX_DIAMETER_MM`, `MIN_LENGTH_MM`, `MAX_LENGTH_MM`).

### 2. Tune the OpenCV pipeline

All thresholds live in `AppConfig.CardCv` / `AppConfig.FastenerCv`.

| Setting | Controls | Practical tuning |
| --- | --- | --- |
| `CardCv.CANNY_LOW/HIGH` | Card edge sensitivity | Raise both if table texture creates false quads; lower for low-contrast cards |
| `CardCv.CLAHE_CLIP_LIMIT` | Local contrast boost | 1.5-3.0; raise for a white card on a white desk |
| `CardCv.EDGE_CLOSE_KERNEL` | Contour fallback closing | 5-11; larger bridges more gaps but may merge clutter |
| `CardCv.ADAPTIVE_BLOCK` | Local threshold window (odd, 3-99) | Increase for glossy cards / uneven lighting |
| `CardCv.ADAPTIVE_C` | Threshold offset | Increase to suppress noise, decrease for faint edges |
| `CardCv.APPROX_EPSILON_RATIO` | Polygon simplification | 0.01-0.02 for crisp corners; higher for rounded corners |
| `CardCv.MAX_ASPECT_ERROR` | Allowed deviation from 1.5858 | Tighten to 0.12 in controlled setups |
| `CardCv.MAX_SCALE_DISCREPANCY` | X/Y scale agreement | Lower rejects more tilted cards |
| `FastenerCv.ADAPTIVE_BLOCK/C` | Fastener binarisation | Smaller block for tiny screws; larger for big bolts |
| `FastenerCv.CANNY_LOW/HIGH` | Fallback edge mask | Lower for black-oxide hardware on dark backgrounds |
| `FastenerCv.MORPH_KERNEL` | Closing kernel | 3 normally; 5 for broken outlines |
| `FastenerCv.HEAD_WIDTH_RATIO` | Head-run threshold | Lower catches shallow button heads; higher ignores thread crests |
| `FastenerCv.MAX_HEAD_LENGTH_FRACTION` | Max head share of length | Raise for hex-key short legs; lower for pan heads |
| `FastenerCv.SHAFT_WIDTH_PERCENTILE` | Robust shaft width | 0.2-0.4; lower ignores more of the head |
| `FastenerCv.RECTIFIED_PX_PER_MM` | Measurement resolution | 8-14; higher is slower but more precise |

### 3. Adjust snap tolerances, guides and theme

- **Identification tolerance:** `AppConfig.Matching` (`ABSOLUTE_TOLERANCE_MM`, `RELATIVE_TOLERANCE`, hex variants).
- **Live scanning:** `AppConfig.Live` (`FRAME_INTERVAL_MS`, `REQUIRED_CONSECUTIVE_FRAMES`, `LOW_LIGHT_MEAN`, part filters).
- **Guide geometry:** `AppConfig.Guides` (normalised to the 3:4 stage).
- **Colors:** `WorkshopPalette` in the same file — the Material 3 theme and every overlay read from it.

## Testing & CI

```bash
./gradlew testDebugUnitTest    # 17 catalogue snapping tests (JVM, no device)
```

[`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml) runs on every push/PR to `main`:

```yaml
./gradlew testDebugUnitTest assembleDebug
```

and uploads the debug APK as an artifact.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| "Step 1" never advances | Fill the left frame with the whole card; avoid glare; hold the phone parallel |
| "Keep the hardware fully inside the right box" | Move the part so head and tip are inside the dashed frame |
| "Scene is dark" | Tap the flashlight button inside the viewfinder |
| "Scan Failed — card not found" | Use a matte surface, keep all four card corners visible and remove reflections |
| "Scan Failed — could not detect clear edges" | Use a plain background; keep the part centred; try the flashlight |
| "Scan Failed — measurement out of range" | The detected size is not a standard; lay the part flat and retry |
| Gradle sync fails on OpenCV | Check internet access to Maven Central; the AAR (~100 MB) downloads once |
| Camera shows black preview | Grant the Camera permission (rationale screen has an Open Settings button) |
| Emulator crashes at launch | Use a real device or an x86_64 image — OpenCV bundles all ABIs either way |

## Privacy

- Frames are analysed in-process and are never uploaded. Live analysis frames are recycled immediately after inspection.
- **My Hardware** saves a cropped JPEG and a small JSON record inside the app's private `filesDir`; deleting an entry removes both.
- The app declares **no** internet permission and makes **zero** network requests.
- No analytics, no ads, no tracking.

## License

MIT — see [LICENSE](LICENSE).
