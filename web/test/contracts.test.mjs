import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { apiUrl, integer, parseConfig, parseJson, parseLines, webUrl, workId } from '../src/api.ts'
import { filterPlaylist, effectiveTime, originalTime } from '../src/hls.ts'
import { matchingEpisode, shouldUpgrade, titleRank } from '../src/danmaku.ts'
import { getBookmark, getPosition, getRecent, getSettings, getSpeed, getVolume, recordStarted, savePosition, saveSpeed, saveVolume } from '../src/storage.ts'

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
  assert.deepEqual(config, [{ name: '🎬A', api: 'https://a.example' }])
  const url = new URL(apiUrl('https://api.example/?ac=list&a=1&wd=old&ids=2', { ac: 'detail', wd: '逐玉 +' }))
  assert.equal(url.searchParams.get('ac'), 'detail')
  assert.equal(url.searchParams.get('wd'), '逐玉 +')
  assert.equal(url.searchParams.get('a'), '1')
  assert.equal(url.searchParams.get('ids'), null)
  assert.equal(webUrl('https://media.example/剧集%2F/第一集.m3u8?token=猫+2'),
    'https://media.example/%E5%89%A7%E9%9B%86%2F/%E7%AC%AC%E4%B8%80%E9%9B%86.m3u8?token=%E7%8C%AB+2')
})

test('Android and Web remove the same interval and preserve the key and relative URLs', () => {
  const { site, uris, durations, playlist, expected, startMs, endMs } = json('hls-filter')
  const uriFingerprints = ['7c4f99cccfbcb28c', 'fe05efc48c56aa91', 'cc8b29bd966317ba', '51a777b555e02b27']
  // FNV-1a fingerprints for the four sanitized URI strings in the shared fixture.
  const rules = [{ site, uriFingerprints, durations }]
  const result = filterPlaylist(site, playlist, rules)
  assert.deepEqual(result, { text: expected, removed: [{ start: startMs / 1000, end: endMs / 1000 }] })
  assert.ok(Math.abs(originalTime(startMs / 1000, result.removed) - endMs / 1000) < 1e-9)
  assert.ok(Math.abs(effectiveTime(endMs / 1000, result.removed) - startMs / 1000) < 1e-9)
  assert.equal(effectiveTime(startMs / 1000 + 1, result.removed), startMs / 1000)
  for (const input of [
    playlist.replace(uris[2], 'changed.ts'),
    playlist.replace('#EXT-X-DISCONTINUITY\n', ''),
    playlist.replace('MEDIA-SEQUENCE:0', 'MEDIA-SEQUENCE:0\n#EXT-X-MEDIA-SEQUENCE:1'),
    playlist.replace('IV=0x00000000000000000000000000000001', 'KEYFORMAT="drm"'),
    playlist.replace('#EXTINF:5,', '#EXT-X-MAP:URI="init.mp4"\n#EXTINF:5,'),
    playlist.replace('#EXTINF:5,', '#EXT-X-BYTERANGE:24@0\n#EXTINF:5,'),
  ]) assert.equal(filterPlaylist(site, input, rules), undefined)
  assert.equal(filterPlaylist('unknown', playlist, rules), undefined)
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
  recordStarted(other, 3)
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

test('unlisted sources share heuristic intervals and preserve the original key sequence', () => {
  for (const { name, playlist, removed } of json('hls-smart')) {
    const result = filterPlaylist('🎬未收录来源', playlist)
    assert.deepEqual(result?.removed ?? [], removed.map(([start, end]) => ({ start: start / 1000, end: end / 1000 })), name)
    if (name === 'same-directory-without-evidence') {
      const fingerprints = ['e18860e8d17c825b', '27bcbb2440ca6534']
      const rules = [{ site: 'fixture', uriFingerprints: fingerprints, durations: [4, 4], continuous: true }]
      assert.deepEqual(filterPlaylist('fixture', playlist, rules)?.removed, [{ start: 60, end: 68 }])
      assert.equal(filterPlaylist('fixture', playlist.replace('part103.ts', 'part333.ts'), rules), undefined)
    }
    if (!result) continue
    assert.match(result.text, /IV=0x00000000000000000000000000000069/)
    assert.ok(result.text.includes(`#EXT-X-VERSION:${removed.length === 2 ? 3 : 2}\n`))
    assert.match(result.text, /#EXT-X-DISCONTINUITY/)
    assert.doesNotMatch(result.text, /#EXT-X-CUE-/)
    assert.equal(originalTime(60, result.removed), 68)
    assert.equal(effectiveTime(65, result.removed), 60)
    if (name === 'repeated-clear-inserts-without-discontinuities') {
      assert.equal(originalTime(120, result.removed), 136)
      assert.equal(effectiveTime(137, result.removed), 121)
      assert.match(result.text, /IV=0x0000000000000000000000000000006e/)
    }
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
