# Feature Services, Data and Diagnostics

## Data boundary

`data` contains Bilibili HTTP/WebSocket/API parsing, local preferences and stores. It maps external DTOs to stable models such as `PlaybackRequest`, feature-owned domain models and feature ports. External URLs, headers, cookies, request order, fallback order and API error semantics are behavior contracts; preserve them unless explicitly changed.

Add a new provider/API capability by:

1. adding the protocol implementation or mapper under `data`,
2. exposing a feature-owned port/model that contains only what the feature needs,
3. assembling the concrete dependency in `CapyCastContainer`, and
4. testing mapping and error behavior at the boundary.

Do not return a Bilibili DTO to UI, platform player or unrelated feature packages.

## Account, update and history

- `feature/account/AccountLoginCoordinator` owns QR login/logout workflow and account projection. `data/auth` supplies token persistence and Bilibili account adapters; `platform/qr` generates the QR graphic.
- `feature/update/UpdateCoordinator` owns update workflow state and user commands. Release retrieval/preferences are feature/data collaborators; Android download, verification and install details belong to platform installer services.
- `feature/history/PlaybackHistoryCoordinator` owns history sessions, periodic reporting, retry outbox flushing and live heartbeat. Preserve its account switch, pause, finish and disable-sync handling rather than adding direct reporting at playback call sites.

All three are constructed by `CapyCastContainer`. New features crossing them should use their public workflow methods or an explicit narrow port, not reach into stores from UI or playback code.

## Diagnostics

`core/diagnostics` is the shared diagnostic boundary. Use `LogHub`, `LogRedaction` and `PlaybackDiagnostics` rather than raw sensitive values. Keep diagnostic event names and serialized fields stable when modifying a behavior that operators already troubleshoot through logs.

For a hard-to-reproduce asynchronous defect, record the boundary transitions and identifiers needed to distinguish stale callbacks, state replacement and resource lifecycle. Do not log cookies, tokens, full URLs, NVA payloads, user text or raw account data.
