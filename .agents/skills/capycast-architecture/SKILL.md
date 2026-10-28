# CapyCast Architecture Skill

Use this skill before changing application behaviour, adding a feature, or fixing a bug that crosses a package boundary. Its purpose is to place a change at the existing ownership boundary and preserve the current user-visible behaviour.

## Start here

1. Read the `Current Architecture` section in `AGENTS.md`.
2. Read [architecture-map.md](references/architecture-map.md) for the whole system and dependency rules.
3. Select exactly the references that match the requested change:

| Task | Read |
|---|---|
| Player state, seek, speed, audio/quality, VOD/Live recovery, HDR, DASH or DRM | [playback.md](references/playback.md) |
| Phone cast, DLNA, NVA, recent casts, renderer protocol or session closing | [cast-and-dlna.md](references/cast-and-dlna.md) |
| Danmaku, timing, socket, DFM rendering or overlay layout | [danmaku.md](references/danmaku.md) |
| Login, update, history reporting, preferences, data API, storage or diagnostics | [services-and-data.md](references/services-and-data.md) |
| Compose screen, focus, HUD, panel, navigation or UI state | [ui-and-host.md](references/ui-and-host.md) |

Read a second reference only when the real call path crosses that boundary. For example, a live-cast problem normally needs `cast-and-dlna.md`, `playback.md`, and `danmaku.md`; a pure settings-screen change only needs `ui-and-host.md` and, if state is persisted, `services-and-data.md`.

## Change procedure

1. Trace the current path from ingress to observable result: command/event → coordinator → port/model → adapter → state/projection.
2. Name the owner of the state, resource and cleanup path before editing.
3. Make the smallest change at that owner. Extend a narrow model or port when a boundary needs new data.
4. Preserve existing timing, ordering, error mapping and cleanup behaviour unless the request explicitly changes it.
5. Add a feature test for deterministic rules or mappings; add/extend an architecture rule when a new boundary creates a plausible dependency regression.
6. Compile and test according to `AGENTS.md`.

## Non-negotiable placement rules

- Do not put Bilibili DTOs, HTTP, persistence or Android player calls into `ui`, `feature/*/domain`, or `platform/player`.
- Do not add business behaviour to `MainActivity`, `DlnaRenderer`, VLC/Media3 SDK callbacks, or Compose functions when an existing coordinator owns that behaviour.
- Do not bypass `PlaybackEventRouter` for playback side effects, or bypass `PlaybackEngineHost` for backend selection and switching.
- Do not make a global UI state field the source of truth for a feature session. Maintain business state in its coordinator/session model, then project it to UI.
- Do not create another generic coordinator, manager, utility, or oversized Host as a shortcut. Extend the named feature owner or introduce a narrow port with one purpose.
