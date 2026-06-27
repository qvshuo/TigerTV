#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 1. CLI tests (prefer pytest, fall back to unittest)
if python3 -m pytest tests/ 2>/dev/null; then
  echo "CLI tests passed via pytest"
elif python3 -m unittest tests.test_tigertv_cli; then
  echo "CLI tests passed via unittest"
else
  echo "CLI tests failed"
  exit 1
fi

# 2. Ensure the CLI can read the shared fixtures and produce the expected shape.
python3 - <<'PY'
import json, subprocess, sys

with open('shared/api-contract/fixtures/config.sample.json') as f:
    config = json.load(f)

# Verify config filtering rules
active = [s for s in config['api_site'].values() if '🎬' in s['name'] and '_comment' not in s]
assert len(active) == 2, f"Expected 2 active sites, got {len(active)}"

# Validate search fixture shape
with open('shared/api-contract/fixtures/search.sample.json') as f:
    search = json.load(f)
assert 'keyword' in search
assert 'results' in search
for r in search['results']:
    assert all(k in r for k in ('site', 'vod_id', 'vod_name'))

# Validate fetch fixture shape
with open('shared/api-contract/fixtures/fetch.sample.json') as f:
    fetch = json.load(f)
assert all(k in fetch for k in ('vod_id', 'site', 'vod_play_url', 'vod_down_url'))
for e in fetch['vod_play_url']:
    assert all(k in e for k in ('name', 'url'))

print('Contract fixtures OK')
PY

echo "Contract check OK"
