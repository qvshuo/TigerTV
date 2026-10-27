package art.anjing.tigertv.core

import art.anjing.tigertv.domain.*
import kotlinx.serialization.json.*

val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

/** Python int coercion: decimal strings are not accepted as integer strings. */
fun lenientInt(value: JsonElement?): Int {
    val primitive = value as? JsonPrimitive ?: return 0
    if (primitive.isString) return primitive.content.trim().toIntOrNull() ?: 0
    if (primitive.booleanOrNull != null) return if (primitive.boolean) 1 else 0
    return primitive.intOrNull ?: primitive.doubleOrNull?.takeIf { it.isFinite() }?.toInt() ?: 0
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
    return SourceConfig(lenientInt(root["cache_time"]), selected)
}

fun parseVodList(raw: String): List<JsonObject> {
    val root = wireJson.parseToJsonElement(raw) as? JsonObject ?: error("无效响应")
    val code = root["code"]
    check(code == null || code == JsonNull || lenientInt(code) == 1) { "请求失败: code=$code, msg=${root.text("msg")}" }
    val list = root["list"] ?: return emptyList()
    if (list == JsonNull) return emptyList()
    check(list is JsonArray) { "list 必须是数组" }
    return list.map { it as? JsonObject ?: error("list 元素必须是对象") }
}

fun parseEpisodeLinks(raw: String, firstOnly: Boolean = false): List<EpisodeLink> = raw.split("$$$").flatMap { group ->
    val parsed = group.split('#').filter { '$' in it }.map { item ->
        EpisodeLink(item.substringBefore('$'), item.substringAfter('$'))
    }
    if (firstOnly) parsed.take(1) else parsed
}

fun extractM3u8(base: String, html: String): List<String> {
    val variants = listOf(html.replace("\\/", "/"), percentDecode(html).replace("\\/", "/"))
    val pattern = Regex("[\"']([^\"'\\s]+\\.m3u8[^\"'\\s]*)[\"']", RegexOption.IGNORE_CASE)
    return variants.flatMap { variant -> pattern.findAll(variant).map { resolveUrl(base, percentDecode(it.groupValues[1])) }.toList() }.distinct()
}
