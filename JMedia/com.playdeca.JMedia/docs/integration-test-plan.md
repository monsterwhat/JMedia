# Integration test plan

Status: plan only. The 314 tests in `src/test/java` are unit tests.
Date: 2026-11-22

## 1. What the current suite proves

`mvn -o test` runs everything below without a database, network, or CDI
container. That is deliberate: it is fast, deterministic, and runs on any
machine.

| Area | Test | What it pins |
|---|---|---|
| DJ fade policy | `DjTransitionFadePolicyTest` | 1-2 s normally, <= 6 s only with a tempo nudge, never < 1 s |
| DJ scoring | `DjScoringTest`, `DjScoringKeyTest` | phrase/exit/entry bands, energy, Camelot mapping |
| Queue advance | `PlaybackQueueControllerAdvanceTest`, `...GenreTest` | depth guard, genre threshold |
| Naming | `SmartNamingServiceTest`, `SiblingInferenceTest` | season/episode parsing, sibling fill |
| HLS | `HlsServiceTest`, `HlsServiceCommandTest`, `HlsServiceSessionCleanupTest` | ffmpeg argument construction, session cleanup |
| Auth bootstrap | `BootstrapCredentialsTest`, `UserPasswordStorageTest` | generated password entropy, bcrypt storage, must-change semantics |
| DJ transition payload | `PlaybackStateSerializationTest` | the planned fade and tempo nudge survive JSON serialization |
| Installers | `RemoteScriptPolicyTest` | no unpinned remote script, https only, opt-in |
| Xtream ids | `XtreamIdsTest`, `LiveChannelGroupsTest` | canonical season keys, category derivation |

## 2. What it cannot prove

These paths need a running process, a database, or an external service. None of
them is covered today:

1. Hibernate can add the new nullable columns to a **populated** `app_user`
   table and to `playback_state` (the must-change flag, the planned fade).
2. A fresh install reaches a one-time admin login and blocks everything else.
3. `POST /api/auth/change-password` end to end, including the filter lifting.
4. HLS session lifecycle against real ffmpeg processes.
5. Xtream responses against a real client (BlueiPTV on a TV).
6. Uber-jar startup, argument handling, and shutdown.
7. Multi-profile isolation (two profiles playing at once).
8. Sync exchange against a second instance.

Two of these now have a harness outside Maven: `JMedia/scripts/smoke-jar.sh`
(covered 2 and 6 in CI) and `JMedia/scripts/dj-fade-check.mjs` (DJ fade
execution, including the stale-clock fixture). Neither replaces items 3-5 or 7-8.

## 2a. Implemented (packaged runtime)

`JMedia/scripts/it/` runs against the built uber-jar in the
`Integration (packaged runtime)` CI job:

| Test | Covers item | Assertions |
| --- | --- | --- |
| `it-bootstrap.sh` | 2, and 3 partially | 19 checks: no shipped default works, generated password accepted once, block is `PASSWORD_CHANGE_REQUIRED` on API and a 307 on pages, policy rejects short/wrong/reused, change unblocks, restart kills the old password |
| `it-migration.sh` | 1 | 14 checks: a 1.46.4 database opens under the current jar, the column is added, the legacy admin is healed, no ERROR line, the new password survives a restart |
| `it-sync.sh` | 8 partially | 16 checks: no key, guessed key and session-only are all 401; a generated key works, is persisted, and a wrong key still fails |
| `it-hls.sh` | 4 | 11 checks: ffmpeg-produced clip is scanned, a session is created, the master playlist is an m3u8, a segment is produced, destroy makes the playlist 404, unknown session 404 |

Still open: 5 (Xtream against a real client), 7 (multi-profile isolation), and
the full sync exchange against a second instance.

## 3. Proposed harness

Constraint from `dj-mode-plan.md` (L-D1): no new **runtime** dependencies. Test
scope dependencies are acceptable, but they must stay in the `test` scope.

### 3.1 Uber-jar smoke test (highest value, cheapest)

A shell script that starts the packaged jar on a free port with a temporary data
directory, waits for `/login.html`, checks that `/api/music/playback/state/1`
returns 401 without a cookie, then shuts down. Run it in CI on `ubuntu-latest`
after `mvn package`.

```
scripts/smoke-jar.sh target/JMedia-runner.jar
```

Acceptance: exits 0 in under 90 s, and the log contains no `ERROR` line during
startup.

Implemented in `JMedia/scripts/smoke-jar.sh`; it redirects `user.home` and the
log path into a temp directory, so the real `~/.jmedia` is never read or written.
It must not run on the developer's machine.

### 3.2 Bootstrap flow test

Same jar, but with an empty data directory:

1. Read the generated password from the startup log (the `AdminUserService`
   warning line).
2. Log in, assert `mustChangePassword` is true.
3. Assert `GET /api/users` is 403 `PASSWORD_CHANGE_REQUIRED`.
4. Change the password, assert 200, then assert `GET /api/users` is 200.
5. Restart the jar and assert the old password is rejected.

### 3.3 Migration test

Check out the previous release tag, run it against a fixture H2 file with users
and playback state, stop it, then start the new jar against the same file.
Assert: no startup exception, the admin still logs in with its existing
password, and the new columns exist and are null.

### 3.4 HLS and transcoding

Start the jar with a small fixture video, request `/api/hls/master/...`, and
assert a playlist and at least one media segment are produced, then assert the
session directory is removed after the cleanup window.

### 3.5 Sync

Start two jars on different ports, configure each with the other's sync key,
trigger an exchange, and assert rows appear once and are idempotent on a second
run.

## 4. Deliberately out of scope

- Browser automation. Playback, fades and mobile layouts are signed off against
  the written matrix in `dj-mode-plan.md`, not with headless browsers.
- Live TMDb/OMDb/TheAudioDB calls. Fixtures only, or the tests become flaky and
  slow.
- H2 file locking. The migration test must stop the first process before the
  second starts.

## 5. Order

1. `3.1` (smoke) — catches packaging and startup regressions on every push.
2. `3.2` (bootstrap) — the highest-risk path for a lockout.
3. `3.3` (migration) — schema changes ship often in this project.
4. `3.4`, `3.5` — expensive; run nightly, not per push.
