package art.anjing.tigertv.core

import art.anjing.tigertv.domain.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** Upstream numeric fields may be JSON numbers or strings, but must represent exact integers. */
fun apiInt(value: JsonElement?, field: String): Int {
    val primitive = value as? JsonPrimitive ?: error("$field 必须是整数")
    val number = primitive.content.toDoubleOrNull() ?: error("$field 必须是整数")
    check(number.isFinite() && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE && number == number.toInt().toDouble()) { "$field 必须是整数" }
    return number.toInt()
}

fun JsonObject.vodId(): Int = apiInt(get("vod_id"), "vod_id").also {
    check(it > 0) { "vod_id 必须大于 0" }
}

fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

fun parseConfig(raw: String): SourceConfig {
    val root = wireJson.parseToJsonElement(raw) as? JsonObject ?: error("配置文件顶层必须是 JSON 对象")
    val sites = root["api_site"] as? JsonObject ?: error("配置缺少 api_site 对象")
    val selected = linkedMapOf<String, SourceSite>()
    for (value in sites.values) {
        val site = value as? JsonObject ?: continue
        val name = site.text("name")
        val api = site.text("api")
        if ("🎬" !in name || "_comment" in site || api.isEmpty() || api in selected) continue
        selected[api] = SourceSite(name, api, site.text("detail"))
    }
    check(selected.isNotEmpty()) { "未找到可用站点，请检查配置格式或过滤条件" }
    return SourceConfig(selected)
}

fun parseVodList(raw: String): List<JsonObject> {
    val root = wireJson.parseToJsonElement(raw) as? JsonObject ?: error("无效响应")
    val code = root["code"]
    check(apiInt(code, "code") == 1) { "请求失败: code=$code, msg=${root.text("msg")}" }
    val list = root["list"] ?: return emptyList()
    if (list == JsonNull) return emptyList()
    check(list is JsonArray) { "list 必须是数组" }
    return list.map { it as? JsonObject ?: error("list 元素必须是对象") }
}

fun extractM3u8(base: String, html: String): List<String> {
    val raw = html.replace("\\/", "/")
    val pattern = Regex("[\"']([^\"'\\s]+\\.m3u8[^\"'\\s]*)[\"']", RegexOption.IGNORE_CASE)
    fun matches(text: String) = pattern.findAll(text).map { match ->
        val link = match.groupValues[1].replace("&amp;", "&")
        // Decode a wrapped URL once; escapes inside an ordinary signed URL must survive.
        val decoded = if (Regex("^(https?%3a|%2f)", RegexOption.IGNORE_CASE).containsMatchIn(link)) percentDecode(link) else link
        resolveUrl(base, decoded)
    }.toList().distinct()
    return matches(raw).ifEmpty { matches(percentDecode(raw)) }
}

/** Preserve group positions, including empty groups, to align vod_play_from with vod_play_url. */
fun parsePlaybackSources(raw: String, from: String): List<PlaybackSource> {
    val names = from.split("$$$")
    return raw.split("$$$").mapIndexedNotNull { index, group ->
        val episodes = group.split('#').mapNotNull { item ->
            if ('$' !in item) return@mapNotNull null
            val url = item.substringAfter('$').trim()
            if (url.isEmpty()) null else EpisodeLink(item.substringBefore('$').trim(), url)
        }
        if (episodes.isEmpty()) null else PlaybackSource(index.toString(), names.getOrNull(index)?.trim().orEmpty().ifEmpty { "线路 ${index + 1}" }, episodes)
    }
}

fun plainDescription(raw: String): String = raw.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")
    .replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&lt;", "<")
    .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&").trim()

/** Decode search metadata directly, skipping large content/playback fields instead of retaining a JSON tree. */
@Serializable
internal data class SearchPage(
    val code: JsonElement = JsonNull,
    val msg: JsonElement = JsonNull,
    @SerialName("pagecount") val totalPages: JsonElement = JsonPrimitive(1),
    val list: List<SearchItem>? = emptyList()
) {
    fun results(site: String): List<SearchResult> {
        check(apiInt(code, "code") == 1) { "请求失败: code=$code, msg=${msg.scalarText()}" }
        return list.orEmpty().map { it.result(site) }
    }
    fun pageCount(): Int = apiInt(totalPages, "pagecount").coerceAtLeast(1)
}

@Serializable
internal data class SearchItem(
    @SerialName("vod_id") val vodId: JsonElement = JsonNull,
    @SerialName("vod_name") val vodName: JsonElement = JsonNull,
    @SerialName("vod_time") val vodTime: JsonElement = JsonNull,
    @SerialName("vod_remarks") val vodRemarks: JsonElement = JsonNull,
    @SerialName("vod_pic") val vodPic: JsonElement = JsonNull,
    @SerialName("vod_year") val vodYear: JsonElement = JsonNull,
    @SerialName("type_name") val typeName: JsonElement = JsonNull
) {
    fun result(site: String): SearchResult {
        val id = apiInt(vodId, "vod_id")
        check(id > 0) { "vod_id 必须大于 0" }
        return SearchResult(site, id, vodName.scalarText(), vodTime.scalarText(), vodRemarks.scalarText(),
            vodPic.scalarText(), vodYear.scalarText(), typeName.scalarText())
    }
}

private fun JsonElement.scalarText(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
