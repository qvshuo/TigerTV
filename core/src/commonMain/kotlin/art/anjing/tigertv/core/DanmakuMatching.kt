package art.anjing.tigertv.core

import art.anjing.tigertv.domain.foldWidth as folded

private fun chineseNumber(value: String): Int? {
    value.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
    var sum = 0
    var digit = 0
    for (c in value) when (c) {
        in "零〇一二两三四五六七八九" -> digit = when (c) {
            '两' -> 2
            '〇' -> 0
            else -> "零一二三四五六七八九".indexOf(c)
        }
        '十', '百' -> {
            sum += (if (digit == 0) 1 else digit) * if (c == '十') 10 else 100
            digit = 0
        }
        else -> return null
    }
    return (sum + digit).takeIf { it > 0 }
}

private val seasonPattern = Regex(selectionRules.textPatterns.season, RegexOption.IGNORE_CASE)
private fun parseSeason(text: String): Int? = seasonPattern.find(text)
    ?.let { chineseNumber(it.groupValues[1].ifEmpty { it.groupValues[2] }) }
fun danmakuSeason(title: String): Int? = parseSeason(folded(title))

private val fromTag = Regex(selectionRules.textPatterns.sourceSuffix, RegexOption.IGNORE_CASE)
private val metadataTag = Regex(selectionRules.textPatterns.metadata, RegexOption.IGNORE_CASE)
private val yearTag = Regex(selectionRules.textPatterns.year)
private val aliasTag = Regex(selectionRules.textPatterns.alias, RegexOption.IGNORE_CASE)
private val titleSpacing = Regex("[\\p{P}\\p{Z}\\s・]+")
private val numericRun = Regex("[0-9]+")
private val numberSeparator = Regex("(?<=[0-9])[\\p{P}\\p{Z}\\s・]+(?=[0-9])")
private fun matchKey(value: String): String = titleSpacing.replace(
    numericRun.replace(numberSeparator.replace(value.lowercase(), "+")) { match ->
        match.value.trimStart('0').ifEmpty { "0" }
    }, "",
)

private fun similarity(left: String, right: String): Double {
    if (left.isEmpty() || right.isEmpty()) return 0.0
    if (left == right) return 1.0
    val size = maxOf(left.length, right.length)
    val rules = selectionRules
    if (minOf(left.length, right.length) < rules.minimumFuzzyLength ||
        minOf(left.length, right.length) < size * rules.matchThreshold ||
        numericRun.findAll(left).map { it.value }.toList() != numericRun.findAll(right).map { it.value }.toList()) return 0.0
    val row = IntArray(right.length + 1) { it }
    for (i in left.indices) {
        var diagonal = row[0]
        row[0] = i + 1
        for (j in right.indices) {
            val previous = row[j + 1]
            row[j + 1] = minOf(previous + 1, row[j] + 1, diagonal + if (left[i] == right[j]) 0 else 1)
            diagonal = previous
        }
    }
    return 1.0 - row.last().toDouble() / size
}

private data class WorkTitle(val main: String, val names: Set<String>, val year: Int?, val season: Int?)

private fun workTitle(raw: String): WorkTitle {
    val text = metadataTag.replace(fromTag.replace(folded(raw), ""), "")
    val year = yearTag.find(text)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] }.toInt() }
    val season = parseSeason(text)
    val aliases = aliasTag.findAll(text).map { it.groupValues[1] }
    val main = yearTag.replace(aliasTag.replace(text, ""), "").trim()
    fun key(name: String) = matchKey(seasonPattern.replace(name, ""))
    return WorkTitle(main.filterNot { it == '《' || it == '》' }.trim(), (sequenceOf(main) + aliases).map(::key).filter { it.isNotEmpty() }.toSet(), year, season)
}

private data class TitleMatch(val rank: Int, val score: Double, val identity: String)

private fun titleMatch(expected: WorkTitle, actual: WorkTitle, year: String, season: Int?): TitleMatch? {
    val expectedYear = expected.year ?: year.toIntOrNull()?.takeIf { it in 1900..2099 }
    val expectedSeason = season ?: expected.season
    if (expectedYear != null && actual.year != null && expectedYear != actual.year) return null
    if ((expectedSeason ?: 1) != (actual.season ?: 1) || actual.names.isEmpty()) return null
    val score = expected.names.maxOfOrNull { left -> actual.names.maxOf { similarity(left, it) } } ?: return null
    if (score < selectionRules.matchThreshold) return null
    if (specialEpisode.find(expected.main)?.value?.lowercase() != specialEpisode.find(actual.main)?.value?.lowercase()) return null
    val rank = when {
        score < 1.0 -> 3
        expectedSeason != actual.season -> 2
        expectedYear != null && expectedYear == actual.year -> 0
        else -> 1
    }
    return TitleMatch(rank, score, actual.names.first())
}

/** Smaller ranks probe first. Unknown season can only stand in for season one. */
fun danmakuTitleRank(query: String, candidate: String, year: String = "", season: Int? = null): Int? =
    titleMatch(workTitle(query), workTitle(candidate), year, season)?.rank

fun danmakuTitleCandidates(query: String, candidates: List<DanmakuTitle>, year: String, season: Int?): List<Pair<Int, DanmakuTitle>> {
    val expected = workTitle(query)
    val matches = candidates.mapNotNull { item -> titleMatch(expected, workTitle(item.title), year, season)?.let { it to item } }
    val exact = matches.filter { it.first.rank < 3 }
    if (exact.isNotEmpty()) {
        val canonical = exact.filter { it.first.identity in expected.names }
        val chosen = if (canonical.isNotEmpty()) canonical else {
            val bestRank = exact.minOf { it.first.rank }
            val identities = exact.filter { it.first.rank == bestRank }.map { it.first.identity }.distinct()
            val identity = identities.singleOrNull() ?: return emptyList()
            exact.filter { it.first.identity == identity }
        }
        return chosen.map { (match, item) -> match.rank to item }
    }
    val identities = matches.groupBy { it.first.identity }.values.sortedByDescending { group -> group.maxOf { it.first.score } }
    val best = identities.firstOrNull() ?: return emptyList()
    val runnerUp = identities.getOrNull(1)
    if (runnerUp != null && best.maxOf { it.first.score } - runnerUp.maxOf { it.first.score } < selectionRules.ambiguityGap) return emptyList()
    return best.map { (match, item) -> match.rank to item }
}

fun reasonableDanmakuTitle(query: String, candidate: String): Boolean = danmakuTitleRank(query, candidate) != null
fun danmakuSearchKeyword(query: String): String = workTitle(query).main

private val chineseEpisode = Regex(selectionRules.textPatterns.ordinal)
private val englishEpisode = Regex(selectionRules.textPatterns.englishEpisode, RegexOption.IGNORE_CASE)
private val dateEpisode = Regex(selectionRules.textPatterns.date)
private val specialEpisode = Regex(selectionRules.textPatterns.special, RegexOption.IGNORE_CASE)
private val bonusEpisode = Regex(selectionRules.textPatterns.bonus)
private val blooperEpisode = Regex(selectionRules.textPatterns.blooper)
private val episodePart = Regex(selectionRules.textPatterns.part)
private val ambiguousPart = Regex("上下篇|上中下")
private enum class EpisodeKind { Episode, Period }

private data class EpisodeLabel(
    val number: Int?, val date: String?, val season: Int?, val kind: EpisodeKind?, val part: String?, val variant: String?,
)

private fun episodeLabel(label: String): EpisodeLabel? {
    val text = metadataTag.replace(folded(label), "").trim()
    // After an explicit separator, the remainder can be a synopsis (e.g. “出发前采”).
    val heading = text.substringBefore(':')
    val subtitle = text.substringAfter(':', "").trimStart()
    val variant = when {
        bonusEpisode.containsMatchIn(heading) -> "加更"
        blooperEpisode.containsMatchIn(heading) -> "花絮特辑"
        else -> null
    }
    val special = variant?.let { heading.replace(it, "") } ?: heading
    if (specialEpisode.containsMatchIn(special) || ambiguousPart.containsMatchIn(heading) ||
        specialEpisode.find(subtitle)?.range?.first == 0) return null
    val date = dateEpisode.find(text)
    val part = episodePart.find(heading)?.groupValues?.get(1)
    val ordinal = chineseEpisode.findAll(heading).firstNotNullOfOrNull { match ->
        chineseNumber(match.groupValues[1])?.takeIf { it < 10000 }?.let { it to match.groupValues[2] }
    }
    val number = ordinal?.first
        ?: englishEpisode.find(heading)?.groupValues?.get(1)?.toIntOrNull()
        ?: heading.toIntOrNull()?.takeIf { date == null && it > 0 }
    return EpisodeLabel(number, date?.groupValues?.drop(1)?.joinToString(""), parseSeason(heading),
        ordinal?.let { if (it.second == "期") EpisodeKind.Period else EpisodeKind.Episode }, part, variant)
}

private fun episodeKey(text: String) = matchKey(seasonPattern.replace(metadataTag.replace(folded(text), ""), ""))

fun regularDanmakuEpisode(label: String): Int? = episodeLabel(label)?.takeIf { it.part == null && it.variant == null }?.number

fun matchingDanmakuEpisode(episodes: List<DanmakuEpisode>, label: String, season: Int?): DanmakuEpisode? {
    val target = episodeLabel(label) ?: return null
    if (target.season != null && season != null && target.season != season) return null
    val expectedSeason = target.season ?: season
    val matches = episodes.mapNotNull { episode ->
        val title = episodeLabel(episode.title) ?: return@mapNotNull null
        if (title.season != null && title.season != (expectedSeason ?: 1)) return@mapNotNull null
        if (target.variant != title.variant || target.part != title.part) return@mapNotNull null
        if (target.kind != null && title.kind != null && target.kind != title.kind) return@mapNotNull null
        val dateAgrees = target.date != null && target.date == title.date
        if (target.date != null && !dateAgrees || target.variant == "加更" && !dateAgrees) return@mapNotNull null
        if (target.number == null && target.date == null) {
            if (title.number != null || title.date != null) return@mapNotNull null
            val score = similarity(episodeKey(label), episodeKey(episode.title))
            return@mapNotNull (episode to score).takeIf { score >= selectionRules.matchThreshold }
        }
        if (target.number == null) return@mapNotNull episode to 1.0
        if (title.number != null) return@mapNotNull (episode to 1.0).takeIf { title.number == target.number }
        if (target.part != null || target.date != null || target.variant != null) return@mapNotNull null
        val number = episode.number.trim().toIntOrNull()
        (episode to 0.5).takeIf { number == target.number }
    }.sortedByDescending { it.second }
    val best = matches.firstOrNull() ?: return null
    val next = matches.getOrNull(1)
    return best.first.takeIf { next == null || best.second - next.second >= selectionRules.ambiguityGap }
}

fun matchingDanmakuEpisode(episodes: List<DanmakuEpisode>, number: Int?, season: Int?): DanmakuEpisode? =
    number?.let { matchingDanmakuEpisode(episodes, it.toString(), season) }
