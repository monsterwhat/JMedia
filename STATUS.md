# JMedia build status

Date: 2026-10-01
Module: `JMedia/com.playdeca.JMedia` (com.playdeca.jmedia:JMedia 1.16.1, Quarkus 3.34.1, Java release 25)

## Result: BUILD SUCCESS (uber-jar produced) + tests executable + VTT payload proven

Latest work (2026-10-01, after `415cbe5`):

1. `mvn test` was silently running **0 tests** (`surefire:2.17` from Maven's default lifecycle
   bindings cannot drive the JUnit Platform). Now pinned to 3.5.4; **50 tests execute,
   3 known pre-existing failures**.
2. The 1.16.1 subtitle fix was only verified to playlist level because ffmpeg was missing.
   With ffmpeg 8.0.1 present, the WebVTT payload is **proven end-to-end**: `get_series_info`
   → m3u8 → master `SUBTITLES` group → non-empty `sub_0.m3u8` → real `.vtt` segments served
   with correct cue timings.
3. Two pre-existing `HlsService` bugs surfaced while proving #2 (intermittent JTA commit
   failure, and cleanup destroying sessions whose ffmpeg already exited). Documented, **not
   fixed** — see "Two pre-existing bugs found" below.

`mvn -DskipTests package -B` succeeded in 4m22s after dependency download (and in ~11s on
warm runs).

Command used:

```bash
cd /home/alvaro/Github/JMedia/JMedia/com.playdeca.JMedia
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn -DskipTests package -B
```

Toolchain: JDK 25.0.4.1 at `/usr/lib/jvm/java-25-openjdk-amd64`, Maven 3.9.12.

## Jar path

```
/home/alvaro/Github/JMedia/JMedia/com.playdeca.JMedia/target/JMedia-runner.jar   (~68 MiB, uber-jar, runnable)
```

`quarkus.package.jar.type=uber-jar` is set in pom.xml, so packaging produces a single
runnable jar. The intermediate thin jar is kept as `target/JMedia.jar.original`.
No `target/quarkus-app/` directory is produced (that only exists for the default
fast-jar packaging).

## How to run

```bash
cd /home/alvaro/Github/JMedia/JMedia/com.playdeca.JMedia
/usr/lib/jvm/java-25-openjdk-amd64/bin/java -jar target/JMedia-runner.jar
```

App listens on `http://0.0.0.0:8080` (see `src/main/resources/application.properties`).
First request to `/` redirects to `/login.html`; default users `admin` and `admin2`
are auto-created on first start and the log warns to change the default password.

Useful overrides:

```bash
# different port
java -Dquarkus.http.port=8181 -jar target/JMedia-runner.jar
# isolated data dir (H2 files + logs normally live in ~/.jmedia)
java -Duser.home=/tmp/jmedia-home -jar target/JMedia-runner.jar
```

Dev mode (per `dev.sh` / `dev.bat`):

```bash
./dev.sh          # mvn quarkus:dev
```

## Smoke run verification (pass)

Ran the uber-jar with `-Duser.home=/tmp/opencode/jmedia-home` and port 8181 (port 8080
was already occupied by another local JMedia process, so the real 8080 path was not
exercised, only the override).

- Startup: `JMedia 1.16.0 on JVM (powered by Quarkus 3.34.1) started in 4.966s. Listening on: http://0.0.0.0:8181`
  (recorded on the 1.16.0 build; the 1.16.1 Xtream-subtitle smoke run below used port 8191
  and reported the same clean startup)
- H2 datasources created and schema migrated (settings / music / video), default admin users created.
- HTTP checks:
  - `GET /` -> 307 redirect to `/login.html?...` (auth filter working as designed)
  - `GET /login.html` -> 200 (6244 bytes)
  - `GET /q/health` -> 200, `{"status":"UP"}` with all three DB connections UP
  - `GET /q/health/ready` -> 200, `GET /q/health/live` -> 200
- Clean shutdown on SIGTERM.

## Latest change (1.16.1): Xtream series episodes now carry subtitles

### Problem

TV episodes reached Xtream IPTV clients with no subtitles at all. Two causes stacked:

1. `XtreamCodesAPI.getSeriesInfo` advertised each episode with its **native container**
   (`mkv`/`mp4`), while `getVodInfo` hardcodes `m3u8` for movies.
2. `XtreamStreamAPI.streamVideoWithHls` only built an `HlsService` session when the
   requested extension was literally `m3u8`. A native-container episode request therefore
   went to `proxyLocalVideo`, which streams the raw file bytes with **no `SUBTITLES` group**
   in any playlist. Progressive byte-serving cannot advertise external or embedded
   subtitles at all, so Parakeet AI sidecars (`fullPath` + `isAiGenerated`) never appeared.

### Fix

- `XtreamCodesAPI` (~:563): episodes always advertise `container_extension = m3u8` and a
  matching `direct_source`, mirroring the movie behaviour in `getVodInfo`.
- `XtreamStreamAPI`: extracted `static boolean prefersHls(String type, String ext)` and
  made it return true for `type == "series"` regardless of the requested extension.
  Series episodes now always get an HLS session, so `HlsService.getMasterPlaylist` emits
  `#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs"` entries plus `,SUBTITLES="subs"` on the
  variant line. **Progressive fallback is untouched**: if `hlsService.createSession`
  throws, the existing catch still falls through to `proxyLocalVideo`, and movie requests
  for `mkv`/`mp4` still stream progressively exactly as before.
- `SubtitleTrackService`: added `resolveVideoAbsolutePath` (same shape as the existing
  helper at `ParakeetService:751`) and routed `refreshSubtitleTracks`, `scanAllSubtitleFiles`
  and the `uploadForVideo` target directory through it. Video paths are stored **relative**
  to the configured library root, so bare `Paths.get(video.path)` missed the file and sidecar
  discovery silently returned nothing. Each call site now guards the `null`/no-parent case.
- `ParakeetService:208`: transcribe-by-video path now uses `resolveVideoAbsolutePath(video.path)`
  like the translate-by-track path at `:449` already did.
- `HlsService.createSubtitleStreams`: sidecar `track.fullPath` is passed through
  `resolveVideoPath` before being handed to ffmpeg, so older rows holding a relative
  `fullPath` still segment instead of failing to launch.

### Verification

`mvn -DskipTests package -B` (JDK 25, JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64): BUILD SUCCESS,
uber-jar at `target/JMedia-runner.jar`. New test `API.Rest.XtreamStreamHlsPreferenceTest`
(5 tests) covers the series/movie routing decision and the sidecar servability gates.

**`mvn test` now works natively — no CLI workaround needed.** Previously it reported
`Tests run: 0` and still exited BUILD SUCCESS. The `2.17` surefire pin was *not* in this
repo and *not* inherited from the Quarkus BOM (the BOM does not manage surefire). It comes
from `maven-core`'s `META-INF/plexus/default-bindings.xml`, the default lifecycle binding for
`jar` packaging in this Maven 3.9.12 build. Surefire 2.17 predates the JUnit Platform and
only ships the JUnit 3/4 provider, so it loaded the test classes but executed none of the
`@Test` methods. `pom.xml` now pins `maven-surefire-plugin` 3.5.4 — the first line shipping
the `surefire-junit-platform` provider that is compatible with the JUnit 6 platform
(`junit-jupiter` 6.0.3) that `quarkus-bom` 3.34.1 manages — plus `failIfNoTests=true` so any
future silent 0-test regression fails the build instead of passing green.

Results: **50 tests, 3 failures**, all three in `SmartNamingServiceTest`
(`testVolExtrasNotMistakenForSeason`, `testFamilyGuyCollectionVolExtras`,
`testTPBSxxXepSpinoff`).

| Test class | run | fail |
|---|---|---|
| `API.Rest.XtreamStreamHlsPreferenceTest` | 5 | 0 |
| `Services.IntroDbServiceTest` | 10 | 0 |
| `Services.PlaybackDataHlsFlagTest` | 3 | 0 |
| `Services.SmartNamingServiceTest` | 25 | 3 |
| `Utils.FragmentedMp4SeekerTest` | 7 | 0 |

(An earlier revision of this file claimed 43 tests via
`mvn org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test`. Re-measured, 3.2.5 also
reports 50 — the 43 figure was simply wrong.)

The 3 `SmartNamingServiceTest` failures are **pre-existing** and unrelated: the surefire
commit touches no Java source. Confirmed identical on pristine `415cbe5` sources (same three
tests, same lines 209/358/382, same expected/actual):

```
testTPBSxxXepSpinoff:209                expected 'extra'  but was 'special'
testVolExtrasNotMistakenForSeason:358   expected null      but was 2
testFamilyGuyCollectionVolExtras:382    expected null      but was 1
```

They are now visible for the first time; left as-is per scope. Every
subtitle/Xtream/HLS-related test passes.

### VTT payload now proven end-to-end (ffmpeg 8.0.1 present)

The previous caveat is **closed**. ffmpeg/ffprobe 8.0.1 are now installed
(`/usr/bin/ffmpeg`, `ffprobe`), `FFmpegDiscoveryService` no longer logs "FFmpeg not found",
and the WebVTT segmenter runs for real. Re-ran the synthetic-library smoke
(uber-jar, port 8192, throwaway `user.home`):

- Library: `Smoke Show/Season 01/Smoke Show - S01E01.mkv` (12.0 s, `testsrc2` + sine,
  x264 `-g 50 -keyint_min 50 -sc_threshold 0`) plus `Smoke Show - S01E01.en.srt`
  with three cues carrying the marker text `VTTPROOF cue one|two|three`.
- `POST /api/settings/1/video-library-path` → `POST /api/video/scan?mode=full` →
  1 video created (`seriesTitle: Smoke Show`, `seasonNumber: 1`, `episodeNumber: 1`).
- `POST /api/video/subtitles/1/add-local` → track id 1, `languageName: English`, `format: srt`.
- `get_series_info` → `container_extension: m3u8`,
  `direct_source: http://localhost:8192/series/admin/<pw>/1.m3u8`. Confirmed.

Full chain over HTTP (session from `POST /api/hls/session/1`):

| Request | Status | Type | Bytes |
|---|---|---|---|
| `GET /api/hls/master/{sid}.m3u8` | 200 | `application/vnd.apple.mpegurl` | 409 |
| `GET` master `URI="...sub_0.m3u8"` | 200 | `application/vnd.apple.mpegurl` | 274 |
| `GET .../sub_0/sub_0_0000.vtt` | 200 | `text/vtt` | 91 |
| `GET .../sub_0/sub_0_0001.vtt` | 200 | `text/vtt` | 51 |
| `GET /api/hls/playlist/{sid}/video_stream.m3u8` | 200 | `application/vnd.apple.mpegurl` | 395 |
| `GET .../video_stream/video_stream_0000.m4s` | 200 | `video/iso.segment` | 641051 |
| `GET .../video_stream/init.mp4` | 200 | `video/mp4` | 1334 |

`sub_0.m3u8` is **not** an empty playlist — it lists two real segments:

```
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-TARGETDURATION:8
#EXTINF:7.900000,
/api/hls/media/{sid}/sub_0/sub_0_0000.vtt
#EXTINF:3.600000,
/api/hls/media/{sid}/sub_0/sub_0_0001.vtt
#EXT-X-ENDLIST
```

and the served payloads are genuine WebVTT — SRT comma timings converted to WebVTT dot
timings, all three cues and their exact timings preserved:

```
WEBVTT

00:00.500 --> 00:03.800
VTTPROOF cue one

00:04.200 --> 00:07.900
VTTPROOF cue two
```
```
WEBVTT

00:08.300 --> 00:11.900
VTTPROOF cue three
```

Served bytes are identical (sha256) to what ffmpeg wrote into
`<videoLibraryPath>/hls/<sessionId>/`. Across every session produced during the smoke run:
**37 `sub_0.m3u8` files, 74 `.vtt` files, 0 empty, 0 missing the `WEBVTT` header**, and only
two distinct sha256 values (one per segment index) — deterministic on every run.

The video variant uses fMP4 with `#EXT-X-MAP` + `#EXT-X-DISCONTINUITY` and a 6 s target
duration; subtitles use a 4 s `-segment_time` from a 12 s source, hence 2 segments each.

Progressive fallback still intact: `GET /series/admin/<pw>/1.mkv` → `200`,
`1288928` bytes, `video/x-matroska`; with `Range: bytes=0-99` → `206`, `100` bytes.

## Two pre-existing bugs found while proving the above (NOT fixed — out of scope)

Both are in `Services/HlsService` and predate `415cbe5` (which changed no transaction or
cleanup code). They do not affect subtitle *generation* — every subtitle segmenter that
started produced correct output — but they degrade the Xtream *entry point*.

### 1. `createSession` fails its JTA commit intermittently → HLS silently degrades to progressive

`HlsService.createSession` is `@Transactional`, but `launchAndMonitorVariantEncoder` does
`Thread.sleep(2000)` inside it (`HlsService.java:284`) while holding the enlisted JDBC
connection, after `subtitleTrackService.refreshSubtitleTracks` has already run in the same
transaction. At commit/rollback Narayana/Agroal then fails:

```
ARJUNA016045: attempted rollback of < ... io.agroal.narayana.LocalXAResource ... >
failed with exception code XAException.XAER_RMERR:
Error trying to transactionRollback local transaction: Enlisted connection used without active transaction
```

which surfaces through `XtreamStreamAPI.streamVideoWithHls` as
`HLS session failed for videoId=1, falling back to progressive stream: Unable to acquire
JDBC Connection`, so `GET /series/admin/<pw>/1.m3u8` returns `200` raw video bytes instead of
the `307` to the master playlist — **losing subtitles exactly like the original bug**.
Measured 8 of 9 attempts failing, on both the series and the movie path (so it is not
series-specific). `POST /api/hls/session/1`, which calls the same `createSession` without the
preceding `XtreamSessionService.startSession` transaction, succeeded every time.

Fix direction: do not hold a transaction across the 2 s encoder warm-up — resolve the
session's DB state in a short transaction, then start ffmpeg outside it.

### 2. Session cleanup destroys a session as soon as its ffmpeg processes exit

`cleanupAbandonedSessions` (`HlsService.java:1731`) removes any session where
`session.processes.values().stream().noneMatch(Process::isAlive)`. Copy-mode HLS and the
WebVTT segmenter on a short clip both exit in well under a second — long before the Java
monitor thread's 2 s sleep returns — so the very next sweep destroys a session whose
playlist and segments are already complete and valid:

```
Xtream session ended: sessionId=ffe21e96... duration=0s reason=timeout
Destroyed HLS session vid-1-xtream:ffe21e96...-anon-2e035574
Cleaned up 1 abandoned HLS sessions
```

The master then returns `404` even though the redirect was correctly issued. Reproduced by
capturing a real `307 Location:` and fetching it ~1 s later. The existing 120 s
`recentlyRestarted` grace does not cover first playback.

Fix direction: treat "all processes exited but the playlist has `#EXT-X-ENDLIST`" as
finished-but-servable (keep it until the idle TTL), rather than as abandoned.

Both were left untouched to keep this change set scoped to the two requested tasks.

## Notes / observations

1. First `mvn package` attempt failed with `Unknown host jitpack.io: Temporary failure in
   name resolution` and cascading "version is missing" errors for every dependency, because
   `quarkus-bom:pom:3.34.1` could not be fetched from any repo. The failure was a transient
   DNS problem, not a pom problem: re-running with `-U` (and
   `MAVEN_OPTS=-Djava.net.preferIPv4Stack=true`) resolved everything and built fine. If a
   build ever fails this way again, just re-run; `-U` forces a re-check of the cached
   `*.lastUpdated` failure markers.
2. The pre-warmed `~/.m2` did not actually contain the versions this project needs
   (it had quarkus-bom 3.27.1...3.39.5 and quarkus-maven-plugin 3.38.3, but the missing
   3.34.1 BOM / 3.34.0 plugin dirs held only `.lastUpdated` failure files). 579 artifacts were
   downloaded from Maven Central during this build, so the first build after a cold
   `~/.m2` needs network access.
3. Non-fatal build warnings:
   - `HibernateOrmProcessor: Could not find a suitable persistence unit for model classes: Utils.GzipJsonConverter`
   - `UberJarBuilder: jakarta.authorization-api / jakarta.authentication-api contain duplicate files: exclude-common.xml`
   - JVM warnings about `sun.misc.Unsafe` and `System::loadLibrary` (brotli4j) with
     "Use --enable-native-access=ALL-UNNAMED" — cosmetic on Java 25.
4. Runtime log shows `Error fetching releases: HTTP 301 Moved Permanently` (GitHub update
   check) — cosmetic, does not affect startup.
5. The packaging build skips tests by design (`-DskipTests`). Tests *were* run separately and
   are now green-executable via plain `mvn test` (50 tests, 3 known pre-existing failures) —
   see the verification section above. Previously they could not run at all.
6. `ffmpeg`/`ffprobe` 8.0.1 are installed on this host at `/usr/bin/ffmpeg`. The earlier
   "FFmpeg not found" caveat recorded in the 1.16.1 notes no longer applies, and the
   WebVTT payload is now verified end-to-end.
