# Repository Guidelines

## Core Principles

- Read the relevant code, configuration, documentation, and existing conventions before making changes. Do not rewrite existing behavior based on assumptions.
- Before changing Android behaviour, read the **Current Architecture** section below. Then read the task-specific reference selected by `.agents/skills/capycast-architecture/SKILL.md`.
- Keep changes focused on the requested outcome. Do not mix unrelated cleanup, refactoring, or formatting with functional changes.
- Preserve and respect existing user changes in the working tree.
- When requirements, scope, or destructive effects are unclear, explain the risk and ask for confirmation before proceeding.

## Branches and Git

- Develop on a dedicated branch; do not modify `main` directly.
- Use branch names in the form `feature/<topic>`, `fix/<topic>`, `refactor/<topic>`, `docs/<topic>`, or `chore/<topic>`.
- Do not overwrite the working tree, rewrite shared history, force-push, or delete branches unless the user explicitly authorizes the action.
- Keep each commit focused. Separate unrelated formatting, refactoring, and functional changes.

### Commit Messages

Use Conventional Commits:

```text
<type>(<scope>): <concise summary>

- Change 1
- Change 2
- Validation or important limitation
```

- Common types: `feat`, `fix`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, and `perf`.
- Write a clear outcome in the subject. Use the body to describe key changes and validation when useful.

## Code Quality

- Follow the repository's existing language, naming, directory, and formatting conventions.
- Keep files, classes, and functions focused on a clear responsibility. Avoid unnecessary abstractions and duplicated logic.
- Use named constants for thresholds, configuration values, and states; avoid unexplained magic numbers and strings.
- Give resources, tasks, listeners, and concurrent work explicit creation, cancellation, and cleanup paths.
- Do not silently ignore errors. Define a recovery, fallback, propagation, or user-facing failure path as appropriate.

## Comments and Logging

- Comments should explain intent, constraints, and non-obvious decisions; do not restate the code.
- Document public interfaces, complex control flow, state transitions, and important boundaries when needed for maintenance.
- Log errors with enough context to diagnose them, without exposing secrets or sensitive user data.

## Validation and Delivery

- Run compilation, tests, or manual verification appropriate to the risk of the change.
- Before committing, run formatting checks when available and inspect the final diff.
- In the final handoff, state the changes made, validation performed, and any known limitations or remaining risks.

## Current Architecture

CapyCast is a single `:app` module with logical layers. Preserve this dependency direction:

```text
Compose UI → UI actions → feature coordinators → core contracts / feature models
                                                   ↑                 ↓
Android host + app composition → platform adapters ← data adapters
```

`MainActivity` is the Android composition root and host. It owns Activity lifecycle, Window/View bindings, Compose attachment, and the narrow host implementations needed to connect the UI and platform callbacks. Put feature policy, protocol parsing, route selection, recovery rules, and session state in the existing feature coordinators instead of adding another branch to `MainActivity`.

### Ownership map

| Concern | Owner and extension point | Keep out of |
|---|---|---|
| Root wiring and Android lifecycle | `app/CapyCastContainer.kt`, `app/PlaybackEngineHost.kt`, `MainActivity.kt` | feature policy and protocol parsing in `MainActivity` |
| UI rendering and UI-only transitions | `ui/tv/*`, `TvStateHolder`, `TvActionDispatcher` | SDK/API calls from composables |
| UI command boundary | `ui/model/TvActions.kt` and `ui/tv/TvActionHost` | direct coordinator or data access from UI |
| Stable playback input and events | `core/playback/PlaybackContracts.kt`, `PlaybackEngine.kt` | Bili DTOs in platform/player or UI |
| Player selection and SDK lifecycle | `core/playback/PlaybackBackendSelector.kt`, `app/PlaybackEngineHost.kt`, `platform/player/*` | backend branches scattered through coordinators |
| Player event side effects | `feature/playback/application/PlaybackEventRouter.kt` | event-specific business effects inside VLC/Media3 adapters |
| Generic controls | `PlaybackCoordinator` | live/VOD route recovery or DLNA protocol handling |
| VOD route recovery and representation fallback | `VodPlaybackCoordinator`, `feature/playback/routing/*` | UI, Activity, or player adapter |
| Live route recovery, quality and watchdog | `LivePlaybackCoordinator`, `feature/playback/routing/*` | UI, Activity, or player adapter |
| Manual audio/quality selection | `PlaybackSelectionCoordinator` | compose screens or player SDK adapters |
| Cast request lifecycle and NVA resolution | `CastSessionCoordinator`, `CastRequestResolver`, `feature/cast/*` | `DlnaRenderer` and `MainActivity` |
| DLNA/UPnP wire protocol | `platform/dlna/*`; callback translation in `app/DlnaCastListenerAdapter.kt` | feature policy in SOAP/HTTP code |
| VOD/live danmaku sessions | `feature/danmaku/application/*`; DFM rendering in `feature/danmaku/presentation/*` | player adapters and UI composables |
| Bilibili, storage and HTTP implementations | `data/*` | UI, platform-player, and feature application APIs |
| Account, update and history workflows | their feature coordinators; implementations are assembled in `CapyCastContainer` | unrelated feature coordinators |
| Android-only device, installer, QR services | `platform/device`, `platform/installer`, `platform/qr` | feature domain/model packages |
| Diagnostics and redaction | `core/diagnostics/*` | ad-hoc unredacted logging |

### Mandatory routing rules

1. Add a user action by extending `TvActions`/`TvActionHost`, then route it to the owning feature coordinator. A composable only renders state and emits actions.
2. Add or modify a playback backend by extending the stable playback contracts and `PlaybackEngineHost`; convert SDK callbacks to `PlaybackEvent`, then let `PlaybackEventRouter` apply business side effects.
3. Add VOD/live recovery, quality, CDN or retry policy in the corresponding playback coordinator or routing/domain policy. Preserve existing candidate order, timing, error classification and cleanup semantics unless the task explicitly changes them.
4. Add a Bilibili API, local preference, socket or HTTP implementation in `data`; expose a feature-owned model or port instead of letting Bili DTOs escape to UI or platform code.
5. Add DLNA SOAP/SSDP/HTTP compatibility in `platform/dlna`; add the resulting application behaviour through `DlnaCastListenerAdapter` and cast/playback coordinators.
6. Treat cast replacement, user stop, DLNA Stop, natural end, playback error, and Activity destruction as distinct lifecycle paths. Reuse the established coordinator/host cleanup entry point; do not copy a partial cleanup sequence.
7. A new coroutine, listener, socket, timer or native resource needs an explicit owner and cancellation/release path in the same feature or adapter that creates it.

### Dependency and behaviour invariants

- `core` is independent of `data`, `feature`, `platform`, and `ui`.
- `platform/player` accepts stable playback contracts and emits stable events. It does not import Bilibili DTOs, UI models, or feature application classes.
- Feature application code coordinates feature models and ports. It does not make UI state the source of business truth or call player SDKs directly.
- `data` implements external protocols and persistence. It does not depend on UI or feature application/presentation code.
- Do not use a `typealias`, fully qualified name, callback bundle, or an additional `Host` method to hide a forbidden dependency. Introduce a narrow feature-owned model or port instead.
- Bugs are fixed at the boundary that owns the violated invariant. Establish the full request/event/state path before changing code; do not add a compensating UI, timer, or null-check patch in a downstream layer.
- Behaviour-sensitive values include API inputs, headers, cookies, route order, recovery thresholds, timeouts, event ordering, cleanup order, DLNA wire responses, persisted data, logging schema, and user-visible text. Preserve them unless the request explicitly changes them.

### Required checks

- Run `:app:testDebugUnitTest` for logic, mapping, lifecycle, routing, or architecture changes.
- Run `:app:assembleDebug` for Android, DI/composition, resource, manifest, player, or UI changes.
- Run both, plus the relevant focused test, when moving a boundary or changing playback/cast/danmaku behaviour.
- Keep `LayerDependencyTest` passing; extend its rule when introducing a new boundary that could regress.
