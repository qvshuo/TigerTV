package art.anjing.tigertv.core

import art.anjing.tigertv.domain.foldWidth as folded

private const val numeral = "[0-9零〇一二两三四五六七八九十百]+"
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

private val seasonPattern = Regex("第\\s*($numeral)\\s*季|(?<![a-z0-9])(?:season\\s*|s)([0-9]+)(?=e[0-9]|[^a-z0-9]|$)", RegexOption.IGNORE_CASE)
private fun parseSeason(text: String): Int? = seasonPattern.find(text)
    ?.let { chineseNumber(it.groupValues[1].ifEmpty { it.groupValues[2] }) }
fun danmakuSeason(title: String): Int? = parseSeason(folded(title))

private const val platforms = "qiyi|iqiyi|youku|qq|tencent|imgo|mgtv|mango|360|bilibili|bili|douban|dandan|renren|sohu|letv|pptv|爱奇艺|优酷|腾讯(?:视频)?|芒果\\s*tv|哔哩哔哩"
private val fromTag = Regex("(?:\\s+from\\s+(?:$platforms)|(?<=】)from\\s+[a-z0-9_+.-]+)\\s*$", RegexOption.IGNORE_CASE)
private val metadataTag = Regex("[【\\[(]\\s*(?:$platforms|国产剧|电视剧|电影|综艺|纪录片|动漫|动画|[日韩欧美港台]剧|短剧|高清|超清|蓝光|全集|完结|\\d{3,4}p|[248]k)\\s*[]】)]", RegexOption.IGNORE_CASE)
private val yearTag = Regex("[【\\[(]((?:19|20)\\d{2})[]】)]|\\s+((?:19|20)\\d{2})(?=\\s*$)")
private val aliasTag = Regex("[【\\[(](?:又名|别名|英文名|原名|aka)\\s*[:：]?\\s*([^】)\\]]+)[]】)]", RegexOption.IGNORE_CASE)
private val titleSpacing = Regex("[\\s·・:：_\\-《》〈〉【】\\[\\]()、，,。.!?！？‘’“”'\"—]+")
private data class WorkTitle(val main: String, val names: Set<String>, val year: Int?, val season: Int?)

private fun workTitle(raw: String): WorkTitle {
    val text = metadataTag.replace(fromTag.replace(folded(raw), ""), "")
    val year = yearTag.find(text)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] }.toInt() }
    val season = parseSeason(text)
    val aliases = aliasTag.findAll(text).map { it.groupValues[1] }
    val main = yearTag.replace(aliasTag.replace(text, ""), "").trim()
    fun key(name: String) = titleSpacing.replace(seasonPattern.replace(name.lowercase(), ""), "")
    return WorkTitle(main.filterNot { it == '《' || it == '》' }.trim(), (sequenceOf(main) + aliases).map(::key).filter { it.isNotEmpty() }.toSet(), year, season)
}

/** Smaller ranks probe first. Unknown season can only stand in for season one. */
fun danmakuTitleRank(query: String, candidate: String, year: String = "", season: Int? = null): Int? {
    val expected = workTitle(query)
    val actual = workTitle(candidate)
    val expectedYear = expected.year ?: year.toIntOrNull()?.takeIf { it in 1900..2099 }
    val expectedSeason = season ?: expected.season
    if (expected.names.none { it in actual.names }) return null
    if (specialEpisode.containsMatchIn(actual.main) && expected.main != actual.main) return null
    if (expectedYear != null && actual.year != null && expectedYear != actual.year) return null
    if ((expectedSeason ?: 1) != (actual.season ?: 1)) return null
    return when {
        expectedSeason != actual.season -> 2
        expectedYear != null && expectedYear == actual.year -> 0
        else -> 1
    }
}

fun reasonableDanmakuTitle(query: String, candidate: String): Boolean = danmakuTitleRank(query, candidate) != null
fun danmakuSearchKeyword(query: String): String = workTitle(query).main

private val chineseEpisode = Regex("第\\s*($numeral)\\s*([集期话話])")
private val englishEpisode = Regex("(?<![a-z0-9])(?:s\\d+\\s*)?(?:episode|ep|e)\\s*(\\d+)(?![\\d.])", RegexOption.IGNORE_CASE)
private val dateEpisode = Regex("(?<!\\d)(20\\d{2})[-./年]?(0[1-9]|1[0-2])[-./月]?(0[1-9]|[12]\\d|3[01])(?!\\d)")
private val specialEpisode = Regex("特别篇?|番外|加更|预告|花絮|特辑|特典|先导|前采|抢鲜|抢先|沉浸|彩蛋|\\b(?:trailer|preview|ova|oad|extra|sp|special)(?=\\b|\\d)", RegexOption.IGNORE_CASE)
private val episodePart = Regex("[集期话話]\\s*[(【\\[]?\\s*([上下中])(?=篇|集|部|[)】\\]:：\\s]|$)")
private val ambiguousPart = Regex("上下篇|上中下")
private enum class EpisodeKind { Episode, Period }

private data class EpisodeLabel(
    val number: Int?, val date: String?, val season: Int?, val kind: EpisodeKind?, val part: String?,
)

private fun episodeLabel(label: String): EpisodeLabel? {
    val text = metadataTag.replace(folded(label), "").trim()
    // After an explicit separator, the remainder can be a synopsis (e.g. “出发前采”).
    val heading = text.substringBefore(':')
    val subtitle = text.substringAfter(':', "").trimStart()
    if (specialEpisode.containsMatchIn(heading) || ambiguousPart.containsMatchIn(heading) ||
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
        ordinal?.let { if (it.second == "期") EpisodeKind.Period else EpisodeKind.Episode }, part)
}

fun regularDanmakuEpisode(label: String): Int? = episodeLabel(label)?.takeIf { it.part == null }?.number

fun matchingDanmakuEpisode(episodes: List<DanmakuEpisode>, label: String, season: Int?): DanmakuEpisode? {
    val target = episodeLabel(label) ?: return null
    if (target.number == null && target.date == null) return null
    if (target.season != null && season != null && target.season != season) return null
    val expectedSeason = target.season ?: season ?: 1
    val multipleSeasons = episodes.asSequence().map { it.seasonId }.filter { it.isNotBlank() }.distinct().take(2).count() > 1
    return episodes.mapNotNull { episode ->
        val title = episodeLabel(episode.title) ?: return@mapNotNull null
        val number = episodeLabel(episode.number) ?: return@mapNotNull null
        val explicitSeasons = listOfNotNull(episode.season, title.season, number.season)
        if (explicitSeasons.any { it != expectedSeason } || multipleSeasons && explicitSeasons.isEmpty()) return@mapNotNull null
        if (target.kind != null && title.kind != null && target.kind != title.kind) return@mapNotNull null
        if (title.part != null && number.part != null && title.part != number.part) return@mapNotNull null
        if (target.part != (title.part ?: number.part)) return@mapNotNull null
        if (title.date != null && number.date != null && title.date != number.date) return@mapNotNull null
        val date = title.date ?: number.date
        if (target.date != null && target.date != date) return@mapNotNull null
        val dateAgrees = target.date != null && target.date == date
        // A split needs an explicit, corresponding title or date, never just the API list ordinal.
        if (target.part != null && !dateAgrees && title.number != target.number) return@mapNotNull null
        // A numbered variety title plus a matching date can override an API list ordinal.
        val chosenNumber = title.number ?: number.number
        if (title.number != null && number.number != null && title.number != number.number &&
            !(title.kind == EpisodeKind.Period && dateAgrees)) return@mapNotNull null
        val matches = if (target.number != null) target.number == chosenNumber else dateAgrees
        if (!matches) return@mapNotNull null
        val explicitEpisode = dateAgrees || title.number != null
        episode to explicitEpisode
    }.let { matches ->
        val explicit = matches.filter { it.second }
        (explicit.ifEmpty { matches }).singleOrNull()?.first
    }
}

fun matchingDanmakuEpisode(episodes: List<DanmakuEpisode>, number: Int?, season: Int?): DanmakuEpisode? =
    number?.let { matchingDanmakuEpisode(episodes, it.toString(), season) }
