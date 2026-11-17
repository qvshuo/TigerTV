package art.anjing.tigertv.core

private fun chineseNumber(value: String): Int? {
    value.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
    var sum = 0; var digit = 0
    for (c in value) when (c) {
        in "零〇一二两三四五六七八九" -> digit = when(c) { '两' -> 2; '〇' -> 0; else -> "零一二三四五六七八九".indexOf(c) }
        '十', '百' -> { sum += (if (digit == 0) 1 else digit) * if (c == '十') 10 else 100; digit = 0 }
        else -> return null
    }
    return (sum + digit).takeIf { it > 0 }
}
private val seasonPattern = Regex("第([0-9零〇一二两三四五六七八九十百]+)季|(?:season\\s*|s)([0-9]+)(?=e[0-9]|\\b)", RegexOption.IGNORE_CASE)
fun danmakuSeason(title: String): Int? = seasonPattern.find(title)?.let { chineseNumber(it.groupValues[1].ifEmpty { it.groupValues[2] }) }

private val specialEpisodePattern = Regex("特别|番外|加更|预告|花絮|特辑|特典|先导|彩蛋|trailer|preview|ova|oad|extra|sp\\b|special", RegexOption.IGNORE_CASE)

/** Specials have no automatic ordinal. Never substitute their array position. */
fun regularDanmakuEpisode(label: String): Int? {
    val text = label.trim()
    if (specialEpisodePattern.containsMatchIn(text)) return null
    text.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
    val match = Regex("第\\s*([0-9零〇一二两三四五六七八九十百]+)\\s*[集期话]|(?:s\\d+)?(?:episode|ep|e)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(text) ?: return null
    return chineseNumber(match.groupValues[1].ifEmpty { match.groupValues[2] })
}
fun reasonableDanmakuTitle(query: String, candidate: String): Boolean {
    fun key(raw: String): String = seasonPattern.replace(raw.substringBefore("【").substringBefore("from ").replace(Regex("[（(](?:19|20)\\d{2}[)）]"), ""), "")
        .lowercase().replace(Regex("[\\s·:：_\\-《》]"), "").trim()
    val year = Regex("[（(]((?:19|20)\\d{2})[)）]")
    val queryYear = year.find(query)?.groupValues?.get(1)
    val candidateYear = year.find(candidate)?.groupValues?.get(1)
    if (queryYear != null && candidateYear != null && queryYear != candidateYear) return false
    return key(query) == key(candidate) && (danmakuSeason(query) ?: 1) == (danmakuSeason(candidate) ?: 1)
}
fun matchingDanmakuEpisode(episodes: List<DanmakuEpisode>, number: Int?, season: Int?): DanmakuEpisode? {
    if (number == null) return null
    if (episodes.map { it.seasonId }.filter { it.isNotBlank() }.distinct().size > 1 && episodes.any { it.season == null }) return null
    return episodes.filter { regularDanmakuEpisode(it.number) == number &&
        (it.season == null || it.season == (season ?: 1)) &&
        !specialEpisodePattern.containsMatchIn(it.title)
    }.singleOrNull()
}
