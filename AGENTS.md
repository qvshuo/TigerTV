# TigerTV – Agent Notes

## Architecture

Kotlin Multiplatform `core` is the single business implementation for the SwiftUI macOS app, Compose Android TV app and Kotlin/Native macOS CLI. Platform adapters call core in-process. macOS and CLI ship no JVM. Windows is not supported; do not add Windows files, targets or workflows.

Python CLI and old platform API/config/history/playback implementations have been removed at the user's request. Do not reintroduce old parameters, output schemas, fallback models, command aliases or migration branches. Development fixture scripts may use Python but must test Kotlin directly, never implement a second business core.

- `core/`: models, aggregation, MacCMS/config networking, playback resolution, QuanX domains, caches and history.
- `nativeBridge/`: static `TigerTVCore.framework` and versioned async JSON boundary.
- `cli/`: macOS arm64 executable `tigertv`.
- `macOS/App/SharedCoreRepository.swift` and Android `data/TigerTVRepository.kt`: production adapters.
- Authoritative contracts and fixtures: `docs/contract.md` and `docs/fixtures/`. Update them together with both platforms whenever changing models or parsing.
- Build and release notes: `docs/README.md`; current API and UI contract: `docs/contract.md`.

## Toolchain and validation

Kotlin/Compose/serialization plugins 2.4.21, Gradle 9.8.2, AGP 9.5.0, JDK 21, Android SDK 37; Xcode 27.2 stable (27B52), its bundled Swift 6.4.2 toolchain, Swift 6 language mode, SwiftPM tools 6.4, macOS SDK 27.2 and deployment target macOS 26+.

```bash
./gradlew -Pandroid=false :core:jvmTest :core:macosArm64Test :nativeBridge:macosArm64Test
./gradlew -Pandroid=false :cli:linkReleaseExecutableMacosArm64
python3 scripts/check-cli-contract.py cli/build/bin/macosArm64/releaseExecutable/tigertv.kexe
swift test --package-path macOS
xcodebuild -project macOS/TigerTV.xcodeproj -scheme TigerTV -destination 'platform=macOS,arch=arm64' CODE_SIGNING_ALLOWED=NO build
./gradlew -Pandroid=true :androidApp:testDebugUnitTest :androidApp:assembleDebug
```

CI uses the `xcode-27` arm64 runner, selects Xcode 27.2 and verifies build 27B52 to reject RC 27B48; the hosted image must contain the stable installation. Xcode builds the native framework automatically with recursive script inputs; do not restore an always-out-of-date build phase. `LM_SKIP_METADATA_EXTRACTION=YES` skips unused App Intents extraction for the unsigned app. Android SDK may require `ANDROID_HOME`. Open the repository root in Android Studio. There is one Gradle entry, with Java 21 daemon criteria, Foojay resolver 1.0.0 and configuration cache. Native-only builds pass `-Pandroid=false` and need no Android SDK. Keep dependency/plugin/Wrapper upgrade advisories enabled; do not suppress them to obtain a clean report. Kotlin 2.4.21 has a known Native bundle enum attribute deprecation on Gradle 9.8.2; the upstream fix is in Kotlin 2.5.0-Beta2, not the current stable release.

## Current model and interaction

- CLI search/quanx require `--query`; bridge search requests and search responses use `query`; bridge fetch/cover requests use `video_id`. Local config uses `--config`, and site selection uses `--site`; there are no alternate names or positional search terms.
- Search emits `{query, works}`; each work has `{id, title, sources}`. No flat results array. Each source includes its own `site` / `vod_id` and metadata.
- Keep aggregation minimal: whitespace-stripped/lowercased title + trimmed year only. No type buckets, external IDs, cast/director matching, fuzzy rules or scores. Accept imperfect grouping at the user’s request.
- Detail emits metadata plus required `play_sources` and `download_sources`. Each line has `{id,name,episodes:[{name,url}]}`. No flattened play/download arrays.
- Raw MacCMS line separator is `$$$`; item separator is `#`; first `$` splits name and URL. Preserve original group positions when aligning `*_from` names; discard empty items/groups only after alignment.
- GUI flow on both platforms: search → work cards → detail → site/line/episode selection → player. Switching site/line cancels pending playback, resets episode selection and never auto-plays. Retry/next-episode must stay in the chosen line. Back returns through detail and results.
- Detail order/text on both platforms: current-source poster + overview → 资源 → 线路 → 选集 (no section counts). No “作品详情” section label. Synopsis is always visible without disclosure controls. Hero play resumes the last actually played episode (first episode if unavailable); TV focus on hero play scrolls to top. Detail poster uses only the selected source, unlike work-card covers. Search progress stays inside a fixed-size search button (arrow becomes spinner); both platforms disable search buttons while searching; macOS also ignores keyboard submission until completion. The shared macOS loader uses the system progress.indicator symbol with repeating iterative variable-color animation; never mask a native progress view inside a disabled button. The search variant is white/gray and remains animated while disabled. No separate search overlay/header progress text. Detail/playback loading share one component per platform and matching messages; retain native macOS hover/materials and TV focus/scale headroom. See `docs/contract.md`.
- Swift models require the current shared schema and must not fall back to an older schema.

## API and network

- Search decodes only lightweight card metadata per page, skipping large content/playback fields; never retain full detail JSON across pages or parse a page twice. Search all configured sites concurrently, with no ten-site semaphore. Search uses `ac=detail&wd=…&pg=…&pagesize=100`, follows pagecount (absent means one), stops on empty pages; max 100 pages. `quanx` uses a first-result list probe then detail.
- Fetch uses `ac=detail&ids=…` and requires a matching response ID.
- `code` must represent integer 1. Upstream numeric strings/exact float integers are accepted; fractions, null, booleans and overflow rejected. Source IDs must be positive.
- Config filtering: name contains `🎬`, no `_comment` key (even null), nonempty API, first exact API URL wins.
- Config priority: explicit source file → fresh cache → CDN (10s) → RAW (5s) → expired cache → error. Store raw config atomically, filter at read time.
- Preserve unrelated API query parameters; replace operation parameters, including within supported `url=` proxies.
- API/config/HTML/HLS response cap 10MB; encode Unicode URL bytes without changing existing escapes or plus signs. All product HTTP clients use the exact shared Safari User-Agent `Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15`, including API/config/HTML/HLS, covers, native players and the installer. Search/detail timeout 20s.
- GUI search publishes incremental aggregated snapshots as each site completes, preserving first-arrival card order and TV focus. Later matches merge into existing cards without moving them; resources within each work retain configuration order. CLI still emits one final response. Per-site search/quanx failures log and continue. CancellationException must propagate. Swift uses generation tokens to discard superseded results.
- HLS resolution handles direct URLs, raw manifests, HTML absolute/relative/escaped/encoded m3u8 links with signed queries. QuanX recursion depth two, first three children per master, KEY/MEDIA domains; never cache depth-truncated results.

## CLI

```bash
tigertv search --query 逐玉
tigertv fetch --site '🎬站点完整名' --video-id 42
tigertv --config sources.json search --query 逐玉
tigertv quanx --query 逐玉
tigertv logs [--full] [--clear]
```

`--config` precedes the command, bypasses remote config/cache. search/fetch emit JSON; quanx/logs text. Errors stderr, logs file. Success/help/closed pipe exit 0, business error 1, argument error 2. Only install command `tigertv` (no aliases); install verifies release SHA-256, honors `TIGERTV_INSTALL_DIR` / `TIGERTV_VERSION`, never edits PATH. Uninstall removes only `tigertv`.

CLI runtime config/log: `/tmp/tigertv-cli-config-cache.json`, `/tmp/tigertv-cli.log`. Logs trim above 5000 lines to last 2000; default display last 50.

## Cache, images and history

GUI shared data uses `shared-v1`. Config TTL one day; search TTL ten minutes (CLI disabled), at most five queries / 10,000 source records total; cover fallback URL TTL seven days with concurrency four; failures not cached. Key construction is `coverCacheKey(site,vodId)` / `SearchResult.coverKey`.

History is capped at 20 and stored outside disposable caches: macOS Application Support / Android filesDir. Clearing caches must not erase history.

Default detail source priority: this work's last played site → global most recently played site → first source with a cover → first configured source. Skip unavailable sites. Cover candidates follow own last site, global recent site, then configuration order, skip blanks and try next on image failure. A preferred source with no poster must not hide another source's poster. `last-played-sources.json` in persistent data stores one latest `{site,line,episode}` bookmark per work and one global recent site, overwritten only on actual playback; no source-history list or separate cover preference. Posters crop to 2:3; all failed candidates use title initial. macOS `CoverImageCache` holds up to 64MB / 256 images in memory plus SHA256-named disk bytes for seven days. Android Coil 3.6.3 uses the app's explicit 64MB DiskCache and startup TTL pruning (skip journal files). Normalize non-ASCII cover URLs. Android progressive prefetch warms at most 24 works, one source per work, in batches of six after visible requests settle; new searches and leaving results cancel owned cover tasks.

## Platform requirements

macOS: SwiftUI + AVPlayer; no subprocess; App Sandbox disabled; ATS arbitrary loads permitted for HTTP sources. Fullscreen hides search/episode controls. Close player on navigation and preserve cancellable request ownership.

Android: minSdk 34. Sony BRAVIA Android 14 TVs can use 32-bit `armeabi-v7a`; universal APK MUST include both v7a and arm64. Media3 1.11.1. All controls D-pad focusable, large targets, dark theme, high contrast focus. `requestFocusSafely` retries 8×30ms; lazy grids may not attach after one frame. Detail controls and episodes share one scroll container so short viewports retain access. Construct dependencies with applicationContext. Resume playback when mediaItemCount > 0. Hide transport controls under loading/error overlays; retry/error actions must receive focus. Results grid column count accounts for poster height and text area; grid cells must match card width (16dp real gaps). Shared glass buttons keep their shape, 56dp height and scale across focus/pressed/disabled states; cards alone scale to 1.04 on focus.

## Signing and release

Current version 3.3.0; Android versionCode 12, macOS build 9. CI checks PRs/main; releases only through workflow_dispatch. Package macOS arm64 ZIP, universal Android APK and macOS arm64 CLI tar.gz with checksums. macOS app unsigned.

Android debug and release share Interlude's existing `~/.android/debug.keystore`, alias `androiddebugkey`, standard Android debug-key credentials. CI restores the same key using the existing four Android signing secrets. Required certificate SHA-256: `0d81ab7cb1a16d58d91a953fe62049eb77fc840cc544a297ae1854f54bc73b96`. Never generate a temporary release signing key. This user-requested key supersedes the former TigerTV certificate; old-certificate installations cannot be updated in place. Increment Android versionCode for releases; do not publish/deploy without user authorization.
