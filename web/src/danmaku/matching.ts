import selectionRules from '../../../shared/danmaku-selection.json' with { type: 'json' }
import { foldWidth as fold } from '../common/text.ts'
import type { Title, DanmakuEpisode } from './client.ts'

const textPatterns = selectionRules.textPatterns
const seasonPattern = new RegExp(textPatterns.season, 'i')
const ordinal = new RegExp(textPatterns.ordinal, 'g')
const special = new RegExp(textPatterns.special, 'i')
const metadata = new RegExp(textPatterns.metadata, 'gi')
const fromTag = new RegExp(textPatterns.sourceSuffix, 'i')
const yearPattern = new RegExp(textPatterns.year, 'g')
const aliasPattern = new RegExp(textPatterns.alias, 'gi')
const datePattern = new RegExp(textPatterns.date)
const bonusPattern = new RegExp(textPatterns.bonus)
const blooperPattern = new RegExp(textPatterns.blooper)
const partPattern = new RegExp(textPatterns.part)
const variantPart = new RegExp(textPatterns.variantPart)
const numberedPart = new RegExp(textPatterns.numberedPart)
const numericSeparator = new RegExp(textPatterns.numericSeparator, 'gu')
const englishEpisode = new RegExp(textPatterns.englishEpisode, 'i')

function number(text = ''): number | undefined {
  if (/^\d+$/.test(text)) {
    const value = Number(text)
    return value > 0 && value <= 2147483647 ? value : undefined
  }
  if (/^[零〇一二两兩三四五六七八九]+$/.test(text)) {
    return number([...text].map(char => '零一二三四五六七八九'.indexOf(
      char === '两' || char === '兩' ? '二' : char === '〇' ? '零' : char)).join(''))
  }
  let sum = 0
  let digit = 0
  for (const char of text) {
    if (char === '十' || char === '百') {
      sum += (digit || 1) * (char === '十' ? 10 : 100)
      digit = 0
    } else {
      digit = '零一二三四五六七八九'.indexOf(char === '两' || char === '兩' ? '二' : char === '〇' ? '零' : char)
      if (digit < 0) return
    }
  }
  return sum + digit || undefined
}
export function season(text: string) {
  const match = seasonPattern.exec(fold(text))
  return number(match?.[1] ?? match?.[2])
}

function matchKey(text: string) {
  return text.toLowerCase().split(numericSeparator).map(part =>
    part.replace(/[\p{P}\p{Z}\s・]+/gu, '').replace(/[0-9]+/g, run => run.replace(/^0+(?=\d)/, ''))).join('+')
}

function similarity(left: string, right: string, threshold = selectionRules.matchThreshold) {
  if (!left || !right) return 0
  if (left === right) return 1
  const size = Math.max(left.length, right.length)
  const length = Math.min(left.length, right.length)
  if (length < selectionRules.minimumFuzzyLength || length < size * threshold ||
    (left.match(/[0-9]+/g) ?? []).join(',') !== (right.match(/[0-9]+/g) ?? []).join(',')) return 0
  const row = Array.from({ length: right.length + 1 }, (_, index) => index)
  for (let i = 0; i < left.length; i++) {
    let diagonal = row[0]
    row[0] = i + 1
    for (let j = 0; j < right.length; j++) {
      const previous = row[j + 1]
      row[j + 1] = Math.min(previous + 1, row[j] + 1, diagonal + Number(left[i] !== right[j]))
      diagonal = previous
    }
  }
  return 1 - row[right.length] / size
}

function titleParts(raw: string) {
  const text = fold(raw).replace(fromTag, '').replace(metadata, '')
  const year = [...text.matchAll(yearPattern)][0]
  const aliases = [...text.matchAll(aliasPattern)].map(match => match[1])
  const main = text.replace(aliasPattern, '').replace(yearPattern, '').replace(/[《》]/g, '').trim()
  const key = (name: string) => matchKey(name.replace(seasonPattern, ''))
  const names = [main, ...aliases].map(key).filter(Boolean)
  return { main, key: key(main), names, year: Number(year?.[1] ?? year?.[2]) || undefined, season: season(main) }
}

export const danmakuSearchKeyword = (query: string) => titleParts(query).main

function titleMatch(expected: ReturnType<typeof titleParts>, actual: ReturnType<typeof titleParts>, year: string, desiredSeason?: number) {
  const expectedYear = expected.year ?? (/^(19|20)\d{2}$/.test(year) ? Number(year) : undefined)
  const expectedSeason = desiredSeason ?? expected.season
  if ((expectedYear && actual.year && expectedYear !== actual.year) ||
    (expectedSeason ?? 1) !== (actual.season ?? 1) || !actual.key) return
  let score = 0
  for (const left of expected.names) for (const right of actual.names) score = Math.max(score, similarity(left, right))
  if (score < selectionRules.matchThreshold ||
    special.exec(expected.main)?.[0].toLowerCase() !== special.exec(actual.main)?.[0].toLowerCase()) return
  const rank = expected.key === actual.key ? expectedYear && expectedYear === actual.year ? 0 : 1 : score === 1 ? 2 : 3
  return { rank, score, identity: JSON.stringify([actual.key, actual.year, actual.season ?? 1]) }
}

export function titleRank(query: string, candidate: string, year: string, desiredSeason?: number) {
  return titleMatch(titleParts(query), titleParts(candidate), year, desiredSeason)?.rank
}

export function titleCandidates(query: string, candidates: Title[], year: string, desiredSeason?: number) {
  const expected = titleParts(query)
  const matches = candidates.flatMap(item => {
    const match = titleMatch(expected, titleParts(item.title), year, desiredSeason)
    return match ? [{ ...match, item }] : []
  })
  const rank = Math.min(...matches.map(match => match.rank))
  const identities = new Map<string, number>()
  for (const match of matches) {
    if (match.rank === rank) identities.set(match.identity, Math.max(match.score, identities.get(match.identity) ?? 0))
  }
  const [best, runnerUp] = [...identities].sort((a, b) => b[1] - a[1])
  return best && (!runnerUp || best[1] - runnerUp[1] >= selectionRules.ambiguityGap)
    ? matches.filter(match => match.rank === rank && match.identity === best[0]) : []
}

function specialKind(value: string) {
  if (['特别', '特别篇', 'sp', 'special'].includes(value)) return '特别篇'
  if (value === 'extra') return '番外'
  if (value === 'trailer' || value === 'preview') return '预告'
  return value
}

function label(raw: string) {
  const text = fold(raw).replace(metadata, '').trim()
  const separator = text.indexOf(':')
  const heading = separator < 0 ? text : text.slice(0, separator)
  const subtitle = separator < 0 ? '' : text.slice(separator + 1).trimStart()
  const subtitleSpecial = special.exec(subtitle)
  const headingVariant = special.exec(heading)
  const tag = headingVariant?.[0] ?? (subtitleSpecial?.index === 0 ? subtitleSpecial[0] : undefined)
  const variant = bonusPattern.test(heading) ? '加更' : blooperPattern.test(heading) ? '花絮特辑' :
    tag && specialKind(tag.toLowerCase())
  if (/上下篇|上中下/.test(heading)) return
  const date = text.match(datePattern)?.slice(1).join('')
  const partNumber = number(heading.match(numberedPart)?.[1])
  const part = heading.match(partPattern)?.[1] ?? partNumber?.toString()
    ?? (headingVariant ? heading.slice(headingVariant.index + headingVariant[0].length).match(variantPart)?.[1] : undefined)
  const ordinalMatch = [...heading.matchAll(ordinal)].find(match => {
    const value = number(match[1])
    return value !== undefined && value < 10000
  })
  const n = number(ordinalMatch?.[1])
  const english = heading.match(englishEpisode)
  return { date, part, variant, season: season(heading), kind: ordinalMatch ? ordinalMatch[2] === '期' ? 'period' : 'episode' : undefined,
    number: n && n < 10000 ? n : number(english?.[1]) ?? (!date && /^\d+$/.test(heading) ? number(heading) : undefined) }
}

export function matchingEpisode(episodes: DanmakuEpisode[], name: string, workSeason?: number) {
  const target = label(name)
  if (!target || (target.season && workSeason && target.season !== workSeason)) return
  const expectedSeason = target.season ?? workSeason
  const matches = episodes.flatMap(episode => {
    const title = label(episode.title)
    if (!title) return []
    const actualSeason = title.season ?? episode.season
    if (actualSeason && actualSeason !== (expectedSeason ?? 1)) return []
    if (target.variant !== title.variant || target.part !== title.part) return []
    if (target.kind && title.kind && target.kind !== title.kind) return []
    if (target.date && title.date && target.date !== title.date) return []
    const dateAgrees = Boolean(target.date && target.date === title.date)
    if (target.number && title.number && target.number !== title.number) return []
    if (dateAgrees) return [{ episode, score: title.number === target.number ? 1 : .9 }]
    const episodeNumber = title.number ?? number(episode.number.trim())
    if (target.number && target.number === episodeNumber)
      return [{ episode, score: !title.number ? .5 : target.date === title.date ? 1 : .8 }]
    if (target.number || target.date || title.number || title.date) return []
    const key = (text: string) => matchKey(fold(text).replace(metadata, '').replace(seasonPattern, ''))
    const threshold = selectionRules.episodeMatchThreshold
    const score = similarity(key(name), key(episode.title), threshold)
    return score >= threshold ? [{ episode, score }] : []
  }).sort((a, b) => b.score - a.score)
  const [best, next] = matches
  return best && (!next || best.score - next.score >= selectionRules.ambiguityGap) ? best.episode : undefined
}
