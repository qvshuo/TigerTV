#!/usr/bin/env python3
"""Sequential offline survey of explicitly reviewed episodes, without a DANMU_LIMIT override.
TIGERTV_DANMU_URL supplies the saved service/token. Raw comments stay in a private cache.
"""
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import signal
import time
import urllib.parse
import urllib.request

CAP = 10 * 1024 * 1024
UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/27.2 Safari/605.1.15'


def expired(*_):
    raise TimeoutError('Request deadline exceeded')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('samples', type=Path, help='Reviewed work/animeId/episodeId/title records')
    parser.add_argument('--cache-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    base = os.environ['TIGERTV_DANMU_URL'].rstrip('/')
    assert urllib.parse.urlsplit(base).scheme in ('http', 'https')
    args.cache_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    signal.signal(signal.SIGALRM, expired)
    searches, details, rows = {}, {}, []
    deadline = time.monotonic() + 480
    stopped = None
    downloaded = 0

    def get(path):
        nonlocal downloaded
        signal.setitimer(signal.ITIMER_REAL, min(45, max(0.001, deadline - time.monotonic())))
        try:
            request = urllib.request.Request(base + '/api/v2/' + path,
                                            headers={'User-Agent': UA, 'Accept-Encoding': 'gzip'})
            with urllib.request.urlopen(request, timeout=10) as response:
                body = response.read(CAP + 1)
                wire = len(body)
                if wire > CAP:
                    raise ValueError('Wire response cap exceeded')
                if response.headers.get('Content-Encoding') == 'gzip':
                    body = gzip.GzipFile(fileobj=io.BytesIO(body)).read(CAP + 1)
                if len(body) > CAP:
                    raise ValueError('Decoded response cap exceeded')
                downloaded += len(body)
                if downloaded > 64 * 1024 * 1024:
                    raise ValueError('Total response cap exceeded')
                return json.loads(body), body, wire
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)

    for sample in json.loads(args.samples.read_text()):
        row = dict(sample)
        if stopped or time.monotonic() >= deadline:
            row.update(status='not_requested', reason=stopped or 'total_deadline')
            rows.append(row)
            args.output.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n')
            continue
        started = time.monotonic()
        try:
            work, aid, eid = sample['work'], int(sample['animeId']), int(sample['episodeId'])
            assert aid > 0 and eid > 0
            if work not in searches:
                searches[work] = get('search/anime?keyword=' + urllib.parse.quote(work))[0]['animes']
            title = next(a for a in searches[work] if a['animeId'] == aid)
            row['source'] = title['source']
            if aid not in details:
                details[aid] = get(f'bangumi/{aid}')[0]['bangumi']['episodes']
            episode = next(e for e in details[aid] if e['episodeId'] == eid)
            # IDs are service-local. Refuse to silently sample a reused ID.
            assert title['animeTitle'] == sample['animeTitle']
            assert episode['episodeTitle'] == sample['episodeTitle']
            data, raw, wire = get(f'comment/{eid}?format=json&duration=true')
            comments = [c for c in data.get('comments', [])
                        if c.get('p', '').split(',')[1:2] == ['1'] and c.get('m', '').strip()]
            (args.cache_dir / f'{eid}-comments.json').write_bytes(raw)
            row.update(status='success', source=title['source'], mode1=len(comments),
                       total=len(data.get('comments', [])), durationSeconds=data.get('videoDuration'),
                       wireBytes=wire, jsonBytes=len(raw),
                       commentSha256=hashlib.sha256(json.dumps(comments, ensure_ascii=False, sort_keys=True).encode()).hexdigest())
        except Exception as error:
            row.update(status='failed', error=type(error).__name__, statusCode=getattr(error, 'code', None))
            if getattr(error, 'code', None) == 429:
                stopped = 'rate_limited'
                row['retryAfter'] = error.headers.get('Retry-After')
        row['seconds'] = round(time.monotonic() - started, 3)
        rows.append(row)
        args.output.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n')
        print(row['work'], row.get('source', ''), row['status'], row.get('mode1', ''), flush=True)


if __name__ == '__main__':
    main()
