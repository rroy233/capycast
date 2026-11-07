# Playback Architecture

## Normal flow

```text
CastSessionCoordinator / PlaybackSelectionCoordinator
  → PlaybackRequest
  → PlaybackEngineHost
  → Media3 adapter
  → PlaybackEvent
  → PlaybackEventRouter
  → VOD/live coordinator, history, danmaku and UI projection
```

`PlaybackRequest` and `MediaTrack` in `core/playback/PlaybackContracts.kt` are the stable boundary between resolution and playback. Data mappers populate them; UI and platform player code consume them. Add a field there only when it represents playback-wide information. Keep provider-specific response shapes in `data/bili`.

## Media3 lifecycle and player events

- `PlaybackEngineHost` owns the Media3 lifecycle, output-surface switching and HDR window callback. DASH VOD merges its split audio/video representations into one Media3 timeline; progressive VOD, HLS and live all use their corresponding Media3 media sources.
- The Media3 adapter translates SDK callbacks into `PlaybackEvent`. It does not decide recovery, history reporting, danmaku or UI state.
- `PlaybackEventRouter` is the single owner of playback-event side effects. Preserve its VOD/live event ordering and cleanup semantics.

When a bug appears only on HDR, DASH or DRM, trace `PlaybackEngineHost` → Media3 adapter → router before changing a generic remote-control or UI path.

## VOD and live sessions

- `VodPlaybackCoordinator` owns the active VOD session, rebuffer classification, same-representation CDN switch, representation/codec fallback and backup refresh Job. Rules belong in `feature/playback/domain`; probing/ranking belongs in `feature/playback/routing` through `VodRouteProbe`.
- `LivePlaybackCoordinator` owns live session state, candidate ranking, quality switch, AVC fallback, rebuffer/stall recovery and backup refresh Job. Use `LiveRouteProbe` and routing/domain rules rather than HTTP code in the coordinator.
- `PlaybackSelectionCoordinator` handles user-requested video quality, live quality and audio selection. It preserves playback position and replaces the appropriate session request.
- `PlaybackCoordinator` handles generic transport controls: play/pause, seek, seek preview, speed and volume. It must not absorb route policy or cast protocol code.

Changing recovery requires a deterministic test that covers candidate order, attempted URLs/representations, position carry-over and the resulting error or retry path.

## Lifecycle rules

Opening a new cast replaces the prior VOD/live session before Media3 opens the new request. Keep the playback-session ownership checks when introducing an asynchronous callback.

For new behavior, decide which of these paths owns cleanup: replacement by a new cast, user stop, DLNA Stop, natural end, terminal player failure, or Activity destruction. These paths intentionally differ in history reporting, DLNA state, retry availability and UI feedback.
