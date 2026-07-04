# TigerTV – Agent Notes

## Project

Single-file Python 3 CLI (`tigertv-cli.py`) that searches video resource sites, fetches play/download links, and generates Quantumult X direct rules. No third-party dependencies — stdlib only. Wrapped by a native SwiftUI macOS app under `macOS/App/` and an Android TV app under `AndroidTV/`.

## Running

```bash
python3 tigertv-cli.py <command> [options]
# or after install.sh: tigertv-cli.py <command>
```

No build step for the CLI. Tests: `python3 -m pytest tests/` or `python3 -m unittest tests.test_tigertv_cli`. No lint or typecheck configuration exists.

## Key Commands

| Command | Example | Output |
|---|---|---|
| `search <keyword>` | `tigertv-cli.py search 逐玉` | JSON |
| `fetch --site <name> --vod_id <id>` | `tigertv-cli.py fetch --site "🎬-爱奇艺-" --vod_id 73480` | JSON |
| `quanx <keyword>` | `tigertv-cli.py quanx 逐玉` | Plain text (Quantumult X rules) |
| `logs [--full] [--clear]` | `tigertv-cli.py logs` | Plain text |

All subcommands except `logs` accept a global `--source <path>` (see below). Errors go to stderr with exit code 1; `search`/`quanx` log per-site failures as warnings and continue.

## Architecture Gotchas

- **Remote config with caching and RAW fallback**: `load_config()` tries (1) `--source` if given, (2) fresh local cache, (3) `CONFIG_CDN_URL` (jsdelivr, 10s timeout), (4) `CONFIG_URL` (GitHub RAW, 5s timeout), (5) expired cache as last resort. Cache: `/tmp/tigertv-cli-config-cache.json`, 1-day TTL. The local `skills/references/LunaTV-config.json` is auto-synced daily by `.github/workflows/sync-from-upstream.yml` and is **not** read by the script.
- **Source filtering**: keep entries where `name` contains `🎬` and entry has no `_comment` key (used to mark disabled/backup sources).
- **Custom source via `--source`**: local JSON file path (same shape as remote config) bypasses both remote fetch and cache. Use this for offline work, CI, or testing.
- **`fetch --site` must be an exact match** of the remote config `name` field, emoji and punctuation included. Wrong site triggers an error listing every available site — use intentionally to discover valid names.
- **`search` is non-blocking per site**: individual site failures log warnings and continue; only a full config-load failure exits.
- **Play URL format**: detail payloads return `vod_play_url` / `vod_down_url` as `name$url` items joined by `#` within a group and `$$$` between groups. `parse_play_urls(first_only=True)` returns the first link per group — used by `quanx`.
- **Response code validation**: `check_response()` raises `RequestError` for any `code != 1`. `search`/`quanx` swallow per-site; `fetch` propagates to user.
- **m3u8 resolution**: non-`.m3u8` play URLs are probed; HTML content is scanned for quoted `.m3u8` paths (supports query strings like `?sign=...`, escaped `\/` slashes, and URL-encoded forms). `fetch_m3u8_domains()` recurses up to depth 2 with a shared `Lock`-guarded cache; only the first 3 sub-playlists per master are followed to bound fanout.
- **Concurrency**: `search` and `quanx` use `ThreadPoolExecutor(max_workers=min(sites, 20))`.
- **Output contract**: `search`/`fetch` → JSON on stdout; `quanx` → plain text on stdout; errors → stderr (via `exit_with_error`, exit code 1); all logs → `/tmp/tigertv-cli.log` (viewed via `logs` command). Logs never mix into stdout to keep pipes parseable.
- **HTTP safety**: `_http_get` enforces `MAX_RESPONSE_SIZE = 10MB` and percent-encodes non-ASCII path/query via `_encode_url` to avoid `UnicodeEncodeError`. All requests send a hardcoded macOS Safari `User-Agent`.
- **API spec reference**: `skills/references/API接口说明V2.txt` documents the upstream MacCMS-style provide API the script targets (ac=list/detail, code/msg/list, etc.). Read-only, not loaded by code.

## Install / Uninstall

- `skills/scripts/install.sh` downloads `tigertv-cli.py` from GitHub RAW (not the local file) into `~/.local/bin/tigertv-cli.py`.
- `skills/scripts/uninstall.sh` removes it.
- Neither script modifies `PATH`; user must add `~/.local/bin` manually.

## Logs

- Runtime logs at `/tmp/tigertv-cli.log` in structured format: `YYYY-MM-DD HH:MM:SS [LEVEL] [site_name]: message`.
- Auto-trimmed to last 2000 lines once it exceeds 5000 lines.
- `logs` shows the last 50 lines by default; `--full` shows everything; `--clear` truncates.

## macOS GUI (`macOS/`)

Native SwiftUI macOS app that reimplements the CLI logic in Swift (no Python subprocess). Target: macOS 26+, Swift 6. App Sandbox is **disabled** in `macOS/App/TigerTV.entitlements` (network and `~/Library/Caches` access fail under sandbox).

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
| `App/HTTPClient.swift` | Shared URL session: Safari UA, 10MB cap, non-ASCII URL encoding |
| `App/ConfigDataSource.swift` | Config cache → CDN → RAW → expired-cache fallback; filters sites by `🎬` and no `_comment` |
| `App/MacCMSApiClient.swift` | MacCMS `ac=list/detail` requests |
| `App/TigerTVRepository.swift` | Concurrent search, 10-minute search cache, episode parsing |
| `App/PlaybackURLResolver.swift` | HTML → m3u8 URL extraction (mirrors CLI logic) |
| `App/SearchHistoryStore.swift` | `UserDefaults`-backed search history (max 20) |
| `App/Models.swift` / `LoadResult.swift` / `TigerTVError.swift` | Domain models and `Result<T>` wrapper |

### GUI Quirks

- **No CLI dependency**: the app no longer bundles or shells out to `tigertv-cli.py`.
- **Config loading**: follows the CLI priority — fresh local cache → CDN (10s) → RAW (5s) → expired cache. Cache lives in `~/Library/Caches/TigerTV/tigertv-config-cache.json`.
- **Source filtering**: same as CLI — keep entries whose `name` contains `🎬` and have no `_comment` key.
- **Config errors**: shown inline on the home screen. Other errors surface via alert.
- **Search history**: persisted in `UserDefaults` (key `tigertv.searchHistory`), capped at 20 items.
- **Timeouts**: search and fetch use 20s; config CDN fetch uses 10s and RAW fallback uses 5s.
- **Fullscreen**: hides the top search bar and right episode panel; exiting restores them.

## Android TV (`AndroidTV/`)

Native Android TV app implemented in Kotlin with Jetpack Compose for TV, Material 3, and Media3 ExoPlayer. It replicates the CLI search/fetch/playback-resolution logic in Kotlin so both platforms share the same output contract and config behavior. The authoritative business rules live in `tigertv-cli.py`; Android mirrors them using the shared API contract in `shared/api-contract/`.

Current version: `3.0.0` (`versionCode 4`). Release APKs are universal and include both `armeabi-v7a` (for broad Android TV compatibility) and `arm64-v8a` (for modern devices and arm64 emulators).

### Build

```bash
cd AndroidTV
./gradlew test assembleDebug
```

Release build (CI generates a temporary keystore):

```bash
cd AndroidTV
./gradlew assembleRelease
```

### Key files

| File | Role |
|---|---|---|
| `data/TigerTVRepository.kt` | Config load, search, fetch orchestration |
| `data/MacCMSApiClient.kt` | MacCMS `ac=list/detail` requests |
| `data/ConfigDataSource.kt` | Remote config + RAW fallback + local cache |
| `data/PlaybackUrlResolver.kt` | HTML → m3u8 URL extraction |
| `domain/Models.kt` | `SearchResponse`, `FetchResponse`, `EpisodeLink` |
| `ui/TigerTVApp.kt` | Compose navigation / app entry |
| `ui/home/HomeScreen.kt` | Search + history |
| `ui/results/ResultsScreen.kt` | TV result grid |
| `ui/episodes/EpisodesScreen.kt` | Episode grid |
| `ui/player/PlayerScreen.kt` | Media3 ExoPlayer wrapper |

### TV constraints

- All interactive UI must be focusable and navigable via D-pad.
- Use large touch targets, high contrast focus state, and dark theme.
- The app declares `android.software.leanback` and sets `touchscreen` not required.

## Cross-Platform Sync

- The output contract for `search`/`fetch` is documented in `shared/api-contract/`.
- Any change to CLI output fields or parsing rules must update `shared/api-contract/` and trigger a review of both `AndroidTV` and `macOS`.
- Contract fixtures in `shared/api-contract/fixtures/` should be used by both CLI and Android tests when possible.

## Network Requirements

Runtime requires access to:
1. `cdn.jsdelivr.net` and/or `raw.githubusercontent.com` (config fetch — RAW is the fallback)
2. Target resource site APIs (varies by site)

Without this access, every command fails at config load unless `--source` is used or a valid cache exists. The script will still run from an expired cache as a last-resort fallback. The Android app mirrors the same CDN-first priority using its local cache.
