import { test } from 'bun:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { integer, parseConfig, parseJson, parseLines, webUrl, workId } from '../src/api.ts'
import { hlsVideoVariants } from '../src/hls-loader.ts'
import { filterPlaylist, effectiveTime, originalTime } from '../src/hls.ts'
import { autoMatch, shouldUpgrade } from '../src/danmaku/selection.ts'
import { DanmakuClient } from '../src/danmaku/client.ts'
import { drawComments, fitsDanmaku } from '../src/danmaku/canvas.ts'
import { matchingEpisode, titleCandidates, titleRank } from '../src/danmaku/matching.ts'
import { getBookmark, getPosition, getRecent, getSettings, getSpeed, getVolume, recordStarted, savePosition, saveSpeed, saveVolume, write } from '../src/storage.ts'

const fixture = name => readFileSync(new URL(`../../docs/fixtures/${name}.sample.json`, import.meta.url), 'utf8')
const json = name => JSON.parse(fixture(name))

test('MacCMS input, first-arrival identity, preserved parameters and playback groups', () => {
  const valid = parseJson(fixture('api-integers'), new Set(['value'])).valid
  for (const { value, expected } of valid) assert.equal(integer(value), expected)
  const raw = JSON.parse(fixture('api-integers'), (key, value, ctx) =>
    typeof value === 'number' && ctx?.source ? ctx.source : value)
  for (const value of raw.invalid) assert.throws(() => integer(value))
  const ids = json('aggregation').map(row => workId({ site: { name: row.site }, id: row.vod_id, title: row.vod_name, year: row.vod_year }))
  assert.equal(new Set(ids).size, 3)
  assert.equal(ids[2], ids[3])
  const source = json('detail-raw').list[0]
  assert.deepEqual(parseLines(source.vod_play_url, source.vod_play_from).map(line => [line.name, line.episodes.length]), [['线路A', 2], ['线路B', 1]])
  assert.deepEqual(parseLines('$$$第二集$https://example.test/a?q=$1', 'A$$$B'), [
    { id: '1', name: 'B', episodes: [{ name: '第二集', url: 'https://example.test/a?q=$1' }] },
  ])
  const config = parseConfig({ api_site: {
    a: { name: '🎬A', api: 'https://a.example' },
    b: { name: '🎬B', api: 'https://a.example' },
    c: { name: '🎬C', api: 'https://c.example', _comment: null },
  } })
  assert.deepEqual(config, [{ name: '🎬A', api: 'https://a.example', gateway: 'a' }])
  assert.equal(webUrl('https://media.example/剧集%2F/第一集.m3u8?token=猫+2'),
    'https://media.example/%E5%89%A7%E9%9B%86%2F/%E7%AC%AC%E4%B8%80%E9%9B%86.m3u8?token=%E7%8C%AB+2')
})

test('Android and Web remove the same interval and preserve the key and relative URLs', () => {
  const { uris, host, playlist, expected, startMs, endMs } = json('hls-filter')
  const result = filterPlaylist(playlist, host)
  assert.deepEqual(result, { text: expected, removed: [{ start: startMs / 1000, end: endMs / 1000 }] })
  assert.ok(Math.abs(originalTime(startMs / 1000, result.removed) - endMs / 1000) < 1e-9)
  assert.ok(Math.abs(effectiveTime(endMs / 1000, result.removed) - startMs / 1000) < 1e-9)
  assert.equal(effectiveTime(startMs / 1000 + 1, result.removed), startMs / 1000)
  for (const input of [
    playlist.replace(uris[2], 'changed.ts'),
    playlist.replaceAll('#EXT-X-DISCONTINUITY\n', ''),
    playlist.replace('MEDIA-SEQUENCE:0', 'MEDIA-SEQUENCE:0\n#EXT-X-MEDIA-SEQUENCE:1'),
    playlist.replace('IV=0x00000000000000000000000000000001', 'KEYFORMAT="drm"'),
    playlist.replace('#EXTINF:5,', '#EXT-X-MAP:URI="init.mp4",BYTERANGE="24"\n#EXTINF:5,'),
    playlist.replace('#EXTINF:5,', '#EXT-X-BYTERANGE:0@0\n#EXTINF:5,'),
  ]) assert.equal(filterPlaylist(input, host), undefined)
})

test('shared adaptive HLS playlists retain initialization maps and original byte offsets', () => {
  const { masters, media } = json('hls-adaptive')
  for (const { playlist, base, variants } of masters)
    assert.deepEqual(Object.fromEntries(hlsVideoVariants(playlist, base)), variants)
  for (const { playlist, host, removed, contains = [], excludes = [] } of media) {
    const output = filterPlaylist(playlist, host)
    assert.deepEqual(output?.removed ?? [], removed.map(([start, end]) => ({ start: start / 1000, end: end / 1000 })))
    if (!removed.length) assert.equal(output, undefined)
    for (const text of contains) assert.ok(output.text.includes(text), text)
    for (const text of excludes) assert.ok(!output.text.includes(text), text)
  }
})

test('match season, date and parts, and upgrade only a compatible cut', () => {
  const fixture = json('danmaku-matching')
  for (const { query, candidate, year, rank } of fixture.titleCases) {
    assert.equal(titleRank(query, candidate, year) ?? null, rank)
  }
  for (const { label, id } of fixture.episodeCases) {
    assert.equal(matchingEpisode(fixture.episodes, label)?.id ?? null, id)
  }
  assert.equal(shouldUpgrade({ comments: [{mode: 1}], duration: 1600 }, { comments: [{mode: 1}, {mode: 1}], duration: 1608 }), true)
  assert.equal(shouldUpgrade({ comments: [{mode: 1}], duration: 1600 }, { comments: [{mode: 1}, {mode: 1}], duration: 1620 }), false)
  for (const { label, episodes, id, workSeason } of fixture.localEpisodeCases) {
    assert.equal(matchingEpisode(episodes, label, workSeason)?.id ?? null, id, label)
  }
  for (const { query, titles, ids } of fixture.titleCandidateCases) {
    const candidates = titles.map((title, index) => ({ id: index + 1, title, source: 'test' }))
    assert.deepEqual(titleCandidates(query, candidates, '').map(match => match.item.id), ids, query)
  }
})

test('work-wide speed, volume settings, separate episode progress and recent deduplication', () => {
  const store = new Map()
  globalThis.localStorage = { getItem: key => store.get(key), setItem: (key, value) => store.set(key, value) }
  assert.equal(getSettings().autoNext, true)
  assert.equal(getSettings().filterAds, true)
  const first = { site: { name: '360', api: '' }, id: 10 }
  const second = { site: { name: '暴风', api: '' }, id: 22 }
  const work = { id: '逐玉', display: first, sources: [first, second] }
  const episode = { work, source: first, line: '0', episode: '第1集', index: 0 }
  const other = { ...episode, source: second, line: '1' }
  const next = { ...episode, episode: '第2集', index: 1 }
  recordStarted(episode, 1)
  recordStarted({ ...episode, work: { ...work, id: '其他作品' } }, 2)
  recordStarted({ ...other, work: { ...work, display: second } }, 3)
  assert.equal(getRecent()[0].work.display.id, first.id)
  assert.equal(getRecent()[0].source.id, second.id)
  assert.equal(getBookmark(work.id, first).episode, episode.episode)
  assert.deepEqual(getRecent().map(r => [r.work.id, r.at]), [[work.id, 3], ['其他作品', 2]])
  saveSpeed(episode.work.id, 1.5, true)
  assert.equal(getSpeed(other.work.id, true), 1.5)
  assert.equal(getSpeed(other.work.id, false), 1)
  saveSpeed(work.id, 2, false)
  assert.equal(getSpeed(work.id, true), 1.5)
  savePosition(episode, 15.23)
  savePosition(next, 4)
  savePosition(other, 2)
  assert.deepEqual([getPosition(episode), getPosition(next), getPosition(other)], [15.23, 4, 2])
  saveVolume(0.42, true, true)
  assert.deepEqual(getVolume(true), { volume: 0.42, muted: true })
  assert.deepEqual(getVolume(false), { volume: 1, muted: false })
})

test('unverified playlist signals never remove a segment', () => {
  for (const { name, playlist } of json('hls-smart')) assert.equal(filterPlaylist(playlist), undefined, name)
})

test('source profiles match a splice across changed asset URIs and keep negative controls', () => {
  for (const { name, host, playlist, removed } of json('hls-source-groups')) {
    const result = filterPlaylist(playlist, host)
    assert.deepEqual(result?.removed ?? [], removed.map(([start, end]) => ({ start: start / 1000, end: end / 1000 })), name)
    if (result) assert.equal(originalTime(removed[0][0] / 1000, result.removed), removed[0][1] / 1000, name)
  }
})

test('conservative work identities are stable across sources', () => {
  const identities = new Map()
  for (const row of json('work-identity')) {
    const id = workId({site:{name:row.site}, id:row.vod_id, title:row.vod_name, year:row.vod_year})
    if (identities.has(row.expected)) assert.equal(id, identities.get(row.expected))
    identities.set(row.expected, id)
  }
  assert.equal(new Set(identities.values()).size, identities.size)
})


test('reopening a remembered episode reuses a parsed track scoped to its API address', async () => {
  const originalFetch = globalThis.fetch
  const requested = []
  globalThis.fetch = async url => {
    requested.push(new URL(url).pathname)
    return Response.json({ videoDuration: 100, comments: [{ p: '4,1,16777215', m: 'fixture' }] })
  }
  try {
    const signal = new AbortController().signal
    const memory = { episode: { title: 7, id: 9 } }
    const tracks = []
    for (const address of ['https://fixture.test/a', 'https://fixture.test/a', 'https://fixture.test/b']) {
      const client = new DanmakuClient(address, signal)
      await autoMatch(client, '作品', '', '第1集', memory, track => tracks.push(track), signal)
    }
    assert.deepEqual(requested, ['/a/api/v2/comment/9', '/b/api/v2/comment/9'])
    assert.equal(tracks.length, 3)
    assert.equal(tracks[0], tracks[1])
    assert.notEqual(tracks[1], tracks[2])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('automatic danmaku uses a same-date fallback while the preferred track is pending', async () => {
  const preferred = Promise.withResolvers()
  const fallbackStarted = Promise.withResolvers()
  const received = []
  const requested = []
  const fallback = { duration: 60, comments: [{ mode: 1, time: 1, text: '备用', color: 0xffffff }] }
  const signal = AbortSignal.timeout(1000)
  const client = {
    search: async () => [
      { id: 1, title: '日期综艺', source: 'imgo' },
      { id: 2, title: '日期综艺', source: 'migu' },
      { id: 3, title: '日期综艺', source: '360' },
    ],
    episodes: async id => id === 2 ? [] : [{
      id, title: '第6期上：节目标题 2026-11-19期', number: '11', seasonId: '',
    }],
    track: id => {
      requested.push(id)
      if (id === 1) return preferred.promise
      fallbackStarted.resolve()
      return Promise.resolve(fallback)
    },
  }
  const matching = autoMatch(client, '日期综艺', '2026', '第20261119期上', {}, track => received.push(track), signal)
  await fallbackStarted.promise
  assert.deepEqual(requested, [1, 3])
  assert.deepEqual(received, [])
  preferred.reject(new DOMException('首选超时', 'TimeoutError'))
  await matching
  assert.deepEqual(received, [fallback])
})

test('duplicate episode labels and source IDs keep separate positions', () => {
  const source = { id: 1, site: { name: 'same site' } }
  const selection = { work: { id: 'duplicate-test' }, source, line: 'first', episode: 'Episode', index: 0 }
  const anotherIndex = { ...selection, index: 1 }
  const anotherSource = { ...selection, source: { ...source, id: 2 } }
  const otherLine = { ...selection, line: 'second' }
  savePosition(selection, 10)
  savePosition(anotherIndex, 20)
  savePosition(anotherSource, 30)
  savePosition(otherLine, 40)
  assert.deepEqual([selection, anotherIndex, anotherSource, otherLine].map(getPosition), [10, 20, 30, 40])
})

test('lane collision checks entry and catch-up, and a seek redraws the active window', () => {
  for (const sample of json('danmaku-collision')) {
    assert.equal(fitsDanmaku(sample.width, sample.previous, sample.incoming, sample.elapsedMs / 1000, sample.gap), sample.fits)
  }
  const savedDpr = globalThis.devicePixelRatio
  globalThis.devicePixelRatio = 1
  try {
    const painted = []
    const context = {
      setTransform() {}, clearRect() { painted.length = 0 },
      measureText() { return { width: 30 } }, strokeText() {},
      fillText(text) { painted.push(text) },
    }
    const canvas = { clientWidth: 500, clientHeight: 72, width: 0, height: 0, getContext: () => context }
    const track = { comments: [
      { time: 7, mode: 1, text: '滚动', color: 0xffffff },
      { time: 7.1, mode: 5, text: '固定', color: 0xffffff },
      { time: 40, mode: 1, text: '空窗后', color: 0xffffff },
    ] }
    assert.equal(drawComments(canvas, track, 16), 40)
    assert.equal(drawComments(canvas, track, 14.9, true), 0)
    assert.deepEqual(painted, ['滚动'])
    canvas.clientWidth = 300
    assert.equal(drawComments(canvas, track, 14.9), 0)
    assert.deepEqual(painted, ['滚动'])
  } finally {
    if (savedDpr === undefined) delete globalThis.devicePixelRatio
    else globalThis.devicePixelRatio = savedDpr
  }
})
