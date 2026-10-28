# Danmaku Architecture

## Two session types, one renderer

VOD and live share `DfmDanmakuEngine` and `MediaClock`, yet have different data and timing semantics:

```text
VOD player events/ticker → DanmakuController → DanmakuRepository → DfmDanmakuEngine
live player events       → LiveDanmakuController → LiveDanmakuClient → DfmDanmakuEngine
```

`DanmakuController` owns VOD segment loading, player-clock sampling, seek, buffering debounce, playback-rate updates and segment replacement. A VOD item has an absolute media timestamp.

`LiveDanmakuController` owns WebSocket session replacement, batching and live clock lifecycle. A live item receives its appearance timestamp when the current live clock is flushed. It drops additions while paused so resuming never floods the display with stale messages.

`DfmDanmakuEngine` owns DFM-specific state, `DanmakuView`, pending renderer items, renderer seek/reset and display settings. It is the only location that calls DFM APIs.

`data/bili/danmaku` owns Bilibili segment APIs, live WebSocket protocol framing/decoding and the concrete implementations of the feature’s repository/client ports.

## Required lifecycle ordering

- Open VOD: reset controller/session state, freeze the clock at the requested start point, load the view/segment, then rebase and resume only after the player emits `Playing`.
- VOD seek: freeze at target, pause/seek DFM, replace the relevant segment, and resume only after a confirming player sample.
- Open a new live room: close the old client and jobs, reset the live clock, clear and reset the DFM render session to zero, then open the new client. Clearing items alone is insufficient because DFM retains its render cursor.
- Pause/live buffering: pause the appropriate clock and renderer; define whether incoming data is retained or dropped in the controller, never in the renderer.
- End/stop/error/destroy: close controller jobs and clients before releasing the renderer. The playback/cast lifecycle owner decides which terminal path applies.

## Correct extension points

| Change | Owner |
|---|---|
| New Bilibili danmaku field or protocol command | `data/bili/danmaku` mapper/protocol, then a feature domain model if it is needed outside data |
| Filtering, visibility policy or feature setting | danmaku domain/settings plus application controller |
| Playback-to-danmaku event ordering | `PlaybackEventRouter` and the controller public methods |
| Clock, seek, segment and batching rules | `DanmakuController` or `LiveDanmakuController` |
| DFM layout, conversion or renderer lifecycle | `DfmDanmakuEngine` |
| Overlay size/placement after a real View layout change | Android host/view binding; keep data/session policy out of it |

## Diagnosing timing issues

Trace packet or segment receipt → controller queue → flush/DFM submission → `danmakuShown`. Compare media clock, DFM timer and current renderer time. A visible overlay with submitted items and no `shown` callback points to renderer timeline or render state, while no queued items points to the data/session path. Keep logs redacted and do not log message content or identity data.
