# Cast, DLNA and Danmaku Architecture

## Cast flow

```text
Phone SOAP action
  → platform/dlna/DlnaRenderer
  → app/DlnaCastListenerAdapter
  → CastSessionCoordinator
  → CastRequestResolver
  → VOD or live coordinator
  → PlaybackEngineHost
```

`DlnaRenderer` is a protocol server: device description, SSDP, HTTP, SOAP parsing and transport-state responses belong there or in its `platform/dlna` collaborators. It forwards semantic callbacks only. `DlnaCastListenerAdapter` is deliberately thin and only translates renderer callbacks to `DlnaCastCommands`.

`CastSessionCoordinator` owns a cast’s replacement/resolve/open/stop lifecycle. It decodes the cast content identity, starts a diagnostics/history session, selects VOD versus live resolution, creates the relevant playback session and manages retry/error outcomes. Extend `CastContent` and `CastRequestResolver` for new identity/resolution behaviour; do not parse NVA metadata in a player, UI or DLNA protocol class.

`RecentCastCoordinator` and `feature/cast/domain/RecentCast` own persistence-facing recent-cast behavior. `RecentCastStore` is the data/local implementation.

## Danmaku flow

```text
PlaybackEventRouter / ticker
  → DanmakuController or LiveDanmakuController
  → DanmakuRepository or LiveDanmakuClient
  → DfmDanmakuEngine
  → DanmakuView
```

- `DanmakuController` owns VOD segment loading, media-clock synchronization, seek/buffer/pause transitions and its Jobs.
- `LiveDanmakuController` owns WebSocket session replacement, batching and the zero-based live timeline. It resets DFM’s render session on a room change so the prior room’s render cursor cannot discard new messages.
- `DfmDanmakuEngine` owns DFM-specific rendering and the Android `DanmakuView`; it does not own Bilibili protocol/session policy.
- `data/bili/danmaku` owns Bilibili request/protocol/WebSocket details and implements the domain client/repository ports.

For timing defects, trace packet receipt → application queue/flush → DFM submission → shown callback. Log only redacted metadata and timing/state values. Do not compensate for a clock/session error by delaying the UI overlay.

## Cast lifecycle checklist

When a cast is replaced or ended, account for: resolver Job, VOD backup-refresh Job, live backup-refresh Job, player backend, danmaku session, history session, DLNA transport state, retry action and UI projection. Reuse coordinator operations instead of duplicating a subset in a new callback.
