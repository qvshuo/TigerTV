package art.anjing.tigertv.cli

import art.anjing.tigertv.core.CoreService
import art.anjing.tigertv.core.FileLogger
import art.anjing.tigertv.core.platformStorage
import art.anjing.tigertv.core.temporaryDirectory
import art.anjing.tigertv.core.wireJson
import kotlinx.coroutines.runBlocking

expect fun output(text: String, error: Boolean = false): Boolean
expect fun terminate(code: Int): Nothing
expect fun initializeConsole()

private const val USAGE = "用法: tigertv [--config PATH] <command> [options]"
private const val HELP = """小老虎爱看剧 (TigerTV)
$USAGE

  search <关键词>                      搜索并聚合所有资源
  fetch --site <完整资源名> --video-id <正整数>
                                      获取指定资源的详情、线路和选集
  quanx <关键词>                       生成 Quantumult X 直连规则
  logs [--full] [--clear]              最近 50 行日志；全部查看或清空
  --help                              显示帮助
  --version                           显示版本

--config 必须放在业务命令之前，指定本地 JSON 配置并跳过远程配置。
关键词含空格时使用引号，以 - 开头时使用 search / quanx -- <关键词>。
search / fetch 输出 JSON；quanx / logs 输出文本；错误写入 stderr。
退出码: 0 成功，1 业务错误，2 参数错误。
"""

private class Usage(message: String) : Exception(message)
private data class Arguments(
    val configPath: String?,
    val command: String,
    val query: String = "",
    val site: String = "",
    val videoId: Int = 0,
    val full: Boolean = false,
    val clear: Boolean = false
)

private fun parse(args: List<String>): Arguments {
    var index = 0
    var configPath: String? = null
    if (args.firstOrNull() == "--config") {
        configPath = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: throw Usage("--config 需要非空文件路径")
        index = 2
    }
    val command = args.getOrNull(index) ?: throw Usage("缺少命令")
    val rest = args.drop(index + 1)
    return when (command) {
        "search", "quanx" -> {
            val values = if (rest.firstOrNull() == "--") rest.drop(1) else rest
            if (values.size != 1 || values[0].isBlank() || (rest.firstOrNull() != "--" && values[0].startsWith("-"))) {
                throw Usage("$command 需要一个关键词；以 - 开头时使用 -- <关键词>")
            }
            Arguments(configPath, command, query = values[0])
        }
        "fetch" -> {
            var site: String? = null
            var id: Int? = null
            var n = 0
            while (n < rest.size) {
                val value = rest.getOrNull(n + 1) ?: throw Usage("${rest[n]} 缺少参数值")
                when (rest[n]) {
                    "--site" -> {
                        if (site != null || value.isBlank()) throw Usage("--site 需要一个非空资源名，且只能指定一次")
                        site = value
                    }
                    "--video-id" -> {
                        if (id != null) throw Usage("--video-id 只能指定一次")
                        id = value.toIntOrNull()?.takeIf { it > 0 } ?: throw Usage("--video-id 需要正整数")
                    }
                    else -> throw Usage("未知参数: ${rest[n]}")
                }
                n += 2
            }
            Arguments(configPath, command, site = site ?: throw Usage("缺少 --site"), videoId = id ?: throw Usage("缺少 --video-id"))
        }
        "logs" -> {
            if (configPath != null) throw Usage("logs 不使用 --config")
            if (rest.any { it != "--full" && it != "--clear" }) throw Usage("logs 仅支持 --full 和 --clear")
            if (rest.distinct().size != rest.size) throw Usage("日志参数只能指定一次")
            Arguments(configPath, command, full = "--full" in rest, clear = "--clear" in rest)
        }
        else -> throw Usage("未知命令: $command")
    }
}

fun main(rawArgs: Array<String>) {
    initializeConsole()
    val args = rawArgs
    val helpFlags = setOf("--help", "-h")
    val commands = setOf("search", "fetch", "quanx", "logs")
    if (args.isEmpty() || (args.size == 1 && args[0] in helpFlags) ||
        (args.size == 2 && args[0] in commands && args[1] in helpFlags)
    ) {
        output(HELP)
        return
    }
    if (args.contentEquals(arrayOf("--version"))) { output("tigertv $VERSION\n"); return }
    val parsed = try { parse(args.toList()) } catch (e: Usage) {
        output("$USAGE\n错误: ${e.message}\n", true)
        terminate(2)
    }
    val storage = platformStorage()
    val directory = temporaryDirectory()
    val logger = FileLogger(storage, "$directory/tigertv-cli.log")
    try {
        val text = if (parsed.command == "logs") {
            val path = "$directory/tigertv-cli.log"
            if (parsed.clear) { storage.writeAtomic(path, ""); "日志已清空" }
            else {
                val lines = storage.read(path).orEmpty().lines().filter { it.isNotEmpty() }
                if (lines.isEmpty()) "日志文件为空" else (if (parsed.full) lines else lines.takeLast(50)).joinToString("\n")
            }
        } else {
            val core = CoreService(directory, parsed.configPath, storage, logger = logger, searchCacheTtlMillis = 0)
            try { runBlocking {
                when (parsed.command) {
                    "search" -> wireJson.encodeToString(core.search(parsed.query))
                    "fetch" -> wireJson.encodeToString(core.fetch(parsed.site, parsed.videoId))
                    "quanx" -> core.collectQuanxDomains(parsed.query).let {
                        if (it.incompleteSites.isNotEmpty()) output("QX：${it.incompleteSites.size} 个站点未完整扫描，详见日志。\n", true)
                        it.render()
                    }
                    else -> error("unknown command")
                }
            } } finally { core.close() }
        }
        output("$text\n") // A broken stdout pipe is a successful, silent early exit.
    } catch (e: Exception) {
        val message = "错误: ${e.message ?: e.toString()}"
        logger.log("ERROR", "main", message)
        output("$message\n", true)
        terminate(1)
    }
}
