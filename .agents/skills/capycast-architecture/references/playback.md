# Playback Architecture

## Normal flow

```text
CastSessionCoordinator / PlaybackSelectionCoordinator
  → PlaybackRequest
  → PlaybackEngineHost
  → PlaybackBackendSelector
  → VLC or Media3 adapter
  → PlaybackEvent
  → PlaybackEventRouter
  → VOD/live coordinator, history, danmaku and UI projection
```

`PlaybackRequest` and `MediaTrack` in `core/playback/PlaybackContracts.kt` are the stable boundary between resolution and playback. Data mappers populate them; UI and platform player code consume them. Add a field there only when it represents playback-wide information. Keep provider-specific response shapes in `data/bili`.

## Backend selection and player events

- `PlaybackBackendSelector` is the sole backend-selection policy. DASH VOD uses Media3 so its split audio/video tracks share a seek and buffering timeline; progressive VOD and live use VLC unless HDR, Dolby Vision or DRM require Media3.
- `PlaybackEngineHost` owns VLC/Media3 lifecycle, surface visibility and HDR window callback. It exposes backend-neutral commands and snapshots.
- VLC/Media3 adapters translate SDK callbacks into `PlaybackEvent`. They do not decide recovery, history reporting, danmaku or UI state.
- `PlaybackEventRouter` is the single owner of playback-event side effects. Preserve its backend-specific event ordering: it carries different VOD/live and cleanup semantics.

When a bug appears only on HDR, DASH or DRM, trace `PlaybackBackendSelector` → `PlaybackEngineHost` → Media3 adapter → router before changing a generic remote-control or UI path.

## VOD and live sessions

- `VodPlaybackCoordinator` owns the active VOD session, rebuffer classification, same-representation CDN switch, representation/codec fallback and backup refresh Job. Rules belong in `feature/playback/domain`; probing/ranking belongs in `feature/playback/routing` through `VodRouteProbe`.
- `LivePlaybackCoordinator` owns live session state, candidate ranking, quality switch, AVC fallback, rebuffer/stall recovery and backup refresh Job. Use `LiveRouteProbe` and routing/domain rules rather than HTTP code in the coordinator.
- `PlaybackSelectionCoordinator` handles user-requested video quality, live quality and audio selection. It preserves playback position and replaces the appropriate session request.
- `PlaybackCoordinator` handles generic transport controls: play/pause, seek, seek preview, speed and volume. It must not absorb route policy or cast protocol code.

Changing recovery requires a deterministic test that covers candidate order, attempted URLs/representations, position carry-over and the resulting error or retry path.

## Lifecycle rules

Opening a new cast replaces the prior VOD/live session before a new backend starts. A player event is ignored when it belongs to a backend that is no longer active. Keep this protection when introducing an asynchronous callback.

For new behavior, decide which of these paths owns cleanup: replacement by a new cast, user stop, DLNA Stop, natural end, terminal player failure, or Activity destruction. These paths intentionally differ in history reporting, DLNA state, retry availability and UI feedback.
