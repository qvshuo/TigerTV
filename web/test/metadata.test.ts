import { expect, test } from 'bun:test'
import { createMetadata, loadSources, metadataPrefix } from '../server/metadata.ts'

const sources = loadSources({ api_site: {
  first: { name: '🎬 Alpha', api: 'https://catalog.test/api?ac=list&keep=1', recommendation: 'ad_filter_verified' },
  second: { name: '🎬 Beta', api: 'https://beta.test/api' },
  duplicate: { name: '🎬 Same', api: 'https://beta.test/api' },
  proxy: { name: '🎬 Wrapper', api: 'https://wrapped.test/api?url=x' },
  local: { name: '🎬 Local', api: 'https://127.0.0.1/api' },
  commented: { name: '🎬 Commented', api: 'https://ignored.test', _comment: null },
} })

test('metadata routes keep source order, enforce CORS and project only catalog fields', async () => {
  const paths: URL[] = []
  const handle = createMetadata(sources, 'https://web.test', async (target, init) => {
    const url = new URL(String(target))
    paths.push(url)
    expect(init?.redirect).toBe('error')
    expect(new Headers(init?.headers).get('User-Agent')).toContain('Safari/')
    if (url.hostname === 'm.douban.com') {
      expect(new Headers(init?.headers).get('Referer')).toBe('https://m.douban.com/tv/')
      expect(url.searchParams.get('count')).toBe('36')
      return Response.json({ total: 50, subject_collection_items: [{ title: '片', pic: { large: 'poster', unrelated: true } }] })
    }
    expect(url.hostname).toBe('catalog.test')
    expect(url.searchParams.get('keep')).toBe('1')
    return new Response('{"code":1.0,"pagecount":2,"list":[{"vod_id":9,"vod_name":"片","vod_play_url":"第1集$https://cdn.test/stream.m3u8","ignored":"x"}]}')
  })
  const call = (path: string, options?: RequestInit) =>
    handle(new Request('http://localhost' + metadataPrefix + path, options))
  const config = await call('config', { headers: { Origin: 'https://web.test' } })
  expect(config.headers.get('Access-Control-Allow-Origin')).toBe('https://web.test')
  const catalog = (await config.json()).api_site
  expect(Object.keys(catalog)).toEqual(['first', 'second'])
  expect(catalog.first.recommendation).toBe('ad_filter_verified')
  expect((await call('config', { headers: { Origin: 'https://other.test' } })).headers.has('Access-Control-Allow-Origin')).toBe(false)
  for (const [route, status] of [
    ['detail/other?id=1', 404], ['search/proxy?q=x', 404], ['search/first?q=x&page=101', 400],
    ['detail/first?id=1&id=2', 400], ['detail/first?id=2147483648', 400], ['search/first?q=%GG', 400],
    ['search/first?q=x&url=https://cdn.test/a.m3u8', 400], ['hot/tv_domestic?url=x', 400], ['hot/tv_domestic?start=1&start=2', 400],
    ['media/first', 404],
  ] as const) expect((await call(route)).status).toBe(status)
  expect((await call('config', { method: 'POST' })).status).toBe(405)
  expect(paths).toHaveLength(0)

  const keyword = new URLSearchParams({ q: '逐玉 +', page: '2' })
  expect(await (await call('search/first?' + keyword)).json()).toEqual({
    code: '1.0', pagecount: '2', list: [{ vod_id: '9', vod_name: '片' }],
  })
  expect(paths[0].searchParams.get('wd')).toBe('逐玉 +')
  expect(paths[0].searchParams.get('pg')).toBe('2')
  const detail = await (await call('detail/first?id=9')).json()
  expect(paths[1].searchParams.get('ids')).toBe('9')
  expect(detail.list[0].vod_play_url).toBe('第1集$https://cdn.test/stream.m3u8')
  const recommendations = await (await call('hot/tv_domestic?start=36')).json()
  expect(paths[2].searchParams.get('start')).toBe('36')
  expect(recommendations.total).toBe(50)
  expect(recommendations.subject_collection_items[0].pic).toEqual({ large: 'poster' })
})

test('metadata enforces response size, deadlines and concurrent upstream limits', async () => {
  const input = () => new Request('http://localhost/api/metadata/detail/first?id=9')
  const oversized = createMetadata(sources, '', async () => new Response(new Uint8Array(10 * 1024 * 1024 + 1)))
  expect((await oversized(input())).status).toBe(502)
  const timeout = createMetadata(sources, '', async (_, init) => {
    await new Promise((_, reject) => init?.signal?.addEventListener('abort', () => reject(init.signal?.reason)))
    return Response.json({})
  }, 10)
  expect((await timeout(input())).status).toBe(502)

  const gate = Promise.withResolvers<void>()
  const handler = createMetadata(sources, '', async () => {
    await gate.promise
    return Response.json({ code: 1, list: [] })
  })
  const parallel = Array.from({ length: 20 }, () => handler(input()))
  expect((await handler(input())).status).toBe(503)
  expect((await handler(new Request('http://localhost/api/metadata/config'))).status).toBe(200)
  gate.resolve()
  expect((await Promise.all(parallel)).every(result => result.ok)).toBe(true)
})

test('serves only public Douban JPEG posters with the required referer', async () => {
  const bytes = Uint8Array.of(0xff, 0xd8, 0xff, 0xd9)
  const calls: string[] = []
  const handle = createMetadata(sources, '', async (url, init) => {
    calls.push(String(url))
    expect(new Headers(init?.headers).get('Referer')).toBe('https://m.douban.com/')
    return new Response(bytes, { headers: { 'Content-Type': 'image/jpeg' } })
  })
  for (const path of ['poster/other/p123.jpg', 'poster/img3/x.m3u8', 'poster/img3/p123.jpg?url=https://example.com']) {
    expect((await handle(new Request('http://localhost' + metadataPrefix + path))).status).toBe(404)
  }
  const response = await handle(new Request('http://localhost' + metadataPrefix + 'poster/img3/p123.jpg'))
  expect(calls).toEqual(['https://img3.doubanio.com/view/photo/m_ratio_poster/public/p123.jpg'])
  expect(response.headers.get('Content-Type')).toBe('image/jpeg')
  expect(new Uint8Array(await response.arrayBuffer())).toEqual(bytes)
})

test('default HTTP and nested metadata endpoints keep a fixed upstream', async () => {
  const raw = await Bun.file(new URL('../../shared/LunaTV-config.json', import.meta.url)).json()
  const sites = loadSources(raw)
  const expected = new Set(['dyttzyapi.com', 'bdzy1.com', 'lovedan.net'])
  const handle = createMetadata(sites, '', async (target, init) => {
    const url = new URL(String(target))
    const inner = new URL(url.searchParams.get('url') ?? url.href)
    expect(init?.redirect).toBe('error')
    expect(inner.searchParams.get('wd')).toBe('中文&url=http://127.0.0.1')
    expect(inner.searchParams.has('url')).toBe(false)
    expect(inner.pathname).toBe('/api.php/provide/vod')
    expect(['caiji.dyttzyapi.com', 'api.apibdzy.com', 'lovedan.net']).toContain(inner.hostname)
    return Response.json({ code: 1, list: [] })
  })
  for (const key of expected) {
    expect(sites.has(key)).toBe(true)
    const query = new URLSearchParams({ q: '中文&url=http://127.0.0.1' })
    expect((await handle(new Request(`http://local/api/metadata/search/${key}?${query}`))).status).toBe(200)
    query.set('url', 'http://127.0.0.1')
    expect((await handle(new Request(`http://local/api/metadata/search/${key}?${query}`))).status).toBe(400)
  }
  expect((await handle(new Request('http://local/api/metadata/media/bdzy1.com'))).status).toBe(404)
})
