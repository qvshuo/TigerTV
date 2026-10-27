# TigerTV – Agent Notes

## Current migration architecture (2026-10)

- Android signing migration (2026-10-27): local Debug and Release share Interlude's existing `~/.android/debug.keystore` (alias `androiddebugkey`, standard Android debug-key credentials). CI restores the same key through the existing four Android signing secrets. Required certificate SHA-256: `0d81ab7cb1a16d58d91a953fe62049eb77fc840cc544a297ae1854f54bc73b96`. This user-requested change supersedes the legacy TigerTV certificate (`fd6656...37bb7`); new APKs cannot directly update old-certificate installations. Obsolete published releases were removed at the user’s request; reuse the shared key for future releases.

Production Android and macOS repositories now call the root Kotlin Multiplatform `core` module. Windows GUI and CLI development is deferred. Desktop GUI and CLI do not ship a JVM. Python CLI and old platform API clients remain migration references, not production GUI dependencies.

- Root build: `./gradlew :core:jvmTest :core:macosArm64Test :nativeBridge:macosArm64Test`.
- Native CLI: `:cli:linkReleaseExecutableMacosArm64`.
- Android: `./gradlew -Pandroid=true :androidApp:testDebugUnitTest :androidApp:assembleDebug` (requires SDK); legacy `AndroidTV/gradlew` entry also works.
- macOS Xcode builds the native framework automatically; Swift-only reference tests remain available via SPM.
- Authoritative shared parsing rules and cache schema: `core` + `shared/api-contract/`; use `scripts/check-cli-parity.py` to compare native CLI with Python.
- Detailed environment, ABI ownership, tests and release instructions: `docs/cross-platform.md`.
- Migration version is 3.3.0 (Android versionCode 12, macOS build 9); Kotlin/Compose compiler plugins use 2.4.21. Existing sections below describe reference implementations where superseded by this migration.

### Current build and runtime details

- Toolchain: Kotlin 2.4.21, Gradle 9.6, AGP 9.2.1, JDK 21, and Android SDK 37. The JDK is a build dependency, not part of the macOS app or CLI runtime.
- `core/` owns shared business logic, models, Ktor networking, config, playback URL resolution, QuanX domain collection, caches, and history. `nativeBridge/` builds the static macOS `TigerTVCore.framework`; `cli/` builds the macOS arm64 Kotlin/Native executable. GUI clients call core in-process, not through a CLI subprocess.
- Native CLI: `search`/`fetch` emit JSON; `quanx`/`logs` emit text. Business errors use stderr and exit 1; argument errors exit 2; successful commands and closed downstream pipes exit 0. Put `--source` before the subcommand to bypass remote config and its cache.
- GUI caches use the `shared-v1` subdirectory of the platform cache directory. Search history is stored separately under macOS Application Support or Android `filesDir`, so clearing caches does not erase it. The cache schema and parsing contract are documented in `shared/api-contract/README.md`.
- `skills/scripts/install.sh` installs the macOS arm64 CLI from the selected GitHub Release, verifies its SHA-256 checksum, and creates a `tigertv-cli.py` compatibility symlink; `uninstall.sh` removes both commands. Both honor `TIGERTV_INSTALL_DIR`; installation also accepts `TIGERTV_VERSION`. Neither modifies `PATH`.
- CI runs checks on PRs and pushes to `main`; releases require `workflow_dispatch`. It packages `TigerTV-macOS-arm64.zip`, `TigerTV-AndroidTV-universal.apk`, and `TigerTV-CLI-macOS-arm64.tar.gz` with checksums. The macOS app is unsigned; Android builds must use the shared fixed signing key described above and increment `android_version_code` for releases.

## Project

Kotlin Multiplatform core shared by the native SwiftUI macOS app, Android TV app, and Kotlin/Native macOS CLI. The original single-file Python CLI (`tigertv-cli.py`) remains a stdlib-only business reference and test oracle.

## Running

```bash
tigertv <command> [options]
# Reference implementation:
python3 tigertv-cli.py <command> [options]
```

Build the native CLI with `./gradlew :cli:linkReleaseExecutableMacosArm64`. Reference Python tests: `python3 -m pytest tests/` or `python3 -m unittest tests.test_tigertv_cli`. No lint or typecheck configuration exists.

## Key Commands

| Command | Example | Output |
|---|---|---|
| `search <keyword>` | `tigertv search 逐玉` | JSON |
| `fetch --site <name> --vod_id <id>` | `tigertv fetch --site "🎬-爱奇艺-" --vod_id 73480` | JSON |
| `quanx <keyword>` | `tigertv quanx 逐玉` | Plain text (Quantumult X rules) |
| `logs [--full] [--clear]` | `tigertv logs` | Plain text |

All subcommands except `logs` accept a global `--source <path>` before the subcommand. Business errors go to stderr with exit code 1; argument errors exit 2. `search`/`quanx` log per-site failures as warnings and continue.

## Legacy Python Reference: Architecture Gotchas

The names and implementation details in this section describe the retained Python reference; use the shared-core notes above and `shared/api-contract/` for current runtime behavior.

- **Remote config with caching and RAW fallback**: `load_config()` tries (1) `--source` if given, (2) fresh local cache, (3) `CONFIG_CDN_URL` (jsdelivr, 10s timeout), (4) `CONFIG_URL` (GitHub RAW, 5s timeout), (5) expired cache as last resort. Cache: `/tmp/tigertv-cli-config-cache.json`, 1-day TTL. The local `skills/references/LunaTV-config.json` is auto-synced daily by `.github/workflows/sync-from-upstream.yml` and is **not** read by the script.
- **Source filtering**: keep entries where `name` contains `🎬` and entry has no `_comment` key (used to mark disabled/backup sources); also requires non-empty `api` and dedups by `api` URL (the first entry for a given `api` URL is kept across CLI, macOS, and Android TV).
- **Custom source via `--source`**: local JSON file path (same shape as remote config) bypasses both remote fetch and cache. Use this for offline work, CI, or testing.
- **`fetch --site` must be an exact match** of the remote config `name` field, emoji and punctuation included. Wrong site triggers an error listing every available site — use intentionally to discover valid names. Malformed `list` elements (e.g. `null` items) are treated as a site failure in `search` (logged and continued) and as an error in `fetch` (propagated to the user); CLI validates this explicitly, while macOS/Android rely on typed decoding, but the behavior is the same.
- **`search` is non-blocking per site**: individual site failures log warnings and continue; only a full config-load failure exits.
- **Play URL format**: detail payloads return `vod_play_url` / `vod_down_url` as `name$url` items joined by `#` within a group and `$$$` between groups. `parse_play_urls(first_only=True)` returns the first link per group — used by `quanx`.
- **Response code validation**: `check_response()` raises `RequestError` for any `code != 1`. Tolerates string/float forms (`"1"`, `1.0`) via `int(code)` coercion — some MacCMS deployments return non-int code. `search`/`quanx` swallow per-site; `fetch` propagates to user.
- **m3u8 resolution**: non-`.m3u8` play URLs are probed; HTML content is scanned for quoted `.m3u8` paths (supports query strings like `?sign=...`, escaped `\/` slashes, and URL-encoded forms). `fetch_m3u8_domains()` recurses up to depth 2 with a shared `Lock`-guarded cache; only the first 3 sub-playlists per master are followed to bound fanout. Depth-truncated results are NOT cached (key is URL-only), preventing an empty set cached at deep layer from poisoning a later top-level resolution of the same URL. Both `#EXT-X-KEY` and `#EXT-X-MEDIA` URIs are extracted for CDN domain collection.
- **Concurrency**: `search` and `quanx` use `ThreadPoolExecutor(max_workers=min(sites, 20))`.
- **Output contract**: `search`/`fetch` → JSON on stdout; `quanx` → plain text on stdout; errors → stderr (via `exit_with_error`, exit code 1); all logs → `/tmp/tigertv-cli.log` (viewed via `logs` command). Logs never mix into stdout to keep pipes parseable. `search` results include `vod_pic` (cover URL passthrough from MacCMS `ac=list`; missing → empty string).
- **Search result covers**: all three platforms parse/emit `vod_pic` with an empty-string fallback and render results as vertical poster cards cropped to a fixed 2:3 aspect (upstream covers mix landscape 16:9 and portrait posters); empty or failed loads fall back to a title-initial placeholder. Android loads images with Coil 3 (`io.coil-kt.coil3:coil-compose` + `coil-network-okhttp`). Cleartext image URLs are fine: Android allows cleartext globally (`network_security_config.xml`) and macOS has ATS arbitrary loads.
- **Cover lazy fallback**: ⚠️ 实测（2026-08）绝大多数站点的 `ac=list` 搜索响应**不含** `vod_pic`（只有暴风资源返回封面），但所有站点的 `ac=detail` 都返回完整封面字段。因此 macOS/Android 在空封面卡片进入视口时按需请求 detail 补齐：内存缓存 key 为 `site-vodId`（命中不发请求）、失败不缓存（重入视口自然重试）、并发闸门为 4（macOS 复用 `AsyncSemaphore`，Android 用 `kotlinx.coroutines.sync.Semaphore(4)`）。macOS 封面 URL 统一走 `HTTPClient.percentEncodedURL(from:)`（trim + 非 ASCII 百分号编码，与 API 请求同一路径）——直接 `URL(string:)` 遇中文路径会得到 nil 静默丢图。
- **Cover 7-day disk cache**: 兜底 URL 与封面图片字节均有两级缓存（内存 + 磁盘，7 天 TTL），解决播放返回结果页后封面重新下载的问题。URL 层：macOS `CoverFallbackURLStore` / Android `CoverFallbackDiskCache`（JSON 原子写，位于各自 caches 目录），进程重启后命中即跳过 detail 请求。图片字节层：macOS 用自研 `CoverImageCache`（NSCache + SHA256 文件名磁盘目录，按修改日期判 7 天过期；AsyncImage 无磁盘缓存才换掉的）；Android 用 Coil 全局 ImageLoader 的 DiskCache（64MB LRU，在 `TigerTVApplication.newImageLoader` 配置——⚠️ Coil 3 **默认不带**磁盘缓存；⚠️ Coil 无内建 TTL，过期文件由 `CoverDiskCachePruner` 在 Application 启动协程里按 mtime 清理，journal 索引文件跳过）。缓存 schema 与 TTL 契约文档在 `shared/api-contract/README.md`（"Cover fallback URL disk cache" 一节）。
- **HTTP safety**: all platforms enforce a 10MB response cap (`MAX_RESPONSE_SIZE = 10MB`), but the check timing is platform-appropriate: CLI reads up to `MAX+1` bytes then checks length; macOS checks `data.count` after `URLSession.data(for:)`; Android uses Okio `source.request(MAX+1)` to reject oversized streams early. All platforms percent-encode non-ASCII URLs and send the same hardcoded macOS Safari `User-Agent` (`Version/26.4`). The CLI additionally decodes JSON bodies with `errors="replace"` (parity with HTML m3u8 probe) so GBK/non-UTF-8 sites don't fail at `make_request`. Config cache write is atomic (temp + `os.replace`). `main` funnels `BrokenPipeError` (silent exit 0) and any unexpected exception through `exit_with_error` so the "errors → stderr" contract is preserved.
- **API spec reference**: `skills/references/API接口说明V2.txt` documents the upstream MacCMS-style provide API the script targets (ac=list/detail, code/msg/list, etc.). Read-only, not loaded by code.

## Install / Uninstall

- `skills/scripts/install.sh` downloads the macOS arm64 native CLI from GitHub Releases, verifies the checksum, and installs `~/.local/bin/tigertv` plus a `tigertv-cli.py` compatibility symlink by default.
- `skills/scripts/uninstall.sh` removes the installed binary and compatibility symlink.
- Neither script modifies `PATH`; user must add `~/.local/bin` manually.

## Logs

- Runtime logs at `/tmp/tigertv-cli.log` in structured format: `YYYY-MM-DD HH:MM:SS [LEVEL] [site_name]: message`.
- Auto-trimmed to last 2000 lines once it exceeds 5000 lines.
- `logs` shows the last 50 lines by default; `--full` shows everything; `--clear` truncates.

## macOS GUI (`macOS/`)

Native SwiftUI macOS app using the shared Kotlin core through `SharedCoreRepository` and the static Native framework (no CLI subprocess). Target: macOS 26+, Swift 6.2 (Xcode project `SWIFT_VERSION = 6.2`; SPM package baseline also pinned to macOS 26). App Sandbox is **disabled** in `macOS/App/TigerTV.entitlements`. `Info.plist` declares `NSAppTransportSecurity → NSAllowsArbitraryLoads = true` so HTTP-only resource sites work.

```
macOS/
├── TigerTV.xcodeproj
├── Package.swift
├── TigerTVTests/
└── App/
    ├── Assets.xcassets/
    ├── TigerTVApp.swift
    ├── ContentView.swift
    ├── HomeScreen.swift
    ├── ResultsLayout.swift
    ├── SearchResultCard.swift
    ├── EpisodePanel.swift
    ├── SearchHistoryView.swift
    ├── GlassSearchBar.swift
    ├── PlayerView.swift
    ├── DesignSystem.swift
    ├── TigerTVViewModel.swift
    ├── SharedCoreRepository.swift
    ├── HTTPClient.swift
    ├── ConfigDataSource.swift
    ├── MacCMSApiClient.swift
    ├── TigerTVRepository.swift
    ├── PlaybackURLResolver.swift
    ├── SearchHistoryStore.swift
    ├── Models.swift
    ├── LoadResult.swift
    ├── TigerTVError.swift
    ├── Info.plist
    └── TigerTV.entitlements
```

### Build

```bash
# App
xcodebuild -project macOS/TigerTV.xcodeproj -scheme TigerTV -destination 'platform=macOS' build

# macOS unit tests (Swift Package Manager XCTest)
cd macOS
swift test
```

CI uses `xcodebuild -project macOS/TigerTV.xcodeproj ... -configuration Release ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO build` and zips the app for the GitHub Release (see `.github/workflows/build-and-release.yml`). Releases are **only** triggered manually via `workflow_dispatch` — no push/tag auto-release.

### Key Files

| File | Role |
|---|---|---|
| `App/TigerTVApp.swift` | `@main` entry, default window 1200×780 |
| `App/ContentView.swift` | Routes home screen vs. results layout |
| `App/HomeScreen.swift` / `App/ResultsLayout.swift` | Empty state and post-search layout |
| `App/SearchResultCard.swift` / `App/EpisodePanel.swift` | Result and episode UI |
| `App/SearchHistoryView.swift` / `App/GlassSearchBar.swift` | History and search input |
| `App/PlayerView.swift` | `AVPlayer` + `VideoPlayer` (AVKit) wrapper |
| `App/TigerTVViewModel.swift` | `@MainActor` UI state container |
| `App/SharedCoreRepository.swift` | Production adapter to the shared Kotlin/Native framework |
| `App/HTTPClient.swift` | Image URL encoding and Swift reference HTTP client |
| `App/ConfigDataSource.swift` / `App/MacCMSApiClient.swift` | Swift reference config and MacCMS clients |
| `App/TigerTVRepository.swift` / `App/PlaybackURLResolver.swift` | Swift reference repository and playback resolver |
| `App/SearchHistoryStore.swift` | Swift reference `UserDefaults` history store |
| `App/Models.swift` / `LoadResult.swift` / `TigerTVError.swift` | Domain models and `Result<T>` wrapper |

### Historical Swift Reference Notes

The older Swift repository, config client, playback resolver, and history store remain for reference tests. Production uses `SharedCoreRepository` and the shared core; current cache/history locations are described above. Fullscreen and UI behavior remain relevant.

- **No CLI dependency**: the app no longer bundles or shells out to `tigertv-cli.py`.
- **Config loading**: follows the CLI priority — fresh local cache → CDN (10s) → RAW (5s) → expired cache. Cache stores the **raw** (pre-filter) `SourceConfig` and filters at read time, so rule changes don't require cache expiry. Cache lives in `~/Library/Caches/TigerTV/tigertv-config-cache.json`; the JSON file is written atomically (`.write(options: .atomic)`) alongside a separate timestamp file.
- **Source filtering**: same as CLI — keep entries whose `name` contains `🎬`, have no `_comment` key, and have a non-empty `api`. Distinct entries sharing the same `api` URL are deduped (the first entry for a given `api` URL is kept).
- **Config errors**: shown inline on the home screen. Other errors surface via alert.
- **Search history**: persisted in `UserDefaults` (key `tigertv.searchHistory`), capped at 20 items.
- **Timeouts**: search and fetch use 20s; config CDN fetch uses 10s and RAW fallback uses 5s.
- **Task cancellation (race-safe)**: `TigerTVViewModel` uses a per-operation generation token (incremented on each new request) so cancelled-but-still-running HTTP results from the previous search/fetch/playback are discarded instead of overwriting the current request's `@Published` flags. `HTTPClient.fetchData` uses `URLSession.data(for:)` so structured `Task.cancel()` forwards to the underlying `URLSessionTask` (no more runaway in-flight reads).
- **Fullscreen**: hides the top search bar and right episode panel; exiting restores them.

## Android TV (`AndroidTV/`)

Native Android TV app implemented in Kotlin with Jetpack Compose for TV, Material 3, and Media3 ExoPlayer. Its production repository delegates search, fetch, playback resolution, and config behavior to the shared Kotlin core. The Python CLI remains a reference oracle for the contract in `shared/api-contract/`.

Current version: `3.3.0` (`versionCode 12`). **minSdk = 34（Android 14）** — 设备实证依据：Sony BRAVIA 4K AE2（Android 14 / SDK 34）主 ABI 为 `armeabi-v7a`，因此 universal APK 必须同时包含 `armeabi-v7a` 与 `arm64-v8a`，去掉 v7a 会让该类 32 位 Android 14 TV 无法安装。Toolchain: Kotlin **2.4.21**（serialization/compose 插件同版本联动）、AGP 9.2.1、Gradle 9.6、Media3 1.11.0、Coil 3.5.0。

### Build

```bash
cd AndroidTV
./gradlew test assembleDebug
```

Release build (CI signs with the **fixed** keystore stored in repo secrets — `ANDROID_KEYSTORE_BASE64` + `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD`；3.3.0 起沿用上文所述的共享签名，旧签名安装无法直接覆盖升级。⚠️ 勿改回每次 `keytool -genkey` 的临时签名——那会导致每个 release 签名不同、电视上无法覆盖安装):

```bash
cd AndroidTV
./gradlew assembleRelease
```

### Key files

| File | Role |
|---|---|---|
| `data/TigerTVRepository.kt` | Android UI adapter to the shared core |
| `data/MacCMSApiClient.kt` / `data/ConfigDataSource.kt` | Legacy reference clients retained for tests |
| `data/PlaybackUrlResolver.kt` | Legacy reference playback resolver retained for tests |
| `core/src/commonMain/kotlin/art/anjing/tigertv/domain/Models.kt` | Shared `SearchResponse`, `FetchResponse`, `EpisodeLink` |
| `ui/TigerTVApp.kt` | Compose navigation / app entry |
| `ui/home/HomeScreen.kt` | Search + history |
| `ui/results/ResultsScreen.kt` | TV result grid |
| `ui/episodes/EpisodesScreen.kt` | Episode grid |
| `ui/player/PlayerScreen.kt` | Media3 ExoPlayer wrapper |

### TV constraints

- All interactive UI must be focusable and navigable via D-pad.
- Use large touch targets, high contrast focus state, and dark theme.
- The app declares `android.software.leanback` and sets `touchscreen` not required.

### Android specifics

Some historical API-client and cache implementation notes below describe retained reference classes. The production repository delegates those operations to `core/`; UI focus, player, image caching, and Android lifecycle notes remain applicable.

- **Dependency injection**: dependencies are constructed in `TigerTVApp.kt` with `LocalContext.current.applicationContext` (NOT the Activity) to avoid leaking the Activity through the long-lived `TigerTVViewModel` / `ConfigDataSource` / `SearchHistoryStore`.
- **Coroutine cancellation**: every `catch (e: Exception)` around a `suspend` call rethrows `CancellationException` first (`MacCMSApiClient.search`, `ConfigDataSource.loadConfig`, `TigerTVRepository.resolvePlaybackUrl`) so `viewModelScope.cancel()` / `Job.cancel()` actually interrupts structured concurrency.
- **Retry button**: `EpisodesScreen` retry calls `viewModel.retryFetch()` (which re-runs `selectResult(current, forceRefresh = true)` to bypass the "same result" short-circuit) — the older `selectResult(it)` form silently no-op'd.
- **Focus restoration**: first-card auto-focus on `ResultsScreen` / `EpisodesScreen` keys off the list identity (no one-shot `focused` flag), so re-entering the screen after a second search/episode list restores focus correctly.
- **Currently-playing episode**: highlighted with `primary` tint in `EpisodesScreen`'s grid (`viewModel.selectedEpisodeIndex`).
- **Next episode**: `PlayerScreen`'s error overlay offers a "下一集" button when `selectedEpisodeIndex + 1 < vodPlayUrl.size`.
- **Player ON_RESUME**: resumes playback whenever `exoPlayer.mediaItemCount > 0` (the previous `&& resolvedPlaybackUrl != null` check made playback silently fail to start when resolution completed while paused).
- **Player overlay overlap**: `PlayerView.useController` is bound to `!(isResolvingPlayback || playbackErrorMessage != null)` via the `AndroidView` `update` block, so ExoPlayer's transport controls hide while the loading/error overlay is up — preventing the semi-transparent overlay from revealing the seekBar/play buttons underneath and stealing D-pad focus from the retry/下一集 buttons.
- **Focus first-card reliability**: `requestFocusSafely` (in `ui/FocusExt.kt`) retries `FocusRequester.requestFocus()` up to 8×30ms instead of a single `yield()`. This is the fix for "搜索结果页 D-pad 上下左右导航失效": the lazy `LazyVerticalGrid` first item often isn't composed/attached one frame after `yield()`, so the old single-attempt `focusRequester.requestFocus()` threw `IllegalStateException` (swallowed by `runCatching`) and NO card received focus — leaving D-pad directional navigation without a focus anchor.
- **Results grid columns are height-constrained**: 竖版海报 2:3，只按宽度自适应列数会让一行 4 张的海报高度超出电视垂直显示范围。`ResultsScreen.derivedColumnCount` 用 `BoxWithConstraints` 按目标两行完整可见反推卡片最大宽度再定列数（clamp 2~6），屏幕越高列数越多；改卡片文字区高度时要同步 `TextBlockHeight` 常量。
- **Cover cache key 单一构造点**: 封面兜底缓存 key 统一为 `site-vodId`，Android 由 `domain/Models.kt` 的 `coverCacheKey()` / `SearchResult.coverKey` 构造，禁止各处手拼字符串漂移；兜底方法两端同名为 `loadCoverFallbackIfPossible`。
- **Cover URL 非 ASCII 编码对齐**: Android 侧封面 URL 经 `util/CoverUrl.kt` 的 `normalizeCoverUrl`（仅编非 ASCII 字符为 UTF-8 百分号，已编码 `%XX` 与 ASCII 原样透传避免双重编码）再交给 Coil，语义与 macOS `HTTPClient.percentEncodedURL(from:)` 一致——部分站点中文路径封面直接丢给图片库会静默 404。
- **Cover 渐进式预取**: `TigerTVViewModel.ensureCoverPrefetch` 在曝光卡片的兜底请求收敛后（`coverLoadsInFlight` 清空轮询），按 `PREFETCH_BATCH=6` 一批批预取未曝光结果：解析兜底 URL + `SingletonImageLoader.execute` 把图片字节预热进 Coil 内存/磁盘缓存，直到列表末尾；已处理 key 跳过、失败不标记（可见时 UI 路径仍会重试）、新搜索取消并清空。触发点为每张卡片组合时的 `loadCoverFallbackIfPossible`（含有封面的卡片，保证纯命中站点也会启动预取）。
- **Config cache**: stores the **raw** (pre-filter) `SourceConfig` and filters at read time; cache file written atomically (tmp + rename). Per-remote errors collected in a local `fetchError` (not a shared mutable field).
- **Source filtering**: same parity as CLI/macOS — `🎬` in name, no `_comment`, non-empty `api`, dedup by `api` URL (the first entry for a given `api` URL is kept).
- **Lenient numeric parsing**: `MacCMSListResponse.code`, `MacCMSListItem.vodId`, `MacCMSDetailResponse.code`, `MacCMSDetailItem.vodId` are decoded with a custom `LenientIntSerializer` that coerces int / float (`1.0`) / numeric-string (`"102405"`) / null → `Int`, mirroring the CLI's `int(code)` / `int(vod.get("vod_id",0) or 0)`. ⚠️ This is the **root cause** of "Android搜到的比 macOS 少": macOS `JSONDecoder` auto-coerces `1.0`→`Int`, but kotlinx.serialization strict-matches and throws — the whole response was silently dropped, losing those sites. String `vod_id` (e.g. 暴风资源) was missed by BOTH macOS and Android before; now Android matches CLI and reaches them. `coerceInputValues = true` still handles plain-null fields → default.
- **HTTP safety**: 10MB response cap enforced on all fetch paths (`MacCMSApiClient`, `ConfigDataSource`, `PlaybackUrlResolver`) via the Okio idiom `source.request(MAX+1)` (reads up to the cap or EOF, returns true if more bytes remain) followed by `source.readByteArray(source.buffer.size)`. ⚠️ Do NOT use `source.readByteArray(MAX+1)` — that overload reads *exactly* `MAX+1` bytes and throws `EOFException` for any smaller body, silently failing every normal-size response.
- **Permissions**: only `INTERNET` declared (the previous `ACCESS_NETWORK_STATE` was unused).

## Cross-Platform Sync

- The output contract for `search`/`fetch` is documented in `shared/api-contract/`.
- Any change to CLI output fields or parsing rules must update `shared/api-contract/` and trigger a review of both `AndroidTV` and `macOS`.
- Contract fixtures in `shared/api-contract/fixtures/` should be used by both CLI and Android tests when possible.

## Network Requirements

Runtime requires access to:
1. `cdn.jsdelivr.net` and/or `raw.githubusercontent.com` (config fetch — RAW is the fallback)
2. Target resource site APIs (varies by site)

Without this access, every command fails at config load unless `--source` is used or a valid cache exists. The script will still run from an expired cache as a last-resort fallback. The Android app mirrors the same CDN-first priority using its local cache.
