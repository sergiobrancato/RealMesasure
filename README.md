<h1 align="center">RealMeasure</h1>

<p align="center">
  <strong>Flatten the world. Measure any shape. Export true 1:1 CAD.</strong><br/>
  An offline-first Android planar metrology scanner for irregular parts, holes, gaskets, brackets, boards and templates.
</p>

<p align="center">
  <img alt="Kotlin 2.2" src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?style=flat-square&logo=kotlin&logoColor=white" />
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-38BDF8?style=flat-square" />
  <img alt="CameraX" src="https://img.shields.io/badge/CameraX-1.4-F59E0B?style=flat-square" />
  <img alt="OpenCV 4.10" src="https://img.shields.io/badge/OpenCV-4.10-0B0D10?style=flat-square&logo=opencv&logoColor=white" />
  <img alt="MIT License" src="https://img.shields.io/badge/License-MIT-34D399?style=flat-square" />
</p>

---

## What it does

RealMeasure turns a phone into a flatbed metrology scanner:

1. Lay a known **ISO reference** flat beside an object — either an **ID-1 bank/ID card** (`85.60 × 53.98 mm`) for small parts or an **A4 sheet** (`297 × 210 mm`) for large objects.
2. Point the rear camera at the scene. A live detector tracks the reference quadrilateral, rejects excessive tilt and advances a stability ring.
3. After five stable frames the app **automatically** captures a full-resolution still (`CAPTURE_MODE_MAXIMIZE_QUALITY`). No shutter button. The still is sampled down to a 2048 px processing edge before OpenCV.
4. `cornerSubPix` refines the four reference corners on the ≤2048 px processing grayscale, a planar homography flattens the entire scene at a uniform **px/mm** scale, and OpenCV traces every outer contour and verified hole.
5. The app reports the **2D planar footprint** — net surface area, exact outline perimeter, minimum bounding box, hole diameters and centre-to-centre spacings — each with a physics-based **`± mm` / `± cm²`** uncertainty.
6. Drag two precision pins (with a 36 dp finger offset, a 3× loupe and 0.1 mm fine-tune arrows) anywhere on the flattened image to measure a custom point-to-point distance. Tap any object to select it.
7. For containers and tall parts, an optional **3D height parallax slider** (0–150 mm) applies `k = 1 − h / 400 mm` to the reported linear dimensions and `k²` to the area in real time.
8. Export a **true-scale 1:1 SVG** whose `width`/`height` are in millimetres, ready for Fusion 360, Onshape, Inkscape or a laser cutter.
9. Save scans locally in **My Scans** with an annotated thumbnail, dimensions, area, hole count, date, SVG re-export and delete.

The app declares **no internet permission** and performs zero network requests. OpenCV runs entirely on-device.

---

## Why computer vision instead of a multimodal LLM?

Dimensional metrology has a hard requirement that language/vision models cannot satisfy: **a traceable metric scale with a calibrated error budget**.

| Requirement | Computer vision pipeline | Multimodal LLM |
| --- | --- | --- |
| Absolute scale | Derived from an ISO reference of known size | Must guess scale from pixels; no physical anchor |
| Repeatability | Deterministic sub-pixel geometry | Sampling-dependent; can vary between runs |
| Error statement | Propagated from sensor noise + calibration drift | Cannot provide a defensible `± mm` |
| Fine geometry | `arcLength`, contour area, image moments, `minAreaRect` | Coarse semantic descriptions; small holes/notches are often hallucinated or missed |
| Output for CAD | Exact 1:1 SVG contours | Text or approximate code, not a measured vector |
| Privacy / offline | Fully local | Usually a remote service |
| Latency / cost | Tens of milliseconds per frame, zero marginal cost | Network round-trip + inference cost |

An LLM can describe *what* an object is. RealMeasure measures *how big* it is and produces a vector file a machine can cut. The camera is used as a calibrated sensor, not as a semantic captioner.

---

## The two reference modes

| Mode | Physical reference | \(W_{\text{ref}}\) | \(H_{\text{ref}}\) | \(R_{\text{true}}\) | Warped scale \(s\) | Intended objects |
| --- | --- | --- | --- | --- | --- | --- |
| **Small** | ISO/IEC 7810 ID-1 card | 85.60 mm | 53.98 mm | 1.58577 | 10 px/mm | parts, brackets, gaskets, tools **< 250 mm** |
| **Large** | ISO 216 A4 sheet | 297.00 mm | 210.00 mm | 1.41429 | 5 px/mm | boards, panels, bags, templates **250–2000 mm** |

The live detector accepts a 4-vertex convex polygon whose aspect ratio is within **±0.12** of the selected reference. The still calibrator additionally rejects any reference whose interior corners deviate from 90° by more than **22°**.

---

## Complete mathematical pipeline

### 1. Corner ordering

For four detected vertices, compute the extremes of \(x+y\) and \(y-x\):

\[
P_0 = \arg\min(x+y),\quad
P_1 = \arg\min(y-x),\quad
P_2 = \arg\max(x+y),\quad
P_3 = \arg\max(y-x)
\]

This yields the clockwise order **top-left → top-right → bottom-right → bottom-left**. If the reference is held portrait, the quad is cyclically rotated so its **long physical edge** becomes \(P_0P_1\); that edge is always mapped to \(W_{\text{ref}}\).

### 2. Perspective tilt gate

For each corner, with adjacent edge vectors

\[
\vec u = P_{(i+1)\bmod 4}-P_i,\qquad
\vec v = P_{(i+3)\bmod 4}-P_i,
\]

the interior angle is

\[
\theta_i = \arccos\!\left(\frac{\vec u\cdot\vec v}{\lVert\vec u\rVert\,\lVert\vec v\rVert}\right)\frac{180}{\pi}.
\]

A still is rejected when

\[
\max_i\left|\theta_i-90^\circ\right| > 22^\circ.
\]

The live tracker uses a stricter **12°** gate to guide the user before the capture, so the captured still almost always passes the 22° gate.

### 3. Sub-pixel corner refinement

The reference is first found in a downscaled copy of the still (fast), then its corners are scaled to the ≤2048 px processing grayscale and refined with:

```text
cv::cornerSubPix(
    gray,
    corners,
    winSize  = (11, 11),
    zeroZone = (-1, -1),
    criteria = TermCriteria(COUNT + EPS, 30, 0.01)
)
```

This recovers approximately \(\pm 0.1\)–\(0.2\) px corner locations instead of the integer-pixel contour vertices.

### 4. Native pixel density

Along the two long reference edges:

\[
s_{\text{native}} =
\frac{\lVert P_1-P_0\rVert + \lVert P_2-P_3\rVert}{2\,W_{\text{ref}}}
\quad [\text{px/mm}]
\]

This is the scale of the **original photo**. It feeds the uncertainty model.

### 5. Base homography \(H_0\)

Map the refined corners to a metric rectangle at the target warped scale \(s\):

\[
P_0\mapsto(0,0),\;
P_1\mapsto(sW_{\text{ref}},0),\;
P_2\mapsto(sW_{\text{ref}},sH_{\text{ref}}),\;
P_3\mapsto(0,sH_{\text{ref}}).
\]

\(H_0\) is computed with `cv::getPerspectiveTransform`.

### 6. Full-canvas bounds translation (never crop the target object)

A naive warp of the reference rectangle would cut off everything beside it. Instead, project the **four corners of the full raw image** through \(H_0\):

\[
(0,0),\;(W_{\text{img}},0),\;(W_{\text{img}},H_{\text{img}}),\;(0,H_{\text{img}})
\;\xrightarrow{\;H_0\;}\;
(x_i',y_i').
\]

Let

\[
x_{\min}=\min_i x_i',\quad x_{\max}=\max_i x_i',\quad
y_{\min}=\min_i y_i',\quad y_{\max}=\max_i y_i'.
\]

The translation

\[
T=\begin{bmatrix}1&0&-x_{\min}\\0&1&-y_{\min}\\0&0&1\end{bmatrix}
\]

shifts the projected scene into positive coordinates. The canvas is

\[
W_c=\left\lceil x_{\max}-x_{\min}\right\rceil,\qquad
H_c=\left\lceil y_{\max}-y_{\min}\right\rceil.
\]

If \(\max(W_c,H_c)>2400\), a uniform scale \(f=2400/\max(W_c,H_c)\) is folded into the homography:

\[
H = S(f)\cdot T\cdot H_0,\qquad
S(f)=\begin{bmatrix}f&0&0\\0&f&0\\0&0&1\end{bmatrix}.
\]

The effective scale becomes \(s_{\text{eff}}=f\,s\). The full image is warped with `cv::warpPerspective` into a canvas where **every pixel is exactly \(1/s_{\text{eff}}\) mm**. The reference lands at a known rectangle \([\,{-x_{\min}},\,{-y_{\min}}\,]\) of size \(sW_{\text{ref}}\times sH_{\text{ref}}\), which is masked out before object detection.

> The translation is the difference between a “card crop” and a **scene scanner**. It preserves the object even when it is far from the reference.

### 7. Irregular object geometry (solid silhouette, not edge rings)

The flattened image is converted to **Lab** colour space. A background colour is estimated from the mat around the border (the reference rectangle is excluded), and the object is seeded from:

- the **Lab distance from the background** (`max(|ΔL|, |Δa|, |Δb|)`), which handles light, dark and coloured cases;
- **L-channel and colour-gradient Canny edges**, which close low-contrast white-on-white outlines;
- an **L adaptive-threshold fallback** for the hardest low-contrast silhouettes.

Each seed is closed and its external contours are found with `RETR_EXTERNAL`. A raw Canny ring is **never measured directly**: a thick edge ring has two boundaries and gives a roughly doubled perimeter and misleading area. For every candidate the app:

1. fills the outer contour into a **solid binary mask**:
   ```text
   cv::drawContours(solidMask, contours, 0, Scalar(255), -1)
   ```
2. re-extracts the clean outer boundary from that solid mask (`RETR_EXTERNAL`);
3. measures the bounding box, perimeter and gross area from the **filled silhouette**.

Holes are then accepted only when all of the following hold:

- the region is inside the **eroded solid object**, so it cannot be part of the outside background;
- its Lab colour and luminance match the mat within the configured tolerances, i.e. it is a true background pass-through;
- its area is **≥ 2.5 mm²**;
- its circularity \(4\pi A/P^2\) is **≥ 0.45**.

The object mean colour contrast must also be high enough for background-like regions to be meaningful. This rejects printed text, small specular highlights and most surface graphics; those are not subtracted from the net area.

A user can additionally draw a box and run **GrabCut** (`GC_INIT_WITH_RECT`, three iterations) to snap the silhouette tightly to a difficult object; that path uses the same solid-fill geometry and strict hole filters, and immediately recalculates the bounding box, 2D planar area and perimeter.

Only top-level contours are object candidates. Objects touching the canvas border are rejected as incomplete.

For each accepted object:

**Rotated bounding box** via `minAreaRect` on the clean filled silhouette:

\[
L_{\text{mm}}=\frac{\max(w_{\text{px}},h_{\text{px}})}{s},\qquad
W_{\text{mm}}=\frac{\min(w_{\text{px}},h_{\text{px}})}{s}.
\]

**Exact outline perimeter** from the clean solid boundary:

\[
P_{\text{mm}}=\frac{\operatorname{arcLength}(C_{\text{outer,solid}},\text{closed})}{s}.
\]

**Net surface area** — the gross area comes from the solid filled silhouette, then verified holes are subtracted. The shoelace/Green’s theorem sum

\[
A(C)=\frac12\left|\sum_i (x_i y_{i+1}-x_{i+1}y_i)\right|
\]

is evaluated on the solid outer contour and every hole:

\[
A_{\text{net,mm}^2}
=\frac{\operatorname{contourArea}(C_{\text{outer,solid}})
-\sum_{k=1}^{n}\operatorname{contourArea}(C_{\text{hole},k})}{s^2},
\qquad
A_{\text{net,cm}^2}=\frac{A_{\text{net,mm}^2}}{100}.
\]

**Hole centroids** via image moments:

\[
M_{00}=\sum I(x,y),\qquad
M_{10}=\sum x\,I(x,y),\qquad
M_{01}=\sum y\,I(x,y),
\]

\[
c_{x,k}=\frac{M_{10}}{s\,M_{00}},\qquad
c_{y,k}=\frac{M_{01}}{s\,M_{00}}.
\]

**Equivalent hole diameter** (correct for circular holes, an equivalent-area diameter for slots):

\[
d_{\text{hole},k}=2\sqrt{\frac{\operatorname{contourArea}(C_{\text{hole},k})}{\pi s^2}}.
\]

**Hole-to-hole centre spacing**, for every pair \(i<j\):

\[
D_{ij}=\sqrt{(c_{x,j}-c_{x,i})^2+(c_{y,j}-c_{y,i})^2}.
\]

For display, storage and SVG, contours are simplified with `approxPolyDP` using

\[
\varepsilon = 0.25\,s\ \text{px} \equiv 0.25\ \text{mm}.
\]

The reported area and perimeter are computed **before** simplification; only the vector representation is simplified.

---

## 2D planar footprint vs 3D containers

The homography recovers the plane \(z=0\), the table surface on which the reference lies. Every contour, net area, perimeter and pin distance is therefore the object's **orthogonal projection onto that plane**:

- for a flat part (gasket, bracket, board, template) this is the true 2D cross-section;
- for a 3D container or tall object it is the **projected footprint**. The top face is closer to the camera than the reference plane, so it subtends a larger angle and appears magnified.

RealMeasure labels the hero metrics **“2D Planar Footprint (from photo)”** for exactly this reason.

### Optional first-order height parallax correction

For the standard handheld camera distance \(H_{\text{cam}} = 400\ \text{mm}\), let

\[
\boxed{k = 1-\frac{h}{H_{\text{cam}}}}
\]

where \(h\) is the object height set by the optional **3D Object Height (Parallax)** slider (`0–150 mm`, default `0`). The reported measurements become

\[
\boxed{D_{\text{true}} = D_{\text{apparent}}\cdot k}
\qquad
\boxed{P_{\text{true}} = P_{\text{apparent}}\cdot k}
\qquad
\boxed{A_{\text{true}} = A_{\text{apparent}}\cdot k^{2}}
\]

Error margins scale the same way: linear margins by \(k\), area margins by \(k^{2}\). At \(h=0\) the correction is exactly 1 and the normal 2D footprint is reported. For a distant/orthographic camera \(h/H_{\text{cam}}\to 0\), so the correction vanishes.

The approximation assumes the object's top face is roughly parallel to the reference plane and the camera is approximately above the object's centre. It is a first-order parallax model, not a full 3D reconstruction. The **SVG and saved scan image remain the projected 2D footprint**; the height correction changes the reported dimensions, not the vector outline.

---

## Measurement uncertainty model

All uncertainty is expressed as a **1-sigma engineering margin**, not a statistical confidence interval. It combines the two dominant error sources.

### 1. Sensor edge localisation

Let \(\sigma_{\text{edge}}=1.25\) px be the sub-pixel edge-localisation uncertainty. The effective error on a measured length \(L\), magnified by the object-to-reference span, is

\[
\boxed{
\Delta_{\text{px}}(L)
=\frac{\sigma_{\text{edge}}}{s_{\text{native}}}
\left(1+\frac{L}{W_{\text{ref}}}\right)
}
\quad[\text{mm}].
\]

The first factor converts pixels to millimetres at the reference scale; the bracket accounts for the fact that a dimension spanning a larger fraction of the calibrated plane accumulates proportionally more edge uncertainty.

### 2. Residual calibration / lens drift

Re-measure the reference aspect ratio on the flattened canvas, \(R_{\text{warped}}=W'_{\text{ref}}/H'_{\text{ref}}\), and compare it with the physical ratio \(R_{\text{true}}\). The relative drift is

\[
\boxed{
\varepsilon_{\text{cal}}
=\frac{\left|R_{\text{warped}}-R_{\text{true}}\right|}{R_{\text{true}}}
}
\]

The implementation re-measures the reference edges with a small edge-profile search around the nominal warped rectangle. If that re-measurement is inconclusive, it falls back to the detected source aspect, so a failed edge search cannot silently zero the calibration error.

### 3. Linear dimensions

\[
\boxed{
\delta L=\Delta_{\text{px}}(L)+L\,\varepsilon_{\text{cal}}
}
\]

The same formula is applied to:

- the long bounding-box side \(L\),
- the short side \(W\),
- the perimeter \(P\),
- each hole diameter \(d\),
- each hole spacing \(D\),
- and each custom pin distance \(D_{\text{pins}}\).

### 4. Net area

Linearising the area with respect to a boundary displacement gives

\[
\boxed{
\delta A_{\text{cm}^2}
=\frac{P_{\text{mm}}\,\delta L_{\text{avg}}
+2\,A_{\text{net,mm}^2}\,\varepsilon_{\text{cal}}}{100}
}
\]

where

\[
\delta L_{\text{avg}}=\frac{\delta L+\delta W}{2}
\]

is the representative perimeter error. The first term is the boundary-length × edge-error term; the second is the scale-error term (a relative scale error \(\varepsilon\) changes an area by approximately \(2\varepsilon\)).

### 5. Precision badge

| Badge | ID card threshold | A4 threshold | Meaning |
| --- | --- | --- | --- |
| **High precision** | \(\delta L \le 0.6\) mm | \(\delta L \le 1.8\) mm | emerald |
| **Moderate precision** | \(\delta L \le 1.5\) mm | \(\delta L \le 3.5\) mm | amber |
| **Low precision** | above | above | warning + recommendation |

For low-precision scans, the app recommends **A4 mode** when an ID-card scan measures an object longer than **250 mm**; otherwise it recommends moving closer, flattening the scene and adding light.

### Worked numerical comparison

#### Small part — ID card

A 40 mm bracket measured with a 10 px/mm native scale and 0.3 % residual calibration drift:

\[
\Delta_{\text{px}}(40)=\frac{1.25}{10}\left(1+\frac{40}{85.60}\right)
=0.125\times1.467=0.183\text{ mm}
\]

\[
\delta L=0.183+40\times0.003=0.183+0.120=0.303\text{ mm}
\]

Result: **\(40.0\pm0.3\) mm** → High precision. This is the sweet spot of the ID-card mode.

#### Same 200 mm board — ID card vs A4

With a 200 mm board, the card must fill only part of the frame, so assume \(s_{\text{native}}=2.5\) px/mm and \(\varepsilon=0.002\):

\[
\Delta_{\text{px}}^{\text{card}}
=\frac{1.25}{2.5}\left(1+\frac{200}{85.60}\right)
=0.5\times3.336=1.668\text{ mm}
\]

\[
\delta L^{\text{card}}=1.668+200\times0.002=2.068\text{ mm}
\]

With an A4 sheet filling the frame, assume \(s_{\text{native}}=8\) px/mm:

\[
\Delta_{\text{px}}^{\text{A4}}
=\frac{1.25}{8}\left(1+\frac{200}{297}\right)
=0.15625\times1.673=0.261\text{ mm}
\]

\[
\delta L^{\text{A4}}=0.261+200\times0.002=0.661\text{ mm}
\]

\[
\frac{2.068-0.661}{2.068}\approx68\%
\]

The A4 reference cuts the linear uncertainty by **more than 60 %** on the same large board. This is exactly why the app exposes two physical anchors instead of one.

#### Area error example

For a bracket with \(P=160\) mm, \(A_{\text{net}}=400\) mm², \(\delta L_{\text{avg}}=0.30\) mm and \(\varepsilon=0.003\):

\[
\delta A_{\text{cm}^2}
=\frac{160\times0.30+2\times400\times0.003}{100}
=\frac{48+2.4}{100}
=0.504\text{ cm}^2.
\]

---

## True-scale 1:1 SVG export

`SvgBuilder` emits a single document:

```svg
<svg xmlns="http://www.w3.org/2000/svg"
     width="123.456mm" height="78.900mm"
     viewBox="0 0 123.456 78.900">
  <path d="M 0 0 L 123.456 0 ... Z M 20 20 ... Z"
        fill-rule="evenodd" fill="none"
        stroke="#000000" stroke-width="0.1"/>
</svg>
```

Key properties:

- `width` / `height` carry explicit **mm** units and the `viewBox` uses the same numbers, so the physical size is exact in any compliant importer.
- The outer contour and every hole are sub-paths of **one** path with `fill-rule="evenodd"`, producing correct holes for solid/CAD import.
- All coordinates are translated so the object’s bounding box starts at `(0,0)`.
- Contours were simplified with a **0.25 mm** tolerance; the measurement metrics are computed from the unsimplified contours.
- Sharing uses Android `FileProvider` with MIME type `image/svg+xml`, so third-party CAD/laser apps receive a readable content URI.
- The export is the **projected 2D footprint**. The optional 3D height parallax slider changes reported dimensions only; it does not warp the SVG outline.

---

## App architecture

```text
RealMeasure/
├─ app/src/main/java/com/realmeasure/app/
│  ├─ RealMeasureApp.kt                 # Application: OpenCVLoader.initLocal()
│  ├─ MainActivity.kt                   # single activity Compose host
│  ├─ config/AppConfig.kt               # every detection/analysis/error threshold + palette
│  ├─ model/
│  │  ├─ MeasurementModels.kt           # ReferenceMode, Pt, quality, detection
│  │  └─ AnalysisModels.kt              # ObjectGeometry, HoleGeometry, HoleSpacing
│  ├─ cv/
│  │  ├─ ReferenceDetector.kt           # live + still 4-vertex ID/A4 finder
│  │  ├─ PrecisionCalibrator.kt         # 2048 px source cap, cornerSubPix, warp ≤2400
│  │  ├─ IrregularShapeAnalyzer.kt      # Lab/edge seeds, solid silhouette, strict holes, GrabCut
│  │  ├─ ErrorEstimator.kt              # Δpx, ε_cal, δL, δA, parallax, quality badge
│  │  ├─ AnalysisRenderer.kt            # annotated history thumbnail
│  │  ├─ Geometry.kt                    # pure ordering/tilt/homography/polygon math
│  │  └─ MatPool.kt                     # deterministic OpenCV Mat release
│  ├─ export/
│  │  ├─ SvgBuilder.kt                  # pure true-scale SVG serialisation
│  │  └─ SvgExporter.kt                 # FileProvider write + Android share sheet
│  ├─ data/
│  │  ├─ ScanRecord.kt                  # persisted scan metadata + SVG geometry
│  │  ├─ ScanRepository.kt              # filesDir/scans.json + scans/*.jpg
│  │  └─ AppPreferences.kt              # "Don't show again"
│  ├─ ui/
│  │  ├─ RealMeasureApp.kt              # bottom nav + onboarding gate
│  │  ├─ ScannerViewModel.kt            # tokenized state, cooldown, bitmap recycling, watchdog
│  │  ├─ ScansViewModel.kt              # My Scans save/delete/refresh
│  │  ├─ Formats.kt                     # ± mm / ± cm² / ± mm² formatting
│  │  ├─ components/
│  │  │  ├─ Controls.kt                 # segmented reference selector, badges, actions
│  │  │  ├─ ScannerOverlay.kt           # emerald tracking polygon + stability ring
│  │  │  ├─ InteractiveCaliper.kt       # normalized pins, loupe, 0.1 mm nudge, GrabCut box
│  │  │  ├─ OnboardingModal.kt          # Canvas diagram of the selected reference
│  │  │  └─ ErrorModal.kt               # reason + Try Again
│  │  └─ screens/
│  │     ├─ ScannerScreen.kt            # CameraX + guarded torch + ImageCapture
│  │     ├─ ResultScreen.kt             # 2D hero, height presets, refine, save, SVG
│  │     └─ ScansScreen.kt              # history cards, re-export, delete
│  └─ res/xml/file_paths.xml            # FileProvider exports/ + scans/
└─ app/src/test/java/com/realmeasure/app/
   ├─ ErrorEstimatorTest.kt             # 13 uncertainty + parallax tests
   ├─ GeometryTest.kt                   # 7 ordering/tilt/homography tests
   └─ SvgBuilderTest.kt                 # 4 true-scale SVG tests
```

### State machine & freeze recovery

```text
Scanning(referenceDetected, guidanceMessage, stabilityProgress, referenceQuad, tiltDeg)
   │  5 stable preview frames
   ▼
Analyzing(token) ── captured still (≤2048 px) ──► Success(ScanAnalysis)
   │                                              │
   ├── calibration / contour failure ──► Error(title, reason) ── Try Again ──► Scanning
   └── 6 s watchdog (capture or OpenCV stall) ──► Error("Scan Timed Out", ...)
```

Every `ImageAnalysis` frame runs inside `imageProxy.use { ... }` and an `AtomicBoolean` guard, so a proxy is never leaked. Preview conversion, still decoding, `warpPerspective`, `findContours`, `moments`, GrabCut and all contour maths run on background dispatchers (`Dispatchers.Default` for the ViewModel pipeline), never on the main thread. Every temporary `Mat` is released through `MatPool`/`finally`. The tokenized `Analyzing` state plus a **6-second watchdog** guarantees a stale capture or a stalled native call cannot leave the UI on a spinner.

### Rapid-scan memory and torch safety

- The captured still is downsampled so its longest side is **≤ 2048 px** before `Utils.bitmapToMat`; the homography output is capped at **2400 px**. All corner coordinates live in that scaled frame.
- `Success` holds only the flattened display bitmap. Replaced bitmaps are explicitly recycled when returning to `Scanning`, overwriting a result, or clearing the ViewModel; the result dialog is disabled while saving or refining so the bitmap cannot be freed under an active operation.
- A **500 ms cooldown** after `Success`/`Error` prevents the next frame from immediately re-triggering auto-capture.
- The torch button is only exposed when `CameraInfo.hasFlashUnit()` is true; every `enableTorch` call is guarded and its future callback ignores `CameraControl.OperationCanceledException`. `ImageCapture.flashMode` is always `FLASH_MODE_OFF`, and the torch is disabled on ViewModel/result disposal and on `Success`, resetting `isTorchOn = false`.

The preview pipeline is paused while `Analyzing`/`Success`/`Error` and while the onboarding modal is open. Switching to **My Scans** unbinds the camera entirely.

---

## Build & run

Requirements: **JDK 17+**, Android SDK, a physical device (OpenCV includes all ABIs; no NDK is required).

```bash
# JVM tests (uncertainty model, geometry, SVG)
./gradlew testDebugUnitTest

# Debug APK
./gradlew assembleDebug

# Install on a connected device
./gradlew installDebug
```

Open the project in Android Studio, let Gradle sync (it downloads Compose, CameraX and the ~100 MB OpenCV AAR once), and press **Run**.

CI (`.github/workflows/android-ci.yml`) runs:

```yaml
./gradlew testDebugUnitTest assembleDebug
```

and uploads the debug APK.

---

## Using the scanner

1. Choose **SMALL · ID CARD** or **LARGE · A4 SHEET** at the top.
2. Read the tutorial (reopen any time with `?`). Use a **contrasting matte surface**.
3. Lay the reference flat and fully visible, with the object **beside it** and a small gap between them.
4. Hold the phone parallel to the table. The live emerald polygon tracks the reference; the guidance pill warns about tilt or low light.
5. When the ring fills after five stable frames, the app captures automatically and flattens the scene.
6. On the result screen:
   - the **2D Planar Footprint (from photo)** card shows area (`cm²` and `mm²`), perimeter, bounding box and hole count with `±` margins;
   - **numbered H1/H2… markers** identify verified holes on the image; their coordinates, diameters and centre-to-centre spacings are listed in the **Geometry Detail** card below;
   - tap any detected object to select it;
   - **drag the P1/P2 grips** (36 dp below the crosshair tips) for a custom point-to-point distance with `± δD`; while dragging, a **3× loupe** shows the pixels under the active tip;
   - use the **Fine-Tune** bar's Pin 1/Pin 2 pills and **← ↑ ↓ →** arrows for exact **0.1 mm** nudges;
   - if the automatic silhouette is imperfect, tap **Refine Object (Draw Box)**, drag a rectangle around the object, and GrabCut snaps the contour and recalculates the bounding box, area and perimeter;
   - use the quick **Flat 0 / 10 / 20 / 30 mm** chips (plus the `0–150 mm` fine slider) to apply the 3D parallax correction;
   - **Save to My Scans**, **Export 1:1 SVG**, or **Scan Another**.
7. In **My Scans**, re-export the SVG or delete an entry (JPEG + JSON record are removed together).

### Failure modes and what they mean

| Message | Cause | Fix |
| --- | --- | --- |
| Reference not found | The selected card/A4 sheet was not detected in the still | Keep all edges visible, flatten the sheet, avoid glare |
| Reference cut off | A corner is at the image border | Pull the phone back |
| Tilt too high | Interior angles deviate by >22° | Hold the phone parallel to the surface |
| Reference distorted | The quad is degenerate | Flatten the sheet/card; avoid folds |
| No object found | No valid contour after masking the reference | Use a contrasting matte background; keep the object fully in frame |
| Scan Timed Out | Capture or OpenCV exceeded the 6 s watchdog | Improve lighting, hold steady, retry |
| Low precision badge | δL above the mode threshold | Move closer, add light, or switch to A4 for objects >250 mm |

---

## Limitations

- The method is **planar**: the object must lie on the same flat surface as the reference. All geometry is the **projected 2D footprint**; true 3D volume and side-wall shape are not recovered from a single image.
- For 3D containers, the optional height slider applies the first-order parallax correction \(D_{\text{true}}=D_{\text{apparent}}(1-h/H_{\text{cam}})\). It assumes a 400 mm camera distance, a roughly parallel top face and a camera above the object centre; it is not a full 3D reconstruction. The SVG and saved image remain the projected footprint.
- A single homography cannot model strong **lens distortion** (wide-angle phone lenses). The uncertainty model absorbs the residual into ε_cal, but a physically flatter lens (or a future calibration grid) improves accuracy.
- Holes are reduced to an **equivalent-area diameter**. Slots and keyholes are correct in area, but their diameter is not a width measurement; use the two-pin caliper or the SVG for their exact profile.
- Objects touching the flattened canvas border are rejected because their contour is incomplete.
- The solid-silhouette pipeline detects light, dark and coloured objects, but low-contrast white-on-white objects rely on edges/GrabCut. Holes are only counted when they resemble the background, are ≥ 2.5 mm² and are reasonably circular; a shadowed slot or a non-circular opening may be omitted from the net area. Use **Refine Object (Draw Box)** for difficult silhouettes.
- The still is capped at **2048 px** on its longest side and the flattened canvas at **2400 px** to keep repeated scans within the Android heap; this is well above the preview resolution and the measurement sampling needs.
- SVG contours are simplified to **0.25 mm**; the reported perimeter/area are computed from the solid silhouette at the processing resolution (≤2048 px source, ≤2400 px canvas).
- The current bundle targets Android; the computer-vision math is pure Kotlin/OpenCV and portable to a desktop JVM or iOS OpenCV build.

---

## Privacy

- Frames are analysed in-process; live frames are recycled immediately.
- A saved scan writes one cropped/annotated JPEG to `filesDir/scans/` and one `filesDir/scans.json` record.
- Exported SVGs are written to `filesDir/exports/` and shared only through an explicit Android share sheet.
- The app declares **no internet permission**, no analytics, no ads and no tracking.

---

## License

MIT — see [LICENSE](LICENSE).
