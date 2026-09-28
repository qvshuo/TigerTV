package art.anjing.tigertv.domain

/**
 * 同一部剧在不同源站各出一条搜索结果时的合并展示单元。
 * 展示字段（封面/标题/进度）来自 [primary]；点击默认走 [primary]，
 * 剧集页可切换到 [sources] 中任意源。
 */
data class MergedSearchResult(
    val primary: SearchResult,
    val sources: List<SearchResult>
) {
    /** 稳定 id：用于 LazyGrid key 与"同一选中项"判断，避免依赖易变的 vodName。 */
    val id: String get() = sources.joinToString("|") { "${it.site}-${it.vodId}" }

    /** 合并掉的额外源数量；0 表示单源组（UI 与合并前一致）。 */
    val extraCount: Int get() = sources.size - 1
}

/**
 * 名称归一化：trim、去内部所有空白、小写、全角字母数字转半角。
 * 用于跨站匹配同一部剧（不同站标点/空格/大小写常不一致）。
 */
fun normalizeVodName(name: String): String {
    val sb = StringBuilder(name.length)
    for (ch in name.trim()) {
        if (ch.isWhitespace()) continue
        // 全角 ASCII（Ａ-Ｚａ-ｚ０-９）转半角
        val half = when (ch) {
            in 'Ａ'..'Ｚ' -> ch - ('Ａ' - 'A')
            in 'ａ'..'ｚ' -> ch - ('ａ' - 'a')
            in '０'..'９' -> ch - ('０' - '0')
            else -> ch
        }
        sb.append(half.lowercaseChar())
    }
    return sb.toString()
}

/** 从 vod_time 提取 4 位年份（如 "2026-01-01" → "2026"）；缺失/不合法返回 ""。 */
fun vodYear(vodTime: String): String {
    val t = vodTime.trim()
    return if (t.length >= 4 && t.substring(0, 4).all { it.isDigit() }) t.substring(0, 4) else ""
}

/** 分组 key：归一化名称 + 年份。年份缺失时退化为纯名称（同名不同年不并，防翻拍误并）。 */
private fun mergeKey(result: SearchResult): String {
    val year = vodYear(result.vodTime)
    return if (year.isEmpty()) normalizeVodName(result.vodName)
    else "${normalizeVodName(result.vodName)}#$year"
}

/**
 * 主源选择优先级（全部确定性、可单测）：
 * 1. 有 vod_pic 封面者优先；
 * 2. vod_remarks 非空（有更新进度）优先；
 * 3. vod_time 较新者优先（字符串比较，ISO 前缀可直接比）；
 * 4. 原列表中靠前者优先（稳定兜底）。
 */
private fun pickPrimary(group: List<SearchResult>): SearchResult =
    group.maxWithOrNull(
        compareBy<SearchResult> { if (it.vodPic.isNotBlank()) 1 else 0 }
            .thenBy { if (it.vodRemarks.isNotBlank()) 1 else 0 }
            .thenBy { it.vodTime }
    ) ?: group.first()

/**
 * 按"同一部剧"归并搜索结果。
 * - 组内 sources 保持原始顺序（先返回的站在前）；
 * - 组间按"组内最早出现位置"插入结果流，避免合并把靠前结果挤到后面；
 * - 单源组原样透传，无回归面。
 */
fun mergeSearchResults(results: List<SearchResult>): List<MergedSearchResult> {
    if (results.isEmpty()) return emptyList()
    // LinkedHashMap 保持 key 首次出现顺序，即组间排序依据。
    val groups = LinkedHashMap<String, MutableList<SearchResult>>()
    for (r in results) {
        groups.getOrPut(mergeKey(r)) { mutableListOf() }.add(r)
    }
    return groups.values.map { members ->
        MergedSearchResult(primary = pickPrimary(members), sources = members.toList())
    }
}
