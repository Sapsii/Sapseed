# Sapseed Edge

Android phone prototype and runtime-independent edge pipeline. Production edge hardware remains intentionally undecided.

## Structure

```text
androidApp/                   Android application and composition root
edge/
  src/commonMain/             Runtime-independent models, pipeline, and contracts
  src/androidMain/            Android camera/location/storage/network adapters
  src/commonTest/             Model and event-validation tests
```

## Implemented workflow

```text
CameraX frame source
    -> model-specific detector
    -> confidence and label validation
    -> GPS location
    -> JPEG evidence storage
    -> durable on-device JSON event queue
    -> idempotent HTTP event upload
```

`AndroidEdgeRuntimeFactory` assembles this workflow from the Android adapters. The detector is injected through `AndroidFrameDetector`; exported models come from `../sapseed-models`.

The app requests camera/location permission, displays live camera previews, and runs continuous detection with exported YOLO11n ONNX or LiteRT models from `../sapseed-models`.

## Camera source

The phone camera remains the default. Tap **Camera: Mobile** at the top of the app to switch sources:

- **Discover wireless camera** finds ESP32-CAM firmware advertising `_sapseedcam._tcp` on the current local network.
- **Enter camera IP or URL** accepts an IP such as `192.168.4.1`, a complete MJPEG URL such as `http://192.168.4.1/stream`, or a raw H.264 URL such as `https://172.16.211.144:4444/video/h264`.
- **Broadcast this phone's camera** turns the phone into a discoverable IP camera. Discovery selects its hardware-encoded Annex-B H.264 stream (`/video/h264`, 640×480, about 25 fps on the prototype phones); MJPEG remains available at `/stream` as a fallback. Another edge unit on the same Wi-Fi connects through **Discover wireless camera** with no URL entry.
- **Camera: Mobile** switches back to CameraX.

For the companion AI-Thinker firmware, flashing instructions, and direct-AP workflow, see the `SapsiiHardware/esp32_cam_wireless` repository. Wireless frames use a latest-frame queue and the same YOLO11n runtimes as mobile-camera frames.

Manual URLs are probed before connecting: `multipart/*` streams use the MJPEG reader, while `video/h264` streams are decoded on-device with MediaCodec (Annex-B NAL units, SPS-derived resolution, YUV-to-RGBA conversion). Self-signed HTTPS cameras are accepted, and a plain-HTTP URL whose server answers with silence is automatically retried over HTTPS. Both behaviors are scoped to these explicit camera connections only.

## Multi-camera prototype: one edge unit, many feeds

Each **Discover** hit or manual URL **adds** a tile to a two-column grid instead of replacing the current source, so one phone processes several footages simultaneously. Tiles show the live preview plus per-camera status; ✕ drops a tile, and dropping the last one returns to the mobile camera. Discovery keeps collecting until its 10 s timeout, so one scan picks up every broadcasting phone at once.

**Live detect** runs a single shared YOLO11n detector across all tiles (frames serialized with a mutex, latest-frame-per-camera, per-tile fps/ms/object line). Choose ONNX CPU, ONNX NNAPI, LiteRT CPU, or LiteRT GPU before switching live detection on. LiteRT GPU is the default. The runtime selector is locked while detection is active.

The phone-specific fast path uses CameraX RGBA output with physical output rotation, an ARM64/NEON-friendly C++ letterbox/normalization step, and LiteRT's OpenCL GPU delegate in sustained-speed mode. Compiled GPU kernels are cached by model digest under the app code cache. The APK is intentionally restricted to `arm64-v8a`.

## Offline event retention

Each validated event is committed to app-private storage as:

- metadata in an atomic JSON queue
- one JPEG photo at quality 85

The default queue retains the newest **60 events/photos**, with an additional **60 MiB evidence cap**. Whichever limit is reached first evicts the oldest event and deletes its photo. Successful or permanently rejected uploads also remove both metadata and photo. Network failures and retryable HTTP responses leave both on disk.

Approximate JPEG usage varies with detail and noise:

| Frame size | Typical JPEG | 60 photos |
|---|---:|---:|
| 640×480 | 60–200 KiB | 3.5–12 MiB |
| 1280×720 | 150–500 KiB | 9–30 MiB |
| 1920×1080 | 400–1,200 KiB | 24–70 MiB |

The hard byte cap protects storage when noisy or high-resolution frames compress poorly. Upload requests are multipart form data with an `application/json` `metadata` part and image-only `photo` parts; video evidence is not supported.

## Deliberately not included

- Backend or frontend code
- iOS
- Premature Linux/JVM/native production targets

## Build and test

```shell
./gradlew :edge:testAndroidHostTest :androidApp:assembleDebug
```
