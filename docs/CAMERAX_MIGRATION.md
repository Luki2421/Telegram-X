# CameraX migration

TGX-Next uses CameraX for all internal camera capture on Android 13+ and `arm64-v8a`.
This includes photos, ordinary videos, QR scanning, and round video messages. The system
camera remains an optional alternative for ordinary capture. Camera1 is no longer an
internal backend or a fallback.

Android version support alone does not guarantee every camera capability or use-case
combination. Keep capability checks and report failures gracefully; do not restore Camera1
to handle hardware differences. See the [platform migration](ANDROID_13_ARM64.md) for the
separate Android/ABI contract.

## Implementation map

Paths below are relative to the repository root.

| Component | Responsibility |
|---|---|
| [CameraController](../app/src/main/java/org/thunderdog/challegram/ui/camera/CameraController.java) | Selects the ordinary/QR or round CameraX manager and recreates it when the capture mode changes. |
| [CameraManagerX](../app/src/main/java/org/thunderdog/challegram/ui/camera/x/CameraManagerX.java) | Binds preview with photo/video capture or QR analysis. Groups use cases with a viewport so capture and preview cover the same area. |
| [CameraQrBridge](../app/src/main/java/org/thunderdog/challegram/ui/camera/CameraQrBridge.kt) | Uses MlKitAnalyzer, with ZXing when Google services are unavailable or analysis fails. Reports bounds in PreviewView coordinates. |
| [CameraManagerRoundX](../app/src/main/java/org/thunderdog/challegram/ui/camera/x/CameraManagerRoundX.java) | Supplies CameraX preview frames to the round renderer through SurfaceRequest and manages camera/surface ownership. |
| [RoundVideoRecorder](../app/src/main/java/org/thunderdog/challegram/player/RoundVideoRecorder.java) | Keeps the GL renderer, circular mask, encoder, audio, camera-switch transition, and progressive MP4 output used by round messages. |
| [RecordAudioVideoController](../app/src/main/java/org/thunderdog/challegram/player/RecordAudioVideoController.java) | Coordinates round-message recording, preview, pause/resume, file generation, and sending. |

The custom round renderer is not a legacy camera backend. Its camera input now comes from
CameraX; it retains the existing media pipeline to preserve round-message behavior.
`CameraManagerTexture` and `CameraTextureView` remain surface/layout helpers for that renderer.
The Camera1 classes `CameraApi`, `CameraApiLegacy`, and `CameraManagerLegacy` were removed.

## Preferences and interface

[Settings](../app/src/main/java/org/thunderdog/challegram/unsorted/Settings.java) retains the
stored preference key and the IDs of the remaining choices:

| Stored camera type | Effective choice |
|---|---|
| Missing value | CameraX (`1`, the default) |
| Former legacy value (`0`) | CameraX (`1`) |
| CameraX (`1`) | CameraX (`1`) |
| System (`2`) | System (`2`) |
| Other values | CameraX (`1`) |

Do not renumber these values or replace an explicit system-camera preference. The interface
shows only **CameraX** and **System**. The legacy option and the experimental CameraX label
were removed. QR and round-message modes no longer force a Camera1 implementation.

## Invariants for upstream integration

When upstream changes camera code, resources, or preferences:

- Port the intended behavior to the current CameraX managers. Do not restore the removed
  classes, `android.hardware.Camera`, `getLegacyManager()`, forced-legacy mode selection,
  or the legacy camera option as a conflict-resolution shortcut.
- Preserve the preference migration and system-camera alternative. Adapt obsolete resource
  IDs and source paths to their current equivalents.
- Preserve asynchronous ownership. ML Kit must finish before its image is released; ZXing
  must respect luminance row/pixel strides. CameraX surface completion must precede release
  or replacement of the round renderer's input texture. Retain an abandoned output texture
  until GL has finished using it.
- Avoid reinitializing the camera merely to update ordinary capture rotation. Keep pending
  captures and finalization from overlapping, and clean recording/torch state on failure.
- Preserve QR bounds in preview coordinates, including rotation, crop, and mirroring. For
  round messages, avoid applying sensor transformations twice and keep the circular mask
  independent of texture crop/rotation.
- Preserve round-message audio, compression settings, switching within one recording,
  preview/discard, pause/resume/concatenation, and progressive file-generation callbacks.
  The renderer may evolve, but replacing it must retain these observable behaviors.

These rules supplement the [upstream sync skill](../.agents/skills/tgx-upstream-sync/SKILL.md).
Use the [CameraX skill](../.agents/skills/camerax/SKILL.md) when implementation guidance is
needed; this document records fork decisions rather than an API tutorial.

## Validation

For camera changes, run the current repository checks from the root:

```bash
./gradlew :app:assembleLatestDebug
./gradlew :app:lintLatestDebug
```

Test infrastructure is external; consult [AGENTS.md](../AGENTS.md) before selecting test
commands. Do not assume temporary migration harnesses are installed or available in a fresh
checkout. Exercise affected flows on Android 13+ and arm64 when a device is available:

- Ordinary preview, front/rear selection, photo/video capture, aspect ratios, and editor
  results matching the preview.
- QR decoding and marker placement with rotation/crop, including the ZXing fallback.
- Round-message audio and circular output, switching during recording, cancellation,
  pause/resume, concatenation, and camera re-entry after returning from preview.
- Closing, pausing, destroying, and switching modes while initialization or capture is
  pending; permission denial and unavailable hardware/use-case combinations.

### Migration validation record

The migration passed debug assembly, debug lint, and 31 isolated external regression checks
using JUnit4/Truth, controlled fakes, and real ZXing decoding. Lint reported zero errors and
four existing unrelated warnings, with 17 warnings filtered by the existing baseline.

Manual checks used an API 36 x86_64 emulator executing the ARM64 APK through native
translation. Photos, ordinary videos, and round-message previews were captured and discarded
without sending messages. A synthetic QR was recognized from the virtual camera scene.
Round output contained H.264 video at 240×240 and AAC audio; switching cameras and resuming
recording produced playable results. A captured 4:3 JPEG measured 480×360 with EXIF rotation.
Audio and video packet timestamps increased across the resumed file. FFmpeg decoding passed
when preserving the input time base rather than rounding to an inferred frame rate.

These are historical results, not guarantees for future integrations. Physical ARM64 devices,
manufacturer-specific camera HALs, a remote message transfer, and complete upload behavior
were not exercised. Re-run relevant checks after adapting upstream camera changes.
