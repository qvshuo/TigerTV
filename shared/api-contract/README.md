# TigerTV Cross-Platform API Contract

This directory documents the shared contract implemented by Kotlin Multiplatform core, the native Agent CLI, macOS and Android TV. `tigertv-cli.py` remains the migration test oracle.

Any change to the CLI output format, config loading rules, or playback URL resolution behavior must be reflected here so the platforms stay in sync.

## Output models

### `SearchResponse`

```json
{
  "keyword": "逐玉",
  "results": [
    {
      "site": "🎬-爱奇艺-",
      "vod_id": 73480,
      "vod_name": "逐玉",
      "vod_time": "2026-01-01",
      "vod_remarks": "更新至 10 集"
    }
  ]
}
```

Fields:

- `keyword`: the original search keyword.
- `results`: array of search results.
  - `site`: exact `name` value from the source config. Emojis and punctuation are significant.
  - `vod_id`: integer content id for the site.
  - `vod_name`: display title.
  - `vod_time`: update/release time string; missing values are output as `""`.
  - `vod_remarks`: status string (e.g. episode count, resolution); missing values are output as `""`.
  - `vod_pic`: cover image URL (already a full, directly fetchable URL from the upstream MacCMS `ac=list` response); missing values are output as `""`. May be plain HTTP — both apps allow cleartext image loads.

### `FetchResponse`

```json
{
  "vod_id": 73480,
  "site": "🎬-爱奇艺-",
  "vod_play_url": [
    {
      "name": "第01集",
      "url": "https://example.com/index.m3u8"
    }
  ],
  "vod_down_url": []
}
```

Fields:

- `vod_id`: same id passed to fetch (not an upstream detail ID).
- `site`: exact source name.
- `vod_play_url`: list of playable episodes.
- `vod_down_url`: list of downloadable episodes (may be empty).

Each episode link contains:

- `name`: episode / quality label.
- `url`: raw play URL or intermediate HTML page.

## Config loading rules

Config shape:

```json
{
  "cache_time": 7200,
  "api_site": {
    "iqiyi.example": {
      "name": "🎬-爱奇艺-",
      "api": "https://example.com/api.php/provide/vod",
      "detail": "https://example.com"
    }
  }
}
```

Loading priority:

1. Custom `--source` path (CLI only) or equivalent local override.
2. Fresh local cache if present and not expired (TTL 1 day).
3. Fetch from `CONFIG_CDN_URL` (jsdelivr, 10s timeout).
4. Fetch from `CONFIG_URL` (GitHub RAW, 5s timeout).
5. Expired local cache as last resort.
6. No usable config → error.

Source filtering:

- Keep entries where `name` contains `🎬`.
- Discard entries that contain a `_comment` key (disabled / backup sources).
- Discard entries with an empty API URL. Deduplicate by exact API URL, preserving the first entry.
- `_comment` presence disables the entry even when its value is null.
- All production clients use the same core implementation.

## MacCMS API rules

Search:

- Endpoint: `{api}?ac=list&wd={keyword}&pagesize=100`
- When `code` is present and non-null, Python integer coercion must yield 1. Missing/null code is tolerated for reference compatibility.
- `list` contains items with `vod_id` and `vod_name`.

Detail:

- Endpoint: `{api}?ac=detail&ids={vod_id}`
- When `code` is present and non-null, Python integer coercion must yield 1. Missing/null code is tolerated for reference compatibility.
- `list[0]` contains `vod_play_url` and `vod_down_url`.

Play URL format:

- Within one group episodes are joined by `#`.
- Groups are separated by `$$$`.
- Each item is `name$url`.
- For display, parse all episodes. For `quanx` or first-playlist-only use cases, take the first URL of each group.

Error handling:

- A full config load failure is fatal.
- A single site failure during search is logged and skipped; other sites continue.
- `fetch` failures are propagated to the user.

## Playback URL resolution

Inputs are episode `url` values from `FetchResponse`.

Rules (same for CLI, macOS, and Android):

1. If the URL path ends with `.m3u8` or `.mp4`, use it directly.
2. Otherwise, fetch the URL.
3. If the response body starts with `#EXTM3U`, return the original URL (some endpoints return raw HLS playlists).
4. Otherwise, treat the response as HTML and extract the first absolute `.m3u8` URL (`https?://...\.m3u8(?:\?...)?`).
5. If no absolute URL, extract the first quoted relative `.m3u8` path, percent-decode it, and resolve it against the base URL.
6. Support query strings and escaped slashes (`\/`).
7. Failure to find a media URL → playback error.

## Fixtures

See [`fixtures/`](./fixtures/) for sample config, search responses, detail responses, and HTML samples used by tests on all platforms.

## Shared cache and bridge contracts

New GUI services use a `shared-v1` directory. Existing history/cache migration is not required.

- Config file `tigertv-cli-config-cache.json`: `{"fetchedAtMillis": <epoch ms>, "config": <raw object>}`. Store pre-filter config, apply filtering at read time; TTL 1 day. Platform atomic replacement is required.
- `cover-fallback-cache.json`: JSON object keyed by `site-vodId`, each entry `{"url":"...","fetchedAtMillis":<epoch ms>}`. TTL 7 days. Failed/empty detail lookups are not cached; concurrent fallback requests are capped at 4.
- Image byte caches remain GUI-specific with a 7-day TTL. macOS uses CoverImageCache; Android uses Coil plus pruning.
- Search cache is 10 minutes for GUI, disabled for the CLI. History file `search-history.json` is a distinct ordered JSON array capped at 20, kept in the GUI persistent data directory (Application Support / Android filesDir), independently of disposable caches.
- API, config and HTML/HLS responses are capped at 10MB; Unicode URL bytes are percent-encoded without changing existing escapes or plus signs.

The native GUI JSON envelope is versioned independently from CLI output: `{"version":1,"data":...}` or `{"version":1,"error":"..."}`. See [bridge lifecycle and operations](../../docs/cross-platform.md). GUI calls the library directly, never the CLI.

CLI output remains unwrapped. Success/help/closed downstream pipe exit 0; business errors exit 1; parameter errors exit 2. Errors use stderr, logs use files. The macOS native CLI must run without JVM or Python.
