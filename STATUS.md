# JMedia build status

Date: 2026-10-01
Module: `JMedia/com.playdeca.JMedia` (com.playdeca.jmedia:JMedia 1.16.1, Quarkus 3.34.1, Java release 25)

## Result: BUILD SUCCESS (uber-jar produced) + verified smoke run

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

Note: the project's pinned `maven-surefire-plugin:2.17` (inherited from the Quarkus BOM)
cannot discover JUnit 5 tests — it silently runs 0 of them. Running the JUnit Platform
provider directly is what actually executes them:

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn -B org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test
```

Results: 43 tests, 3 failures, all three in `SmartNamingServiceTest`
(`testVolExtrasNotMistakenForSeason`, `testFamilyGuyCollectionVolExtras`,
`testTPBSxxXepSpinoff`). These are **pre-existing** — confirmed by re-running that class
against a stashed working tree (same 3 failures on `f825468` before any of these changes).
Every subtitle/Xtream/HLS-related test passes.

Runtime smoke run (uber-jar, port 8191, throwaway `user.home`) against a synthetic library
holding one series episode plus an `.en.srt` sidecar:

- `get_series_info` → `container_extension: m3u8`, `direct_source: .../series/admin/.../1.m3u8`
- `GET /series/admin/.../1.m3u8` → `307` to `/api/hls/master/vid-1-...m3u8`
- `GET /series/admin/.../1.mkv` (native ext) → `307` to the same HLS master path
- master playlist contained:
  `#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",AUTOSELECT=YES,DEFAULT=YES,FORCED=NO,URI="/api/hls/playlist/.../sub_0.m3u8"`
  and `#EXT-X-STREAM-INF:...,SUBTITLES="subs"`
- progressive path still intact: `GET /movie/.../2.mkv` → `200`, `1500000` bytes,
  `video/x-matroska`; with `Range: bytes=0-99` → `206`, `100` bytes
- `GET /api/video/subtitles/1/local-files` returned the resolved absolute sidecar path

Caveat on the smoke run: **ffmpeg/ffprobe are not installed on this machine**, so
`FFmpegDiscoveryService` logged "FFmpeg not found" and the encoder plus the WebVTT
segmenter processes never started. The master playlist and the `SUBTITLES` group were still
generated correctly (that logic runs in Java, not ffmpeg) and `sub_0.m3u8` existed, but the
`.vtt` segments inside it were empty. Subtitle payload generation is therefore **not**
end-to-end proven here and needs a re-check on a host with ffmpeg.

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
5. Tests were skipped by design (`-DskipTests`); no test run was performed.
