package art.anjing.tigertv.cli

import art.anjing.tigertv.core.*
import kotlinx.coroutines.runBlocking

expect fun output(text: String, error: Boolean = false): Boolean
expect fun terminate(code: Int): Nothing
expect fun initializeConsole()
expect fun platformArguments(args: Array<String>): Array<String>

private const val HELP = """小老虎爱看剧 (TigerTV)
usage: tigertv [--config PATH] <command> [options]
  search --query <keyword>
  fetch --site <name> --video-id <id>
  quanx --query <keyword>
  logs [--full] [--clear]
  --help, --version

Search: --query is the title or text to search for.
Video: --site selects a resource site; --video-id selects a video on that site.
Config: --config reads a local JSON configuration file.
Logs: --full shows all lines; --clear clears the log.
"""

private class Usage(message: String) : Exception(message)
private data class Arguments(val configPath: String?, val command: String, val keyword: String = "", val site: String = "", val id: Int = 0, val full: Boolean = false, val clear: Boolean = false)

private fun parse(args: List<String>): Arguments {
    var index = 0
    var configPath: String? = null
    if (args.firstOrNull() == "--config") {
        configPath = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: throw Usage("--config requires a path")
        index = 2
    }
    val command = args.getOrNull(index) ?: throw Usage("missing command")
    val rest = args.drop(index + 1)
    return when (command) {
        "search", "quanx" -> {
            if (rest.size != 2 || rest[0] != "--query" || rest[1].isBlank()) throw Usage("$command requires --query <keyword>")
            Arguments(configPath, command, keyword = rest[1])
        }
        "fetch" -> {
            var site: String? = null
            var id: Int? = null
            var n = 0
            while (n < rest.size) {
                val value = rest.getOrNull(n + 1) ?: throw Usage("missing value for ${rest[n]}")
                when (rest[n]) {
                    "--site" -> {
                        if (site != null || value.isBlank()) throw Usage("--site requires one nonempty name")
                        site = value
                    }
                    "--video-id" -> {
                        if (id != null) throw Usage("--video-id must appear once")
                        id = value.toIntOrNull()?.takeIf { it > 0 } ?: throw Usage("--video-id requires a positive integer")
                    }
                    else -> throw Usage("unrecognized argument: ${rest[n]}")
                }
                n += 2
            }
            Arguments(configPath, command, site = site ?: throw Usage("--site is required"), id = id ?: throw Usage("--video-id is required"))
        }
        "logs" -> {
            if (rest.any { it != "--full" && it != "--clear" }) throw Usage("unrecognized logs argument")
            Arguments(configPath, command, full = "--full" in rest, clear = "--clear" in rest)
        }
        else -> throw Usage("unknown command: $command")
    }
}

fun main(rawArgs: Array<String>) {
    initializeConsole()
    val args = platformArguments(rawArgs)
    if (args.isEmpty() || "--help" in args || "-h" in args) { output(HELP); return }
    if (args.contentEquals(arrayOf("--version"))) { output("tigertv $VERSION\n"); return }
    val parsed = try { parse(args.toList()) } catch (e: Usage) {
        output("usage: tigertv [--config PATH] <command> [options]\n错误: ${e.message}\n", true)
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
                    "search" -> wireJson.encodeToString(core.search(parsed.keyword))
                    "fetch" -> wireJson.encodeToString(core.fetch(parsed.site, parsed.id))
                    "quanx" -> core.collectQuanxDomains(parsed.keyword).render()
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
