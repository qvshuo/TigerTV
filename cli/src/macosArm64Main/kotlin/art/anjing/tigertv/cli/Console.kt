@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package art.anjing.tigertv.cli
import kotlinx.cinterop.*
import platform.posix.*

actual fun initializeConsole() { signal(SIGPIPE, SIG_IGN) }
actual fun platformArguments(args: Array<String>): Array<String> = args
actual fun output(text: String, error: Boolean): Boolean {
    val bytes = text.encodeToByteArray()
    if (bytes.isEmpty()) return true
    return bytes.usePinned { fwrite(it.addressOf(0), 1u, bytes.size.toULong(), if (error) stderr else stdout) == bytes.size.toULong() }
}
actual fun terminate(code: Int): Nothing { exit(code); error("unreachable") }
