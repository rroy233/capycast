# Current Architecture Map

## Composition and direction

The project remains one Gradle module, `:app`, with package-level boundaries. `MainActivity` creates `CapyCastContainer`, `PlaybackEngineHost`, the DLNA renderer and feature host adapters. It binds Android lifecycle, views, window state and Compose; it is not the place for new feature policy.

```text
DLNA phone / Compose UI / player SDK
             │
             ▼
 app adapters and MainActivity host bindings
             │
             ▼
 feature coordinators and feature-owned models
             │
             ├── core contracts and diagnostics
             ├── data adapters for Bilibili and persistence
             └── platform adapters for Android, players and DLNA protocol
```

`CapyCastContainer` is the dependency composition root for account, update, history, preferences, Bilibili resolvers and device services. A new concrete implementation is assembled there when it is consumed by an existing feature. Do not instantiate data implementations ad hoc in a coordinator.

## Package responsibilities

| Package | Responsibility | May depend on |
|---|---|---|
| `app` | composition, Android hosts, protocol-to-command adapters | feature/core/data/platform/UI bindings |
| `core` | stable playback contracts and diagnostics primitives | Kotlin/Android primitives where already established; never app/data/feature/platform/UI |
| `feature/<name>/domain` | rules, state machines and stable feature models | same feature model/domain and core contracts |
| `feature/<name>/application` | commands, workflows, session ownership and ports | feature domain/model/routing plus narrow ports |
| `feature/<name>/presentation` | feature-to-UI or feature-to-renderer mapping | feature models and UI presentation models |
| `data` | Bilibili APIs, WebSocket/HTTP, DTO mapping, preferences and local stores | core and feature-owned ports/models |
| `platform` | Android/SDK/protocol implementation details | core contracts and Android/third-party SDKs |
| `ui` | Compose rendering, focus/navigation and UI state projection | UI models and action contracts |

The repository enforces the essential rules in `app/src/test/java/com/roy233/tvcast/architecture/LayerDependencyTest.kt`. Respect its intent even when an import can be made to compile through a fully qualified name or typealias.

## State and resource ownership

- `TvStateHolder` owns the Compose root projection. `TvActionDispatcher` handles UI-only transitions; `TvActionHost` sends domain commands to the Android host.
- `PlaybackEngineHost` owns VLC/Media3 creation, backend selection, visible surface switching and engine release.
- `PlaybackEventRouter` owns the mapping from backend-neutral events to playback, history, danmaku and UI effects.
- `CastSessionCoordinator`, `LivePlaybackCoordinator`, `VodPlaybackCoordinator`, `PlaybackHistoryCoordinator`, account/update coordinators and danmaku controllers own their session Jobs and transition state.
- `DlnaRenderer` owns HTTP, SSDP and protocol threads. `DfmDanmakuEngine` owns DFM view integration; application controllers own their danmaku data/session lifecycle.

When adding a resource, assign it to one of these owners or add a focused owner. The creator cancels/releases it on replacement, failure and Activity destruction through the existing lifecycle path.

## How to choose an extension point

| Desired change | Correct starting point |
|---|---|
| New UI affordance | `TvActions` → `TvActionHost` → owning coordinator |
| New Bilibili response/data source | data adapter and mapper → feature-owned stable model/port |
| New player capability | core playback contract → platform adapter → `PlaybackEngineHost` → event router/coordinator |
| New playback recovery condition | VOD/Live domain or coordinator, with a focused test |
| New cast command or content identity | cast domain/resolver/coordinator; DLNA adapter only translates protocol callbacks |
| New device/network/protocol operation | platform service behind a feature-owned port |
| New persistent setting | owning feature model + data/local adapter + UI projection/action |

Avoid changing a downstream UI state update when the defect originates in the source session, adapter or event mapping. First establish where the invariant becomes false.
