package art.anjing.tigertv.core

import io.ktor.http.Url

private val hostname = Regex("(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*")
private val ipv4Shape = Regex("[0-9]+(?:\\.[0-9]+){3}")
private val hextet = Regex("[0-9a-f]{1,4}")

private fun ipv4Parts(host: String): List<Int>? {
    if (!ipv4Shape.matches(host)) return null
    return host.split('.').map { it.toIntOrNull()?.takeIf { part -> part in 0..255 } ?: return null }
}

private fun ipv6Host(raw: String): String? {
    var host = raw
    if ('.' in host) {
        val tail = ipv4Parts(host.substringAfterLast(':')) ?: return null
        host = host.substringBeforeLast(':') + ":" + ((tail[0] shl 8) + tail[1]).toString(16) +
            ":" + ((tail[2] shl 8) + tail[3]).toString(16)
    }
    fun words(part: String) = if (part.isEmpty()) emptyList() else part.split(':')
    val halves = host.split("::")
    if (halves.size > 2) return null
    val left = words(halves.first())
    val right = if (halves.size == 2) words(halves.last()) else emptyList()
    if ((left + right).any { !hextet.matches(it) }) return null
    val zeros = 8 - left.size - right.size
    if (halves.size == 1 && zeros != 0 || halves.size == 2 && zeros <= 0) return null
    val expanded = (left + List(zeros) { "0" } + right).map { it.toInt(16).toString(16) }
    var start = 0
    var longest = 1
    var index = 0
    while (index < expanded.size) {
        if (expanded[index] != "0") { index++; continue }
        val begin = index
        while (index < expanded.size && expanded[index] == "0") index++
        if (index - begin > longest) { start = begin; longest = index - begin }
    }
    return if (longest == 1) expanded.joinToString(":")
    else expanded.take(start).joinToString(":") + "::" + expanded.drop(start + longest).joinToString(":")
}

private fun canonicalHost(value: String): String? {
    val host = value.lowercase().removeSuffix(".").removeSurrounding("[", "]")
    if (':' in host) return ipv6Host(host)
    if (ipv4Shape.matches(host)) return ipv4Parts(host)?.joinToString(".")
    return host.takeIf(hostname::matches)
}

internal fun quanxHost(url: String): String? {
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
    val host = runCatching { Url(normalizeUrl(url)).host }.getOrNull() ?: return null
    return canonicalHost(host)
}

private fun rule(host: String) = when {
    ':' in host -> "ip6-cidr, $host/128, direct"
    ipv4Shape.matches(host) -> "ip-cidr, $host/32, direct"
    else -> "host, $host, direct"
}

private fun importedHost(line: String): String? {
    val fields = line.split(',').map(String::trim)
    if (fields.size != 3 || !fields[2].equals("direct", true)) return null
    val value = fields[1]
    return when (fields[0].lowercase()) {
        "host", "host-suffix" -> canonicalHost(value)
        "ip-cidr" -> value.removeSuffix("/32").takeIf { value.endsWith("/32") && ipv4Parts(it) != null }?.let(::canonicalHost)
        "ip6-cidr" -> value.removeSuffix("/128").takeIf { value.endsWith("/128") && ':' in it }?.let(::canonicalHost)
        else -> null
    }
}

private val ruleGroupNames = listOf("资源站 API 域名", "播放/下载域名", "m3u8 域名", "历史规则")

private fun previousGroups(rules: String): List<Set<String>> {
    val groups = List(ruleGroupNames.size) { mutableSetOf<String>() }
    val text = rules.replace("\r\n", "\n").removeSuffix("\n")
    val blocks = text.split("\n\n")
    if (blocks.size in 3..4 && blocks.all { block ->
            block.isEmpty() || block.lineSequence().all { importedHost(it) != null }
        }) {
        blocks.forEachIndexed { index, block -> block.lineSequence().mapNotNullTo(groups[index], ::importedHost) }
    } else {
        var group = groups.lastIndex
        for (line in text.lineSequence()) {
            val category = ruleGroupNames.indexOf(line.trim().removePrefix("; "))
            if (category >= 0) group = category
            else importedHost(line)?.let { groups[group] += it }
        }
    }
    return groups
}

data class QuanxDomains(
    val api: Set<String>,
    val urls: Set<String>,
    val m3u8: Set<String>,
    val incompleteSites: List<String> = emptyList(),
    val incompleteReasons: Map<String, List<String>> = emptyMap(),
) {
    fun ruleGroups(previousRules: String = ""): Map<String, List<String>> {
        val current = listOf(api, urls, m3u8, emptySet()).map { it.mapNotNull(::canonicalHost).toSet() }
        val known = current.flatten().toSet()
        val previous = previousGroups(previousRules)
        val seen = mutableSetOf<String>()
        return ruleGroupNames.mapIndexed { index, name ->
            val hosts = current[index] + previous[index].filterNot { it in known }
            name to hosts.sorted().filter(seen::add).map(::rule)
        }.toMap()
    }
}
