# TigerTV Cross-Platform API Contract

This directory documents the shared contract between `tigertv-cli.py`, the macOS app, and the Android TV app.

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

- `vod_id`: same id passed to fetch.
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
- macOS and Android mirror this filtering before searching.

## MacCMS API rules

Search:

- Endpoint: `{api}?ac=list&wd={keyword}&pagesize=100`
- Success response has `code == 1`.
- `list` contains items with `vod_id` and `vod_name`.

Detail:

- Endpoint: `{api}?ac=detail&ids={vod_id}`
- Success response has `code == 1`.
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

## Cover fallback URL disk cache

All GUI platforms persist lazily-resolved cover URLs (most sites omit `vod_pic` in
`ac=list` but return it in `ac=detail`) so a process restart does not re-issue detail
requests. The on-disk schema is shared and must stay in sync:

- File name: `cover-fallback-cache.json` (macOS: `~/Library/Caches/TigerTV/...`;
  Android: app cache dir). Written atomically (temp file + rename / `.atomic`).
- Top level: JSON object keyed by `"<site>-<vodId>"`.
- Entry shape: `{"url": "<cover url>", "fetchedAt": "<RFC3339 Date>"}` (macOS) /
  `{"url": "<cover url>", "fetchedAtMillis": <epoch ms>}` (Android — same field set,
  timestamp encoding differs by platform serializer).
- TTL: **7 days** for both the URL store and the cover *image* byte cache
  (macOS deletes expired files by mtime; Android prunes its Coil DiskCache directory
  at app start — Coil itself has no TTL, only an LRU bound).
- Failures are never cached: an empty/failed detail lookup leaves no entry, so the
  card retries naturally when it re-enters the viewport.

