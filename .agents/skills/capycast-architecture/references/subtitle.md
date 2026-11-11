# Subtitle Architecture

## Pipeline

```text
CastSessionCoordinator.openPlayback (VOD only)
  → SubtitleController.open(content)
  → SubtitleRepository (data/bili/subtitle)
      → x/player/wbi/v2 (tracks, manual + AI)
      → subtitle_url JSON (cues)
  → SubtitleController commit gate
  → SubtitleRenderer (app/SubtitleOverlayHost) → TvUiState.subtitleLines
  → ui/tv/player/SubtitleOverlay (Compose)
```

`SubtitleSyncDriver` samples the player position every 100 ms while a session is open and forwards it to `SubtitleController.onPlayerTime`. Cue selection is a pure function of the position, so seeks settle within one sampling interval; when the player pauses, the position stops changing and nothing is re-rendered.

`CastSessionCoordinator` opens the session only for VOD; `PlaybackEventRouter` (end/failure) and `PlaybackSessionLifecycle.stopPlayback` (user stop, dismissed error, DLNA stop, destroy) close it. Live playback closes it too, since live has no subtitles.

## Data identity and session ownership

- `SubtitleContent(aid, cid, epId)` is the data identity; `SubtitleTrackKey(id, idStr, lan, url)` identifies a track inside it. Never cache tracks or cues by `cid`, `lan`, or the URL alone.
- `SubtitleController.open()` is the only session-replacement primitive: it retires the previous session, cancels its track/body jobs, resets the overlay and reloads tracks.
- An async result may touch the session, the host projection, or the renderer only when `activeSession === session`; a body result must additionally still match the selected `SubtitleTrackKey`.
- The repository is a bounded cross-session LRU keyed by content, so switching videos never requires clearing a cache.

## Manual versus AI subtitles

- `SubtitleOriginClassifier` maps `ai_status` (1 = exposure, 2 = assist), `ai_type`, and the `lan_doc` markers onto `SubtitleOrigin`. Data mapping must keep those server fields; do not re-derive origin from display strings in the UI.
- Every track from the response is kept and listed, AI included. `SubtitleTrackOrder` only orders them: manual first, then Chinese, then the preferred language.
- `SubtitleSelection` enables a manual track automatically; an AI track is only auto-enabled when the persisted `autoAiEnabled` setting is on.
- `SubtitleLabels` adds the `AI` marker once; the panel renders `SubtitleTrackOption.ai` and must not append its own suffix.

## Rendering and geometry

- `SubtitleRenderer` is the only sink. `app/SubtitleOverlayHost` converts it into UI state; `ui/tv/player/SubtitleOverlay` renders it with `BasicText`, two lines maximum, bottom inset as a ratio of the playback viewport.
- The overlay shares the playback viewport with the danmaku layer and never takes focus or input. Keep text and style decisions in Compose; keep cue selection in the application layer.
- Do not route Bilibili subtitles through Media3 subtitle tracks: the cues are out-of-manifest JSON, so they would lose the content identity, cache, and shared position source.

## Failure handling

- Track request, signature, or body failures set `SubtitleAvailability.UNAVAILABLE`, record `subtitle.error`, and leave playback untouched.
- An untrusted `subtitle_url` (host outside `hdslb.com`/`bilibili.com` or path without `subtitle`) is dropped while mapping; never fetch it.
- A response without a `subtitle` section is `EMPTY`, not an error.

## Diagnostics

`PlaybackDiagnostics.attribute` records `subtitle.state`, `subtitle.tracks`, `subtitle.manual`, `subtitle.ai`, `subtitle.selected`, `subtitle.cues`, `subtitle.lines`, `subtitle.offsetMs`, and `subtitle.error`. Logs carry generation, `aid`/`cid`/`ep`, track `lan`, and cue counts; never log cue text.
