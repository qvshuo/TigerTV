# TigerTV – Agent Notes

## Architecture

Android TV, Web/PWA and the native CLI are the maintained platforms. The SwiftUI macOS app and its bridge are archived. Desktop playback uses the Web/PWA.

Kotlin Multiplatform `core` remains the business implementation for Android TV and the Kotlin/Native CLI. Web uses Svelte 5, TypeScript, Vite, Tailwind CSS 4 and Video.js 10. Both runtimes follow `docs/contract.md` and shared fixtures. Native players and the Web media adapter own playback, with the HLS rule data shared in `shared/hls-ad-rules.json`.

- `core/`: models, aggregation, MacCMS/config networking, QuanX scanning, caches and history.
- `AndroidTV/`: Compose TV, Media3 and the in-process Core adapter.
- `web/`: static browser client and PWA shell. The browser directly fetches third-party API and media.
- `cli/`: native `tigertv` for macOS arm64 and Linux x86_64 / arm64.
- `macOS/` and `nativeBridge/`: archived SwiftUI and bridge code, omitted from maintained build/release validation.
- Authoritative contracts and fixtures: `docs/contract.md` and `docs/fixtures/`.
- Build and release notes: `docs/README.md`.

## Toolchain and validation

Kotlin/Compose/serialization plugins 2.4.21, Gradle 9.8.2, AGP 9.5.1, JDK 21, Android SDK 37. Xcode and macOS SDK 27.2 for the native CLI, Node.js 24+ for Web, and Go 1.27 for the optional Gateway.

```bash
./gradlew :core:jvmTest :core:macosArm64Test
./gradlew :cli:linkReleaseExecutableMacosArm64
python3 scripts/check-cli-contract.py cli/build/bin/macosArm64/releaseExecutable/tigertv.kexe
# Linux host: :cli:linkReleaseExecutableLinuxX64 / LinuxArm64; run the same CLI checker.
# Linux x86_64 host: ./gradlew :core:linuxX64Test
./AndroidTV/gradlew testDebugUnitTest assembleDebug
npm --prefix web ci
npm --prefix web run check
npm --prefix web test
npm --prefix web run build
```

CI uses the `xcode-27` arm64 runner and the Xcode 27.2 version alias, verifying Xcode and macOS SDK 27.2 without pinning a build number. The hosted alias may select an RC until the stable image is available. Android SDK may require `ANDROID_HOME`. Open `AndroidTV/` in Android Studio. Root Gradle builds core/CLI without an Android SDK; the independent Android build includes the same core sources. Both entries share `gradle/` via a symlink (Wrapper, Java 21 daemon criteria and plugin versions), use Foojay resolver 1.0.0 and configuration cache. Android core build outputs stay under `AndroidTV/build/core` to avoid cross-build conflicts. Keep dependency/plugin/Wrapper upgrade advisories enabled; do not suppress them to obtain a clean report. Kotlin 2.4.21 has a known Native bundle enum attribute deprecation on Gradle 9.8.2; the upstream fix is in Kotlin 2.5.0-Beta2, not the current stable release.

## Current model and interaction

- CLI search/quanx accept one keyword positional argument; use `--` before keywords beginning with `-`. Local config uses `--config`, and site selection uses `--site`.
- Search emits `{query, works}`; each work has `{id, title, display_source, sources}`. Each source includes its own `site` / `vod_id` and metadata.
- Aggregation matches whitespace-stripped/lowercased title + trimmed year. Display uses original source text. Empty titles remain isolated by source identity; year `0` means unknown.
- Detail emits metadata plus required `play_sources` and `download_sources`. Each line has `{id,name,episodes:[{name,url}]}`.
- Raw MacCMS line separator is `$$$`; item separator is `#`; first `$` splits name and URL. Preserve original group positions when aligning `*_from` names; discard empty items/groups only after alignment.
- GUI flow on Android TV and Web: search → work cards → detail → site/line/episode selection → player. Switching site/line cancels pending playback, resets episode selection and never auto-plays. Playback errors offer only retry and back; retry retains the selected resource, line and episode. TV shows only a default-focused Retry button and uses the remote Back key to exit. Back returns through detail and results.
- Detail order/text on Android TV and Web: current-source poster + overview → 资源 → 线路 → 选集 (no section counts). Keep line/episode headings mounted during loading, errors and empty responses; replace only their contents. Render overview content directly above the resource choices. Synopsis is always visible without disclosure controls. Both clients preserve the last loaded text overview and disable pending hero playback during source requests. TV keeps the selected resource focused at its current viewport position; late responses must not undo new directional navigation. Hero play resumes the last actually played episode (first episode if unavailable); TV focus on hero play scrolls to top. Detail poster uses only the selected source, unlike work-card covers. Search progress stays inside a fixed-size search button (arrow becomes spinner); Android TV disables the search button while searching and uses the same loading component for detail and playback. See `docs/contract.md`.

## API and network

- Search decodes only lightweight card metadata per page, skipping large content/playback fields; never retain full detail JSON across pages or parse a page twice. Search all configured sites concurrently. Search uses `ac=detail&wd=…&pg=…&pagesize=100`, follows pagecount (absent means one), stops on empty pages; max 100 pages. QX pages through all keyword matches and probes the first episode of each playback/download line.
- Fetch uses `ac=detail&ids=…` and requires a matching response ID.
- `code` must represent integer 1. Upstream numeric strings/exact float integers are accepted; fractions, null, booleans and overflow rejected. Source IDs must be positive.
- Config filtering: name contains `🎬`, no `_comment` key (even null), nonempty API, first exact API URL wins.
- Config priority: explicit source file → fresh cache → CDN (10s) → RAW (5s) → expired cache → error. Store raw config atomically, filter at read time.
- Preserve unrelated API query parameters; replace operation parameters, including within supported `url=` proxies.
- API/config/HTML/HLS text response cap 10 MiB; encode Unicode URL bytes without changing existing escapes or plus signs. Native product HTTP clients use the exact shared Safari User-Agent `Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15`, including API/config/HTML/HLS, covers, native players and the installer. Search/detail request budget 20s; HTML/HLS 10s; connection budget at most 5s and socket idle budget at most 10s where supported. Total request budget includes redirects and body reads. Each site search has a 60s budget.
- GUI search publishes incremental aggregated snapshots as each site completes, preserving first-arrival card order and TV focus. Later matches merge into existing cards without moving them; resources within each work retain configuration order. CLI still emits one final response. Per-site search/quanx failures log and continue. CancellationException must propagate. Web uses abortable operations and discards superseded results.
- Ordinary playback passes `EpisodeLink.url` directly to Media3, with URL encoding and the shared User-Agent only. No preflight HTTP, HTML extraction or HLS resolution; unsupported page URLs may fail. Retry reloads the same raw URL. Core and repositories expose no ordinary playback resolution operation.
- QX alone resolves direct URLs, raw manifests and HTML absolute/relative/escaped/encoded m3u8 links with signed queries. QX follows redirects and all HLS variants/MEDIA/I-FRAME/RENDITION-REPORT playlists, collecting KEY/MAP/segment URI domains. Share parsed playback links within a scan, detect cycles, probe media files with HEAD. Site concurrency 20, site budget 120s, scan budget 1000 probe requests; redirects max 10 per probe. Report incomplete sites to stderr/logs and retain discovered rules on stdout.

## CLI

```bash
tigertv search 逐玉
tigertv fetch --site '🎬站点完整名' --video-id 42
tigertv --config sources.json search 逐玉
tigertv quanx 逐玉
tigertv logs [--full] [--clear]
```

`--config` precedes business commands, bypassing remote config/cache; logs rejects it and duplicate options. Help accepts `--help` / `-h` or `<command> --help`; option values remain literal text. search/fetch emit JSON; quanx/logs text. Errors stderr, logs file. Success/help/closed pipe exit 0, business error 1, argument error 2. Only install command `tigertv` (no aliases); install verifies release SHA-256, honors `TIGERTV_INSTALL_DIR` / `TIGERTV_VERSION`, never edits PATH. Uninstall removes only `tigertv`.

CLI runtime config/log: `/tmp/tigertv-cli-config-cache.json`, `/tmp/tigertv-cli.log`. Logs trim above 5000 lines to last 2000; default display last 50.

## Cache, images and history

Android TV shared data uses `shared-v1`. Config TTL one day; search TTL ten minutes (CLI disabled), at most five queries / 10,000 source records total; cover fallback URL TTL seven days with concurrency four; failures not cached. Key construction is `coverCacheKey(site,vodId)` / `SearchResult.coverKey`.

Notify core once for each actually started playback session, including replays of the same episode; core alone decides whether persistent bookmarks/recent site have changed. Empty API episode names remain valid for history and progress.

History changes use core `addHistory` / `removeHistory` / `clearHistory` read-modify-write operations; never persist a window’s full snapshot. In-process CoreService instances serialize persistent updates so windows cannot overwrite unrelated history/bookmarks/positions.

History is capped at 20 and stored outside disposable caches: Android filesDir. Clearing caches must not erase history.

Default detail source priority: this work's last played site → global most recently played site → first source with a cover → first configured source. Skip unavailable sites. Card text comes entirely from the first-arriving source identified by `display_source` (cover key), retained through incremental merging and caching. Card covers try that source, then configuration order; supplement missing URLs asynchronously and retain the first successfully displayed image. History affects detail selection and resume only. `last-played-sources.json` in persistent data stores each work's last site, one `{line,episode}` bookmark per resource site, and one global recent site, updating only the actually played resource; independent millisecond positions per resource site / line / episode and no separate cover preference. Save every five seconds and flush on pause, seek, navigation and background; resume only the exact resource / line / episode, reset completed episodes to zero, and bind callbacks to the original playback session. Resume never borrows another resource or line's episode; missing lines/episodes fall back to the first available item, without old-schema migration. Posters crop to 2:3; all failed candidates use title initial. Android Coil 3.6.3 uses the app's explicit 64MB DiskCache and startup TTL pruning (skip journal files). Normalize non-ASCII cover URLs. Android progressive prefetch warms at most 24 works, one source per work, in batches of six after visible requests settle; new searches and leaving results cancel owned cover tasks.

## Platform requirements

Android: custom Compose playback controls over Media3 video/subtitles, styled with native TV Material 3 surfaces and theme-colored progress indicators. Center the 64dp primary play control on the viewport, use 56dp previous/next buttons with 24dp icons, and align the 56dp-minimum-height native pill speed button (24dp icon, 8dp gap, 倍速 label) independently to the right, matching the timeline and menu edge. Below 640dp, move speed onto its own bottom row to avoid the centered transport group. The speed menu is 152dp wide and contains only rate options, without a title. Center icon/text groups across the full button width; play/pause icons are 40dp and use the official vector viewport without positional compensation. Keep the transport group symmetric and preserve remote focus paths. Use official Google Material Symbols Rounded vectors (including play/pause/replay/skip icons) with concise Chinese accessibility labels and current-speed descriptions; expose seekable timeline progress and retain stable test tags; bottom actions are previous episode, play/pause, next episode and speed only. D-pad center toggles playback from the video/timeline, left/right seeks there and navigates on action rows, up/down reveals controls. Short seek is 10 seconds; held seek previews accelerate to 30/60-second steps and commit once on release, preserving playback intent. Back cancels seek, closes speed options, hides controls, then exits; errors return directly. Pause/save on exit, retain the frame until removal and release/clear playback on disposal; player/detail boundaries use no alpha transitions. Handle remote Back on key-up so the destination never receives the release. Stop position polling when controls are hidden; read the live position when showing controls or seeking. Hide controls after five idle playing seconds, keep them visible while paused/seeking or speed options are open, delay buffering UI 500ms, retain manual pause across background/foreground, and use MediaSession for system playback commands. minSdk 34. Sony BRAVIA Android 14 TVs can use 32-bit `armeabi-v7a`; universal APK MUST include both v7a and arm64. Media3 1.11.1. All controls D-pad focusable, large targets, dark theme, high contrast focus. `requestFocusSafely` retries 8×30ms; lazy grids may not attach after one frame. Detail controls and episodes share one scroll container so short viewports retain access. Construct dependencies with applicationContext. On foreground, resume only retained playback intent with mediaItemCount > 0; defer media loads requested in the background. Hide transport controls under loading/error overlays; Retry must receive focus even when an error arrives while already blocked, and regain it after the window/page resumes. Error-state focus belongs to Retry; directional keys keep it there, while remote Back exits directly. TV pages use 48dp horizontal / 28dp vertical safety margins. Results and hot cards use five columns, 24dp gaps and 12dp horizontal / 24dp vertical grid padding for the native card focus scale. Catalogs use Foundation BringIntoViewSpec to center focused content within the viewport so scaled card borders remain visible. Restore result focus only after navigation resumes and the selected card attaches; incremental snapshots must not steal focus. Results/detail use the remote Back key rather than on-screen back buttons. Use native TV Material 3 IconButton, Button, FilterChip, InputChip, Card, ListItem and Surface with their default focus/pressed effects; do not add custom player focus borders or override focused colors. Keep 56dp controls, 44dp chips and 64dp hero play as minimum sizes; text controls grow naturally with font scaling. Cards use the native 1.1 focus scale. Detail selection uses native chip state without checkmark placeholders; labels stay centred and have intrinsic width in horizontal lists.

## Signing and release

Current version 4.4.2; Android default versionCode 26 (4.4.2 release: 27). CI checks PRs; releases only through workflow_dispatch. Package the universal Android APK, static Web tar.gz and macOS arm64 / Linux x86_64 / Linux arm64 CLI tar.gz with checksums. Linux uses Curl, glibc and system CA certificates; Ubuntu 24.04 is the CI baseline.

Android debug and release use `~/.android/debug.keystore`, alias `androiddebugkey`, standard Android debug-key credentials. CI restores the same key using the existing four Android signing secrets. Required certificate SHA-256: `0d81ab7cb1a16d58d91a953fe62049eb77fc840cc544a297ae1854f54bc73b96`. Never generate a temporary release signing key. Increment Android versionCode for releases; do not publish/deploy without user authorization.

Do not commit or push unless the user explicitly asks for that action. Preserve user-provided screenshots and IDE changes when cleaning generated files.

Hot searches independently use six official Douban collection APIs in core: Chinese TV, variety, American/British TV, Korean TV, Japanese TV and animation, in that order. Core owns category metadata; each category has at most 12 cards, a six-hour cache and up to one-day stale fallback. Show the first category before background warming of the other five; core shares per-category locks/cache. Warm once per home visit, never block config/search, and cancel warmup on leaving home; category changes cancel only the foreground request. Use the heading 热门推荐 on Android TV and 豆瓣热门 on Web, with native category controls and consistent spacing above cards. Android TV Home has the app name and search/settings actions above up to 12 recent playback cards, then the Douban gallery. Recent cards use the existing playback record with optional source and progress metadata and open the exact saved resource, line and episode when available. Restore focus and scroll when returning; prefer the recent row for initial focus. The independent Search screen owns the input and history. Settings owns the saved Danmaku API address. Home uses the same poster cards as results in multiple rows with vertical scrolling. Titles include year when available. Core returns structured card metadata; authoritative fields and fixtures are in docs/contract.md and docs/fixtures/. Douban image requests need the shared User-Agent and Referer https://m.douban.com/ on Android TV. Web uses a dated snapshot when the browser cannot read the live collection. Fetch category metadata once. Android TV loads posters independently through Coil and its bounded network dispatcher. TV retains the last measured height while loading. Show failures and retry within the module without stealing TV focus, and search only on a click. Loading and retry share the detail line status area. GUI failures use short action-specific copy; log technical diagnostics.

Android TV search has no clear icon. Clear history is a plain text button. TV uses the official TV Material 3 components with a neutral dark palette, default typography and shapes; use native IconButton/Button/Card/FilterChip/InputChip/ListItem/Surface and Foundation input/layout. Keep choice labels intrinsically sized. Progress indicators use explicit TV theme colors because TV has no progress component. Hot categories and detail resource/line/episode choices share SelectionChip, a native FilterChip wrapper distinguishing remote focus (white outline) from the active choice (primary fill / onPrimary text). Moving focus does not change selections; retain native scale and press feedback. Module state crossfades use 180ms; preserve the native TV component focus and press animations.
Playback settings default to auto-next, per-work speed memory and ad filtering enabled. Web also defaults to remembering global volume and mute. Natural end can continue within the same source and line; errors, manual navigation and the last episode never auto-switch. Show the ad status only when a playlist is actually rewritten. Use direct HTTPS browser requests and keep media, segments and keys off TigerTV infrastructure.

The optional Web metadata Gateway lives in `gateway/` and uses the Go standard library. Keep source IDs and allowed upstream routes fixed by configuration. Static mode remains available. Native clients always call their original sources directly. Keep HLS heuristics and expected intervals in shared data and fixtures. Exact HLS fingerprints are site-scoped. Generic heuristics run on supported VOD playlists regardless of site.
