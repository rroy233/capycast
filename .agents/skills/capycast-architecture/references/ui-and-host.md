# UI and Android Host Architecture

## UI flow

Compose screens in `ui/tv` render `TvUiState` and emit `TvActions`. `TvActionDispatcher` performs UI-only state changes such as navigation, HUD/panel visibility and confirmation overlays. Operations that invoke a feature, persistence or platform capability go through `TvActionHost` to the Android host and then the owning coordinator.

When adding an interaction:

1. add a user-oriented action to `TvActions`,
2. let `TvActionDispatcher` own it when it only changes UI projection,
3. otherwise add the matching narrow command to `TvActionHost`,
4. delegate from the Activity host to the feature owner, and
5. update the feature’s projected UI fields after the authoritative state transition.

Do not call a repository, player engine or DLNA renderer from a composable.

## MainActivity boundary

`MainActivity` keeps Android-specific work: lifecycle methods, `VLCVideoLayout`/`PlayerView`/`DanmakuView`, Compose setup, Window HDR flags, focus/back handling, system resource release and dependency composition. It also implements existing narrow host ports while the app remains one module.

An Activity method should be a thin adapter. If an addition requires session state, network/error policy, parsing, route selection, retry sequencing or protocol semantics, put it in a feature coordinator or data/platform adapter and expose the smallest host operation needed.

## State discipline

`TvUiState` is a rendered projection, not a general feature database. A coordinator’s session/model remains authoritative for playback, cast, route recovery, history and danmaku lifecycle. Update UI state from the completed feature transition; do not infer or recreate business state from a UI field.

Preserve focus order, back behavior, panel state, Toast/error text, default values and seek-preview timing unless the product request explicitly changes them. These are observable TV interaction contracts.

## Long documents, content and overlays

- A screen, modal or overlay owns only layout, local focus and input handling. It consumes a document but never owns the document body, persistence policy or feature state.
- Put static long-form user-facing content with an independent review lifecycle, such as legal agreements and privacy policies, in `ui/legal/*`. Keep this content free of Compose, Android, actions, state holders and storage dependencies. When acknowledgement or policy updates need tracking, give the document an explicit version identifier in the same package.
- Put reusable Markdown parsing and TV document rendering in `ui/tv/document/*`. The renderer accepts document blocks and focus targets; it does not decide which document is shown, mutate `TvUiState`, or invoke a host.
- Persisted acknowledgement, consent and document-version state belongs in the owning preference or feature model and is projected through `TvUiState`. A document composable must never become that state’s source of truth.
- Extract a concern from a presentation file when it gains an independent lifecycle, reuse site, review path or state owner. Do not turn overlay files into catch-all containers.
- `TvModalDialog` owns the Compose `Dialog` window, platform scrim, Back dismissal and focus isolation. Its visible card uses `TvModalCardKind` / `Modifier.tvModalCard`. Keep cards within the shared TV-safe size bands, including document and alert dialogs.
- Render only `TvUiState.topOverlay`. Modal UI uses `TvModalDialog`, so Compose owns the dialog window, system Back handling and focus isolation; do not emulate a modal by layering a focusable full-screen `Box` over a page.
- A `Dialog` has its own Android window and focus owner. Leave the underlying Compose focus unchanged; when the dialog closes, the Activity window resumes with its retained focus. Use `focusRestorer(fallback)` on lazy lists for focus re-entry during in-window navigation. Use `saveFocusedChild()` / `restoreFocusedChild()` only for a real screen transition that removes and later recreates the focus group, as prescribed by the Compose focus-restoration guide.
