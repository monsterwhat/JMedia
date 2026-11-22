# Access control matrix

Who may read or change what, and the test that proves it. "Owner" means the
account whose id is on the profile; admins bypass every ownership check.

| Resource | Read | Write | Enforced in | Proven by |
| --- | --- | --- | --- | --- |
| Sessions, login, logout | anyone | anyone (credentials required) | `EnhancedAuthAPI` | `it-bootstrap.sh` |
| Your own profile list | owner | - | `ProfileAPI` (queries by current user) | `it-profiles.sh` |
| Playback state, queue, volume, position | owner | owner | `PlaybackAPI` resolves the profile from the session; the path id is ignored | `it-profiles.sh` ("a foreign profile id in the path is ignored") |
| Per-profile settings (sidebar, HLS, library paths) | owner or admin | owner or admin | `SettingsApi.checkProfileOwnerOrAdmin` | `it-profiles.sh` (403 for another profile) |
| User management (`/api/users`) | admin | admin | `UserManagementAPI.isAdmin` | `it-profiles.sh` (403 for a normal user) |
| Private playlists | owner | owner or admin | `PlaylistAccessFilter` + `MusicPlaylistApi.createPlaylist` | `it-profiles.sh` (cross-account list/create/rename/delete are 403) |
| Global (shared) playlists | any signed-in user | owner or admin | `PlaylistAccessFilter` | see the rule below |
| Peer sync (`/api/sync/exchange`, `/api/sync/ping`) | any instance holding the key | same | `JMediaAuthFilter` + `SyncExchangeAPI.validateApiKey`; `AuthGateFilter` exempts only these two | `it-sync-exchange.sh`, `it-sync.sh` |
| Sync settings, peers, trigger, logs | admin | admin | `SyncAPI` admin checks | `it-sync.sh` (a key cannot read settings or trigger) |
| Media streams (music, video, HLS, Xtream) | per endpoint rules | - | `JMediaAuthFilter` streaming list | `it-hls.sh` (session destroy, unknown session) |

## The global-playlist rule (open decision)

Every playlist created through the API is stored with `isGlobal = true`, and
listing includes `profileId = me OR isGlobal = true`. That makes playlists shared
by default.

`PlaylistService.delete` decides whether to remove the row by asking
`settingsService.getActiveProfile()` — a single **globally active profile** — plus
`isMainProfileActive()`, rather than the caller of the request. Whether a delete
takes effect therefore depends on unrelated global state: the same request can
remove the row or silently leave it, and in both cases the API answers
`200 deleted`.

Measured during `it-profiles.sh`: the second account's delete of its own global
playlist removed the row, because the main profile was considered active at that
moment. Reordering the same calls changes the outcome. For that reason the test
does not assert either result, and the service now logs every refusal with the
playlist and profile ids instead of failing silently.

This is worth fixing as a unit:

1. Pass the caller's profile id (or user) into the service, exactly like the
   filter does, and decide from that.
2. Choose the rule: owner deletes their own playlist regardless of `isGlobal`, or
   only the main profile/admin may delete a shared one.
3. Make the API report the outcome instead of always answering `200 deleted`.

Until then, treat playlist deletion by non-admin users as unreliable.
