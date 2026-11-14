# StereoCam — documentation (English)

[Русский](README.ru.md) · [Deutsch](README.de.md)

> **Status: build 4. The app has never been run on a phone.** The Android code has not been compiled (the development environment has no Android SDK). Only the pair alignment and the MPO writer were tested, on a desktop JVM. Everything that involves the phone's cameras is untested.

## 1. What it is

StereoCam takes a **stereo photo with one phone and one tap**: it shoots with two rear cameras at the same time (for example the main and the ultra-wide camera), aligns the pair and saves it in several formats. The project was split off from the StereoPano panorama app and reuses its interface style, the `.vr.jpg` writer and the 7 languages (en, de, fr, it, es, ru, uk).

The idea: the two lenses of a phone already form a stereo pair with a fixed baseline (usually 1–2 cm). Shoot static scenes, no closer than about 30 cm; closer than that the difference between the two views becomes too large. This approach is not suitable for macro (see section 8).

## 2. How it works

1. **Camera check (start screen).** The app enumerates the phone's cameras, finds "logical" cameras made of several physical ones and lists the pairs worth trying. For every physical camera the report shows: focal length (and 35 mm equivalent), sensor size, largest JPEG size, minimum focus distance, lens position relative to the main camera (this gives the baseline) and the sensor synchronisation type.
2. **Shooting.** The app opens the logical camera and creates one Camera2 session with three streams: a preview and two JPEG streams, each bound to its own physical camera. One request returns two pictures. If the phone does not accept full resolution, the app tries 3264 and then 2048 pixels on the long side.
3. **Processing.**
   - Both frames are rotated according to sensor orientation and screen rotation.
   - The field of view is matched: the wider camera's picture is cropped to its centre according to the ratio of the equivalent focal lengths; the ratio is then checked against the pictures themselves (±10 % search by image matching) and corrected if the lens data are inaccurate.
   - Block matching (normalised correlation) measures the residual errors; vertical offset, rotation and scale difference are fitted from them and the right image is corrected.
   - The zero-parallax plane is set to the median of the scene and the edges are cropped with a common frame.
   - Left and right are determined from the lens positions and the screen rotation. The "Swap L/R" button exchanges them by hand.
4. **Saving** to `Pictures/StereoCam` (Android 10+ via MediaStore).

### 2.1 Shift mode (one camera)

Works on **any** phone, including those where two cameras cannot be used at once. Use the "Shoot with shift (one camera)" button on the start screen; if no camera pairs were found it becomes the main action.

1. The "subject distance" slider (5 cm to about 16 m) shows **how far to move the phone**: about 1/30 of the distance (for example 3 m → 10 cm, 1 m → 3.3 cm).
2. The **first shot** is the left eye. Afterwards exposure, white balance and (if the phone allows manual focus) focus are locked; optical and video stabilisation are switched off so they do not cancel the shift.
3. A **semi-transparent copy of the first shot** (the "ghost") is overlaid on the live view: move the phone to the right **without turning it** and bring the two pictures into line.
4. The "Turn: tilt / pan / roll" line (rotation sensor) shows the deviation from the position of the first shot. Green means under 1°, yellow under 2.5°, red more.
5. The **second shot** goes through the same alignment and is saved in the same formats. "Start over" discards the first shot. "Swap L/R" reverses the direction: the first shot becomes the right eye and the phone must be moved to the left.

Honest limits: the phone's sensors cannot measure the shift accurately — it is judged by eye from the hint; moving subjects (people, branches, water) cause errors; the alignment corrects vertical offset, tilt and scale, but not a wrong baseline. The mode has not been tested on a phone. For macro (5 cm distance → shift ≈ 1.7 mm) the shift cannot be held by hand; a turntable is better for macro — that mode does not exist yet.

### 2.2 Fast lens switching (one after another)

For phones that do not expose two cameras at once (Android 11+ required). Use the "Fast lens switching" button on the start screen; the app also switches to this mode by itself if the two-camera session cannot be set up.

1. **One session of the logical camera** is opened: a preview and one JPEG stream. This is a standard combination that Android guarantees.
2. The first shot is taken with the first lens. Immediately afterwards the zoom is switched to the second lens, the same way the stock camera app does it. The zoom factors are computed from the equivalent focal lengths relative to the lens that is active at zoom 1.0.
3. The app waits until the frame metadata (`ACTIVE_PHYSICAL_ID`) confirms the second lens for three frames in a row. If it has not switched within 0.9 s, the zoom is nudged slightly (up to four attempts).
4. The shot with the second lens follows, then the usual processing and saving. The **interval between the two shots in milliseconds** is shown in the status line.

Limits: the interval will probably be hundreds of milliseconds — movement of the subject or the hand causes errors that the alignment cannot fix (it removes vertical offset, tilt and scale, but not parallax from shaking). The mode works only if the phone's logical camera switches lenses with zoom (Pixel and many others do, not all). Not tested on a phone.

## 3. File formats

| Format | Used for |
|---|---|
| MPO (`.mpo`) | Standard 3D photo format (Fujifilm, Nintendo 3DS, 3D TVs) |
| Side-by-side JPEG | Left and right next to each other: VR glasses, cardboard viewers |
| `.vr.jpg` | For the StereoPano Viewer on the computer (right image inside extended XMP) |
| Red-cyan anaglyph | For glasses with colour filters |
| Separate L/R JPEG | For external editors |

MPO, side-by-side and automatic alignment are on by default. The MPO is first written to Pictures with the type `image/mpo`; if the system rejects that type, the file goes to `Download/StereoCam`.

## 4. Installation

1. Open the project folder in Android Studio (Hedgehog or newer).
2. Wait for the Gradle sync (if Studio offers to create the Gradle wrapper, accept).
3. Connect a phone (Android 9 or newer, `minSdk 28`) and press Run.

## 5. Usage

1. Start the app and grant camera access.
2. See whether camera pairs were found. If not, read the report at the bottom of the screen.
3. Choose a pair (the first one has the most similar focal lengths and is the easiest to work with), choose the formats and press "Start shooting".
4. Hold the phone so that the lenses are **on one horizontal line**. The app picks the screen orientation from the lens layout.
5. Press the shutter button. The result is saved and a thumbnail appears at the bottom left.
6. If the depth looks inverted (near things look far), press "Swap L/R" and shoot again.

### 5.1. Gallery and motion view

The "Gallery / motion view" button on the main screen lists photos from Pictures/StereoCam and Download/StereoCam (MPO, .vr.jpg, SBS), or any file via "Open a file…". The chosen pair is shown as camera motion on an ordinary screen: from the left eye to the right and back, easing at the ends (sine). Paths: "Back and forth" or "Around the subject" (adds a vertical part, i.e. circling). Swing is set in multiples of the real baseline (×1…×6), speed has three steps, tapping the picture pauses.

**How many degrees this really is.** The real baseline between a phone's two cameras is about 1–2.5 cm, so the angle between the views is ≈ arctan(baseline / distance): with a 2 cm baseline about 1° at 1 m, ≈2.3° at 0.5 m, ≈3.8° at 0.3 m. A 20–30° swing would need 5–10 times the baseline a phone has. Such views are **synthesised** from a depth map computed from the pair itself (~480 px); the larger the swing, the more visible the artifacts at object edges (areas no camera saw are filled with background). The practical limit is about ×4–×6 of the real baseline (≈10–15° for close subjects), hence the ×1…×6 choice.

**Honestly:** the depth map is estimated from two images of different lenses and is unreliable on plain surfaces and highlights; rendering runs on the CPU, a few frames per second on a phone (only the algorithm was tested, on a PC; not run on a device). This is a viewer, not an export: no video/GIF is produced.

The depth map for Motion mode uses semi-global matching (SGM, census cost, ±1 px vertical tolerance); the disparity range is taken from the picture itself, and narrow strips with outlying depth are rejected and filled from their surroundings.

### 5.2. Start, settings and gallery

The app opens straight on the camera image (the mode is chosen automatically: two cameras if the phone provides them, otherwise shift shooting). The gear at the top right opens settings: capture mode (auto / two cameras / fast switching / shift), camera pair, file formats, alignment and report. The thumbnail of the last photo at the bottom left opens the gallery. In the gallery: tap = motion view; press and hold = select several files (share, convert to SBS / anaglyph / separate L and R / MPO, delete). Conversion creates new files and leaves the originals unchanged; deletion is permanent (on Android 11+ the system may ask for confirmation).

### 5.3. View modes (as in StereoPano)

The top bar of round buttons matches StereoPano: gallery, mode, pause and motion settings (Motion mode only), gyro, half SBS (SBS mode only), lens correction (Cardboard only), reset zoom, settings. The mode button cycles: **Motion** → **2D** → **Cardboard** (two eyes side by side, landscape, gentle barrel correction) → **SBS glasses** (full or "half": each eye fills its half of the screen). Double-tap toggles 2D ↔ the last stereo mode; pinch zooms; a tap shows/hides the bar. The gyro pans the zoomed image as you tilt the phone (rough estimate: 60° ≈ one image width).

Honestly: lens correction is approximate and not calibrated for any particular headset; not verified on a device.

## 6. Tips for good stereo

- The baseline is fixed, so choose your distance accordingly: a good stereo pair has the subject no closer than about 30× the baseline.
- Shoot static scenes: the two cameras are not always perfectly synchronised (the report shows `calibrated` or `approximate`).
- For a "main + ultra-wide" pair the app crops the wide frame at the centre. The final resolution is set by the narrower camera.
- Clean the lenses and avoid featureless scenes (clear sky, white wall): the alignment relies on image detail.

## 7. Compatibility: which phones work

### 7.1 Requirements

- Android 9 (API 28) or newer.
- A rear "logical multi-camera" with at least two physical cameras.
- The phone must expose the **physical cameras** to third-party apps and allow **simultaneous** capture from two of them in one session.

### 7.2 The honest answer: there is no verified list

**There is no reliable public list of phones on which two physical cameras work simultaneously in a third-party app, and no phone has been verified with StereoCam yet.** Reasons:

- According to the Android documentation, physical cameras are available to apps from Android 9, but what the logical camera offers is "entirely dependent on the OEM implementation" of the camera HAL.
- Only a minimal combination is guaranteed: **one YUV or RAW stream of the logical camera can be replaced by two streams from physical cameras of the same size and format.** From Android 10 on, a logical camera is not even required to support combinations with physical streams.
- StereoCam uses **JPEG streams (possibly of different sizes) plus a preview**. That combination is **not covered by the Android guarantees**. This is the main technical risk (see section 9).
- Manufacturers may hide cameras or break the session on specific firmware versions.

### 7.3 What is known from public sources

These are scattered statements, partly from users and developers. None of them confirms that **simultaneous** capture from two cameras will work.

| Brand / model | What is reported | Reliability |
|---|---|---|
| Google Pixel 3 | Named in the Android documentation as an example of a device with a logical camera | Google documentation (2018), about the API, not about simultaneous capture |
| Huawei Mate 20 | Named in an Android Developers article as supporting the multi-camera API at launch of Android 9 | 2018 article; the device is long out of updates |
| Pixel (in general) | "Expose everything" | Retold in a third-party podcast; weak source |
| Samsung Galaxy S23 Ultra | Shows all lenses through the standard API | Same weak source |
| Samsung Galaxy S22–S25 | In a Samsung developer forum thread a user replies that Samsung does not provide telephoto camera information and CameraX has to be used | One forum opinion, no official Samsung answer |
| OnePlus 9 (OxygenOS 12) | The Android 12 update removed third-party access to auxiliary cameras | Retold in a third-party source |
| OnePlus 7 Pro | The ultra-wide camera was not visible to a third-party app (visible to Open Camera) | Developer blog, 2021 |
| Motorola (models not named) | The ultra-wide camera is not available to third-party apps | Developer blog, 2021 |
| Xiaomi POCO X8 Pro (Android 16, HyperOS 3) | A session through the logical camera with physical streams fails with `ERROR_CAMERA_DEVICE` while video stabilisation is enabled on the physical cameras; without it, according to the author, it works | A single forum report, no confirmation from Xiaomi |

This information ages quickly: firmware updates change behaviour. Use the table as a hint, not as a guarantee.

### 7.4 How to test your own phone

Install the app and open the start screen:

- **Pairs listed** — the phone shows two physical cameras to the app (necessary, but not sufficient).
- **"No usable camera pair"** — the phone does not expose the physical cameras: the two-camera mode is unavailable, use the shift mode (section 2.1).
- Then you need a real shot. If the app shows `Mode 1/2/3: …` and saves a file, simultaneous capture works. The mode is the resolution step (1 — maximum, 2 — up to 3264, 3 — up to 2048).
- If it says the phone refuses to run both cameras at once, the app switches to fast lens switching by itself (section 2.2). If only one of the two images arrived, the pair does not work for simultaneous capture; try the mode in 2.2.

### 7.5 Table of verified devices (filled from reports)

Empty for now. If you tested StereoCam on your phone, send the report ("Share report" on the start screen) and a note on whether shooting worked and in which mode. The data are collected in `docs/devices.csv`.

| Manufacturer | Model | Android | Pair | Mode | Result |
|---|---|---|---|---|---|
| — | — | — | — | — | no data yet |

## 8. Limitations

- Nothing that involves Android has been tested on a phone: camera discovery, the session with two physical streams, the preview and its rotation, saving through MediaStore.
- Macro: the fixed lens baseline (1–2 cm) is too large for subjects closer than about 30 cm. A shift mode exists (section 2.1), but for macro a shift of a few millimetres cannot be held by hand; a turntable mode is not implemented yet.
- The automatic alignment has been tested only on a synthetic pair. Real scenes (glare, repetitive textures, little detail) may produce a wrong fit. The alignment can be switched off in the settings.
- Left/right is derived from the lens positions; on a phone without position data the order "A is left" is assumed and can be corrected with the button.
- Memory: photos are limited to 3600 pixels on the long side; the app requests a large heap.
- The translations have not been reviewed by native speakers.

## 9. Known risks and next steps

- **Fallback modes.** If simultaneous capture does not work, there is fast lens switching (2.2) and one-camera shifting (2.1).
- **Stream combination.** It would be more robust to use two `YUV_420_888` streams of identical size and encode the JPEG ourselves; this combination is guaranteed on Android 9 for devices with physical cameras. The preview for that mode would have to come from one of the streams. This is the second step worth adding if the first does not work on your phone.
- On Android 10+ the combination can be checked in advance with `CameraDevice.isSessionConfigurationSupported` and the result shown on the start screen.
- On some phones the physical cameras are not listed in `getCameraIdList()` (hidden) but can be reached by their ID; the app queries them directly.

## 10. Troubleshooting

| Message | Meaning |
|---|---|
| "No usable camera pair" | The phone does not expose two physical cameras to apps |
| "This phone refuses to stream both cameras at once" | The session could not be configured at any of the three resolutions |
| "The phone delivered only one of the two images" | The second frame did not arrive within 9 seconds |
| "The camera could not be opened" | The camera is in use by another app or unavailable |

## 11. Project structure

`ProbeActivity` (check and settings) · `DualCaptureActivity` (shooting) · `DualProbe` (pair discovery) · `Processor` (processing and writing) · `StereoAligner` (pure Java: estimation and correction) · `MpoWriter` · `VrJpgWriter`, `ExifGps` (from StereoPano) · `Prefs` · `Ui` · `tools/gen_strings.py` (strings in 7 languages). Desktop JVM test: `test/AlignTest.java`.

## 12. Sources

- [Multi-camera API — Android Developers](https://developer.android.com/media/camera/camera2/multi-camera)
- [Multi-camera support — Android Open Source Project](https://source.android.com/docs/core/camera/multi-camera)
- [Getting the Most from the New Multi-Camera API — Android Developers (Medium)](https://medium.com/androiddevelopers/getting-the-most-from-the-new-multi-camera-api-5155fb3d77d9)
- [Android Camera Fragmentation: Why Apps See Different Lenses](https://www.myweirdprompts.com/episode/android-camera-fragmentation-physical-lenses/)
- [Every Camera, Every Angle on Android — GameChanger Tech Blog](https://tech.gc.com/every-camera-every-angle-on-android/)
- [Telephoto access via third party application — Samsung Developer Forums](https://forum.developer.samsung.com/t/telephoto-access-via-third-party-application/42137)
- [Camera2 API Issue: Physical Camera fails via Logical Camera Flow … POCO X8 Pro](https://android-overflow.vercel.app/thread/79931897)
