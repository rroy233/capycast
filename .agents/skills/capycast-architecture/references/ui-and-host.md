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
